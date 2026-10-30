// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestStreakWidgetTest : RobolectricTest() {
    private fun me(
        streak: Int,
        state: String,
        reviews: Int,
        minutes: Int = 0,
        needed: Int = 0,
    ): JSONObject =
        JSONObject()
            .put("user", "hill")
            .put("display", "hill")
            .put("streak", streak)
            .put("streak_state", state)
            .put("today_reviews", reviews)
            .put("today_minutes", minutes)
            .put("streak_minutes", needed)
            .put("day_ends_at", TimeManager.time.intTimeMS() + 3_600_000)

    private fun render(
        board: JSONArray?,
        player: String? = "hill",
    ): View =
        AnkiquestStreakWidget
            .views(targetContext, board, player)
            .apply(targetContext, FrameLayout(targetContext))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `shows your own streak as a big number with today's status`() {
        val board = JSONArray().put(me(5, "pending", 0).put("user", "friend")).put(me(42, "studied", 30, 12, 10))
        val widget = render(board)
        assertEquals("42", widget.text(R.id.ankiquest_streak_number))
        assertEquals("day streak", widget.text(R.id.ankiquest_streak_label))
        assertEquals("Studied today", widget.text(R.id.ankiquest_streak_status))
    }

    @Test
    fun `counts the minutes still missing for the server's minimum`() {
        val widget = render(JSONArray().put(me(9, "pending", 8, 6, 10)))
        assertEquals("6 of 10 minutes today", widget.text(R.id.ankiquest_streak_status))
    }

    @Test
    fun `asks to set up the account when there is no board`() {
        val widget = render(null)
        assertEquals("–", widget.text(R.id.ankiquest_streak_number))
        assertEquals("Set up ankiquest in Settings", widget.text(R.id.ankiquest_streak_status))
    }

    @Test
    fun `the companion's pose follows the streak`() {
        assertEquals(R.drawable.aki_streak, AnkiquestStreakWidget.pose("studied", 10))
        assertEquals(R.drawable.aki_freeze, AnkiquestStreakWidget.pose("protected", 10))
        assertEquals(R.drawable.aki_review, AnkiquestStreakWidget.pose("short", 10))
        assertEquals(R.drawable.aki_welcome, AnkiquestStreakWidget.pose("pending", 0))
        assertEquals(R.drawable.aki_face, AnkiquestStreakWidget.pose("unknown", 10))
    }
}
