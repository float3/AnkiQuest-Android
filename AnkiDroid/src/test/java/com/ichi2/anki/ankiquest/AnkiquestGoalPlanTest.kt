// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Choice
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.ankiquest.AnkiquestGoalPlan.Forecast
import com.ichi2.anki.ankiquest.AnkiquestGoalPlan.Projection
import com.ichi2.anki.common.time.TimeManager
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestGoalPlanTest : RobolectricTest() {
    private val forecast = Forecast(due = 40, backlog = 300, dailyDue = 35.5, secondsPerCard = 10.0)

    @Test
    fun `days left count down from the day the plan started`() {
        assertEquals(30, AnkiquestGoalPlan.daysLeft(30, 100, 100))
        assertEquals(21, AnkiquestGoalPlan.daysLeft(30, 100, 109))
        assertEquals(1, AnkiquestGoalPlan.daysLeft(30, 100, 129))
        assertEquals(30, AnkiquestGoalPlan.daysLeft(30, 100, 130), "a plan that ran out starts again")
        assertEquals(30, AnkiquestGoalPlan.daysLeft(30, null, 130), "an unknown start counts as today")
        assertEquals(30, AnkiquestGoalPlan.daysLeft(30, 140, 130), "a start in the future never lengthens the plan")
        assertFalse(AnkiquestGoalPlan.planEnded(30, 100, 129))
        assertTrue(AnkiquestGoalPlan.planEnded(30, 100, 130))
        assertFalse(AnkiquestGoalPlan.planEnded(30, null, 130))
    }

    @Test
    fun `the backlog is spread evenly and never dwindles below one card`() {
        assertEquals(10, AnkiquestGoalPlan.extra(300, 30))
        assertEquals(11, AnkiquestGoalPlan.extra(301, 30))
        assertEquals(1, AnkiquestGoalPlan.extra(3, 30))
        assertEquals(7, AnkiquestGoalPlan.extra(7, 1))
        assertEquals(0, AnkiquestGoalPlan.extra(0, 30))
    }

    @Test
    fun `old daily percentages become the closest plan length`() {
        assertEquals(60, AnkiquestGoalPlan.daysForPercent(5))
        assertEquals(30, AnkiquestGoalPlan.daysForPercent(10))
        assertEquals(14, AnkiquestGoalPlan.daysForPercent(20))
        assertEquals(7, AnkiquestGoalPlan.daysForPercent(34))
        assertEquals(1, AnkiquestGoalPlan.daysForPercent(100))
        assertEquals(7, AnkiquestGoalPlan.daysForPercent(50))
        assertEquals(60, AnkiquestGoalPlan.daysForPercent(1))
    }

    @Test
    fun `a backlog plan asks for today's cards and a share of the backlog`() {
        assertEquals(Projection(cards = 50, seconds = 500, clearedInDays = 30), project(Mode.BACKLOG, 30))
        assertEquals(Projection(cards = 83, seconds = 830, clearedInDays = 7), project(Mode.BACKLOG, 7))
    }

    @Test
    fun `a number of cards clears the backlog with what is left after the cards that come due`() {
        assertEquals(Projection(cards = 100, seconds = 1000, clearedInDays = 5), project(Mode.CARDS, 100))
        assertEquals(Projection(cards = 36, seconds = 360, clearedInDays = 600), project(Mode.CARDS, 36))
    }

    @Test
    fun `too few cards never clear the backlog`() {
        assertNull(project(Mode.CARDS, 35)!!.clearedInDays)
        assertNull(project(Mode.CARDS, 10)!!.clearedInDays)
        assertNull(project(Mode.MINUTES, 5)!!.clearedInDays)
    }

    @Test
    fun `minutes become cards at the player's own pace`() {
        assertEquals(Projection(cards = 120, seconds = 1200, clearedInDays = 4), project(Mode.MINUTES, 20))
        val slow = forecast.copy(secondsPerCard = 7.0)
        assertEquals(171, AnkiquestGoalPlan.project(Choice(Mode.MINUTES, 20), slow)!!.cards)
    }

    @Test
    fun `nothing overdue needs no days to clear`() {
        val clear = forecast.copy(backlog = 0)
        assertEquals(Projection(cards = 40, seconds = 400, clearedInDays = 0), AnkiquestGoalPlan.project(Choice(Mode.BACKLOG, 30), clear))
        assertEquals(0, AnkiquestGoalPlan.project(Choice(Mode.CARDS, 1), clear)!!.clearedInDays)
        assertEquals(0, AnkiquestGoalPlan.project(Choice(Mode.MINUTES, 1), clear)!!.clearedInDays)
    }

    @Test
    fun `a goal that is off has no estimates`() {
        assertNull(project(Mode.OFF, 100))
    }

    @Test
    fun `the smallest shrinking goal is one more card than come due`() {
        assertEquals(36, AnkiquestGoalPlan.shrinkingCards(35.5))
        assertEquals(36, AnkiquestGoalPlan.shrinkingCards(35.0))
        assertEquals(1, AnkiquestGoalPlan.shrinkingCards(0.0))
        assertEquals(36, AnkiquestGoalPlan.keepingUpCards(35.5))
        assertEquals(0, AnkiquestGoalPlan.keepingUpCards(0.0))
    }

    @Test
    fun `the forecast counts the next week's due cards and the recent answer time`() {
        val fresh = AnkiquestGoalPlan.forecast(col)
        assertEquals(0.0, fresh.dailyDue)
        assertEquals(AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD, fresh.secondsPerCard)
        val today = col.sched.today
        repeat(3) { addBasicNote("front $it", "back") }
        col.db.execute("update cards set type = 2, queue = 2, due = ?", today + 3)
        col.db.execute(
            "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, 0, 3, 1, 1, 2500, 12000, 1)",
            1L,
        )
        col.db.execute(
            "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, 0, 3, 1, 1, 2500, 6000, 1)",
            TimeManager.time.intTimeMS(),
        )
        val week = AnkiquestGoalPlan.forecast(col)
        assertEquals(3.0 / AnkiquestGoalPlan.FORECAST_DAYS, week.dailyDue)
        assertEquals(6.0, week.secondsPerCard, "answers older than 30 days are left out")
        assertEquals(0, week.backlog)
    }

    private fun project(
        mode: Mode,
        value: Int,
    ) = AnkiquestGoalPlan.project(Choice(mode, value), forecast)
}
