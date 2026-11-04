// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestIcon.Mood
import com.ichi2.anki.common.preferences.sharedPrefs
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestIconTest : RobolectricTest() {
    private val hour = 3_600_000L
    private val now = 100 * hour

    private fun profile(
        state: String,
        streak: Int,
        hoursLeft: Long,
    ) = JSONObject()
        .put("streak_state", state)
        .put("streak", streak)
        .put("day_ends_at", now + hoursLeft * hour)

    @Test
    fun `the mood follows today's streak`() {
        assertEquals(Mood.HAPPY, AnkiquestIcon.mood(profile("studied", 12, 10), now))
        assertEquals(Mood.FROZEN, AnkiquestIcon.mood(profile("protected", 12, 10), now))
        assertEquals(Mood.DEFAULT, AnkiquestIcon.mood(profile("pending", 12, 10), now))
        assertEquals(Mood.WORRIED, AnkiquestIcon.mood(profile("pending", 12, 3), now))
        assertEquals(Mood.SLEEPY, AnkiquestIcon.mood(profile("pending", 0, 3), now))
        assertEquals(Mood.DEFAULT, AnkiquestIcon.mood(profile("studied", 12, -1), now), "an ended day says nothing about today")
        assertEquals(Mood.DEFAULT, AnkiquestIcon.mood(null, now))
    }

    @Test
    fun `builds without the quest aliases keep their launcher entry`() {
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        assertFalse(AnkiquestIcon.available(targetContext))
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(Mood.HAPPY))
    }

    @Test
    fun `exactly one launcher icon is enabled`() {
        // The aliases come from the quest build's manifest, which unit tests do not merge.
        for (mood in Mood.entries) {
            shadowOf(targetContext.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = targetContext.packageName
                    name = component(mood).className
                    targetActivity = "com.ichi2.anki.IntentHandler"
                    enabled = mood == Mood.DEFAULT
                },
            )
        }
        assertTrue(AnkiquestIcon.available(targetContext))
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled())
        AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
        assertEquals(listOf(Mood.WORRIED), enabled())
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }
        AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
        assertEquals(listOf(Mood.DEFAULT), enabled())
    }

    private fun component(mood: Mood) = ComponentName(targetContext.packageName, "com.ichi2.anki.ankiquest.${mood.alias}")

    private fun state(mood: Mood) = targetContext.packageManager.getComponentEnabledSetting(component(mood))

    private fun enabled(): List<Mood> =
        Mood.entries.filter { mood ->
            val state = state(mood)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && mood == Mood.DEFAULT)
        }
}
