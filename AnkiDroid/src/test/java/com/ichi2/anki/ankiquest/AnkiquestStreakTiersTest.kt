// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 33])
class AnkiquestStreakTiersTest : RobolectricTest() {
    private fun board(streak: Int): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("user", "hill")
                .put("streak", streak)
                .put("streak_state", "studied")
                .put("today_reviews", 20)
                .put("today_minutes", 12)
                .put("streak_minutes", 10)
                .put("day_ends_at", TimeManager.time.intTimeMS() + 3_600_000),
        )

    private fun render(
        board: JSONArray?,
        size: Size,
    ): View = AnkiquestStreakWidget.views(targetContext, board, "hill", size).apply(targetContext, FrameLayout(targetContext))

    @Test
    fun `flame grows at each tier and stays stable until the next`() {
        for (size in Size.entries) {
            var previous = 0
            for ((first, last) in listOf(1 to 6, 7 to 29, 30 to 99, 100 to 364, 365 to 1000)) {
                val number = render(board(first), size).findViewById<TextView>(R.id.ankiquest_streak_number)
                val flame = assertNotNull(number.compoundDrawablesRelative[2], "$size at $first")
                assertTrue(flame.intrinsicHeight > previous, "$size at $first")
                val later = render(board(last), size).findViewById<TextView>(R.id.ankiquest_streak_number)
                assertEquals(flame.intrinsicHeight, assertNotNull(later.compoundDrawablesRelative[2]).intrinsicHeight)
                assertEquals(first.toString(), number.text.toString())
                previous = flame.intrinsicHeight
            }
        }
    }

    @Test
    fun `large widgets name the next milestone at tier boundaries`() {
        for (size in listOf(Size.LARGE, Size.HUGE)) {
            for ((streak, expected) in listOf(1 to 7, 6 to 7, 7 to 30, 29 to 30, 30 to 100, 99 to 100, 100 to 365, 364 to 365)) {
                val view = render(board(streak), size)
                val milestone = assertNotNull(view.findViewById<TextView>(R.id.ankiquest_streak_milestone))
                val remaining = expected - streak
                val days = if (remaining == 1) "day" else "days"
                assertEquals(View.VISIBLE, milestone.visibility)
                assertEquals("$remaining $days to $expected 🔥", milestone.text.toString())
                assertContains(view.contentDescription.toString(), milestone.text.toString())
            }
            val final = render(board(365), size)
            assertEquals(View.GONE, final.findViewById<TextView>(R.id.ankiquest_streak_milestone).visibility)
        }
    }

    @Test
    fun `missing stale and zero streaks clear the optional details on reapply`() {
        val stale = board(100).apply { getJSONObject(0).put("day_ends_at", TimeManager.time.intTimeMS()) }
        for (size in Size.entries) {
            val view = render(board(100), size)
            for (missing in listOf(null, JSONArray(), board(0), stale)) {
                AnkiquestStreakWidget.views(targetContext, missing, "hill", size).reapply(targetContext, view)
                assertNull(view.findViewById<TextView>(R.id.ankiquest_streak_number).compoundDrawablesRelative[2], size.name)
                val milestone = view.findViewById<TextView>(R.id.ankiquest_streak_milestone)
                if (size == Size.LARGE || size == Size.HUGE) {
                    assertEquals(View.GONE, assertNotNull(milestone).visibility, size.name)
                } else {
                    assertNull(milestone, size.name)
                }
            }
        }
    }
}
