// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.deckpicker.heatmap

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.ichi2.anki.common.android.R as CommonR

@RunWith(RobolectricTestRunner::class)
@Config(application = EmptyApplication::class, qualifiers = "en-rUS-mdpi")
@Category(EmptyApplicationCategory::class)
class ReviewHeatmapViewTest {
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), CommonR.style.Theme_Light)

    @Test
    fun `selecting leap day previews details and only the full size button opens cards`() {
        val leapDay = LocalDate.of(2024, 2, 29)
        val data = ReviewHeatmap.summarize(LocalDate.of(2024, 3, 1), mapOf(leapDay to 42), emptyMap())
        val opened = mutableListOf<LocalDate>()
        val view =
            ReviewHeatmapView(context).apply {
                setData(data)
                onDaySelected = { _, date -> opened += date }
            }
        layout(view)
        val grid = view.findViewById<ReviewHeatmapGrid>(R.id.review_heatmap_grid)
        val bounds = grid.cellBounds(leapDay.dayOfYear - 1)
        touch(grid, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY())
        touch(grid, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY())

        assertEquals(leapDay, grid.selectedDate)
        assertTrue(view.findViewById<TextView>(R.id.review_heatmap_selected_day).text.contains("February 29"))
        assertEquals("42 reviews", view.findViewById<TextView>(R.id.review_heatmap_selected_count).text.toString())
        assertTrue(opened.isEmpty())
        view.findViewById<View>(R.id.review_heatmap_view_cards).performClick()
        assertEquals(listOf(leapDay), opened)
    }

    @Test
    fun `year navigation includes a forecast across New Year and stops at available history`() {
        val today = LocalDate.of(2024, 12, 30)
        val view = ReviewHeatmapView(context)
        view.setData(ReviewHeatmap.summarize(today, mapOf(LocalDate.of(2023, 12, 31) to 1), mapOf(today.plusDays(3) to 8)))
        val previous = view.findViewById<View>(R.id.review_heatmap_previous_year)
        val next = view.findViewById<View>(R.id.review_heatmap_next_year)
        val year = view.findViewById<TextView>(R.id.review_heatmap_year)

        previous.performClick()
        assertEquals("2023", year.text.toString())
        assertFalse(previous.isEnabled)
        next.performClick()
        next.performClick()
        assertEquals("2025", year.text.toString())
        assertFalse(next.isEnabled)
        assertTrue(previous.isEnabled)
    }

    @Test
    fun `accessible days announce review counts and forecast estimates and can be selected`() {
        val today = LocalDate.of(2024, 2, 28)
        val leapDay = today.plusDays(1)
        val data = ReviewHeatmap.summarize(today, mapOf(today to 12), mapOf(leapDay to 3))
        val view = ReviewHeatmapView(context).apply { setData(data) }
        layout(view)
        val grid = view.findViewById<ReviewHeatmapGrid>(R.id.review_heatmap_grid)
        val provider = assertNotNull(grid.accessibilityNodeProvider)
        val todayNode = assertNotNull(provider.createAccessibilityNodeInfo(today.dayOfYear - 1))
        assertTrue(todayNode.contentDescription.toString().contains("12 reviews"))
        assertTrue(todayNode.contentDescription.toString().contains("Today"))
        val futureNode = assertNotNull(provider.createAccessibilityNodeInfo(leapDay.dayOfYear - 1))
        assertTrue(futureNode.contentDescription.toString().contains("February 29"))
        assertTrue(futureNode.contentDescription.toString().contains("3 review cards due (estimate)"))

        assertTrue(provider.performAction(leapDay.dayOfYear - 1, AccessibilityNodeInfoCompat.ACTION_CLICK, null))
        assertEquals(leapDay, grid.selectedDate)
        assertEquals("View due cards", view.findViewById<TextView>(R.id.review_heatmap_view_cards).text.toString())
        assertTrue(view.findViewById<View>(R.id.review_heatmap_view_cards).isEnabled)

        val beyondForecast = today.plusDays(91)
        provider.performAction(beyondForecast.dayOfYear - 1, AccessibilityNodeInfoCompat.ACTION_CLICK, null)
        assertFalse(view.findViewById<View>(R.id.review_heatmap_view_cards).isEnabled)
        assertEquals(
            context.getString(R.string.review_heatmap_forecast_unavailable),
            view.findViewById<TextView>(R.id.review_heatmap_selected_count).text.toString(),
        )
    }

    @Test
    fun `swiping the year does not select a day`() {
        val today = LocalDate.of(2024, 6, 1)
        val view = ReviewHeatmapView(context).apply { setData(ReviewHeatmap.summarize(today, mapOf(today to 12), emptyMap())) }
        layout(view)
        val grid = view.findViewById<ReviewHeatmapGrid>(R.id.review_heatmap_grid)
        val bounds = grid.cellBounds(today.minusDays(7).dayOfYear - 1)
        touch(grid, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY())
        touch(grid, MotionEvent.ACTION_MOVE, bounds.centerX() + 100, bounds.centerY())
        touch(grid, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY())
        assertEquals(today, grid.selectedDate)
    }

    @Test
    fun `loading error retry and no history are distinct states`() {
        val view = ReviewHeatmapView(context)
        var retries = 0
        view.onRetry = { retries++ }
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.review_heatmap_loading).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.review_heatmap_calendar).visibility)
        view.setError()
        assertEquals(View.GONE, view.findViewById<View>(R.id.review_heatmap_loading).visibility)
        view.findViewById<View>(R.id.review_heatmap_retry).performClick()
        assertEquals(1, retries)
        view.setData(ReviewHeatmap.summarize(LocalDate.of(2024, 1, 1), emptyMap(), emptyMap()))
        assertEquals(
            context.getString(R.string.review_heatmap_empty),
            view.findViewById<TextView>(R.id.review_heatmap_status).text.toString(),
        )
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.review_heatmap_calendar).visibility)
        assertFalse(view.findViewById<View>(R.id.review_heatmap_view_cards).isEnabled)
    }

    @Test
    fun `footer recycling keeps the chosen year and collapsed state`() {
        val today = LocalDate.of(2024, 6, 1)
        val adapter = ReviewHeatmapAdapter({ _, _ -> }, {})
        adapter.setData(ReviewHeatmap.summarize(today, mapOf(today.minusYears(1) to 5), emptyMap()))
        adapter.onDeckListCommitted(hasDecks = true)
        val parent = FrameLayout(context)
        val first = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(first, 0)
        first.heatmap.findViewById<View>(R.id.review_heatmap_previous_year).performClick()
        first.heatmap.findViewById<View>(R.id.review_heatmap_collapse).performClick()
        // A filter with no deck matches hides the footer without losing its chosen year or state.
        adapter.onDeckListCommitted(hasDecks = false)
        assertEquals(0, adapter.itemCount)
        adapter.setLoading()
        adapter.setData(ReviewHeatmap.summarize(today, mapOf(today.minusYears(1) to 5), emptyMap()))
        adapter.onDeckListCommitted(hasDecks = true)
        val recycled = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(recycled, 0)

        assertEquals(
            "2023",
            recycled.heatmap
                .findViewById<TextView>(R.id.review_heatmap_year)
                .text
                .toString(),
        )
        assertEquals(View.GONE, recycled.heatmap.findViewById<View>(R.id.review_heatmap_calendar).visibility)
        assertEquals(View.VISIBLE, recycled.heatmap.findViewById<View>(R.id.review_heatmap_summary).visibility)
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun touch(
        view: View,
        action: Int,
        x: Float,
        y: Float,
    ) {
        MotionEvent.obtain(0, 1, action, x, y, 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
    }
}
