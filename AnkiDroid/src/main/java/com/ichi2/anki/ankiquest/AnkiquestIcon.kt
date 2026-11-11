// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * Aki's mood on the app icon, like a pet waiting on the home screen. On by default where the build
 * has the moods; launchers redraw the icon when it changes, and some briefly drop it from the home
 * screen while they do.
 *
 * Each mood aliases AnkiquestLauncher, which forwards into DeckPicker's stable task so disabling
 * an alias cannot finish a review. Exactly one is enabled. Builds without the aliases keep
 * IntentHandler's launcher entry.
 */
internal object AnkiquestIcon : DefaultLifecycleObserver {
    const val PREF_ENABLED = "ankiquestMoodIcon"
    private const val PREF_CURRENT = "ankiquestMoodIconCurrent"
    private const val PREF_LAST_PROFILE = "ankiquestMoodIconProfile"

    private val lock = Any()
    private val changes = Any()
    private var visibility: Visibility = Visibility.Background

    private sealed interface Visibility {
        data object Background : Visibility

        data class Visible(
            val pending: Pending?,
        ) : Visibility
    }

    private data class Pending(
        val context: Context,
        val prefs: SharedPreferences,
    )

    /** The last hours of a study day in which a streak not yet secured counts as at risk. */
    const val WORRY_HOURS = 6

    enum class Mood(
        val alias: String,
    ) {
        DEFAULT("AnkiquestIconDefault"),
        HAPPY("AnkiquestIconHappy"),
        WORRIED("AnkiquestIconWorried"),
        FROZEN("AnkiquestIconFrozen"),
        SLEEPY("AnkiquestIconSleepy"),
    }

    /** The mood for your [profile] at [now]: happy once today counts, worried in the last hours of an open day. */
    fun mood(
        profile: JSONObject?,
        now: Long,
    ): Mood {
        val dayEndsAt = profile?.optLong("day_ends_at") ?: 0L
        if (profile == null || dayEndsAt <= now) return Mood.DEFAULT
        val streak = profile.optLong("streak")
        return when (profile.optString("streak_state")) {
            "studied" -> Mood.HAPPY
            "protected" -> Mood.FROZEN
            else ->
                when {
                    streak == 0L -> Mood.SLEEPY
                    dayEndsAt - now <= WORRY_HOURS * 3_600_000L -> Mood.WORRIED
                    else -> Mood.DEFAULT
                }
        }
    }

    fun enabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_ENABLED, true)

    /** Whether this build has the mood aliases; only the quest build does. */
    fun available(context: Context): Boolean =
        try {
            context.packageManager.getActivityInfo(component(context, Mood.DEFAULT), PackageManager.MATCH_DISABLED_COMPONENTS)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Requests the mood for [profile], or the plain icon when off, and remembers [profile] for [showLastKnown]. */
    fun update(
        context: Context,
        profile: JSONObject?,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (profile != null) remember(profile, prefs)
        show(context, if (enabled(prefs)) mood(profile, now) else Mood.DEFAULT, prefs)
    }

    /**
     * Requests the mood for the last profile without waiting for the next poll, as when the setting
     * is turned on. Until a profile has been seen that is the plain icon.
     */
    fun showLastKnown(
        context: Context,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = show(context, lastMood(now, prefs), prefs)

    private fun lastMood(
        now: Long,
        prefs: SharedPreferences,
    ): Mood = mood(lastKnown(prefs), now)

    /**
     * Queue a refresh on resume. The current account and day are read again when the app stops.
     */
    fun onAppStart(
        context: Context,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        try {
            if (enabled(prefs)) {
                showLastKnown(context, TimeManager.time.intTimeMS(), prefs)
            }
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest icon could not catch up")
        }
    }

    /**
     * The parts of the configured account's last profile that the mood and the streak widget read;
     * another account's are never used.
     */
    internal fun lastKnown(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): JSONObject? =
        prefs.getString(lastProfileKey(prefs), null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun remember(
        profile: JSONObject,
        prefs: SharedPreferences,
    ) {
        val kept =
            JSONObject()
                .put("streak", profile.optLong("streak"))
                .put("streak_state", profile.optString("streak_state"))
                .put("day_ends_at", profile.optLong("day_ends_at"))
                .put("goal_reached", profile.optJSONObject("goal")?.optBoolean("reached") == true)
                .toString()
        val key = lastProfileKey(prefs)
        if (prefs.getString(key, null) != kept) prefs.edit { putString(key, kept) }
    }

    private fun lastProfileKey(prefs: SharedPreferences) = scopedKey(PREF_LAST_PROFILE, AnkiquestHomeData.account(prefs)?.scope)

    /** Android can finish tasks when an alias is disabled. Wait until the last activity stops. */
    fun show(
        context: Context,
        mood: Mood,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val apply =
            synchronized(lock) {
                when (visibility) {
                    Visibility.Background -> true
                    is Visibility.Visible -> {
                        visibility = Visibility.Visible(Pending(context.applicationContext, prefs))
                        false
                    }
                }
            }
        if (apply) {
            synchronized(changes) { applyMood(context, mood, prefs) }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        synchronized(lock) {
            visibility = Visibility.Visible(null)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val pending =
            synchronized(lock) {
                val pending = (visibility as? Visibility.Visible)?.pending
                visibility = Visibility.Background
                pending
            } ?: return
        applicationScope.launch(Dispatchers.IO) { applyPending(pending) }
    }

    private fun applyPending(pending: Pending) {
        synchronized(changes) {
            synchronized(lock) {
                val visible = visibility as? Visibility.Visible
                if (visible != null) {
                    // A quick return keeps the refresh pending until the next stop.
                    if (visible.pending == null) {
                        visibility = Visibility.Visible(pending)
                    }
                    return
                }
            }
            // Serialize launcher writes without blocking lifecycle callbacks on binder calls.
            // A switch already started can finish safely because the task uses the stable launcher.
            val prefs = pending.prefs
            val mood = if (enabled(prefs)) lastMood(TimeManager.time.intTimeMS(), prefs) else Mood.DEFAULT
            applyMood(pending.context, mood, prefs)
        }
    }

    /** Check the actual components so a failed or partial switch can be retried. */
    private fun applyMood(
        context: Context,
        mood: Mood,
        prefs: SharedPreferences,
    ) {
        if (!available(context)) {
            return
        }
        val packageManager = context.packageManager
        try {
            val wrong = Mood.entries.filter { enabled(packageManager, context, it) != (it == mood) }
            if (wrong.isEmpty()) {
                if (prefs.getString(PREF_CURRENT, null) != mood.name) prefs.edit { putString(PREF_CURRENT, mood.name) }
                return
            }
            // Enable the new icon before disabling the old one, so there is never no launcher entry.
            for (other in wrong.sortedBy { it != mood }) {
                packageManager.setComponentEnabledSetting(
                    component(context, other),
                    wanted(other, other == mood),
                    PackageManager.DONT_KILL_APP,
                )
            }
            if (Mood.entries.all { enabled(packageManager, context, it) == (it == mood) }) {
                prefs.edit { putString(PREF_CURRENT, mood.name) }
            } else {
                prefs.edit { remove(PREF_CURRENT) }
                Timber.w("AnkiQuest icon did not change to %s", mood)
            }
        } catch (e: Exception) {
            prefs.edit { remove(PREF_CURRENT) }
            Timber.w(e, "AnkiQuest icon could not change to %s", mood)
        }
    }

    /** The mood the icon was last switched to completely, or null when unknown or a switch failed. */
    internal fun current(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Mood? =
        Mood.entries.firstOrNull { it.name == prefs.getString(PREF_CURRENT, null) }

    /** The setting that makes [mood]'s alias [on]: the plain icon is enabled in the manifest, the moods are not. */
    private fun wanted(
        mood: Mood,
        on: Boolean,
    ): Int =
        when {
            on == (mood == Mood.DEFAULT) -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            on -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

    /** Whether [mood]'s alias is a launcher entry now. */
    private fun enabled(
        packageManager: PackageManager,
        context: Context,
        mood: Mood,
    ): Boolean =
        when (packageManager.getComponentEnabledSetting(component(context, mood))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> mood == Mood.DEFAULT
            else -> false
        }

    private fun component(
        context: Context,
        mood: Mood,
    ) = ComponentName(context.packageName, "com.ichi2.anki.ankiquest.${mood.alias}")
}
