// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.drawToBitmap
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [33])
class AnkiquestStreakScreenshotTest : ScreenshotTest() {
    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun tiers() {
        // Fixed study day keeps the rotating companion pose stable across baselines.
        val now = 1_750_000_000_000L
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
            for (streaks in listOf(1, 7, 30, 100, 365, 1000).chunked(2)) {
                val row = LinearLayout(targetContext)
                for (streak in streaks) {
                    val board =
                        JSONArray().put(
                            JSONObject()
                                .put("user", "hill")
                                .put("streak", streak)
                                .put("streak_state", "studied")
                                .put("today_reviews", 20)
                                .put("today_minutes", 12)
                                .put("streak_minutes", 10)
                                .put("day_ends_at", now + 3_600_000),
                        )
                    val widget =
                        AnkiquestStreakWidget
                            .views(
                                targetContext,
                                board,
                                "hill",
                                size,
                                now,
                            ).apply(targetContext, FrameLayout(targetContext))
                    row.addView(widget, LinearLayout.LayoutParams(widthPx, heightPx))
                }
                grid.addView(row)
            }
            grid.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx * 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx * 3, View.MeasureSpec.EXACTLY),
            )
            grid.layout(0, 0, widthPx * 2, heightPx * 3)
            val directory = "build/outputs/roborazzi/${javaClass.simpleName}"
            val options = provideRoborazziContext().options
            grid.drawToBitmap().captureRoboImage(
                filePath = "$directory/${fileNamePrefix}${size.name.lowercase()}.png",
                roborazziOptions = options.copy(compareOptions = options.compareOptions.copy(outputDirectoryPath = "$directory/diffs")),
            )
        }
    }
}
