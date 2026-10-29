// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.deckpicker.heatmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.R
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** Draws one year without creating hundreds of child views; each day remains accessible. */
class ReviewHeatmapGrid
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val density = resources.displayMetrics.density
        private val labels = HeatmapLabels(context)
        private val labelPaint = labels.paint
        private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outlineColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
        private val selectedColor = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        private val protectedColor = ContextCompat.getColor(context, R.color.review_heatmap_protected)
        private val reviewColors =
            intArrayOf(
                R.color.review_heatmap_empty,
                R.color.review_heatmap_level_one,
                R.color.review_heatmap_level_two,
                R.color.review_heatmap_level_three,
                R.color.review_heatmap_level_four,
            ).map { ContextCompat.getColor(context, it) }
        private val forecastColors =
            intArrayOf(
                R.color.review_heatmap_empty,
                R.color.review_heatmap_forecast_one,
                R.color.review_heatmap_forecast_two,
                R.color.review_heatmap_forecast_three,
                R.color.review_heatmap_forecast_four,
            ).map { ContextCompat.getColor(context, it) }

        // Grow the rows with large fonts so weekday labels do not overlap.
        private val pitch = labels.pitch
        private val headingHeight = labels.headingHeight
        private val locale: Locale get() = resources.configuration.locales[0]
        private val firstDayOfWeek: DayOfWeek get() = WeekFields.of(locale).firstDayOfWeek
        private val accessibility = DayAccessibility()
        private var data: ReviewHeatmapData? = null
        private var year = 2000
        private var firstDate = LocalDate.of(year, 1, 1)
        private var offset = 0
        private var downX = 0f
        private var downY = 0f
        private var dragged = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        var selectedDate: LocalDate? = null
            set(value) {
                field = value
                invalidate()
                accessibility.invalidateRoot()
            }

        var onDaySelected: ((LocalDate) -> Unit)? = null

        init {
            isFocusable = true
            ViewCompat.setAccessibilityDelegate(this, accessibility)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        fun showYear(
            data: ReviewHeatmapData,
            year: Int,
        ) {
            this.data = data
            this.year = year
            firstDate = LocalDate.of(year, 1, 1)
            offset = (firstDate.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
            requestLayout()
            invalidate()
            accessibility.invalidateRoot()
        }

        /** Position of a date in the horizontally scrollable year. */
        fun xForDate(date: LocalDate): Int = cellBounds(date.dayOfYear - 1).left.toInt()

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val weeks = ceil((offset + firstDate.lengthOfYear()) / 7.0).toInt()
            setMeasuredDimension(
                resolveSize((weeks * pitch + 2 * density).toInt(), widthMeasureSpec),
                resolveSize((headingHeight + 7 * pitch + 4 * density).toInt(), heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val currentData = data ?: return
            for (month in 1..12) {
                val date = LocalDate.of(year, month, 1)
                canvas.drawText(
                    date.month.getDisplayName(TextStyle.SHORT, locale),
                    cellBounds(date.dayOfYear - 1).left,
                    -labels.fontMetrics.ascent,
                    labelPaint,
                )
            }
            for (index in 0 until firstDate.lengthOfYear()) {
                val date = firstDate.plusDays(index.toLong())
                val bounds = cellBounds(index)
                val future = date > currentData.today
                val count = (if (future) currentData.scheduled else currentData.reviews)[date] ?: 0
                cellPaint.style = Paint.Style.FILL
                cellPaint.color = (if (future) forecastColors else reviewColors)[intensity(count, currentData.dailyAverage)]
                cellPaint.alpha = if (date > currentData.forecastEndDate) 70 else 255
                canvas.drawRoundRect(bounds, 3 * density, 3 * density, cellPaint)
                cellPaint.alpha = 255
                if (date in currentData.protectedDays) {
                    cellPaint.style = Paint.Style.STROKE
                    cellPaint.strokeWidth = 2 * density
                    cellPaint.color = protectedColor
                    canvas.drawRoundRect(bounds, 3 * density, 3 * density, cellPaint)
                }
                if (date == selectedDate || date == currentData.today || accessibility.keyboardFocusedVirtualViewId == index) {
                    cellPaint.style = Paint.Style.STROKE
                    cellPaint.strokeWidth = 2 * density
                    cellPaint.color = if (date == selectedDate) selectedColor else outlineColor
                    canvas.drawRoundRect(bounds, 3 * density, 3 * density, cellPaint)
                }
            }
        }

        private fun intensity(
            count: Int,
            dailyAverage: Double,
        ): Int =
            when {
                count <= 0 -> 0
                count <= dailyAverage.coerceAtLeast(1.0) / 2 -> 1
                count <= dailyAverage.coerceAtLeast(1.0) -> 2
                count <= dailyAverage.coerceAtLeast(1.0) * 2 -> 3
                else -> 4
            }

        internal fun cellBounds(index: Int): RectF {
            val position = offset + index
            val x = position / 7 * pitch + 2 * density
            val y = headingHeight + position % 7 * pitch + 2 * density
            return RectF(x, y, x + pitch - 4 * density, y + pitch - 4 * density)
        }

        private fun dayAt(
            x: Float,
            y: Float,
        ): Int {
            if (x < 0 || y < headingHeight || y >= headingHeight + pitch * 7) return ExploreByTouchHelper.INVALID_ID
            val column = (x / pitch).toInt()
            val row = ((y - headingHeight) / pitch).toInt()
            val day = column * 7 + row - offset
            return if (day in 0 until firstDate.lengthOfYear()) day else ExploreByTouchHelper.INVALID_ID
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) dragged = true
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val day = dayAt(event.x, event.y)
                    if (!dragged && day != ExploreByTouchHelper.INVALID_ID && day == dayAt(downX, downY)) {
                        selectDay(day)
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragged = true
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun selectDay(index: Int) {
            val date = firstDate.plusDays(index.toLong())
            selectedDate = date
            onDaySelected?.invoke(date)
            accessibility.sendEventForVirtualView(index, AccessibilityEvent.TYPE_VIEW_CLICKED)
        }

        override fun dispatchHoverEvent(event: MotionEvent): Boolean =
            accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        }

        private inner class DayAccessibility : ExploreByTouchHelper(this@ReviewHeatmapGrid) {
            override fun getVirtualViewAt(
                x: Float,
                y: Float,
            ): Int = dayAt(x, y)

            override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
                if (data == null) return
                // Include the full year so keyboard and TalkBack navigation can scroll to off-screen days.
                virtualViewIds.addAll(0 until firstDate.lengthOfYear())
            }

            override fun onPopulateNodeForVirtualView(
                virtualViewId: Int,
                node: AccessibilityNodeInfoCompat,
            ) {
                val date = firstDate.plusDays(virtualViewId.toLong())
                val currentData = data
                node.contentDescription =
                    if (currentData == null) {
                        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
                    } else {
                        currentData.dayDescription(context, date)
                    }
                node.className = "android.widget.Button"
                node.isClickable = true
                node.isSelected = date == selectedDate
                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                val bounds = Rect()
                cellBounds(virtualViewId).roundOut(bounds)
                // ExploreByTouchHelper requires parent bounds to derive screen bounds and keyboard focus order.
                @Suppress("DEPRECATION")
                node.setBoundsInParent(bounds)
            }

            override fun onPerformActionForVirtualView(
                virtualViewId: Int,
                action: Int,
                arguments: Bundle?,
            ): Boolean {
                if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || virtualViewId !in 0 until firstDate.lengthOfYear()) return false
                selectDay(virtualViewId)
                return true
            }

            override fun onVirtualViewKeyboardFocusChanged(
                virtualViewId: Int,
                hasFocus: Boolean,
            ) {
                if (hasFocus && virtualViewId in 0 until firstDate.lengthOfYear()) {
                    val bounds = Rect()
                    cellBounds(virtualViewId).roundOut(bounds)
                    requestRectangleOnScreen(bounds)
                }
                invalidate()
            }
        }
    }

/** Stationary weekday labels remain visible while the year scrolls horizontally. */
class ReviewHeatmapWeekdays
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val labels = HeatmapLabels(context)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(
                resolveSize(labels.width.toInt(), widthMeasureSpec),
                resolveSize((labels.headingHeight + 7 * labels.pitch + 4 * resources.displayMetrics.density).toInt(), heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val metrics = labels.fontMetrics
            for (row in 0..6) {
                canvas.drawText(
                    labels.firstDay.plus(row.toLong()).getDisplayName(TextStyle.SHORT, labels.locale),
                    0f,
                    labels.headingHeight + row * labels.pitch + (labels.pitch - metrics.ascent - metrics.descent) / 2,
                    labels.paint,
                )
            }
        }
    }

private class HeatmapLabels(
    context: Context,
) {
    val locale: Locale = context.resources.configuration.locales[0]
    val firstDay: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
    private val density = context.resources.displayMetrics.density
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
            color = MaterialColors.getColor(context, android.R.attr.textColorSecondary, 0)
        }
    val fontMetrics = paint.fontMetrics
    val pitch = maxOf(22 * density, paint.fontSpacing + 6 * density)
    val headingHeight = paint.fontSpacing + 8 * density
    val width = DayOfWeek.values().maxOf { paint.measureText(it.getDisplayName(TextStyle.SHORT, locale)) } + 8 * density
}

internal fun ReviewHeatmapData.dayCountDescription(
    context: Context,
    date: LocalDate,
): String {
    val number = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])

    fun reviews(): String {
        val count = reviews[date] ?: 0
        return context.resources.getQuantityString(R.plurals.review_heatmap_review_count, count, number.format(count))
    }

    fun due(): String {
        val count = scheduled[date] ?: 0
        return context.resources.getQuantityString(R.plurals.review_heatmap_due_count, count, number.format(count))
    }
    return when {
        date > forecastEndDate -> context.getString(R.string.review_heatmap_forecast_unavailable)
        date > today -> due()
        date in protectedDays -> context.getString(R.string.review_heatmap_protected_day, reviews())
        else -> reviews()
    }
}

internal fun ReviewHeatmapData.dayDescription(
    context: Context,
    date: LocalDate,
): String {
    val formattedDate =
        date.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(context.resources.configuration.locales[0]),
        )
    val description = context.getString(R.string.review_heatmap_day_description, formattedDate, dayCountDescription(context, date))
    return if (date == today) context.getString(R.string.review_heatmap_today_description, description) else description
}
