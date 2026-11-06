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
    fun `every size shows the streak number`() {
        val board = JSONArray().put(me(42, "studied", 30, 12, 10))
        for (size in AnkiquestStreakWidget.Size.entries) {
            val widget =
                AnkiquestStreakWidget
                    .views(targetContext, board, "hill", size)
                    .apply(targetContext, FrameLayout(targetContext))
            assertEquals("42", widget.text(R.id.ankiquest_streak_number), size.name)
        }
    }

    @Test
    fun `the layout grows with the widget`() {
        assertEquals(AnkiquestStreakWidget.Size.SMALL, AnkiquestStreakWidget.Size.fitting(57f, 57f))
        assertEquals(AnkiquestStreakWidget.Size.MEDIUM, AnkiquestStreakWidget.Size.fitting(130f, 110f))
        assertEquals(AnkiquestStreakWidget.Size.MEDIUM, AnkiquestStreakWidget.Size.fitting(320f, 110f), "a wide strip keeps the card")
        assertEquals(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.fitting(250f, 260f))
        assertEquals(
            AnkiquestStreakWidget.Size.LARGE,
            AnkiquestStreakWidget.Size.fitting(250f, 600f),
            "a narrow column keeps the large card",
        )
        assertEquals(AnkiquestStreakWidget.Size.HUGE, AnkiquestStreakWidget.Size.fitting(320f, 400f))
        assertEquals(AnkiquestStreakWidget.Size.HUGE, AnkiquestStreakWidget.Size.fitting(800f, 800f))
    }

    @Test
    fun `the companion's pose follows the streak`() {
        assertEquals(R.drawable.aki_comeback, AnkiquestStreakWidget.pose("studied", 1))
        assertEquals(R.drawable.aki_streak, AnkiquestStreakWidget.pose("studied", 3))
        assertEquals(R.drawable.aki_streak_hot, AnkiquestStreakWidget.pose("studied", 10))
        assertEquals(R.drawable.aki_streak_blazing, AnkiquestStreakWidget.pose("studied", 45))
        assertEquals(R.drawable.aki_streak_legend, AnkiquestStreakWidget.pose("studied", 365))
        assertEquals(R.drawable.aki_freeze_used, AnkiquestStreakWidget.pose("protected", 10))
        assertEquals(R.drawable.aki_at_risk, AnkiquestStreakWidget.pose("short", 10))
        assertEquals(R.drawable.aki_sleeping, AnkiquestStreakWidget.pose("pending", 0))
        assertEquals(R.drawable.aki_face, AnkiquestStreakWidget.pose("unknown", 10))
    }

    @Test
    fun `the big companion fits today's state and streak`() {
        val now = TimeManager.time.intTimeMS()
        val poses = { state: String, streak: Int, entry: JSONObject?, goal: Boolean ->
            AnkiquestStreakWidget.poses(state, streak, entry, goal, now)
        }
        val day = { minutes: Int, needed: Int, hoursLeft: Long ->
            JSONObject()
                .put("today_minutes", minutes)
                .put("streak_minutes", needed)
                .put("day_ends_at", now + hoursLeft * 3_600_000)
        }
        assertEquals(listOf(R.drawable.aki_celebrate, R.drawable.aki_streak), poses("studied", 3, day(20, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_goal, R.drawable.aki_celebrate), poses("studied", 3, day(20, 0, 10), true))
        assertEquals(listOf(R.drawable.aki_minimum), poses("studied", 40, day(11, 10, 10), false), "the minimum was just reached")
        assertEquals(listOf(R.drawable.aki_streak_hot, R.drawable.aki_celebrate), poses("studied", 10, day(30, 10, 10), false))
        assertEquals(listOf(R.drawable.aki_streak_blazing, R.drawable.aki_celebrate), poses("studied", 45, day(30, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone), poses("studied", 365, day(30, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_comeback), poses("studied", 1, day(30, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_freeze_used, R.drawable.aki_freeze), poses("protected", 10, day(0, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_at_risk), poses("pending", 10, day(0, 0, 3), false), "the last hours of the day")
        assertEquals(listOf(R.drawable.aki_review, R.drawable.aki_welcome), poses("short", 10, day(4, 10, 10), false))
        assertEquals(listOf(R.drawable.aki_sleeping, R.drawable.aki_comeback), poses("pending", 0, day(0, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_face, R.drawable.aki_welcome), poses("unknown", 10, null, false))
    }

    @Test
    fun `the big companion keeps its pose all day and changes between days`() {
        val day = 86_400_000L
        val entry = { endsAt: Long -> JSONObject().put("day_ends_at", endsAt) }
        val pose = { endsAt: Long -> AnkiquestStreakWidget.bigPose("studied", 3, entry(endsAt), false, endsAt - 1) }
        val today = 20_000 * day + 5 * 3_600_000
        assertEquals(pose(today), AnkiquestStreakWidget.bigPose("studied", 3, entry(today), false, today - 10 * 3_600_000))
        assertEquals(
            setOf(R.drawable.aki_celebrate, R.drawable.aki_streak),
            setOf(pose(today), pose(today + day)),
            "two days in a row show both poses",
        )
    }
}
