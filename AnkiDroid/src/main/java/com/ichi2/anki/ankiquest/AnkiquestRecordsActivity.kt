// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The record boards, natively, as the website's `/records` shows them: the best rolling hour, 24
 * hours, 7, 30 and 365 days, the longest streak and the most days studied among the player's
 * friends and groups, everyone, or one group, each with whoever came closest; a name opens that
 * player's profile ([AnkiquestProfileActivity]). A server without the
 * boards' JSON shows its page in [AnkiquestActivity].
 */
class AnkiquestRecordsActivity : AnkiActivity(R.layout.activity_ankiquest_records) {
    private lateinit var views: AnkiquestRecordViews
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var boards: List<AnkiquestRecords.Board>? = null

    /** What the player can pick between; null until asked, empty when there is no choice. */
    private var scopes: List<AnkiquestRecords.Scope>? = null

    /** The picked scope's [AnkiquestRecords.Scope.value]; null for the server's default. */
    private var scope: String? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        views = AnkiquestRecordViews(this)
        scroll = findViewById(R.id.aq_records_scroll)
        views.frame(R.string.aq_records_title, R.string.aq_records_refresh) { reload() }
        scope = savedInstanceState?.getString(STATE_SCOPE)
        account = AnkiquestHomeData.account()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCOPE, scope)
    }

    override fun onResume() {
        super.onResume()
        if (!::views.isInitialized) return
        // Signing out or into another account in settings must never leave the old account's boards here.
        if (AnkiquestHomeData.account()?.scope != account?.scope) reload()
    }

    private fun reload() {
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
                if (gone) {
                    // A server without the boards' JSON still has its page.
                    startActivity(AnkiquestActivity.intent(this@AnkiquestRecordsActivity, AnkiquestNavigation.RECORDS_PATH))
                    finish()
                    return@launch
                }
                if (problem == R.string.aq_feed_account_changed) {
                    reload()
                    return@launch
                }
                failure = problem
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
        if (!::views.isInitialized) return
        val content = views.content
        val position = scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_records_loading).isVisible = loading
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
        views.text(panel, getString(label(board.kind)), heading = true)
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

        fun intent(context: Context): Intent = Intent(context, AnkiquestRecordsActivity::class.java)

        @StringRes
        internal fun label(kind: AnkiquestRecords.Kind): Int =
            when (kind) {
                AnkiquestRecords.Kind.HOUR -> R.string.aq_profile_best_hour
                AnkiquestRecords.Kind.DAY -> R.string.aq_profile_best_day
                AnkiquestRecords.Kind.WEEK -> R.string.aq_profile_best_week
                AnkiquestRecords.Kind.MONTH -> R.string.aq_profile_best_month
                AnkiquestRecords.Kind.YEAR -> R.string.aq_profile_best_year
                AnkiquestRecords.Kind.STREAK -> R.string.review_heatmap_longest_streak
                AnkiquestRecords.Kind.DAYS -> R.string.review_heatmap_days_studied
            }
    }
}
