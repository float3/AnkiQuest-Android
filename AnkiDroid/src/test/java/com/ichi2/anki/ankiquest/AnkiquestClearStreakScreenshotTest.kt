// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.get
import androidx.core.view.allViews
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Style
import com.ichi2.anki.common.preferences.sharedPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Native rendering on both wallpaper tones, at each layout's minimum footprint. */
@Config(sdk = [33])
class AnkiquestClearStreakScreenshotTest : ScreenshotTest() {
    private val now = 1_750_000_000_000L
    private val textContrast = 4.5

    @Test
    fun lightWallpaper() = captureStates("light_wallpaper", Color.rgb(250, 250, 250))

    @Test
    fun darkWallpaper() = captureStates("dark_wallpaper", Color.rgb(24, 24, 28))

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureStates(
        name: String,
        background: Int,
    ) {
        val prefs = targetContext.sharedPrefs()
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
            putBoolean(AnkiquestIcon.PREF_ENABLED, false)
        }
        AnkiquestWidget.forgetFresh()
        val sizes =
            listOf(Triple(Size.SMALL, 57, 57), Triple(Size.MEDIUM, 100, 90), Triple(Size.LARGE, 200, 220), Triple(Size.HUGE, 300, 380))
        for ((size, width, height) in sizes) {
            val grid =
                LinearLayout(targetContext).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    setBackgroundColor(background)
                }
            val density = grid.resources.displayMetrics.density
            val widthPx = (width * density).toInt()
            val heightPx = (height * density).toInt()
            for (examples in listOf("risk", "goal", "protected", "none", "syncing", "unknown").chunked(2)) {
                val row = LinearLayout(targetContext)
                for (example in examples) {
                    val board = remember(example)
                    val widget =
                        AnkiquestStreakWidget
                            .views(targetContext, board, "hill", size, now, Style.TRANSPARENT)
                            .apply(targetContext, FrameLayout(targetContext))
                    assertNull(widget.background)
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
            for (row in 0 until grid.childCount) {
                val children = grid.getChildAt(row) as ViewGroup
                for (column in 0 until children.childCount) {
                    checkLayout(children.getChildAt(column) as ViewGroup, size)
                }
            }
            val directory = "build/outputs/roborazzi/${javaClass.simpleName}"
            val options = provideRoborazziContext().options
            snapshot.captureRoboImage(
                filePath = "$directory/${fileNamePrefix}${name}_${size.name.lowercase()}.png",
                roborazziOptions = options.copy(compareOptions = options.compareOptions.copy(outputDirectoryPath = "$directory/diffs")),
            )
        }
    }

    private fun remember(example: String): JSONArray {
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
                .put(
                    "streak",
                    when (example) {
                        "none" -> 0
                        "risk" -> 9
                        else -> 365
                    },
                ).put("streak_state", state)
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
        val prefs = targetContext.sharedPrefs()
        AnkiquestDay.remember(profile, prefs)
        AnkiquestDay.rememberLocal(
            assertNotNull(HomeAccount.current(prefs)).scope,
            assertNotNull(AnkiquestDay.Server.profile(profile)),
            if (example == "syncing") listOf(AnkiquestDay.Review(now - 1, 240_000)) else emptyList(),
            prefs,
        )
        AnkiquestIcon.update(targetContext, profile, now, prefs)
        return JSONArray().put(AnkiquestWidget.ownRow(profile, null).put("user", "hill"))
    }

    private fun checkLayout(
        widget: ViewGroup,
        size: Size,
    ) {
        val number = widget.findViewById<TextView>(R.id.ankiquest_streak_number)
        assertTrue(number.height >= number.layout.height, "$size clips the number")
        val status = widget.findViewById<TextView>(R.id.ankiquest_streak_status)
        if (status.isVisible) {
            val lastLine = minOf(status.maxLines, status.layout.lineCount) - 1
            assertTrue(lastLine >= 0 && status.layout.getLineEnd(lastLine) == status.text.length, "$size truncates the status")
            assertTrue(status.layout.height <= status.height, "$size clips the status")
            // The shadow must separate small white text from a pale wallpaper.
            val text = status.drawToBitmap()
            var contrast = 1.0
            for (y in 0 until text.height) {
                for (x in 0 until text.width) {
                    val pixel = ColorUtils.compositeColors(text[x, y], Color.WHITE)
                    contrast = maxOf(contrast, ColorUtils.calculateContrast(Color.WHITE, pixel))
                }
            }
            assertTrue(contrast >= textContrast, "$size status outline contrast is $contrast on white")
        }
        for (meter in widget.allViews.filterIsInstance<ProgressBar>().filter { it.isVisible }) {
            val bounds = Rect(0, 0, meter.width, meter.height)
            widget.offsetDescendantRectToMyCoords(meter, bounds)
            assertTrue(bounds.top >= 0 && bounds.bottom <= widget.height, "$size clips progress")
            assertTrue(meter.height > meter.paddingTop + meter.paddingBottom, "$size has no visible progress area")
        }
    }
}
