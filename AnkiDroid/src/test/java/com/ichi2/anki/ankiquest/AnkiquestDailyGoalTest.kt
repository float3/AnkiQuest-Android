// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Choice
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Load
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Settings
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
class AnkiquestDailyGoalTest {
    private val prefs = RuntimeEnvironment.getApplication().sharedPrefs().also { it.edit { clear() } }

    @Test
    fun `the default goal is today's cards plus the backlog spread over thirty days`() {
        val goal = AnkiquestDailyGoal.goal(Load(day = 5, studiedCards = 0, minutes = 0, dueToday = 40, backlog = 300), Settings())
        assertEquals(10, goal.extra)
        assertEquals(50, goal.target)
        assertEquals(300, goal.backlog)
        assertEquals(30, goal.daysLeft)
    }

    @Test
    fun `the backlog is spread over the days left in the plan`() {
        val load = Load(5, studiedCards = 0, minutes = 0, dueToday = 40, backlog = 300, studyDay = 20_400)
        val plan = Settings(backlogDays = 30, since = 20_390)
        val goal = AnkiquestDailyGoal.goal(load, plan)
        assertEquals(20, goal.daysLeft)
        assertEquals(15, goal.extra)
        assertEquals(55, goal.target)
        val lastDay = AnkiquestDailyGoal.goal(load.copy(backlog = 7), plan.copy(since = 20_371))
        assertEquals(1, lastDay.daysLeft)
        assertEquals(7, lastDay.extra, "the last day takes what is left")
        val ended = AnkiquestDailyGoal.goal(load, plan.copy(since = 20_370))
        assertEquals(30, ended.daysLeft, "a plan that ran out starts again")
    }

    @Test
    fun `cards studied before the goal is set stay part of it`() {
        val goal = AnkiquestDailyGoal.goal(Load(5, studiedCards = 25, minutes = 9, dueToday = 15, backlog = 100), Settings())
        assertEquals(25 + 15 + 4, goal.target)
    }

    @Test
    fun `custom goals use the chosen number`() {
        val load = Load(5, 0, 0, 40, 300)
        assertEquals(50, AnkiquestDailyGoal.goal(load, Settings(Mode.CARDS, cards = 50)).target)
        assertEquals(15, AnkiquestDailyGoal.goal(load, Settings(Mode.MINUTES, minutes = 15)).target)
    }

    @Test
    fun `the goal is fixed for the day so studying does not move it`() {
        val morning = AnkiquestDailyGoal.status(Load(5, 0, 0, 40, 300), prefs)
        val evening = AnkiquestDailyGoal.status(Load(5, 70, 30, 0, 270), prefs)
        assertEquals(50, morning.goal.target)
        assertEquals(50, evening.goal.target)
        assertTrue(evening.goalDone)
        val tomorrow = AnkiquestDailyGoal.status(Load(6, 0, 0, 20, 280), prefs)
        assertEquals(30, tomorrow.goal.target)
    }

    @Test
    fun `the day's goal is kept for each account`() {
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "cerro")
        }
        assertEquals(50, AnkiquestDailyGoal.status(Load(5, 0, 0, 40, 300), prefs).goal.target)
        prefs.edit { putString(Ankiquest.PREF_USER, "hill") }
        assertEquals(40, AnkiquestDailyGoal.status(Load(5, 0, 0, 30, 300), prefs).goal.target)
        prefs.edit { putString(Ankiquest.PREF_USER, "cerro") }
        assertEquals(50, AnkiquestDailyGoal.status(Load(5, 0, 0, 30, 300), prefs).goal.target)
    }

    @Test
    fun `an old daily backlog percentage becomes the closest plan length`() {
        prefs.edit {
            putString(AnkiquestDailyGoal.PREF_MODE, "backlog")
            putString(AnkiquestDailyGoal.PREF_BACKLOG_PERCENT, "20")
        }
        assertEquals(Choice(Mode.BACKLOG, 14), AnkiquestDailyGoal.localChoice(prefs))
        prefs.edit { putString(AnkiquestDailyGoal.PREF_BACKLOG_PERCENT, "10") }
        assertEquals(Choice.DEFAULT, AnkiquestDailyGoal.localChoice(prefs))
    }

    @Test
    fun `changing the goal setting works it out again`() {
        AnkiquestDailyGoal.status(Load(5, 0, 0, 40, 300), prefs)
        prefs.edit {
            putString(AnkiquestDailyGoal.PREF_MODE, "minutes")
            putInt(AnkiquestDailyGoal.PREF_MINUTES, 25)
        }
        val status = AnkiquestDailyGoal.status(Load(5, 10, 12, 30, 300), prefs)
        assertEquals(Mode.MINUTES, status.mode)
        assertEquals(25, status.goal.target)
        assertEquals(12, status.progress)
    }

    @Test
    fun `the streak minimum follows the server's minutes`() {
        AnkiquestDailyGoal.remember(JSONObject().put("today", JSONObject().put("streak_minutes", 10)), prefs)
        val short = AnkiquestDailyGoal.status(Load(5, 8, 6, 30, 0), prefs)
        assertEquals(10, short.streakMinutes)
        assertFalse(short.minimumDone)
        assertTrue(AnkiquestDailyGoal.status(Load(5, 14, 10, 30, 0), prefs).minimumDone)
        AnkiquestDailyGoal.remember(JSONObject().put("today", JSONObject()), prefs)
        assertTrue(AnkiquestDailyGoal.status(Load(5, 1, 0, 30, 0), prefs).minimumDone, "older servers count any review")
    }

    @Test
    fun `another account never inherits the streak minimum`() {
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "cerro")
        }
        AnkiquestDailyGoal.remember(JSONObject().put("today", JSONObject().put("streak_minutes", 10)), prefs)
        prefs.edit { putString(Ankiquest.PREF_USER, "hill") }
        assertEquals(0, AnkiquestDailyGoal.streakMinutes(prefs))
        prefs.edit { putString(Ankiquest.PREF_USER, "cerro") }
        assertEquals(10, AnkiquestDailyGoal.streakMinutes(prefs))
    }
}
