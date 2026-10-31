// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import org.json.JSONObject
import timber.log.Timber

/**
 * Aki's mood on the app icon, like a pet waiting on the home screen. Opt-in: launchers redraw the
 * icon when it changes, and some briefly drop it from the home screen while they do.
 *
 * Each mood is a launcher alias of IntentHandler in the manifest; exactly one is enabled.
 */
internal object AnkiquestIcon {
    const val PREF_ENABLED = "ankiquestMoodIcon"
    private const val PREF_CURRENT = "ankiquestMoodIconCurrent"
    private const val WORRY_HOURS = 6

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

    fun enabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_ENABLED, false)

    /** Shows the mood for [profile], or the plain icon when the setting is off. */
    fun update(
        context: Context,
        profile: JSONObject?,
        now: Long,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = show(context, if (enabled(prefs)) mood(profile, now) else Mood.DEFAULT, prefs)

    fun show(
        context: Context,
        mood: Mood,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (prefs.getString(PREF_CURRENT, Mood.DEFAULT.name) == mood.name) return
        val packageManager = context.packageManager
        try {
            // Enable the new icon before disabling the old one, so there is never no launcher entry.
            packageManager.setComponentEnabledSetting(
                component(context, mood),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (other in Mood.entries.filter { it != mood }) {
                packageManager.setComponentEnabledSetting(
                    component(context, other),
                    if (other == Mood.DEFAULT) {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    },
                    PackageManager.DONT_KILL_APP,
                )
            }
            if (mood == Mood.DEFAULT) {
                packageManager.setComponentEnabledSetting(
                    component(context, Mood.DEFAULT),
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
            prefs.edit { putString(PREF_CURRENT, mood.name) }
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest icon could not change to %s", mood)
        }
    }

    private fun component(
        context: Context,
        mood: Mood,
    ) = ComponentName(context.packageName, "com.ichi2.anki.ankiquest.${mood.alias}")
}
