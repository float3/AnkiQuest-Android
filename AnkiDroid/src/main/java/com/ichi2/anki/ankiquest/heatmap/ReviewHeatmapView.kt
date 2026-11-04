// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestNavigation
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Local review history presented independently of deck selection or an online account. */
class ReviewHeatmapView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        internal class State {
            var year: Int? = null
            var selectedDate: LocalDate? = null
            var expanded = true
            var scrollX: Int? = null
        }

        private var state = State()
        private var data: ReviewHeatmapData? = null
        private val status: TextView
        private val loading: View
        private val retry: View
        private val summary: View
        private val calendar: View
        private val collapse: ImageButton
        private val previousYear: View
        private val nextYear: View
        private val yearLabel: TextView
        private val grid: ReviewHeatmapGrid
        private val scroll: HorizontalScrollView
        private val selectedDay: TextView
        private val selectedCount: TextView
        private val viewCards: View

        var onDaySelected: ((ReviewHeatmapData, LocalDate) -> Unit)? = null
        var onRetry: (() -> Unit)? = null

        init {
            orientation = VERTICAL
            LayoutInflater.from(context).inflate(R.layout.view_review_heatmap, this, true)
            status = findViewById(R.id.review_heatmap_status)
            loading = findViewById(R.id.review_heatmap_loading)
            retry = findViewById(R.id.review_heatmap_retry)
            summary = findViewById(R.id.review_heatmap_summary)
            calendar = findViewById(R.id.review_heatmap_calendar)
            collapse = findViewById(R.id.review_heatmap_collapse)
            previousYear = findViewById(R.id.review_heatmap_previous_year)
            nextYear = findViewById(R.id.review_heatmap_next_year)
            yearLabel = findViewById(R.id.review_heatmap_year)
            grid = findViewById(R.id.review_heatmap_grid)
            scroll = findViewById(R.id.review_heatmap_scroll)
            selectedDay = findViewById(R.id.review_heatmap_selected_day)
            selectedCount = findViewById(R.id.review_heatmap_selected_count)
            viewCards = findViewById(R.id.review_heatmap_view_cards)
            retry.setOnClickListener { onRetry?.invoke() }
            collapse.setOnClickListener {
                state.expanded = !state.expanded
                updateExpanded()
            }
            previousYear.setOnClickListener { changeYear(-1) }
            nextYear.setOnClickListener { changeYear(1) }
            grid.onDaySelected = { date ->
                state.selectedDate = date
                showSelectedDay()
            }
            scroll.setOnScrollChangeListener { _, x, _, _, _ -> state.scrollX = x }
            viewCards.setOnClickListener {
                val currentData = data ?: return@setOnClickListener
                val date = state.selectedDate ?: return@setOnClickListener
                if (ReviewHeatmap.searchForDay(currentData, date) != null) onDaySelected?.invoke(currentData, date)
            }
            setLoading()
            val companion = findViewById<android.widget.ImageView>(R.id.review_heatmap_aki)
            companion.isVisible = AnkiDroidApp.sharedPrefsOrNull() != null && AnkiquestNavigation.enabled() &&
                com.ichi2.anki.ankiquest.AnkiquestCompanion
                    .visible()
            if (companion.isVisible) {
                companion.setImageResource(
                    com.ichi2.anki.ankiquest.AnkiquestCompanion
                        .resource(R.drawable.aki_streak),
                )
            }
        }

        fun setData(data: ReviewHeatmapData) = setData(data, state)

        internal fun setData(
            data: ReviewHeatmapData,
            state: State,
        ) {
            this.data = data
            this.state = state
            loading.isVisible = false
            retry.isVisible = false
            status.isVisible = data.reviews.isEmpty()
            status.setText(R.string.review_heatmap_empty)
            summary.isVisible = true
            collapse.isVisible = true
            val number = NumberFormat.getIntegerInstance(resources.configuration.locales[0])

            fun streak(value: Int) = resources.getQuantityString(R.plurals.review_heatmap_streak_days, value, number.format(value))
            setStat(R.id.review_heatmap_current_streak, streak(data.currentStreak), R.string.review_heatmap_current_streak)
            setStat(R.id.review_heatmap_longest_streak, streak(data.longestStreak), R.string.review_heatmap_longest_streak)
            setStat(R.id.review_heatmap_days_studied, number.format(data.daysStudied), R.string.review_heatmap_days_studied)
            val decimal = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
            setStat(R.id.review_heatmap_daily_average, decimal.format(data.dailyAverage), R.string.review_heatmap_daily_average)
            findViewById<TextView>(R.id.review_heatmap_total).text =
                context.getString(R.string.review_heatmap_total, number.format(data.totalReviews))
            val earliest = minOf(data.firstReviewDate?.year ?: data.today.year, data.today.year)
            val latest = data.forecastEndDate.year
            state.year = (state.year ?: data.today.year).coerceIn(earliest, latest)
            if (state.selectedDate?.year != state.year) {
                state.selectedDate = if (state.year == data.today.year) data.today else LocalDate.of(state.year!!, 1, 1)
            }
            showYear()
            updateExpanded()
        }

        fun setLoading() {
            showStatus(R.string.review_heatmap_loading)
            loading.isVisible = true
            retry.isVisible = false
        }

        fun setError() {
            showStatus(R.string.review_heatmap_error)
            loading.isVisible = false
            retry.isVisible = true
        }

        private fun showStatus(message: Int) {
            data = null
            status.isVisible = true
            status.setText(message)
            summary.isVisible = false
            calendar.isVisible = false
            collapse.isVisible = false
        }

        private fun setStat(
            id: Int,
            value: String,
            label: Int,
        ) {
            val text = SpannableString(context.getString(R.string.review_heatmap_stat, value, context.getString(label)))
            text.setSpan(StyleSpan(Typeface.BOLD), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            findViewById<TextView>(id).text = text
        }

        private fun updateExpanded() {
            calendar.isVisible = data != null && state.expanded
            collapse.setImageResource(
                if (state.expanded) R.drawable.ic_expand_less_black_24dp else R.drawable.ic_expand_more_black_24dp_xml,
            )
            collapse.contentDescription =
                context.getString(if (state.expanded) R.string.review_heatmap_collapse else R.string.review_heatmap_expand)
        }

        private fun changeYear(delta: Int) {
            val currentData = data ?: return
            val firstYear = minOf(currentData.firstReviewDate?.year ?: currentData.today.year, currentData.today.year)
            state.year = ((state.year ?: currentData.today.year) + delta).coerceIn(firstYear, currentData.forecastEndDate.year)
            state.selectedDate = if (state.year == currentData.today.year) currentData.today else LocalDate.of(state.year!!, 1, 1)
            state.scrollX = null
            showYear()
        }

        private fun showYear() {
            val currentData = data ?: return
            val year = state.year ?: currentData.today.year
            yearLabel.text =
                NumberFormat.getIntegerInstance(resources.configuration.locales[0]).apply { isGroupingUsed = false }.format(year)
            yearLabel.contentDescription = context.getString(R.string.review_heatmap_year, yearLabel.text)
            previousYear.isEnabled = year > minOf(currentData.firstReviewDate?.year ?: currentData.today.year, currentData.today.year)
            nextYear.isEnabled = year < currentData.forecastEndDate.year
            previousYear.alpha = if (previousYear.isEnabled) 1f else 0.35f
            nextYear.alpha = if (nextYear.isEnabled) 1f else 0.35f
            grid.showYear(currentData, year)
            grid.selectedDate = state.selectedDate
            showSelectedDay()
            val savedScroll = state.scrollX
            // Wait until the year's width is measured. Leave recent weeks before today's cell visible.
            scroll.post {
                if (data !== currentData || state.year != year) return@post
                val x = savedScroll ?: if (year == currentData.today.year) grid.xForDate(currentData.today) - scroll.width * 2 / 3 else 0
                scroll.scrollTo(x.coerceAtLeast(0), 0)
            }
        }

        private fun showSelectedDay() {
            val currentData = data ?: return
            val date = state.selectedDate ?: return
            selectedDay.text =
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(resources.configuration.locales[0]))
            selectedCount.text = currentData.dayCountDescription(context, date)
            viewCards.isEnabled = ReviewHeatmap.searchForDay(currentData, date) != null
            (viewCards as TextView).setText(
                if (date >
                    currentData.today
                ) {
                    R.string.review_heatmap_view_due_cards
                } else {
                    R.string.review_heatmap_view_reviewed_cards
                },
            )
        }
    }
