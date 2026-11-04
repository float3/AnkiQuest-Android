// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapGrid
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapWeekdays
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The player's study history, natively, as the website's `/history` shows it: a year of uploaded
 * study days on the review heatmap, the year's totals, and what happened on the day picked. A
 * server without the calendar's JSON says it needs updating.
 */
class AnkiquestHistoryActivity : AnkiActivity(R.layout.activity_ankiquest_records) {
    private lateinit var views: AnkiquestRecordViews
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var study: AnkiquestRecords.Study? = null

    /** The year asked for; null for the player's current one. */
    private var year: Int? = null
    private var selected: LocalDate? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var generation = 0
    private var loadJob: Job? = null
    private var detail: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        views = AnkiquestRecordViews(this)
        scroll = findViewById(R.id.aq_records_scroll)
        views.frame(R.string.aq_history_title, R.string.aq_history_refresh) { reload() }
        savedInstanceState?.let { saved ->
            year = saved.getInt(STATE_YEAR).takeIf { it > 0 }
            selected = saved.getString(STATE_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        }
        account = AnkiquestHomeData.account()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        year?.let { outState.putInt(STATE_YEAR, it) }
        selected?.let { outState.putString(STATE_DAY, it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        if (!::views.isInitialized) return
        // Signing out or into another account in settings must never leave the old account's history here.
        if (AnkiquestHomeData.account()?.scope != account?.scope) reload()
    }

    private fun reload() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            study = null
            year = null
            selected = null
        }
        load()
    }

    private fun load() {
        val current = account
        loadJob?.cancel()
        val turn = ++generation
        val asked = year
        failure = null
        if (current == null) {
            study = null
            loading = false
            render()
            return
        }
        loading = true
        render()
        loadJob =
            lifecycleScope.launch {
                var gone = false
                val problem =
                    AnkiquestRecordViews.attempt(R.string.aq_challenges_offline, R.string.aq_history_server_error) {
                        try {
                            val history = AnkiquestRecords.study(current, asked)
                            if (turn == generation) {
                                study = history
                                year = history.year
                                if (selected?.year != history.year) selected = history.latest
                            }
                        } catch (e: AnkiquestHttpException) {
                            if (e.code != 404) throw e
                            gone = true
                        }
                    }
                if (turn != generation) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    reload()
                    return@launch
                }
                failure = if (gone) R.string.aq_server_outdated else problem
                loading = false
                render()
            }
    }

    private fun render() {
        if (!::views.isInitialized) return
        val content = views.content
        val position = scroll.scrollY
        content.removeAllViews()
        detail = null
        findViewById<View>(R.id.aq_records_loading).isVisible = loading
        content.addView(AnkiquestAki.image(this, R.drawable.aki_streak, 72))
        views.text(content, getString(R.string.aq_history_intro), small = true)
        if (account == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_history_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        val history = study
        if (history == null) {
            if (loading) views.text(content, getString(R.string.aq_history_loading), small = true)
        } else {
            renderHistory(content, history)
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true)
            if (problem == R.string.aq_home_auth) views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderHistory(
        content: LinearLayout,
        history: AnkiquestRecords.Study,
    ) {
        if (history.years.size > 1) {
            views.text(content, getString(R.string.aq_history_year), small = true)
            views.choices(content, history.years.map { it to it.toString() }, history.year, enabled = !loading) { picked ->
                if (picked != history.year) {
                    year = picked
                    selected = null
                    load()
                } else {
                    render()
                }
            }
        }
        views.text(content, freshness(history), small = true)
        views.tiles(
            content,
            listOf(
                views.number(history.reviews) to getString(R.string.aq_history_year_reviews, history.year),
                views.number(history.studyDays.toLong()) to getString(R.string.aq_history_year_days, history.year),
                views.number(history.xp) to getString(R.string.aq_history_year_xp, history.year),
            ),
        )
        if (history.days.isEmpty()) {
            views.text(content, getString(R.string.aq_history_empty), heading = true).setPadding(0, views.dp(16), 0, 0)
            views.text(content, getString(R.string.aq_history_empty_hint), small = true)
            return
        }
        detail = views.card(content).also { showDay(it, history) }
        renderCalendar(content, history)
        views.text(content, getString(R.string.aq_history_cutoff), small = true)
    }

    /** The year on the review heatmap below the deck list, coloured by uploaded reviews; tapping a day shows it above. */
    private fun renderCalendar(
        content: LinearLayout,
        history: AnkiquestRecords.Study,
    ) {
        val panel = views.card(content)
        views.text(panel, getString(R.string.aq_history_calendar, history.year), heading = true)
        views.text(panel, getString(R.string.review_heatmap_scroll_hint), small = true)
        val data = history.heatmap(LocalDate.now())
        val grid =
            ReviewHeatmapGrid(this).apply {
                showYear(data, history.year)
                selectedDate = selected
                onDaySelected = { date ->
                    selected = date
                    detail?.let { showDay(it, history) }
                }
            }
        val sideways =
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = true
                addView(grid)
            }
        val line =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(ReviewHeatmapWeekdays(this@AnkiquestHistoryActivity))
                addView(sideways, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        panel.addView(
            line,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    views.dp(8)
            },
        )
        val shown = selected ?: return
        // Once the year is measured, keep the picked day in sight with the weeks before it.
        sideways.post { sideways.scrollTo((grid.xForDate(shown) - sideways.width * 2 / 3).coerceAtLeast(0), 0) }
    }

    private fun showDay(
        panel: LinearLayout,
        history: AnkiquestRecords.Study,
    ) {
        panel.removeAllViews()
        val date = selected
        if (date == null) {
            views.text(panel, getString(R.string.aq_history_choose), heading = true)
            views.text(panel, getString(R.string.aq_history_choose_hint), small = true)
            return
        }
        val day = history.day(date)
        views.text(
            panel,
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AnkiquestLanguage.locale())),
            heading = true,
        )
        if (day.frozen) views.text(panel, getString(R.string.aq_history_frozen), small = true)
        views.tiles(
            panel,
            listOf(
                views.number(day.reviews.toLong()) to getString(R.string.aq_challenges_kind_reviews),
                views.number(day.minutes) to getString(R.string.aq_history_minutes),
                views.number(day.xp) to getString(R.string.aq_history_xp),
                views.number(day.newCards.toLong()) to getString(R.string.aq_history_new_cards),
                views.number(day.streak.toLong()) to getString(R.string.aq_profile_streak),
            ),
        )
        views.text(panel, getString(if (day.reviews > 0) R.string.aq_history_day_hint else R.string.aq_history_day_empty), small = true)
        panel.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun freshness(history: AnkiquestRecords.Study): String {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        history.lastReceivedAt?.let { return getString(R.string.aq_history_updated, DateUtils.formatDateTime(this, it, flags)) }
        history.lastReviewAt?.let { return getString(R.string.aq_history_latest_review, DateUtils.formatDateTime(this, it, flags)) }
        return getString(R.string.aq_history_no_reviews)
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    companion object {
        private const val STATE_YEAR = "ankiquest.history_year"
        private const val STATE_DAY = "ankiquest.history_day"

        fun intent(context: Context): Intent = Intent(context, AnkiquestHistoryActivity::class.java)
    }
}
