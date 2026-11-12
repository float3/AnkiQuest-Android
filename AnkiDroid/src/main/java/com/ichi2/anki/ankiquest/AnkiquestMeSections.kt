// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapGrid
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapWeekdays
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Me owns the sections; each loads on expansion and keeps its filters through profile refreshes. */
internal class AnkiquestMeSections(
    private val activity: AnkiActivity,
    savedState: Bundle?,
) {
    enum class Destination { RECORDS, HISTORY }

    private val restored = savedState?.takeIf { it.getString(STATE_ACCOUNT) == AnkiquestHomeData.account()?.scope }
    private val initial = Destination.entries.firstOrNull { it.name == activity.intent.getStringExtra(EXTRA_SECTION) }
    private var pendingTarget = initial.takeIf { savedState == null }
    private val records = RecordsSection(activity, restored, initial)
    private val history = HistorySection(activity, restored, initial)
    private val root =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(records.root)
            addView(history.root)
        }

    fun attach(parent: LinearLayout) {
        (root.parent as? ViewGroup)?.removeView(root)
        parent.addView(root)
    }

    fun refresh() {
        records.refresh()
        history.refresh()
    }

    fun save(out: Bundle) {
        out.putString(STATE_ACCOUNT, AnkiquestHomeData.account()?.scope)
        records.save(out)
        history.save(out)
    }

    fun showTarget() {
        when (pendingTarget) {
            Destination.RECORDS -> records.showHeader()
            Destination.HISTORY -> history.showHeader()
            null -> return
        }
        pendingTarget = null
    }

    companion object {
        private const val EXTRA_SECTION = "ankiquest.me_section"
        private const val STATE_ACCOUNT = "ankiquest.me_account"

        fun intent(
            context: Context,
            destination: Destination,
        ): Intent = AnkiquestProfileActivity.intent(context).putExtra(EXTRA_SECTION, destination.name)
    }
}

/** The expansion control stays attached while only its own body is refreshed. */
private abstract class MeSection(
    protected val activity: AnkiActivity,
    private val target: AnkiquestMeSections.Destination,
    @StringRes title: Int,
    @StringRes loadingLabel: Int,
    contentId: Int,
    loadingId: Int,
    saved: Bundle?,
    initial: AnkiquestMeSections.Destination?,
) : ContextWrapper(activity) {
    protected val views = AnkiquestRecordViews(activity)
    protected val scroll: NestedScrollView = activity.findViewById(R.id.aq_board_scroll)
    protected val lifecycleScope get() = activity.lifecycleScope
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    protected val content =
        LinearLayout(activity).apply {
            id = contentId
            orientation = LinearLayout.VERTICAL
        }
    protected val progress =
        ProgressBar(activity).apply {
            id = loadingId
            contentDescription = getString(loadingLabel)
            isVisible = false
        }
    private val stateKey = "ankiquest.me_expanded.${target.name}"
    private var expanded = saved?.getBoolean(stateKey) ?: (initial == target)
    private val heading =
        views.button(root, getString(title)) {
            expanded = !expanded
            showExpansion()
            if (expanded) {
                reload()
            }
        }

    init {
        root.addView(progress)
        root.addView(content)
        showExpansion()
    }

    private fun showExpansion() {
        content.isVisible = expanded
        heading.contentDescription = "${heading.text}, ${getString(if (expanded) CommonString.collapse else CommonString.expand)}"
        if (!expanded) {
            progress.isVisible = false
        }
    }

    fun refresh() {
        if (expanded) {
            reload()
        }
    }

    fun save(out: Bundle) {
        out.putBoolean(stateKey, expanded)
        saveFilters(out)
    }

    fun showHeader() {
        heading.post {
            val bounds = Rect(0, 0, heading.width, heading.height)
            scroll.offsetDescendantRectToMyCoords(heading, bounds)
            scroll.scrollTo(0, bounds.top)
        }
    }

    protected abstract fun reload()

    protected abstract fun saveFilters(out: Bundle)
}

private class RecordsSection(
    activity: AnkiActivity,
    saved: Bundle?,
    initial: AnkiquestMeSections.Destination?,
) : MeSection(
        activity,
        AnkiquestMeSections.Destination.RECORDS,
        R.string.aq_records_title,
        R.string.aq_records_loading,
        R.id.aq_me_records_content,
        R.id.aq_me_records_loading,
        saved,
        initial,
    ) {
    private var account: HomeAccount? = AnkiquestHomeData.account()
    private var boards: List<AnkiquestRecords.Board>? = null

    /** What the player can pick between; null until asked, empty when there is no choice. */
    private var scopes: List<AnkiquestRecords.Scope>? = null

    /** The picked scope's [AnkiquestRecords.Scope.value]; null for the server's default. */
    private var scope: String? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var generation = 0
    private var loadJob: Job? = null

    init {
        scope = saved?.getString(STATE_SCOPE)
    }

    override fun saveFilters(out: Bundle) {
        // A collapsed section may not have reloaded since the account changed.
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            return
        }
        out.putString(STATE_SCOPE, scope)
    }

    override fun reload() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            scopes = null
            scope = null
            boards = null
        }
        load()
    }

    private fun load() {
        val current = account
        loadJob?.cancel()
        val turn = ++generation
        failure = null
        if (current == null) {
            boards = null
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
                    AnkiquestRecordViews.attempt(R.string.aq_challenges_offline, R.string.aq_records_server_error) {
                        val choices = scopes ?: optionalScopes(current)
                        val asked = choices.firstOrNull { it.value == scope }
                        try {
                            val held =
                                try {
                                    AnkiquestRecords.records(current, asked)
                                } catch (e: AnkiquestHttpException) {
                                    // A group the player has left: back to the default boards.
                                    if (asked == null || e.code != 403) throw e
                                    if (turn == generation) scope = null
                                    AnkiquestRecords.records(current)
                                }
                            if (turn == generation) {
                                boards = held
                                scopes = choices
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

    /** The scope choices, or none when the server cannot say: the boards show without them. */
    private suspend fun optionalScopes(current: HomeAccount): List<AnkiquestRecords.Scope> =
        try {
            AnkiquestRecords.scopes(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private fun render() {
        val position = scroll.scrollY
        content.removeAllViews()
        progress.isVisible = loading && content.isVisible
        content.addView(AnkiquestAki.image(this, R.drawable.aki_winner, 72))
        views.text(content, getString(R.string.aq_records_lead), heading = true)
        views.text(content, getString(R.string.aq_records_intro), small = true)
        val current = account
        if (current == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_records_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        val choices = scopes.orEmpty()
        if (choices.isNotEmpty()) {
            val selected = choices.firstOrNull { it.value == scope } ?: AnkiquestRecords.Scope.CIRCLE
            views.choices(content, choices.map { it to scopeLabel(it) }, selected, enabled = !loading) { picked ->
                val value = picked.value.takeUnless { picked == AnkiquestRecords.Scope.CIRCLE }
                if (value != scope) {
                    scope = value
                    load()
                } else {
                    render()
                }
            }
        }
        val held = boards
        when {
            held == null -> if (loading) views.text(content, getString(R.string.aq_records_loading), small = true)
            held.all { it.holders.isEmpty() } -> {
                views.text(content, getString(R.string.aq_records_empty), heading = true).setPadding(0, views.dp(16), 0, 0)
                views.text(content, getString(R.string.aq_records_empty_hint), small = true)
            }
            else -> held.filter { it.holders.isNotEmpty() }.forEach { renderBoard(content, it, current.user) }
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true)
            if (problem == R.string.aq_home_auth) views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderBoard(
        content: LinearLayout,
        board: AnkiquestRecords.Board,
        me: String,
    ) {
        val best = board.holders.first()
        val panel = views.card(content, tinted = best.user == me)
        views.text(panel, getString(AnkiquestRecordsActivity.label(board.kind)), heading = true)
        views.text(panel, whenSet(board.kind, best), small = true)
        board.holders.forEachIndexed { index, holder ->
            val lead = index == 0
            val name = if (holder.user == me) getString(R.string.aq_challenges_you, holder.display) else holder.display
            val amount = amount(board, holder.value)
            val line = views.row(panel)
            views.cell(line, if (lead) "🏆" else views.number(index + 1L), 0f, bold = true)
            views.cell(line, name, 1f, bold = lead || holder.user == me)
            views.cell(line, amount, 0f, bold = lead, end = true)
            val behind = best.value - holder.value
            if (!lead) views.cell(line, "−" + views.number(behind), 0f, small = true, end = true)
            line.contentDescription =
                if (lead) {
                    getString(R.string.aq_records_holder, name, amount)
                } else {
                    getString(R.string.aq_records_chaser, index + 1, name, amount, amount(board, behind))
                }
            for (i in 0 until line.childCount) line.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            line.isFocusable = true
            line.minimumHeight = views.dp(48)
            val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            line.foreground = AppCompatResources.getDrawable(this, ripple.resourceId)
            line.setOnClickListener { startActivity(AnkiquestProfileActivity.intent(this, holder.user)) }
            if (lead && holder.detail > 0) {
                views.text(
                    panel,
                    resources.getQuantityString(R.plurals.aq_records_reviews, holder.detail.toInt(), views.number(holder.detail)),
                    small = true,
                )
            }
            views.bar(panel, if (best.value > 0) (holder.value * 100 / best.value).toInt() else 0)
        }
    }

    private fun amount(
        board: AnkiquestRecords.Board,
        value: Long,
    ): String =
        if (board.unit == "days") {
            resources.getQuantityString(R.plurals.aq_records_days, value.toInt(), views.number(value))
        } else {
            getString(R.string.aq_board_xp, views.number(value))
        }

    /** When the record was set: a window's span, when a streak ended, or since when days were counted. */
    private fun whenSet(
        kind: AnkiquestRecords.Kind,
        holder: AnkiquestRecords.Holder,
    ): String {
        val dateOnly = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
        return when (kind) {
            AnkiquestRecords.Kind.STREAK -> getString(R.string.aq_records_ended, DateUtils.formatDateTime(this, holder.at, dateOnly))
            AnkiquestRecords.Kind.DAYS -> getString(R.string.aq_records_since, DateUtils.formatDateTime(this, holder.at, dateOnly))
            AnkiquestRecords.Kind.HOUR, AnkiquestRecords.Kind.DAY -> {
                val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
                DateUtils.formatDateRange(this, holder.at, maxOf(holder.until, holder.at), flags)
            }
            else -> DateUtils.formatDateRange(this, holder.at, maxOf(holder.until, holder.at), dateOnly)
        }
    }

    private fun scopeLabel(scope: AnkiquestRecords.Scope): String =
        when (scope) {
            AnkiquestRecords.Scope.CIRCLE -> getString(R.string.aq_records_scope_circle)
            AnkiquestRecords.Scope.GLOBAL -> getString(R.string.aq_records_scope_global)
            else -> scope.name ?: scope.value
        }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    companion object {
        private const val STATE_SCOPE = "ankiquest.records_scope"
    }
}

private class HistorySection(
    activity: AnkiActivity,
    saved: Bundle?,
    initial: AnkiquestMeSections.Destination?,
) : MeSection(
        activity,
        AnkiquestMeSections.Destination.HISTORY,
        R.string.aq_history_title,
        R.string.aq_history_loading,
        R.id.aq_me_history_content,
        R.id.aq_me_history_loading,
        saved,
        initial,
    ) {
    private var account: HomeAccount? = AnkiquestHomeData.account()
    private var study: AnkiquestRecords.Study? = null

    /** The year asked for; null for the player's current one. */
    private var year: Int? = null
    private var selected: LocalDate? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var generation = 0
    private var loadJob: Job? = null
    private var detail: LinearLayout? = null

    init {
        year = saved?.getInt(STATE_YEAR)?.takeIf { it > 0 }
        selected = saved?.getString(STATE_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    override fun saveFilters(out: Bundle) {
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            return
        }
        year?.let { out.putInt(STATE_YEAR, it) }
        selected?.let { out.putString(STATE_DAY, it.toString()) }
    }

    override fun reload() {
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
        val position = scroll.scrollY
        content.removeAllViews()
        detail = null
        progress.isVisible = loading && content.isVisible
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
                addView(ReviewHeatmapWeekdays(this@HistorySection))
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
    }
}
