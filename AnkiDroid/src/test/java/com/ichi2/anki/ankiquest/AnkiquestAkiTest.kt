// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestAkiTest : RobolectricTest() {
    private fun profile(
        state: String,
        reviews: Int,
    ) = JSONObject()
        .put("streak_state", state)
        .put("day_ends_at", Long.MAX_VALUE)
        .put("today", JSONObject().put("reviews", reviews).put("streak_minutes", 10))

    @Test
    fun `no history or empty decks never earns a celebration`() {
        assertEquals(AnkiquestAki.Mood.WELCOME, AnkiquestAki.mood(null, null))
        assertEquals(AnkiquestAki.Mood.WELCOME, AnkiquestAki.mood(HomeLocal(emptyList(), 0), null))
        assertEquals(AnkiquestAki.Mood.WELCOME, AnkiquestAki.mood(HomeLocal(listOf(HomeDeck(1, "Empty", 0, 0, 0)), 1), null))
    }

    @Test
    fun `cards due locally keep the focus on studying after remote progress`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        assertEquals(AnkiquestAki.Mood.REVIEW, AnkiquestAki.mood(local, profile("studied", 20)))
    }

    @Test
    fun `finishing selected deck does not claim all decks are done`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0), HomeDeck(2, "Geography", 0, 0, 2)), 1)
        assertEquals(AnkiquestAki.Mood.REVIEW, AnkiquestAki.mood(local, profile("studied", 20)))
    }

    @Test
    fun `celebration needs a real study day and no remaining local cards`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1)
        assertEquals(AnkiquestAki.Mood.CELEBRATE, AnkiquestAki.mood(local, profile("studied", 20)))
        assertEquals(AnkiquestAki.Mood.WELCOME, AnkiquestAki.mood(local, profile("pending", 0)))
    }

    @Test
    fun `the companion celebrates unsent study that secures today`() {
        val dayStarts = 10_000L
        val profile =
            profile("pending", 0)
                .put("day_ends_at", dayStarts + 86_400_000)
                .put("today", JSONObject().put("starts_at", dayStarts).put("reviews", 0).put("streak_minutes", 10))
        val goal =
            AnkiquestDailyGoal.Status(
                day = 1,
                minutes = 10,
                streakMinutes = 10,
                studiedCards = 1,
                mode = AnkiquestDailyGoal.Mode.OFF,
                goal = AnkiquestDailyGoal.Goal(0),
                unsent = listOf(AnkiquestDay.Review(dayStarts + 1, 600_000)),
            )
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1, goal = goal)
        assertEquals(AnkiquestAki.Mood.CELEBRATE, AnkiquestAki.mood(local, profile, now = dayStarts + 2))
    }

    @Test
    fun `only an actually protected streak gets the freeze pose`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 4)), 1)
        assertEquals(AnkiquestAki.Mood.FREEZE, AnkiquestAki.mood(local, profile("protected", 0)))
        assertEquals(AnkiquestAki.Mood.REVIEW, AnkiquestAki.mood(local, profile("pending", 0).put("freezes", 3)))
    }

    @Test
    fun `expired progress never promises today's protection or completion`() {
        val local = HomeLocal(listOf(HomeDeck(1, "Spanish", 0, 0, 0)), 1)
        assertEquals(AnkiquestAki.Mood.WELCOME, AnkiquestAki.mood(local, profile("protected", 0).put("day_ends_at", 1000), now = 1000))
        assertEquals(AnkiquestAki.Mood.WELCOME, AnkiquestAki.mood(local, profile("studied", 20).put("day_ends_at", 1000), now = 1001))
    }

    @Test
    fun `notification art is downsampled and decorative art is not an extra control`() {
        val bitmap = assertNotNull(AnkiquestAki.notificationIcon(targetContext))
        assertTrue(bitmap.width <= 192 && bitmap.height <= 192)
        assertEquals(
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            AnkiquestAki.image(targetContext, com.ichi2.anki.R.drawable.aki_review).importantForAccessibility,
        )
    }
}
