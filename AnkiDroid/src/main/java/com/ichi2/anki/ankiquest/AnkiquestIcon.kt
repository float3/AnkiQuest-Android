// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject
import timber.log.Timber

/**
 * Aki's mood on the app icon, like a pet waiting on the home screen. On by default where the build
 * has the moods; launchers redraw the icon when it changes, and some briefly drop it from the home
 * screen while they do.
 *
 * Each mood is a launcher alias of IntentHandler; exactly one is enabled. The aliases are part of
 * the AnkiQuest branding in src/quest, so other builds keep IntentHandler's own launcher entry.
 */
internal object AnkiquestIcon {
    const val PREF_ENABLED = "ankiquestMoodIcon"
    private const val PREF_CURRENT = "ankiquestMoodIconCurrent"
    private const val PREF_LAST_PROFILE = "ankiquestMoodIconProfile"

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
    ): Mood = mood(AnkiquestDay.Server.profile(profile), now)

    private fun mood(
        server: AnkiquestDay.Server?,
        now: Long,
        prefs: SharedPreferences? = null,
    ): Mood {
        if (server == null) {
            return Mood.DEFAULT
        }
        val day =
            if (prefs == null) {
                AnkiquestDay.judge(server, emptyList(), now)
            } else {
                AnkiquestDay.judgeOwn(server, now, prefs)
            }
        return when (day?.label) {
            AnkiquestDay.State.STUDIED -> Mood.HAPPY
            AnkiquestDay.State.PROTECTED -> Mood.FROZEN
            AnkiquestDay.State.PENDING, AnkiquestDay.State.SHORT ->
                when {
                    day.streak == 0 -> Mood.SLEEPY
                    server.dayEndsAt - now <= WORRY_HOURS * 3_600_000L -> Mood.WORRIED
                    else -> Mood.DEFAULT
                }
            AnkiquestDay.State.UNKNOWN, null -> Mood.DEFAULT
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

    /** Shows the mood for [profile], or the plain icon when the setting is off, and remembers [profile] for [showLastKnown]. */
    fun update(
        context: Context,
        profile: JSONObject?,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (profile != null) remember(profile, prefs)
        val mood = if (enabled(prefs)) mood(AnkiquestDay.Server.profile(profile), now, prefs) else Mood.DEFAULT
        show(context, mood, prefs)
    }

    /**
     * Shows the mood for the last profile the poll saw, without waiting for the next poll, as when the
     * setting is turned on. Until a poll has seen a profile that is the plain icon.
     */
    fun showLastKnown(
        context: Context,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val server = AnkiquestDay.last(prefs) ?: AnkiquestDay.Server.profile(lastKnown(prefs))
        show(context, mood(server, now, prefs), prefs)
    }

    /**
     * At app start the icon catches up with the last known day. Opening the app then refreshes the
     * profile, which brings in today, also for whoever an update just turned the setting on for.
     */
    fun onAppStart(
        context: Context,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        try {
            if (enabled(prefs) && available(context)) showLastKnown(context, TimeManager.time.intTimeMS(), prefs)
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

    /**
     * Enables [mood]'s alias and disables the others. The pref only says what was last asked for:
     * a switch can fail or be undone halfway, so the launcher's actual components are checked too,
     * and only those that differ are changed, since every change makes some launchers redraw.
     */
    fun show(
        context: Context,
        mood: Mood,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (!available(context)) return
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
