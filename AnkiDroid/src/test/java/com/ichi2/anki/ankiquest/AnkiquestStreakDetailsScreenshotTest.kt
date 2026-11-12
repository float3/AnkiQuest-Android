// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import com.ichi2.anki.common.preferences.sharedPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Config(sdk = [33])
class AnkiquestStreakDetailsScreenshotTest : ScreenshotTest() {
    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun statesAndGoals() {
        val now = 1_750_000_000_000L
        val prefs = targetContext.sharedPrefs()
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
            putBoolean(AnkiquestIcon.PREF_ENABLED, false)
        }
        AnkiquestWidget.forgetFresh()
        val account = assertNotNull(HomeAccount.current(prefs))
        val layoutProblems = mutableListOf<String>()
        val sizes =
            listOf(Triple(Size.SMALL, 57, 57), Triple(Size.MEDIUM, 100, 90), Triple(Size.LARGE, 200, 220), Triple(Size.HUGE, 300, 380))
        for ((size, width, height) in sizes) {
            val grid =
                LinearLayout(targetContext).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                }
            val density = grid.resources.displayMetrics.density
            val widthPx = (width * density).toInt()
            val heightPx = (height * density).toInt()
            for (examples in listOf("risk", "goal", "protected", "none", "syncing", "unknown").chunked(2)) {
                val row = LinearLayout(targetContext)
                for (example in examples) {
                    val state =
                        when (example) {
                            "goal" -> "studied"
                            "protected" -> "protected"
                            else -> "pending"
                        }
                    val minutes =
                        when (example) {
                            "goal" -> 10
                            "risk", "syncing" -> 6
                            else -> 0
                        }
                    val profile =
                        JSONObject()
                            .put("streak", if (example == "none") 0 else 9)
                            .put("streak_state", state)
                            .put("day_ends_at", if (example == "unknown") now else now + 3_600_000)
                            .put("last_review_id", now - 60_000)
                            .put(
                                "today",
                                JSONObject()
                                    .put("starts_at", now - 7_200_000)
                                    .put("reviews", if (minutes > 0) 12 else 0)
                                    .put("minutes", minutes)
                                    .put("time_ms", minutes * 60_000)
                                    .put("streak_minutes", 10),
                            ).put(
                                "goal",
                                JSONObject()
                                    .put("mode", "cards")
                                    .put("value", 20)
                                    .put("progress", 12)
                                    .put("reached", false),
                            )
                    AnkiquestDay.remember(profile, prefs)
                    val server = assertNotNull(AnkiquestDay.Server.profile(profile))
                    AnkiquestDay.rememberLocal(
                        account.scope,
                        server,
                        if (example == "syncing") listOf(AnkiquestDay.Review(now - 1, 240_000)) else emptyList(),
                        prefs,
                    )
                    AnkiquestIcon.update(targetContext, profile, now, prefs)
                    val board = JSONArray().put(AnkiquestWidget.ownRow(profile, null).put("user", "hill"))
                    val widget =
                        AnkiquestStreakWidget
                            .views(targetContext, board, "hill", size, now)
                            .apply(targetContext, FrameLayout(targetContext))
                    row.addView(widget, LinearLayout.LayoutParams(widthPx, heightPx))
                }
                grid.addView(row)
            }
            grid.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx * 3, View.MeasureSpec.EXACTLY),
            )
            grid.layout(0, 0, widthPx * 2, heightPx * 3)
            val snapshot = grid.drawToBitmap()
            for (rowIndex in 0 until grid.childCount) {
                val row = grid.getChildAt(rowIndex) as LinearLayout
                for (column in 0 until row.childCount) {
                    val widget = row.getChildAt(column)
                    val number = widget.findViewById<TextView>(R.id.ankiquest_streak_number)
                    assertTrue(number.height >= number.layout.height, "$size clips the number at $rowIndex/$column")
                    val status = widget.findViewById<TextView>(R.id.ankiquest_streak_status)
                    if (status.isVisible) {
                        val lastLine = minOf(status.maxLines, status.layout.lineCount) - 1
                        if (lastLine >= 0 && status.layout.getLineEnd(lastLine) < status.text.length) {
                            layoutProblems += "$size truncates status at $rowIndex/$column: ${status.text}"
                        }
                        if (status.layout.height > status.height) {
                            layoutProblems +=
                                "$size clips status height at $rowIndex/$column: ${status.layout.height}px into ${status.height}px"
                        }
                    }
                    if (size == Size.LARGE || size == Size.HUGE) {
                        val companion = widget.findViewById<View>(R.id.ankiquest_streak_companion)
                        if (companion.isVisible && companion.height < 24 * density) {
                            layoutProblems += "$size leaves only ${companion.height / density} dp for Aki at $rowIndex/$column"
                        }
                    }
                    val goal = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_goal)
                    if (goal.isVisible) {
                        // These captures are not attached to a window, so screen locations are all zero.
                        val goalBounds = Rect(0, 0, goal.width, goal.height)
                        (widget as ViewGroup).offsetDescendantRectToMyCoords(goal, goalBounds)
                        assertTrue(
                            goal.height > 0 && goalBounds.top >= 0 && goalBounds.bottom <= widget.height,
                            "$size clips the goal at $rowIndex/$column",
                        )
                        if (size == Size.SMALL) {
                            val outline = Outline().also { widget.outlineProvider.getOutline(widget, it) }
                            val bounds = Rect()
                            if (outline.getRect(bounds)) {
                                val radius = outline.radius
                                for (x in listOf(goalBounds.left, goalBounds.right)) {
                                    for (y in listOf(goalBounds.top, goalBounds.bottom)) {
                                        val centerX = x.toFloat().coerceIn(bounds.left + radius, bounds.right - radius)
                                        val centerY = y.toFloat().coerceIn(bounds.top + radius, bounds.bottom - radius)
                                        if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) > (radius + 1) * (radius + 1)) {
                                            layoutProblems += "$size draws the goal beyond the rounded card at $rowIndex/$column: " +
                                                "point=$x,$y bounds=$bounds radius=$radius goal=$goalBounds"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val directory = "build/outputs/roborazzi/${javaClass.simpleName}"
            val options = provideRoborazziContext().options
            snapshot.captureRoboImage(
                filePath = "$directory/${fileNamePrefix}${size.name.lowercase()}.png",
                roborazziOptions = options.copy(compareOptions = options.compareOptions.copy(outputDirectoryPath = "$directory/diffs")),
            )
        }
        assertTrue(layoutProblems.isEmpty(), layoutProblems.joinToString("\n"))
    }
}
