// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `shows progress to the minimum in each size`() {
        for (size in AnkiquestStreakWidget.Size.entries) {
            for (minutes in listOf(0, 6, 10, 12)) {
                val state = if (minutes >= 10) "studied" else "pending"
                val board = JSONArray().put(me(9, state, 8, minutes, 10))
                val widget =
                    AnkiquestStreakWidget
                        .views(targetContext, board, "hill", size)
                        .apply(targetContext, FrameLayout(targetContext))
                val progress = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
                val expected = minutes.coerceAtMost(10)
                assertEquals(View.VISIBLE, progress.visibility, size.name)
                assertEquals(10, progress.max, size.name)
                assertEquals(expected, progress.progress, size.name)
                val description = widget.contentDescription.toString()
                if (minutes < 10) {
                    assertContains(description, "$minutes of 10 minutes today", message = size.name)
                } else {
                    assertContains(description, "Studied today", message = size.name)
                    assertFalse(description.contains("10 of 10 minutes today"), size.name)
                }
            }
        }
    }

    @Test
    fun `hides the meter for stale or missing data`() {
        val stale = me(9, "pending", 8, 6, 10).put("day_ends_at", TimeManager.time.intTimeMS())
        for (board in listOf(null, JSONArray().put(stale), JSONArray().put(me(9, "pending", 8)))) {
            val progress = render(board).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            assertEquals(View.GONE, progress.visibility)
        }
    }

    @Test
    fun `studied and protected days fill the meter below the minimum`() {
        for (state in listOf("studied", "protected")) {
            val widget = render(JSONArray().put(me(9, state, 5, 3, 10)))
            val meter = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            assertEquals(View.VISIBLE, meter.visibility, state)
            assertEquals(10, meter.progress, state)
            assertFalse(widget.contentDescription.toString().contains("3 of 10 minutes"), state)
        }
    }

    @Test
    fun `a row without today's review count hides the meter`() {
        val row = me(9, "pending", 8, 6, 10).apply { remove("today_reviews") }
        val meter = render(JSONArray().put(row)).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
        assertEquals(View.GONE, meter.visibility)
    }

    @Test
    fun `an unsent earlier day hides the own meter despite a current row`() {
        val now = TimeManager.time.intTimeMS()
        val prefs = targetContext.sharedPrefs()
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        val server =
            AnkiquestDay.Server(
                streak = 9,
                streakState = "pending",
                dayEndsAt = now + 3_600_000,
                lastReviewId = now - 7_200_000,
                startsAt = now - 3_600_000,
                reviews = 8,
                timeMs = 360_000,
                streakMinutes = 10,
            )
        AnkiquestDay.remember(server.json(), prefs)
        AnkiquestDay.rememberLocal(
            assertNotNull(HomeAccount.current(prefs)).scope,
            server,
            listOf(AnkiquestDay.Review(now - 3_600_001, 60_000)),
            prefs,
        )
        assertEquals(AnkiquestDay.State.UNKNOWN, assertNotNull(AnkiquestDay.own(now)).label)

        for (size in AnkiquestStreakWidget.Size.entries) {
            val widget =
                AnkiquestStreakWidget
                    .views(targetContext, JSONArray().put(me(9, "pending", 8, 6, 10)), "hill", size)
                    .apply(targetContext, FrameLayout(targetContext))
            val meter = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            assertEquals(View.GONE, meter.visibility, size.name)
        }
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
        assertEquals(R.drawable.aki_comeback, AnkiquestStreakWidget.pose(AnkiquestDay.State.STUDIED, 1))
        assertEquals(R.drawable.aki_streak, AnkiquestStreakWidget.pose(AnkiquestDay.State.STUDIED, 3))
        assertEquals(R.drawable.aki_streak_hot, AnkiquestStreakWidget.pose(AnkiquestDay.State.STUDIED, 10))
        assertEquals(R.drawable.aki_streak_blazing, AnkiquestStreakWidget.pose(AnkiquestDay.State.STUDIED, 45))
        assertEquals(R.drawable.aki_streak_legend, AnkiquestStreakWidget.pose(AnkiquestDay.State.STUDIED, 365))
        assertEquals(R.drawable.aki_freeze_used, AnkiquestStreakWidget.pose(AnkiquestDay.State.PROTECTED, 10))
        assertEquals(R.drawable.aki_at_risk, AnkiquestStreakWidget.pose(AnkiquestDay.State.SHORT, 10))
        assertEquals(R.drawable.aki_sleeping, AnkiquestStreakWidget.pose(AnkiquestDay.State.PENDING, 0))
        assertEquals(R.drawable.aki_face, AnkiquestStreakWidget.pose(AnkiquestDay.State.UNKNOWN, 10))
    }

    @Test
    fun `the big companion fits today's state and streak`() {
        val now = TimeManager.time.intTimeMS()
        val poses = { state: AnkiquestDay.State, streak: Int, entry: JSONObject?, goal: Boolean ->
            AnkiquestStreakWidget.poses(state, streak, entry, goal, now)
        }
        val day = { minutes: Int, needed: Int, hoursLeft: Long ->
            JSONObject()
                .put("today_minutes", minutes)
                .put("streak_minutes", needed)
                .put("day_ends_at", now + hoursLeft * 3_600_000)
        }
        assertEquals(listOf(R.drawable.aki_celebrate, R.drawable.aki_streak), poses(AnkiquestDay.State.STUDIED, 3, day(20, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_goal, R.drawable.aki_celebrate), poses(AnkiquestDay.State.STUDIED, 3, day(20, 0, 10), true))
        assertEquals(
            listOf(R.drawable.aki_minimum),
            poses(AnkiquestDay.State.STUDIED, 40, day(11, 10, 10), false),
            "the minimum was just reached",
        )
        assertEquals(
            listOf(R.drawable.aki_streak_hot, R.drawable.aki_celebrate),
            poses(AnkiquestDay.State.STUDIED, 10, day(30, 10, 10), false),
        )
        assertEquals(
            listOf(R.drawable.aki_streak_blazing, R.drawable.aki_celebrate),
            poses(AnkiquestDay.State.STUDIED, 45, day(30, 0, 10), false),
        )
        assertEquals(
            listOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone),
            poses(AnkiquestDay.State.STUDIED, 365, day(30, 0, 10), false),
        )
        assertEquals(listOf(R.drawable.aki_comeback), poses(AnkiquestDay.State.STUDIED, 1, day(30, 0, 10), false))
        assertEquals(
            listOf(R.drawable.aki_freeze_used, R.drawable.aki_freeze),
            poses(AnkiquestDay.State.PROTECTED, 10, day(0, 0, 10), false),
        )
        assertEquals(
            listOf(R.drawable.aki_at_risk),
            poses(AnkiquestDay.State.PENDING, 10, day(0, 0, 3), false),
            "the last hours of the day",
        )
        assertEquals(listOf(R.drawable.aki_review, R.drawable.aki_welcome), poses(AnkiquestDay.State.SHORT, 10, day(4, 10, 10), false))
        assertEquals(listOf(R.drawable.aki_sleeping, R.drawable.aki_comeback), poses(AnkiquestDay.State.PENDING, 0, day(0, 0, 10), false))
        assertEquals(listOf(R.drawable.aki_face, R.drawable.aki_welcome), poses(AnkiquestDay.State.UNKNOWN, 10, null, false))
    }

    @Test
    fun `the big companion keeps its pose all day and changes between days`() {
        val day = 86_400_000L
        val entry = { endsAt: Long -> JSONObject().put("day_ends_at", endsAt) }
        val pose = { endsAt: Long -> AnkiquestStreakWidget.bigPose(AnkiquestDay.State.STUDIED, 3, entry(endsAt), false, endsAt - 1) }
        val today = 20_000 * day + 5 * 3_600_000
        assertEquals(pose(today), AnkiquestStreakWidget.bigPose(AnkiquestDay.State.STUDIED, 3, entry(today), false, today - 10 * 3_600_000))
        assertEquals(
            setOf(R.drawable.aki_celebrate, R.drawable.aki_streak),
            setOf(pose(today), pose(today + day)),
            "two days in a row show both poses",
        )
    }

    /** Edge of the companion bitmap each big layout shows at its largest, from its ImageView maxHeight. */
    private fun shownPx(size: AnkiquestStreakWidget.Size): Int {
        val dp = if (size == AnkiquestStreakWidget.Size.LARGE) LARGE_COMPANION_DP else HUGE_COMPANION_DP
        return minOf((dp * targetContext.resources.displayMetrics.density).toInt(), COMPANION_MAX_PX)
    }

    /** The companion's bitmap edge in [size], for a first streak day (one pose in every layout). */
    private fun companionPx(
        size: AnkiquestStreakWidget.Size,
        options: Bundle? = null,
    ): Int {
        val board = JSONArray().put(me(1, "studied", 20, 30, 10))
        val widget =
            AnkiquestStreakWidget
                .views(targetContext, board, "hill", size, options = options)
                .apply(targetContext, FrameLayout(targetContext))
        val drawable = widget.findViewById<ImageView>(R.id.ankiquest_streak_companion).drawable
        val bitmap = assertNotNull((drawable as? BitmapDrawable)?.bitmap, size.name)
        return minOf(bitmap.width, bitmap.height)
    }

    @Test
    @Config(qualifiers = "560dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the big companion is never upscaled on a dense screen`() {
        for (size in listOf(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.HUGE)) {
            val shown = shownPx(size)
            val actual = companionPx(size)
            assertTrue(actual >= shown, "$size shows $shown px from $actual px")
        }
    }

    @Test
    @Config(qualifiers = "xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the big companion is decoded for its shown size`() {
        for (size in listOf(AnkiquestStreakWidget.Size.LARGE, AnkiquestStreakWidget.Size.HUGE)) {
            val shown = shownPx(size)
            val actual = companionPx(size)
            // Power-of-two sampling covers the shown size with less than twice its edge.
            assertTrue(actual in shown until 2 * shown, "$size shows $shown px from $actual px")
        }
    }

    @Test
    @Config(qualifiers = "xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a narrow widget decodes a smaller companion`() {
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 150)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 400)
            }
        val shown = (150 * targetContext.resources.displayMetrics.density).toInt()
        val actual = companionPx(AnkiquestStreakWidget.Size.HUGE, options)
        assertTrue(actual in shown until 2 * shown, "150 dp shows $shown px from $actual px")
    }

    @Test
    @Config(qualifiers = "560dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `every streak pose covers the huge layout on a dense screen`() {
        val shown = shownPx(AnkiquestStreakWidget.Size.HUGE)
        // Every pose the big layouts rotate through (poses), and Ankilope's stand-ins for them.
        val poses =
            listOf(
                R.drawable.aki_face,
                R.drawable.aki_welcome,
                R.drawable.aki_freeze_used,
                R.drawable.aki_freeze,
                R.drawable.aki_goal,
                R.drawable.aki_celebrate,
                R.drawable.aki_comeback,
                R.drawable.aki_minimum,
                R.drawable.aki_streak_legend,
                R.drawable.aki_streak_milestone,
                R.drawable.aki_streak_blazing,
                R.drawable.aki_streak_hot,
                R.drawable.aki_streak,
                R.drawable.aki_sleeping,
                R.drawable.aki_at_risk,
                R.drawable.aki_review,
                R.drawable.ankilope_face,
                R.drawable.ankilope_welcome,
                R.drawable.ankilope_celebrate,
                R.drawable.ankilope_study,
            )
        assertEquals(20, poses.toSet().size)
        for (pose in poses) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(targetContext.resources, pose, bounds)
            val name = targetContext.resources.getResourceEntryName(pose)
            assertTrue(minOf(bounds.outWidth, bounds.outHeight) >= shown, "$name is ${bounds.outWidth} px for $shown px")
        }
    }

    private companion object {
        /** The ImageView maxHeight of the large and huge layouts. */
        const val LARGE_COMPANION_DP = 220
        const val HUGE_COMPANION_DP = 520

        /** The streak widget poses are exported at this size (float3/ankiquest#253). */
        const val COMPANION_MAX_PX = 1024
    }
}
