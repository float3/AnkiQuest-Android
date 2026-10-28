// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.QueueType
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestDecksTest : RobolectricTest() {
    @Before
    fun useNativeSchedulerClock() {
        // The native scheduler's day and cutoff use the system clock, not RobolectricTest's 2020 clock.
        TimeManager.reset()
    }

    @Test
    fun `study day changes at local rollover rather than midnight`() {
        val day = 20_000L
        val rolloverUtc = day * 86_400_000 + 2 * 3_600_000
        assertEquals(day - 1, AnkiquestDecks.day(rolloverUtc - 1, -120, 4))
        assertEquals(day, AnkiquestDecks.day(rolloverUtc, -120, 4))
        assertEquals(-1L, AnkiquestDecks.day(0, 0, 4))
    }

    @Test
    fun `last review completes its deck but unrelated deck stays due`() {
        addBasicNote()
        val other = col.decks.id("Geography")
        val otherCard = addBasicNote("Other", "Back").firstCard()
        otherCard.did = other
        col.updateCards(listOf(otherCard))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)

        assertEquals(0L, snapshot(1).getLong("remaining"))
        assertEquals(1L, snapshot(1).getLong("reviewed_today"))
        assertEquals(1L, snapshot(other).getLong("remaining"))
        val states = AnkiquestDecks.dailyStates(col, TimeManager.time.intTimeMS())
        assertEquals(DailyDeckStatus.DONE, states[1]?.status)
        assertEquals(DailyDeckStatus.REVIEW_NOW, states[other]?.status)
    }

    @Test
    fun `daily new limit can finish today with new cards left`() {
        repeat(2) { addBasicNote("Front $it", "Back") }
        val config = col.decks.configDictForDeckId(1)
        config.new.perDay = 1
        col.decks.save(config)
        assertEquals(1L, snapshot(1).getLong("remaining"))
        col.sched.answerCard(col.sched.card!!, Rating.EASY)

        assertEquals(1, col.findCards("is:new").size)
        assertEquals(0L, snapshot(1).getLong("remaining"))
        assertEquals(1L, snapshot(1).getLong("reviewed_today"))
    }

    @Test
    fun `learning later today keeps deck and parent unfinished`() {
        val parent = col.decks.id("Spanish")
        val child = col.decks.id("Spanish::Vocabulary")
        val card = addBasicNote().firstCard()
        card.did = child
        card.queue = QueueType.Lrn
        card.type = CardType.Lrn
        // Halfway between now and the cutoff: always still ahead, always before rollover, whatever time CI runs.
        card.due = ((TimeManager.time.intTime() + col.sched.dayCutoff) / 2).toInt()
        col.updateCards(listOf(card))

        assertEquals(1L, snapshot(child).getLong("remaining"))
        assertEquals(1L, snapshot(parent).getLong("remaining"))
        val states = AnkiquestDecks.dailyStates(col, TimeManager.time.intTimeMS())
        assertEquals(DailyDeckStatus.LEARNING_LATER, states[child]?.status)
        assertEquals(DailyDeckStatus.LEARNING_LATER, states[parent]?.status)
        assertEquals(card.due * 1000L, states[child]?.nextLearningAt)
    }

    @Test
    fun `learning after rollover is excluded`() {
        val card = addBasicNote().firstCard()
        card.queue = QueueType.Lrn
        card.type = CardType.Lrn
        card.due = (col.sched.dayCutoff + 60).toInt()
        col.updateCards(listOf(card))

        assertEquals(0L, snapshot(1).getLong("remaining"))
        assertEquals(DailyDeckStatus.NOTHING_SCHEDULED, AnkiquestDecks.dailyStates(col, TimeManager.time.intTimeMS())[1]?.status)
    }

    @Test
    fun `the study day window is Anki's own`() {
        val cutoff = col.sched.dayCutoff * 1000
        val card = addBasicNote().firstCard()
        card.queue = QueueType.Lrn
        card.type = CardType.Lrn
        card.due = (col.sched.dayCutoff + 60).toInt()
        col.updateCards(listOf(card))
        val laterClock = ((cutoff + 7_200_000).mod(86_400_000L) / 60_000).toInt()
        val rows = AnkiquestDecks.snapshots(col, cutoff - 3_600_000, laterClock, 0)
        val deck = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == "1" }

        assertEquals(0L, deck.getLong("remaining"))
    }

    @Test
    fun `due cards in a filtered deck keep the home deck unfinished`() {
        val card = addBasicNote().firstCard()
        val filtered = col.decks.newFiltered("Practice")
        card.oDid = 1
        card.did = filtered
        col.updateCards(listOf(card))

        assertEquals(1L, snapshot(1).getLong("remaining"))
        assertTrue(snapshots().none { it.getString("id") == filtered.toString() })
        val state = AnkiquestDecks.dailyStates(col, TimeManager.time.intTimeMS())[1]
        assertEquals(DailyDeckStatus.REVIEW_NOW, state?.status)
        assertEquals(1L, state?.readyCount)
    }

    @Test
    fun `empty default deck remains in catalog when tree omits it`() {
        col.decks.id("Spanish")
        assertTrue(snapshots().any { it.getString("id") == "1" })
        assertEquals(0L, snapshot(1).getLong("reviewed_today"))
        assertEquals(DailyDeckStatus.NOTHING_SCHEDULED, AnkiquestDecks.dailyStates(col, TimeManager.time.intTimeMS())[1]?.status)
    }

    private fun snapshot(id: Long): JSONObject = snapshots().single { it.getString("id") == id.toString() }

    private fun snapshots(): List<JSONObject> {
        val now = TimeManager.time.intTimeMS()
        val rows = AnkiquestDecks.snapshots(col, now, -TimeZone.getDefault().getOffset(now) / 60_000, 4)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
}
