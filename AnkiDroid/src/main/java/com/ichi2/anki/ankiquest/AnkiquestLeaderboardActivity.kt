// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The leaderboard, natively: the website's `/hour` … `/all` boards with each player's rank, picture,
 * XP for the period, streak and today's study status. Tapping a player opens their profile
 * ([AnkiquestProfileActivity]). Rank changes and weekly recaps open the week.
 */
class AnkiquestLeaderboardActivity : AnkiActivity(R.layout.activity_ankiquest_board) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var views: AnkiquestBoardViews
    private var account: HomeAccount? = null
    private var period = AnkiquestBoards.Period.WEEK
    private var rows: List<AnkiquestBoards.Standing>? = null
    private var weekEnds: Long? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        views = AnkiquestBoardViews(this)
        content = findViewById(R.id.aq_board_content)
        scroll = findViewById(R.id.aq_board_scroll)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.aq_board_title), true)
        findViewById<View>(R.id.aq_board_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.aq_board_refresh).apply {
            contentDescription = getString(R.string.aq_board_refresh)
            setOnClickListener {
                status = null
                load()
            }
        }
        val root = findViewById<View>(R.id.aq_board_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        account = AnkiquestHomeData.account()
        period = AnkiquestBoards.Period.of(savedInstanceState?.getString(STATE_PERIOD) ?: intent.getStringExtra(EXTRA_PERIOD))
        if (savedInstanceState == null) {
            val addressed = intent.getStringExtra(EXTRA_ACCOUNT)
            if (addressed != null && addressed != account?.notificationAccount) status = getString(R.string.aq_board_account_changed)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        // Signing out or into another account in settings must never leave the old account's board here.
        if (AnkiquestHomeData.account()?.scope != account?.scope) load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PERIOD, period.query)
    }

    /** Loads [period]'s board for the current account, and when the week resets. */
    private fun load() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            status = null
        }
        loadJob?.cancel()
        val turn = ++generation
        val asked = period
        failure = null
        if (current == null) {
            rows = null
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
                    boardAttempt {
                        try {
                            val board = AnkiquestBoards.leaderboard(current, asked)
                            val ends = if (asked == AnkiquestBoards.Period.WEEK) optionalWeekEnds(current) else weekEnds
                            if (turn == generation) {
                                rows = board
                                weekEnds = ends
                            }
                        } catch (e: AnkiquestHttpException) {
                            if (e.code != 404) throw e
                            gone = true
                        }
                    }
                if (turn != generation) return@launch
                if (gone) {
                    // A server without the board's JSON still has its page.
                    startActivity(AnkiquestActivity.intent(this@AnkiquestLeaderboardActivity, asked.path))
                    finish()
                    return@launch
                }
                if (problem == R.string.aq_feed_account_changed) {
                    load()
                    return@launch
                }
                failure = problem
                loading = false
                render()
                if (problem == null) loadPictures(current)
            }
    }

    /** When the week resets; the board shows without it when the server cannot say. */
    private suspend fun optionalWeekEnds(current: HomeAccount): Long? =
        try {
            AnkiquestBoards.weekEnds(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest week end unavailable")
            null
        }

    private fun loadPictures(current: HomeAccount) {
        val users = rows.orEmpty().map { it.user }
        if (users.isEmpty()) return
        lifecycleScope.launch {
            try {
                AnkiquestAvatars.refresh(users)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "AnkiQuest pictures unavailable")
                return@launch
            }
            if (account?.scope == current.scope && !loading) render()
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_board_loading).isVisible = loading
        status?.let { line ->
            views.text(content, line, small = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        }
        val current = account
        if (current == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_board_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        views.choices(
            content,
            AnkiquestBoards.Period.entries.map { it to getString(it.label) },
            selected = period,
            enabled = true,
        ) { choice ->
            if (choice != period) {
                period = choice
                rows = null
                load()
            } else {
                render()
            }
        }
        views.section(content, getString(R.string.aq_board_xp_in, getString(periodPhrase(period))))
        weekEnds?.takeIf { period == AnkiquestBoards.Period.WEEK }?.let { ends ->
            val now = TimeManager.time.intTimeMS()
            if (ends > now) {
                val left = DateUtils.getRelativeTimeSpanString(ends, now, DateUtils.MINUTE_IN_MILLIS)
                views.text(content, getString(R.string.aq_board_resets, left), small = true)
            }
        }
        val board = rows
        when {
            board == null -> if (loading) views.text(content, getString(R.string.aq_board_loading), small = true)
            board.isEmpty() -> if (!loading) empty(R.string.aq_board_empty)
            board.all { it.xp == 0L } -> if (!loading) empty(R.string.aq_board_no_xp)
            else -> {
                standing(current, board)
                board.forEachIndexed { index, row -> row(index + 1, row, row.user == current.user, board.first().xp) }
            }
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (problem == R.string.aq_home_auth) views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        views.button(content, getString(R.string.aq_board_my_profile)) { openProfile(current.user) }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun empty(
        @StringRes message: Int,
    ) {
        content.addView(AnkiquestAki.image(this, R.drawable.aki_welcome, 72))
        views.text(content, getString(message), heading = true)
    }

    /** Where the player stands, with the companion's podium art for the top three. */
    private fun standing(
        current: HomeAccount,
        board: List<AnkiquestBoards.Standing>,
    ) {
        val index = board.indexOfFirst { it.user == current.user }
        if (index < 0) return
        val place = index + 1
        val panel = views.card(content, tinted = true, horizontal = true)
        AnkiquestArt.place(place)?.takeIf { board[index].xp > 0 && AnkiquestCompanion.visible() }?.let { art ->
            panel.addView(AnkiquestAki.image(this, art, 56))
        }
        views.column(panel).also { column ->
            views.text(column, getString(R.string.aq_board_your_place, place, board.size), heading = true)
        }
    }

    private fun row(
        place: Int,
        row: AnkiquestBoards.Standing,
        mine: Boolean,
        leader: Long,
    ) {
        val panel = views.card(content, tinted = mine, horizontal = true)
        val medal = AnkiquestArt.medal(place)
        if (medal != null) {
            panel.addView(
                ImageView(this).apply {
                    setImageResource(medal)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(views.dp(28), views.dp(28)),
            )
        } else {
            panel.addView(
                TextView(this).apply {
                    text = views.number(place.toLong())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.aq_home_muted))
                },
                LinearLayout.LayoutParams(views.dp(28), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        views.avatar(panel, row.user, row.display, 40)
        val details = views.column(panel)
        val name = if (mine) getString(R.string.aq_challenges_you, row.display) else row.display
        views.text(details, name, heading = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, false)
        }
        val streak = streakLine(row)
        views
            .text(
                details,
                listOfNotNull(getString(R.string.ankiquest_widget_level, row.level), streak).joinToString(" · "),
                small = true,
            ).apply {
                if (row.streak > 0) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(streakIcon(row.state), 0, 0, 0)
                    compoundDrawablePadding = views.dp(4)
                }
            }
        views.bar(details, AnkiquestBoards.percent(row.xp, leader.coerceAtLeast(1)))
        val score =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
        panel.addView(
            score,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart =
                    views.dp(8)
            },
        )
        views.text(score, getString(R.string.aq_board_xp, views.number(row.xp)), heading = true).apply {
            gravity = Gravity.END
            ViewCompat.setAccessibilityHeading(this, false)
        }
        views.text(score, getString(R.string.aq_board_today_reviews, views.number(row.todayReviews)), small = true).gravity = Gravity.END
        val closing = row.closingXp(TimeManager.time.intTimeMS())?.takeIf { period == AnkiquestBoards.Period.WEEK }
        closing?.let { xp ->
            views.text(score, getString(R.string.aq_board_closing_xp, views.number(xp)), small = true).gravity = Gravity.END
        }
        panel.contentDescription =
            listOfNotNull(
                getString(R.string.aq_board_row_description, place, name, row.level, views.number(row.xp)),
                streak?.let { getString(R.string.ankiquest_streak_description, views.number(row.streak.toLong()), it) },
                closing?.let { getString(R.string.aq_board_closing_xp, views.number(it)) },
            ).joinToString(". ")
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        panel.foreground = AppCompatResources.getDrawable(this, ripple.resourceId)
        panel.setOnClickListener { openProfile(row.user) }
    }

    /** Today's status for a row with a streak, in words; null without a streak. */
    private fun streakLine(row: AnkiquestBoards.Standing): String? {
        if (row.streak <= 0) return null
        val status =
            when (row.state) {
                "studied" -> getString(R.string.ankiquest_streak_studied)
                "protected" -> getString(R.string.ankiquest_streak_protected)
                "short" -> getString(R.string.ankiquest_streak_short, minOf(row.todayMinutes, row.streakMinutes), row.streakMinutes)
                "pending" -> getString(R.string.ankiquest_streak_pending)
                else -> getString(R.string.ankiquest_streak_unknown)
            }
        return "${views.number(row.streak.toLong())} · $status"
    }

    private fun streakIcon(state: String): Int =
        when (state) {
            "studied" -> R.drawable.ankiquest_streak_studied
            "protected" -> R.drawable.ankiquest_streak_protected
            else -> R.drawable.ankiquest_streak_pending
        }

    private fun openProfile(user: String) = startActivity(AnkiquestProfileActivity.intent(this, user))

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    companion object {
        /** A period's name, as [AnkiquestBoards.Period.query]; the week when missing or unknown. */
        const val EXTRA_PERIOD = "ankiquest.board_period"

        /** The account a notification was for, as [HomeAccount.notificationAccount]; another account's board says so. */
        const val EXTRA_ACCOUNT = "ankiquest.board_account"

        private const val STATE_PERIOD = "ankiquest.board_period"

        fun intent(
            context: Context,
            period: String = AnkiquestBoards.Period.WEEK.query,
            account: String? = null,
        ): Intent =
            Intent(context, AnkiquestLeaderboardActivity::class.java)
                .putExtra(EXTRA_PERIOD, period)
                .apply { account?.let { putExtra(EXTRA_ACCOUNT, it) } }

        /** The phrase for [period] after "XP", such as "this week". */
        @StringRes
        internal fun periodPhrase(period: AnkiquestBoards.Period): Int =
            when (period) {
                AnkiquestBoards.Period.HOUR -> R.string.ankiquest_period_hour
                AnkiquestBoards.Period.DAY -> R.string.ankiquest_period_day
                AnkiquestBoards.Period.WEEK -> R.string.ankiquest_period_week
                AnkiquestBoards.Period.MONTH -> R.string.ankiquest_period_month
                AnkiquestBoards.Period.YEAR -> R.string.ankiquest_period_year
                AnkiquestBoards.Period.ALL -> R.string.ankiquest_period_all
            }
    }
}
