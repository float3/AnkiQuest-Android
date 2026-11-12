// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A player's profile, natively, as the website's `/week#<user>` shows it: level and XP, streak and
 * freezes, today, daily quests, personal bests, hard-won cards and achievements. The player's own
 * profile adds their latest wins and leads to the native settings, never to a web page. Personal bests
 * lead on to the records ([AnkiquestRecordsActivity]) and, for the player, their study history
 * ([AnkiquestHistoryActivity]).
 */
class AnkiquestProfileActivity : AnkiActivity(R.layout.activity_ankiquest_board) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var views: AnkiquestBoardViews
    private lateinit var undo: AnkiquestUndo
    private var account: HomeAccount? = null
    private var page: AnkiquestBoards.PlayerPage? = null
    private var loading = false
    private var allAchievements = false

    /** Whether the player muted the one shown, and gets deck alerts from them; null where unknown. */
    private var muted: Boolean? = null
    private var deckAlerts: Boolean? = null
    private var changing = false

    @StringRes private var failure: Int? = null
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        undo = AnkiquestUndo(this)
        views = AnkiquestBoardViews(this)
        content = findViewById(R.id.aq_board_content)
        scroll = findViewById(R.id.aq_board_scroll)
        findViewById<TextView>(R.id.aq_board_title).apply {
            setText(if (intent.hasExtra(EXTRA_USER)) R.string.aq_profile_title else R.string.ankiquest_me)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<View>(R.id.aq_board_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.aq_board_refresh).apply {
            contentDescription = getString(R.string.aq_profile_refresh)
            setOnClickListener { load() }
        }
        findViewById<View>(R.id.aq_board_loading).contentDescription = getString(R.string.aq_profile_loading)
        val root = findViewById<LinearLayout>(R.id.aq_board_root)
        // Me and other profiles are pages above their caller, with Back going up one level.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        account = AnkiquestHomeData.account()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        // Signing out or into another account must never leave what the old one could see here.
        if (AnkiquestHomeData.account()?.scope != account?.scope) load()
    }

    /** The player shown: the one asked for, or the signed-in player. */
    private fun player(current: HomeAccount): String = intent.getStringExtra(EXTRA_USER)?.ifEmpty { null } ?: current.user

    private fun load() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            undo.clear()
            account = current
            page = null
            muted = null
            deckAlerts = null
        }
        loadJob?.cancel()
        val turn = ++generation
        failure = null
        if (current == null) {
            page = null
            loading = false
            render()
            return
        }
        loading = true
        render()
        loadJob =
            lifecycleScope.launch {
                val user = player(current)
                val problem =
                    boardAttempt {
                        val loaded = AnkiquestBoards.player(current, user)
                        if (turn == generation) page = loaded
                    }
                if (turn != generation) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    load()
                    return@launch
                }
                if (problem == R.string.aq_profile_hidden) page = null
                failure = problem
                loading = false
                render()
                if (problem == null) refreshPicture(current, user)
                if (problem == null && page?.own == false) loadControls(current, user, turn)
            }
    }

    /**
     * What the player can change about someone else from their profile: muting them, and their deck
     * alerts while they share decks with the player. A control the server cannot tell about stays hidden.
     */
    private fun loadControls(
        current: HomeAccount,
        user: String,
        turn: Int,
    ) {
        lifecycleScope.launch {
            var mute: Boolean? = null
            var alerts: Boolean? = null
            boardAttempt { mute = AnkiquestSocial.overview(current).isMuted(user) }
            boardAttempt {
                val subscriptions = AnkiquestSubscriptions.repository.load(current)
                if (subscriptions.senders.any { it.user == user }) alerts = user !in subscriptions.unsubscribedSenders
            }
            if (turn != generation || account?.scope != current.scope) return@launch
            muted = mute
            deckAlerts = alerts
            render()
        }
    }

    /** Runs one change from the profile, then reads the profile and its controls again. */
    private fun change(
        after: (() -> Unit)? = null,
        work: suspend (HomeAccount) -> Unit,
    ) {
        val current = account ?: return
        if (changing) return
        changing = true
        render()
        lifecycleScope.launch {
            val problem = boardAttempt { work(current) }
            changing = false
            if (problem != null) showThemedToast(this@AnkiquestProfileActivity, getString(problem), shortLength = false)
            if (problem == null) {
                after?.invoke()
            }
            load()
        }
    }

    private fun controls(page: AnkiquestBoards.PlayerPage) {
        val p = page.profile
        if (page.own) {
            val enabled = p.freezesEnabled
            views
                .button(content, getString(if (enabled) R.string.aq_profile_freezes_pause else R.string.aq_profile_freezes_resume)) {
                    if (changing) return@button
                    AlertDialog
                        .Builder(this)
                        .setTitle(if (enabled) R.string.aq_profile_freezes_pause else R.string.aq_profile_freezes_resume)
                        .setMessage(R.string.ankiquest_streak_protection_summary)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> change { Ankiquest.setStreakProtection(!enabled) } }
                        .show()
                }.isEnabled = !changing
            return
        }
        deckAlerts?.let { on ->
            val label = if (on) R.string.aq_profile_deck_alerts_stop else R.string.aq_profile_deck_alerts_start
            views
                .button(content, getString(label, p.display)) {
                    change { AnkiquestSubscriptions.repository.setSubscribed(it, p.user, !on) }
                }.isEnabled = !changing
        }
        muted?.let { isMuted ->
            views
                .button(content, getString(if (isMuted) R.string.aq_friends_unmute else R.string.aq_friends_mute)) {
                    if (isMuted) {
                        unmute(p.user)
                    } else {
                        val current = account ?: return@button
                        undo.apply(
                            current,
                            getString(R.string.aq_friends_muted_done, p.display),
                            perform = { done -> change(after = done) { AnkiquestSocial.change(it, AnkiquestSocial.Change.Mute(p.user)) } },
                            inverse = { unmute(p.user) },
                        )
                    }
                }.isEnabled = !changing
        }
    }

    private fun unmute(user: String) {
        undo.clear()
        change { AnkiquestSocial.change(it, AnkiquestSocial.Change.Unmute(user)) }
    }

    private fun refreshPicture(
        current: HomeAccount,
        user: String,
    ) {
        if (AnkiquestAvatars.bitmap(user) != null) return
        lifecycleScope.launch {
            val fetched = boardAttempt { AnkiquestAvatars.refresh(listOf(user)) } == null
            if (fetched && account?.scope == current.scope && AnkiquestAvatars.bitmap(user) != null) render()
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_board_loading).isVisible = loading
        val current = account
        if (current == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_board_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        val shown = page
        if (shown == null) {
            if (loading) views.text(content, getString(R.string.aq_profile_loading), small = true)
        } else {
            findViewById<TextView>(R.id.aq_board_title).text =
                if (shown.own) getString(R.string.ankiquest_me) else shown.profile.display
            profile(shown)
        }
        failure?.let { problem ->
            if (problem == R.string.aq_profile_hidden) content.addView(AnkiquestAki.image(this, R.drawable.aki_sleeping, 72))
            views.text(content, getString(problem), small = problem != R.string.aq_profile_hidden).accessibilityLiveRegion =
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // Settings must remain reachable even when Me cannot load its profile.
        if (!intent.hasExtra(EXTRA_USER) || shown?.own == true || failure == R.string.aq_home_auth) {
            views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        views.button(content, getString(R.string.aq_profile_leaderboard)) {
            startActivity(AnkiquestLeaderboardActivity.intent(this))
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun profile(page: AnkiquestBoards.PlayerPage) {
        val p = page.profile
        val hero = views.card(content, horizontal = true)
        views.avatar(hero, p.user, p.display, 56)
        views.column(hero).also { column ->
            views.text(column, p.display, heading = true)
            views.text(column, getString(R.string.aq_profile_level, p.level))
            views.bar(column, AnkiquestBoards.percent(p.xpIntoLevel, p.xpForNext))
            views.text(
                column,
                getString(
                    R.string.aq_profile_level_progress,
                    views.number(p.xpIntoLevel),
                    views.number(p.xpForNext),
                    p.level + 1,
                    views.number(p.xpTotal),
                ),
                small = true,
            )
        }
        if (page.own && p.atRisk) {
            val notice = views.card(content, tinted = true)
            val line =
                if (p.streakMinutes > 0) {
                    getString(R.string.aq_profile_at_risk_minutes, p.streak, minOf(p.todayMinutes, p.streakMinutes), p.streakMinutes)
                } else {
                    getString(R.string.aq_profile_at_risk, p.streak)
                }
            views.text(notice, line)
        }
        AnkiquestBoards.closingLine(this, p.closingWeek, TimeManager.time.intTimeMS())?.let { line ->
            views.text(views.card(content, tinted = true), line)
        }
        tiles(
            listOf(
                Triple(getString(R.string.aq_profile_streak), views.number(p.streak.toLong()), streakStatus(p)),
                Triple(
                    getString(R.string.aq_profile_freezes),
                    getString(R.string.aq_profile_freezes_count, p.freezes),
                    getString(if (p.freezesEnabled) R.string.aq_profile_freezes_ready else R.string.aq_profile_freezes_paused),
                ),
                Triple(getString(R.string.aq_profile_reviews_today), views.number(p.todayReviews), null),
                Triple(getString(R.string.aq_profile_xp_today), views.number(p.todayXp), null),
            ),
        )
        if (p.periods.isNotEmpty()) {
            views.section(content, getString(R.string.aq_profile_periods))
            val panel = views.card(content)
            for ((period, xp) in p.periods) {
                views.text(panel, "${getString(period.label)} · ${getString(R.string.aq_board_xp, views.number(xp))}")
            }
        }
        if (p.quests.isNotEmpty()) {
            views.section(content, getString(R.string.aq_home_quests))
            val panel = views.card(content)
            for (quest in p.quests) {
                views.text(panel, if (quest.done) getString(R.string.aq_profile_quest_done, quest.title) else quest.title)
                views.bar(panel, AnkiquestBoards.percent(quest.progress, quest.target))
                views.text(
                    panel,
                    getString(R.string.aq_profile_quest_progress, views.number(quest.progress), views.number(quest.target), quest.reward),
                    small = true,
                )
            }
        }
        if (page.wins.isNotEmpty()) {
            views.section(content, getString(R.string.aq_profile_wins))
            for (win in page.wins) {
                val panel = views.card(content)
                views.text(panel, win.title, heading = true)
                if (win.body.isNotEmpty()) views.text(panel, win.body)
                if (win.createdAt > 0) {
                    views.text(panel, DateUtils.formatDateTime(this, win.createdAt * 1000, DATE), small = true)
                }
            }
        }
        if (p.conquests.isNotEmpty()) {
            views.section(content, getString(R.string.aq_profile_conquests))
            views.text(content, resources.getQuantityString(R.plurals.aq_profile_conquered, p.conquered, p.conquered), small = true)
            for (conquest in p.conquests) {
                val panel = views.card(content)
                views.text(
                    panel,
                    getString(if (conquest.leech) R.string.aq_profile_leech else R.string.aq_profile_conquest),
                    heading = true,
                )
                views.text(panel, resources.getQuantityString(R.plurals.aq_profile_lapses, conquest.lapses, conquest.lapses))
                views.text(panel, DateUtils.formatDateTime(this, conquest.at, DATE), small = true)
                views.clickable(panel) { startActivity(AnkiquestConquestActivity.intent(this, p.user, conquest.at)) }
            }
        }
        views.section(content, getString(R.string.aq_profile_bests))
        views.card(content).also { panel ->
            for ((period, best) in p.records) {
                views.text(panel, getString(bestLabel(period)), heading = true).apply { ViewCompat.setAccessibilityHeading(this, false) }
                views.text(
                    panel,
                    getString(R.string.aq_profile_best_value, views.number(best.xp), views.number(best.reviews)),
                    small = true,
                )
            }
            views.text(panel, getString(R.string.review_heatmap_longest_streak), heading = true).apply {
                ViewCompat.setAccessibilityHeading(this, false)
            }
            views.text(panel, days(p.bestStreak), small = true)
            views
                .text(
                    panel,
                    getString(R.string.review_heatmap_days_studied),
                    heading = true,
                ).apply { ViewCompat.setAccessibilityHeading(this, false) }
            views.text(panel, days(p.daysActive), small = true)
        }
        // The bests lead on to the records everyone is chasing and, on the player's own profile, their study calendar.
        views.button(content, getString(R.string.aq_records_title)) { startActivity(AnkiquestRecordsActivity.intent(this)) }
        if (page.own) views.button(content, getString(R.string.aq_history_title)) { startActivity(AnkiquestHistoryActivity.intent(this)) }
        views.section(content, getString(R.string.aq_profile_lifetime))
        tiles(
            listOf(
                Triple(getString(R.string.aq_challenges_kind_reviews), views.number(p.lifetimeReviews), null),
                Triple(getString(R.string.aq_profile_hours), views.number(p.lifetimeHours), null),
                Triple(getString(R.string.aq_profile_best_streak), views.number(p.bestStreak), null),
                Triple(getString(R.string.aq_profile_best_combo), views.number(p.bestCombo), null),
            ),
        )
        if (p.achievements.isNotEmpty()) achievements(p)
        controls(page)
    }

    private fun achievements(p: AnkiquestBoards.Profile) {
        views.section(content, getString(R.string.aq_profile_achievements))
        views.text(content, getString(R.string.aq_profile_unlocked, p.unlocked, p.achievements.size), small = true)
        val ordered = AnkiquestBoards.ordered(p.achievements)
        val shown = if (allAchievements) ordered else ordered.take(maxOf(ACHIEVEMENTS_SHOWN, p.unlocked))
        for (achievement in shown) {
            val panel = views.card(content, tinted = achievement.unlocked != null)
            views.text(panel, achievement.title, heading = true).apply { ViewCompat.setAccessibilityHeading(this, false) }
            if (achievement.description.isNotEmpty()) views.text(panel, achievement.description)
            val unlocked = achievement.unlocked
            if (unlocked != null) {
                views.text(panel, getString(R.string.aq_profile_achievement_unlocked, unlocked, achievement.reward), small = true)
            } else {
                views.bar(panel, AnkiquestBoards.percent(achievement.progress, achievement.target))
                views.text(
                    panel,
                    getString(R.string.aq_home_progress_fraction, achievement.progress.toInt(), achievement.target.toInt()),
                    small = true,
                )
            }
        }
        if (ordered.size > shown.size || allAchievements) {
            views.button(content, getString(if (allAchievements) R.string.aq_profile_show_fewer else R.string.aq_profile_show_all)) {
                allAchievements = !allAchievements
                render()
            }
        }
    }

    /** Two tiles a row, each a [Triple] of label, value and an optional note. */
    private fun tiles(items: List<Triple<String, String, String?>>) {
        for (pair in items.chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            content.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            pair.forEachIndexed { index, (label, value, note) ->
                val tile =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(views.dp(14), views.dp(10), views.dp(14), views.dp(10))
                        background = views.shape(R.color.aq_home_surface)
                        isFocusable = true
                    }
                line.addView(
                    tile,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        topMargin = views.dp(10)
                        if (index > 0) marginStart = views.dp(10)
                    },
                )
                views.text(tile, label, small = true)
                views.text(tile, value).apply {
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                }
                note?.let { views.text(tile, it, small = true) }
                tile.contentDescription = listOfNotNull(label, value, note).joinToString(", ")
                tile.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                tile.children.forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = views.dp(10) })
        }
    }

    /** Today's streak status as the server judged it, in the words every screen uses. */
    private fun streakStatus(p: AnkiquestBoards.Profile): String {
        val server =
            AnkiquestDay.Server(
                streak = p.streak,
                streakState = p.streakState,
                dayEndsAt = p.dayEndsAt.takeIf { it > 0 } ?: Long.MAX_VALUE,
                lastReviewId = Long.MAX_VALUE,
                startsAt = null,
                reviews = p.todayReviews.toInt(),
                timeMs = p.todayMinutes * 60_000L,
                streakMinutes = p.streakMinutes.toInt(),
            )
        return AnkiquestDay.words(this, AnkiquestDay.label(server, TimeManager.time.intTimeMS()), p.todayMinutes, p.streakMinutes)
    }

    private fun days(count: Long): String = resources.getQuantityString(R.plurals.aq_profile_days, count.toInt(), views.number(count))

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    companion object {
        /** The player to show, as their user name; the signed-in player when missing. */
        const val EXTRA_USER = "ankiquest.profile_user"

        /** How many achievements show before "Show all", unless more are unlocked. */
        private const val ACHIEVEMENTS_SHOWN = 6

        private const val DATE = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH

        fun intent(
            context: Context,
            user: String? = null,
        ): Intent = Intent(context, AnkiquestProfileActivity::class.java).apply { user?.let { putExtra(EXTRA_USER, it) } }

        @StringRes
        internal fun bestLabel(period: AnkiquestBoards.Period): Int =
            when (period) {
                AnkiquestBoards.Period.HOUR -> R.string.aq_profile_best_hour
                AnkiquestBoards.Period.DAY -> R.string.aq_profile_best_day
                AnkiquestBoards.Period.WEEK -> R.string.aq_profile_best_week
                AnkiquestBoards.Period.MONTH -> R.string.aq_profile_best_month
                AnkiquestBoards.Period.YEAR, AnkiquestBoards.Period.ALL -> R.string.aq_profile_best_year
            }
    }
}
