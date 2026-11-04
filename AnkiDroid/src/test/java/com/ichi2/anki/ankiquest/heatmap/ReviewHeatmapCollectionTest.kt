// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewHeatmapCollectionTest : RobolectricTest() {
    @Before
    fun useNativeSchedulerClock() {
        // The Rust scheduler uses the system clock, not RobolectricTest's 2020 clock.
        TimeManager.reset()
    }

    @Test
    fun `tap time search uses current scheduler day without requiring saved history`() {
        val today = ReviewHeatmap.studyDate(col.sched.dayCutoff)
        val data =
            ReviewHeatmap.summarize(
                today,
                mapOf(today to 2, today.minusDays(1) to 3),
                mapOf(today.plusDays(1) to 4, today.plusDays(90) to 5),
            )
        for (offset in listOf(-1L, 0L, 1L, 90L)) {
            val date = today.plusDays(offset)
            assertEquals(ReviewHeatmap.searchForDay(data, date), ReviewHeatmap.searchForDay(col, date))
        }
        assertNull(ReviewHeatmap.searchForDay(col, today.plusDays(91)))
        assertEquals(0, col.db.queryScalar("select count(*) from revlog"))
    }

    @Test
    fun `retained reviews include deleted cards but ignore manual and future entries`() {
        val cardId = addBasicNote().firstCard().id
        val manualOnlyCard = addBasicNote("Manual only", "Back").firstCard().id
        val rescheduledOnlyCard = addBasicNote("Rescheduled only", "Back").firstCard().id
        val unratedOnlyCard = addBasicNote("Unrated only", "Back").firstCard().id
        val cutoff = col.sched.dayCutoff * 1000L
        review(cutoff - DAY_MILLIS + 100, cardId)
        review(cutoff - DAY_MILLIS + 200, cardId, type = 3)
        review(cutoff - DAY_MILLIS + 300, cardId + 1000)
        review(cutoff - DAY_MILLIS + 400, manualOnlyCard, ease = 0, type = 4)
        review(cutoff - DAY_MILLIS + 500, rescheduledOnlyCard, ease = 0, type = 5)
        review(cutoff - DAY_MILLIS + 600, unratedOnlyCard, ease = 0, type = 0)
        review(cutoff, cardId)

        val data = ReviewHeatmap.load(col)
        assertEquals(3, data.reviews[data.today])
        assertEquals(3L, data.totalReviews)
        assertEquals(1, data.daysStudied)
        assertEquals(1, data.currentStreak)
        // Answers and retained deleted-card history do not imply three browsable cards.
        assertEquals(listOf(cardId), col.findCards(assertNotNull(ReviewHeatmap.searchForDay(data, data.today))))
    }

    @Test
    fun `exact cutoff milliseconds and repeated cards agree with native day search`() {
        val firstCard = addBasicNote("First", "Back").firstCard().id
        val secondCard = addBasicNote("Second", "Back").firstCard().id
        val cutoff = col.sched.dayCutoff * 1000L
        review(cutoff - 2 * DAY_MILLIS, firstCard)
        review(cutoff - DAY_MILLIS - 1, secondCard)
        review(cutoff - DAY_MILLIS, firstCard)
        review(cutoff - 1, firstCard)

        val data = ReviewHeatmap.load(col)
        assertEquals(2, data.reviews[data.today])
        assertEquals(2, data.reviews[data.today.minusDays(1)])
        assertEquals(2, data.currentStreak)
        assertEquals(setOf(firstCard), col.findCards(assertNotNull(ReviewHeatmap.searchForDay(data, data.today))).toSet())
        assertEquals(
            setOf(firstCard, secondCard),
            col.findCards(assertNotNull(ReviewHeatmap.searchForDay(data, data.today.minusDays(1)))).toSet(),
        )
    }

    @Test
    fun `forecast matches native search for review learning preview and filtered cards`() {
        val tomorrow = col.sched.today + 1
        val dueBySeconds = (col.sched.dayCutoff + 86_400).toInt()
        val expected =
            setOf(
                scheduledCard(queue = 2, due = tomorrow),
                scheduledCard(queue = 3, due = tomorrow, type = 1),
                scheduledCard(queue = 1, due = dueBySeconds, type = 1),
                scheduledCard(queue = 4, due = dueBySeconds, type = 2),
                scheduledCard(queue = 2, due = col.sched.today + 20, originalDue = tomorrow),
            )
        scheduledCard(queue = -1, due = tomorrow)
        scheduledCard(queue = -2, due = tomorrow)
        scheduledCard(queue = -3, due = tomorrow)
        scheduledCard(queue = 0, due = tomorrow, type = 0)
        scheduledCard(queue = 4, due = dueBySeconds, type = 0)
        scheduledCard(queue = 2, due = col.sched.today)
        scheduledCard(queue = 2, due = col.sched.today + 91)
        val lastForecastCard = scheduledCard(queue = 2, due = col.sched.today + 90)

        val data = ReviewHeatmap.load(col)
        assertTrue(data.reviews.isEmpty())
        assertEquals(expected.size, data.scheduled[data.today.plusDays(1)])
        assertEquals(2, data.scheduled.size)
        assertEquals(expected, col.findCards(assertNotNull(ReviewHeatmap.searchForDay(data, data.today.plusDays(1)))).toSet())
        assertEquals(
            listOf(lastForecastCard),
            col.findCards(assertNotNull(ReviewHeatmap.searchForDay(data, data.forecastEndDate))),
        )
    }

    private fun review(
        id: Long,
        cardId: Long,
        ease: Int = 3,
        type: Int = 1,
    ) {
        col.db.execute(
            "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, -1, ?, 1, 1, 2500, 1000, ?)",
            id,
            cardId,
            ease,
            type,
        )
    }

    private fun scheduledCard(
        queue: Int,
        due: Int,
        type: Int = 2,
        originalDue: Int = 0,
    ): Long {
        val cardId = addBasicNote("Card $queue $due $originalDue", "Back").firstCard().id
        val deckId = if (originalDue != 0) col.decks.newFiltered("Filtered $cardId") else 1L
        col.db.execute(
            "update cards set queue = ?, type = ?, due = ?, odue = ?, odid = ?, did = ? where id = ?",
            queue,
            type,
            due,
            originalDue,
            if (originalDue != 0) 1L else 0L,
            deckId,
            cardId,
        )
        return cardId
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
    }
}
