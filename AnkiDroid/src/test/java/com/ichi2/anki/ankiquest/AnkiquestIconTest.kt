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
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `the mood icon is on unless turned off`() {
        val prefs = targetContext.sharedPrefs()
        prefs.edit { remove(AnkiquestIcon.PREF_ENABLED) }
        assertTrue(AnkiquestIcon.enabled(prefs))
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }
        assertFalse(AnkiquestIcon.enabled(prefs))
    }

    @Test
    fun `builds without the quest aliases ignore the default at start`() {
        val prefs = targetContext.sharedPrefs()
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        AnkiquestIcon.onAppStart(targetContext, prefs)
        AnkiquestIcon.showLastKnown(targetContext, now, prefs)
        for (mood in Mood.entries) assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(mood), mood.name)
    }

    @Test
    fun `turning the icon on shows the last known day at once`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, false) }
        AnkiquestIcon.update(targetContext, profile("studied", 12, 10), now, prefs)
        assertEquals(listOf(Mood.DEFAULT), enabled(), "off shows the plain icon")
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        AnkiquestIcon.showLastKnown(targetContext, now, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled(), "the profile seen while off is used without another poll")
    }

    @Test
    fun `the app start catches up with the last known day`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        prefs.edit { remove(AnkiquestIcon.PREF_ENABLED) }
        assertNull(AnkiquestIcon.lastKnown(prefs))
        AnkiquestIcon.onAppStart(targetContext, prefs)
        assertEquals(listOf(Mood.DEFAULT), enabled(), "nothing known yet keeps the plain icon")
        val clock = TimeManager.time.intTimeMS()
        val protected = JSONObject().put("streak_state", "protected").put("streak", 12).put("day_ends_at", clock + 10 * hour)
        AnkiquestIcon.update(targetContext, protected, clock, prefs)
        AnkiquestIcon.show(targetContext, Mood.DEFAULT, prefs)
        AnkiquestIcon.onAppStart(targetContext, prefs)
        assertEquals(listOf(Mood.FROZEN), enabled())
    }

    @Test
    fun `exactly one launcher icon is enabled`() {
        registerAliases()
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

    @Test
    fun `a worried icon turns happy once today counts`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putBoolean(AnkiquestIcon.PREF_ENABLED, true) }
        AnkiquestIcon.update(targetContext, profile("pending", 12, 2), now, prefs)
        assertEquals(listOf(Mood.WORRIED), enabled())
        AnkiquestIcon.update(targetContext, profile("studied", 13, 2), now, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled())
        assertEquals(Mood.HAPPY, AnkiquestIcon.current(prefs))
    }

    @Test
    fun `the launcher's components win over what the pref says`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        AnkiquestIcon.show(targetContext, Mood.HAPPY, prefs)
        assertEquals(listOf(Mood.HAPPY), enabled())
        // A switch the system undid or that stopped halfway: the plain icon is back and happy is off.
        val packageManager = targetContext.packageManager
        packageManager.setComponentEnabledSetting(component(Mood.HAPPY), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        packageManager.setComponentEnabledSetting(component(Mood.DEFAULT), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        assertEquals(Mood.HAPPY, AnkiquestIcon.current(prefs), "the pref still says happy")

        AnkiquestIcon.show(targetContext, Mood.HAPPY, prefs)

        assertEquals(listOf(Mood.HAPPY), enabled())
    }

    @Test
    fun `a matching icon is left alone`() {
        registerAliases()
        val prefs = targetContext.sharedPrefs()
        AnkiquestIcon.show(targetContext, Mood.FROZEN, prefs)
        val packageManager = targetContext.packageManager
        // Equivalent to the manifest's own state, so nothing needs to change for the launcher.
        packageManager.setComponentEnabledSetting(component(Mood.HAPPY), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)

        AnkiquestIcon.show(targetContext, Mood.FROZEN, prefs)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(Mood.HAPPY), "an alias that is already off is not touched")
        assertEquals(listOf(Mood.FROZEN), enabled())
    }

    /** The aliases come from the quest build's manifest, which unit tests do not merge. */
    private fun registerAliases() {
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
