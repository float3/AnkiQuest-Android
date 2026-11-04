// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Formatter
import java.util.Locale
import java.util.TimeZone

/**
 * The community calendar, natively, as the website's `/community#calendar` shows it: who won each day
 * of a month, each week's champions and each month's season, whether a result is final, and the full
 * standings of any of them. It reads `GET /api/community`; a server without it says it needs updating.
 */
class AnkiquestCalendarActivity : AnkiActivity(R.layout.activity_ankiquest_board) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var views: AnkiquestBoardViews
    private var account: HomeAccount? = null
    private var kind = AnkiquestCommunity.Kind.DAY
    private var year = 0
    private var month = 0

    /** The period whose standings are open, as [AnkiquestCommunity.Period.reference]. */
    private var selected: String? = null
    private var calendar: AnkiquestCommunity.Calendar? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        views = AnkiquestBoardViews(this)
        content = findViewById(R.id.aq_board_content)
        scroll = findViewById(R.id.aq_board_scroll)
        findViewById<TextView>(R.id.aq_board_title).apply {
            setText(R.string.aq_calendar_title)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<View>(R.id.aq_board_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.aq_board_refresh).apply {
            contentDescription = getString(R.string.aq_calendar_refresh)
            setOnClickListener { load() }
        }
        val root = findViewById<View>(R.id.aq_board_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        val now = TimeManager.time.calendar()
        kind = AnkiquestCommunity.Kind.of(savedInstanceState?.getString(STATE_KIND) ?: intent.getStringExtra(EXTRA_KIND))
        year = savedInstanceState?.getInt(STATE_YEAR) ?: now.get(Calendar.YEAR)
        month = savedInstanceState?.getInt(STATE_MONTH) ?: (now.get(Calendar.MONTH) + 1)
        selected = savedInstanceState?.getString(STATE_SELECTED)
        account = AnkiquestHomeData.account()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        if (AnkiquestHomeData.account()?.scope != account?.scope) load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KIND, kind.query)
        outState.putInt(STATE_YEAR, year)
        outState.putInt(STATE_MONTH, month)
        outState.putString(STATE_SELECTED, selected)
    }

    private fun load() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            calendar = null
        }
        loadJob?.cancel()
        val turn = ++generation
        val askedYear = year
        val askedMonth = month
        failure = null
        if (current == null) {
            calendar = null
            loading = false
            render()
            return
        }
        loading = true
        render()
        loadJob =
            lifecycleScope.launch {
                var gone = false
                var answer: AnkiquestCommunity.Calendar? = null
                val problem =
                    boardAttempt {
                        try {
                            answer = AnkiquestCommunity.calendar(current, askedYear, askedMonth)
                        } catch (e: AnkiquestHttpException) {
                            if (e.code != 404) throw e
                            gone = true
                        }
                    }
                if (turn != generation) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    load()
                    return@launch
                }
                answer?.let { calendar = it }
                failure = if (gone) R.string.aq_server_outdated else problem
                loading = false
                render()
            }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_board_loading).isVisible = loading
        if (account == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_board_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) {
                startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
            }
            return
        }
        views.choices(content, AnkiquestCommunity.Kind.entries.map { it to getString(it.label) }, kind, enabled = true) { choice ->
            if (choice != kind) {
                kind = choice
                selected = null
            }
            render()
        }
        stepper()
        val archive = calendar
        when {
            archive == null -> if (loading) views.text(content, getString(R.string.aq_calendar_loading), small = true)
            kind == AnkiquestCommunity.Kind.DAY -> days(archive)
            else -> periods(archive)
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        archive?.let { notes(it) }
        scroll.post { scroll.scrollTo(0, position) }
    }

    /** ‹ October 2026 › for days, ‹ 2026 › for weeks and seasons. */
    private fun stepper() {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        content.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = views.dp(8)
            },
        )
        val days = kind == AnkiquestCommunity.Kind.DAY
        row.addView(step("‹", getString(if (days) R.string.aq_calendar_previous_month else R.string.review_heatmap_previous_year), -1))
        row.addView(
            TextView(this).apply {
                text = if (days) monthName(year, month) else year.toString()
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.aq_home_text))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(step("›", getString(if (days) R.string.aq_calendar_next_month else R.string.review_heatmap_next_year), 1))
    }

    private fun step(
        label: String,
        description: String,
        direction: Int,
    ): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 20f
            contentDescription = description
            minWidth = views.dp(48)
            minimumWidth = views.dp(48)
            setTextColor(getColor(R.color.aq_home_text))
            setOnClickListener { move(direction) }
        }

    private fun move(direction: Int) {
        if (kind == AnkiquestCommunity.Kind.DAY) {
            month += direction
            if (month < 1) {
                month = 12
                year--
            } else if (month > 12) {
                month = 1
                year++
            }
        } else {
            year += direction
        }
        selected = null
        load()
    }

    /** A month of days, Monday first as on the website, each showing who won it. */
    private fun days(archive: AnkiquestCommunity.Calendar) {
        if (archive.year != year || archive.month != month) return
        val grid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(views.dp(4), views.dp(8), views.dp(4), views.dp(8))
                background = views.shape(R.color.aq_home_surface)
            }
        content.addView(
            grid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = views.dp(10)
            },
        )
        val names = DateFormatSymbols.getInstance(AnkiquestLanguage.locale()).shortWeekdays
        val header = week(grid)
        for (day in WEEK) {
            header.addView(
                TextView(this).apply {
                    text = names[day]
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(getColor(R.color.aq_home_muted))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        val first = utc(year, month, 1)
        val count = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val padding = WEEK.indexOf(first.get(Calendar.DAY_OF_WEEK))
        val periods = archive.days.associateBy { it.key }
        val today = todayKey()
        var row = week(grid)
        repeat(padding) { row.addView(View(this), cellParams()) }
        for (day in 1..count) {
            if (row.childCount == 7) row = week(grid)
            val key = dayKey(year, month, day)
            row.addView(dayCell(day, key, periods[key], archive, key == today, key > today), cellParams())
        }
        while (row.childCount < 7) row.addView(View(this), cellParams())
        selected?.let(archive::find)?.takeIf { it.kind == AnkiquestCommunity.Kind.DAY }?.let { details(it, archive) }
        views.text(content, getString(R.string.aq_calendar_day_hint), small = true)
    }

    private fun week(grid: LinearLayout): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            grid.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun cellParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(views.dp(1), views.dp(1), views.dp(1), views.dp(1))
        }

    private fun dayCell(
        day: Int,
        key: String,
        period: AnkiquestCommunity.Period?,
        archive: AnkiquestCommunity.Calendar,
        today: Boolean,
        upcoming: Boolean,
    ): View {
        val winners = period?.winners.orEmpty()
        val open = period != null && "day:$key" == selected
        val cell =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = views.dp(64)
                setPadding(views.dp(2), views.dp(4), views.dp(2), views.dp(4))
                background =
                    views.shape(
                        when {
                            open -> R.color.aq_home_primary
                            today || winners.isNotEmpty() -> R.color.aq_home_tint
                            else -> R.color.aq_home_background
                        },
                        radius = 10,
                    )
            }
        val color = getColor(if (open) R.color.aq_home_primary_text else R.color.aq_home_text)
        cell.addView(
            TextView(this).apply {
                text = views.number(day.toLong())
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(color)
                if (today) setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        val winner =
            when {
                winners.size > 1 -> getString(R.string.aq_calendar_more_winners, archive.name(winners[0]), winners.size - 1)
                winners.size == 1 -> archive.name(winners[0])
                else -> ""
            }
        cell.addView(
            TextView(this).apply {
                text = winner
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (open) color else getColor(R.color.aq_home_muted))
            },
        )
        val status = period?.let { getString(statusLabel(it)) }
        cell.contentDescription =
            listOfNotNull(
                dateLabel(key),
                when {
                    winners.isNotEmpty() -> winners.joinToString(" & ") { archive.name(it) }
                    period != null -> getString(R.string.aq_calendar_no_study)
                    upcoming -> getString(R.string.aq_calendar_upcoming)
                    else -> getString(R.string.aq_calendar_no_result)
                },
                status,
                getString(R.string.aq_calendar_partial).takeIf { period?.partial == true },
            ).joinToString(", ")
        if (period != null) {
            views.clickable(cell) { toggle(period) }
        } else {
            cell.isEnabled = false
        }
        return cell
    }

    /** Weeks or seasons of the year, newest first, each with its champion or current leader. */
    private fun periods(archive: AnkiquestCommunity.Calendar) {
        if (archive.year != year) return
        val list = archive.periods(kind).reversed()
        views.section(
            content,
            getString(
                if (kind ==
                    AnkiquestCommunity.Kind.WEEK
                ) {
                    R.string.aq_calendar_weeks
                } else {
                    R.string.aq_calendar_months
                },
            ),
        )
        if (list.isEmpty()) {
            views.text(content, getString(R.string.aq_calendar_empty), small = true)
            return
        }
        for (period in list) {
            val panel = views.card(content, tinted = period.reference == selected)
            views.text(panel, periodName(period), heading = true).apply { ViewCompat.setAccessibilityHeading(this, false) }
            views.text(panel, winnerLine(period, archive))
            views.text(panel, badges(period), small = true)
            views.clickable(panel) { toggle(period) }
            if (period.reference == selected) standings(panel, period)
        }
    }

    private fun toggle(period: AnkiquestCommunity.Period) {
        selected = if (selected == period.reference) null else period.reference
        render()
    }

    /** The open day: its result and full standings, below the month. */
    private fun details(
        period: AnkiquestCommunity.Period,
        archive: AnkiquestCommunity.Calendar,
    ) {
        val panel = views.card(content, tinted = true)
        views.text(panel, periodName(period), heading = true)
        views.text(panel, winnerLine(period, archive))
        views.text(panel, badges(period), small = true)
        standings(panel, period)
    }

    private fun standings(
        panel: LinearLayout,
        period: AnkiquestCommunity.Period,
    ) {
        if (period.standings.isEmpty()) {
            views.text(panel, getString(R.string.aq_calendar_no_standings), small = true)
            return
        }
        period.standings.forEachIndexed { index, row ->
            val line = views.card(panel, horizontal = true)
            views.avatar(line, row.user, row.display, 32)
            val column = views.column(line)
            views.text(column, getString(R.string.aq_calendar_place, index + 1, row.display)).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            views.text(
                column,
                listOf(
                    getString(R.string.aq_profile_best_value, views.number(row.xp), views.number(row.reviews)),
                    getString(R.string.aq_calendar_minutes, views.number(row.timeMs / 60_000)),
                ).joinToString(" · "),
                small = true,
            )
        }
        if (period.reconstructed) views.text(panel, getString(R.string.aq_calendar_rebuilt_note), small = true)
        if (period.partial) views.text(panel, getString(R.string.aq_calendar_partial_note), small = true)
    }

    private fun winnerLine(
        period: AnkiquestCommunity.Period,
        archive: AnkiquestCommunity.Calendar,
    ): String {
        val names = period.winners.joinToString(" & ") { archive.name(it) }
        return when {
            period.winners.isEmpty() -> getString(R.string.aq_calendar_no_study)
            period.winners.size > 1 -> getString(R.string.aq_calendar_shared, names)
            period.final -> getString(R.string.aq_calendar_champion, names, views.number(period.margin))
            else -> getString(R.string.aq_calendar_leader, names, views.number(period.margin))
        }
    }

    private fun badges(period: AnkiquestCommunity.Period): String =
        listOfNotNull(
            getString(statusLabel(period)),
            getString(R.string.aq_calendar_partial).takeIf { period.partial },
        ).joinToString(" · ")

    /** Final results, rebuilt ones, those waiting for late syncs and those still running. */
    @StringRes
    private fun statusLabel(period: AnkiquestCommunity.Period): Int =
        when {
            period.final && period.reconstructed -> R.string.aq_calendar_rebuilt
            period.final -> R.string.aq_calendar_final
            period.status == "provisional" -> R.string.aq_calendar_pending
            else -> R.string.aq_calendar_live
        }

    /** The server's scoring rules and when results become final. */
    private fun notes(archive: AnkiquestCommunity.Calendar) {
        val zone = archive.timeZone.ifEmpty { TimeZone.getDefault().id }
        views.text(
            content,
            getString(R.string.aq_calendar_cutoff, zone, hourLabel(archive.rolloverHour), archive.graceHours),
            small = true,
        )
        if (archive.scoringNote.isNotEmpty()) views.text(content, archive.scoringNote, small = true)
    }

    /** An hour of the day as this device writes times, such as "04:00" or "4:00 AM". */
    private fun hourLabel(hour: Int): String =
        android.text.format.DateFormat
            .getTimeFormat(this)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(hour * 3_600_000L))

    private fun periodName(period: AnkiquestCommunity.Period): String =
        when (period.kind) {
            AnkiquestCommunity.Kind.DAY -> dateLabel(period.key)
            AnkiquestCommunity.Kind.MONTH -> {
                val start = parse(period.start) ?: return period.key
                monthName(start.get(Calendar.YEAR), start.get(Calendar.MONTH) + 1)
            }
            AnkiquestCommunity.Kind.WEEK -> {
                val start = parse(period.start) ?: return period.key
                // The end is exclusive: the week's last day is the one before it.
                val last = parse(period.end)?.apply { add(Calendar.DAY_OF_MONTH, -1) } ?: start
                range(start.timeInMillis, last.timeInMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            }
        }

    private fun dateLabel(key: String): String {
        val date = parse(key) ?: return key
        return range(date.timeInMillis, date.timeInMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
    }

    private fun monthName(
        year: Int,
        month: Int,
    ): String {
        val at = utc(year, month, 1).timeInMillis
        return range(at, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR)
    }

    /** Dates are whole days on the competition calendar, so they are written as at noon UTC. */
    private fun range(
        start: Long,
        end: Long,
        flags: Int,
    ): String = DateUtils.formatDateRange(this, Formatter(StringBuilder(), AnkiquestLanguage.locale()), start, end, flags, "UTC").toString()

    companion object {
        /** The view to open, as [AnkiquestCommunity.Kind.query]; days when missing. */
        const val EXTRA_KIND = "ankiquest.calendar_kind"

        private const val STATE_KIND = "ankiquest.calendar_kind"
        private const val STATE_YEAR = "ankiquest.calendar_year"
        private const val STATE_MONTH = "ankiquest.calendar_month"
        private const val STATE_SELECTED = "ankiquest.calendar_selected"

        private val WEEK =
            listOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
                Calendar.SATURDAY,
                Calendar.SUNDAY,
            )

        internal fun intent(
            context: Context,
            kind: AnkiquestCommunity.Kind = AnkiquestCommunity.Kind.DAY,
        ): Intent = Intent(context, AnkiquestCalendarActivity::class.java).putExtra(EXTRA_KIND, kind.query)

        private fun utc(
            year: Int,
            month: Int,
            day: Int,
        ): Calendar =
            TimeManager.time.calendar().apply {
                clear()
                timeZone = TimeZone.getTimeZone("UTC")
                set(year, month - 1, day, 12, 0)
            }

        /** A `YYYY-MM-DD` date at noon UTC, or null when it is not one. */
        internal fun parse(date: String): Calendar? {
            val parts = date.split('-').mapNotNull { it.toIntOrNull() }
            if (parts.size != 3) return null
            return utc(parts[0], parts[1], parts[2])
        }

        /** The `YYYY-MM-DD` key the server gives a day. */
        internal fun dayKey(
            year: Int,
            month: Int,
            day: Int,
        ): String = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

        /** Today on this device, as the website marks it. */
        private fun todayKey(): String {
            val now = TimeManager.time.calendar()
            return dayKey(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        }
    }
}
