// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmap
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapGrid
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapView
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import com.ichi2.anki.common.android.R as CommonR

/** The complete scrollable calendar at a narrow width, including its day targets and captions. */
class AnkiquestHeatmapScreenshotTest : ScreenshotTest() {
    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun calendar() = captureCalendar("calendar")

    @Test
    fun calendarLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureCalendar("calendar_200percent")
    }

    private fun captureCalendar(name: String) {
        val context = ContextThemeWrapper(targetContext, if (theme.isNight) CommonR.style.Theme_Dark else CommonR.style.Theme_Light)
        val today = LocalDate.of(2024, 9, 22)
        val reviews = (0L..600L).filter { it % 9 != 0L }.associate { today.minusDays(it) to (it % 140 + 1).toInt() }
        val scheduled = (1L..90L).associate { today.plusDays(it) to (it % 80 + 1).toInt() }
        val heatmap = ReviewHeatmapView(context).apply { setData(ReviewHeatmap.summarize(today, reviews, scheduled)) }
        val root =
            FrameLayout(context).apply {
                setBackgroundColor(MaterialColors.getColor(context, android.R.attr.colorBackground, 0))
                addView(heatmap)
            }
        val width = (320 * context.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        heatmap.findViewById<HorizontalScrollView>(R.id.review_heatmap_scroll).scrollTo(
            heatmap.findViewById<ReviewHeatmapGrid>(R.id.review_heatmap_grid).xForDate(today) - width / 2,
            0,
        )
        captureView(name, root)
    }
}
