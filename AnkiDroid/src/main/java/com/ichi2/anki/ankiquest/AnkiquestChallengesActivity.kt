// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.util.UUID

/**
 * Friend goals, natively: invitations to accept or decline, goals in progress with everyone's
 * progress, finished ones with their result, the weekly suggested friend and starting a new goal.
 * [EXTRA_CHALLENGE] opens one goal. A server without the endpoint gets the website instead.
 */
class AnkiquestChallengesActivity : AnkiActivity(R.layout.activity_ankiquest_feed) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var overview: AnkiquestChallenges.Overview? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null

    /** The goal shown on its own, or null for the list. */
    private var selected: Long? = null

    /** Whether [selected] was opened from the list, so going back shows the list again. */
    private var fromList = false
    private var showHistory = false

    /** A change on its way; every button that would change something waits for it. */
    private var busy = false
    private var generation = 0
    private var loadJob: Job? = null

    /** The goal last sent and its request id, reused only to retry that same goal. */
    private var pending: Pair<AnkiquestChallenges.NewGoal, String>? = null

    private val backToList =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = showList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.aq_feed_content)
        scroll = findViewById(R.id.aq_feed_scroll)
        findViewById<TextView>(R.id.aq_feed_title).apply {
            setText(R.string.aq_challenges_title)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<View>(R.id.aq_feed_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.aq_feed_refresh).apply {
            contentDescription = getString(R.string.aq_challenges_refresh)
            setOnClickListener {
                status = null
                reload()
            }
        }
        findViewById<View>(R.id.aq_feed_loading).contentDescription = getString(R.string.aq_challenges_loading)
        onBackPressedDispatcher.addCallback(this, backToList)
        val root = findViewById<View>(R.id.aq_feed_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        account = AnkiquestHomeData.account()
        if (savedInstanceState == null) {
            val addressed = intent.getStringExtra(EXTRA_ACCOUNT)
            if (addressed != null && addressed != account?.notificationAccount) {
                // Another account's goal id means nothing here; this account's list opens instead.
                status = getString(R.string.aq_challenges_account_route_changed)
            } else {
                selected = intent.getLongExtra(EXTRA_CHALLENGE, 0).takeIf { it > 0 }
            }
        } else {
            selected = savedInstanceState.getLong(STATE_SELECTED).takeIf { it > 0 }
            fromList = savedInstanceState.getBoolean(STATE_FROM_LIST)
            showHistory = savedInstanceState.getBoolean(STATE_HISTORY)
        }
        backToList.isEnabled = selected != null && fromList
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SELECTED, selected ?: 0)
        outState.putBoolean(STATE_FROM_LIST, fromList)
        outState.putBoolean(STATE_HISTORY, showHistory)
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        // Another account's goals must never stay on screen after signing out or switching.
        if (AnkiquestHomeData.account()?.scope != account?.scope) reload()
    }

    private fun reload() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            overview = null
            pending = null
            status = null
            showList()
        }
        busy = false
        load()
    }

    private fun load() {
        val current = account
        loadJob?.cancel()
        val turn = ++generation
        failure = null
        if (current == null) {
            loading = false
            render()
            return
        }
        loading = true
        render()
        loadJob =
            lifecycleScope.launch {
                var missing = false
                val problem =
                    attempt {
                        try {
                            val next = AnkiquestChallenges.overview(current)
                            if (turn == generation) overview = next
                        } catch (e: AnkiquestHttpException) {
                            if (e.code != 404) throw e
                            missing = true
                        }
                    }
                if (turn != generation) return@launch
                if (missing) {
                    openWebsite()
                    return@launch
                }
                if (problem == ACCOUNT_CHANGED) {
                    reload()
                    return@launch
                }
                failure = problem
                loading = false
                render()
            }
    }

    /** An older server has no friend goal endpoint; its website still has the page. */
    private fun openWebsite() {
        val path = selected?.let(AnkiquestNavigation::challengePath) ?: AnkiquestNavigation.FRIENDS_PATH
        startActivity(AnkiquestActivity.intent(this, path, account?.notificationAccount))
        finish()
    }

    /** Runs [work] and answers the message for its failure, or null when it worked. */
    private suspend fun attempt(work: suspend () -> Unit): Int? =
        try {
            work()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: HomeAccountChanged) {
            ACCOUNT_CHANGED
        } catch (e: AnkiquestHttpException) {
            Timber.w(e, "AnkiQuest friend goals request refused")
            if (e.code == 401) R.string.aq_home_auth else R.string.aq_challenges_server_error
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest friend goals unreachable")
            R.string.aq_challenges_offline
        } catch (e: JSONException) {
            Timber.w(e, "AnkiQuest friend goals unreadable")
            R.string.aq_challenges_server_error
        }

    /**
     * Sends a change and shows the list the server answers. [done] is the line for success; a
     * refusal shows [failed] with the server's own reason when it gave one.
     */
    private fun change(
        @StringRes failed: Int,
        done: (AnkiquestChallenges.Overview) -> String,
        work: suspend (HomeAccount) -> AnkiquestChallenges.Overview,
        after: (AnkiquestChallenges.Overview) -> Unit = {},
    ) {
        val current = account ?: return
        if (busy) return
        busy = true
        status = getString(R.string.aq_challenges_updating)
        render()
        lifecycleScope.launch {
            var answer: AnkiquestChallenges.Overview? = null
            var reason: String? = null
            val problem =
                attempt {
                    try {
                        answer = work(current)
                    } catch (e: AnkiquestHttpException) {
                        if (e.code == 400 || e.code == 403 || e.code == 404) reason = e.explanation
                        throw e
                    }
                }
            if (account?.scope != current.scope) return@launch
            busy = false
            if (problem == ACCOUNT_CHANGED) {
                reload()
                return@launch
            }
            val result = answer
            if (result != null) {
                val before = overview
                overview = result
                status = done(result)
                after(before ?: result)
            } else {
                status = getString(R.string.aq_feed_failed_because, getString(failed), reason ?: getString(problem!!))
            }
            render()
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_feed_loading).isVisible = loading
        status?.let { line ->
            text(content, line, small = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        }
        val current = account
        if (current == null) {
            val panel = card(content)
            text(panel, getString(R.string.aq_challenges_connect))
            button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        failure?.let { problem ->
            text(content, getString(problem), small = true)
            if (problem == R.string.aq_home_auth) button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        val shown = overview
        if (shown == null) {
            if (loading) text(content, getString(R.string.aq_challenges_loading), small = true)
            return
        }
        val goal = selected
        if (goal != null) renderDetail(shown, goal, current.user) else renderList(shown, current.user)
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderList(
        shown: AnkiquestChallenges.Overview,
        user: String,
    ) {
        val now = now()
        val others = shown.recipients.filter { it.user != user }
        button(content, getString(R.string.aq_challenges_start), primary = true, enabled = !busy && others.isNotEmpty()) {
            askNewGoal(others)
        }
        shown.suggestion?.let { renderSuggestion(it) }
        val invited = shown.challenges.filter { it.invites(user, now) }
        val active = shown.challenges.filter { !it.ended(now) && it !in invited }
        val ended = shown.challenges.filter { it.ended(now) }
        if (invited.isNotEmpty()) {
            section(getString(R.string.aq_challenges_invitations), getString(R.string.aq_challenges_invitations_hint))
            invited.forEach { renderChallenge(it, user, detail = false) }
        }
        if (active.isNotEmpty()) {
            section(getString(R.string.aq_challenges_active), getString(R.string.aq_challenges_active_hint))
            active.forEach { renderChallenge(it, user, detail = false) }
        }
        if (invited.isEmpty() && active.isEmpty() && !loading) {
            content.addView(
                AnkiquestAki.image(this, R.drawable.aki_challenge, 72).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
                },
            )
            text(content, getString(R.string.aq_challenges_empty), heading = true)
            text(content, getString(R.string.aq_challenges_empty_hint), small = true)
        }
        if (ended.isNotEmpty()) {
            val label =
                if (showHistory) {
                    getString(
                        R.string.aq_challenges_hide_history,
                    )
                } else {
                    getString(R.string.aq_challenges_history, ended.size)
                }
            button(content, label) {
                showHistory = !showHistory
                render()
            }
            if (showHistory) ended.forEach { renderChallenge(it, user, detail = false) }
        }
    }

    private fun renderSuggestion(suggestion: AnkiquestChallenges.Suggestion) {
        val panel = card(content, tinted = suggestion.challengeId == null && !suggestion.dismissed)
        text(panel, getString(R.string.aq_challenges_weekly), heading = true)
        val goal = suggestion.challengeId
        when {
            goal != null -> {
                text(panel, getString(R.string.aq_challenges_weekly_sent))
                button(panel, getString(R.string.aq_challenges_weekly_view)) { open(goal) }
            }
            suggestion.dismissed -> text(panel, getString(R.string.aq_challenges_weekly_dismissed))
            else -> {
                text(panel, suggestion.friend.display, heading = true)
                text(panel, getString(R.string.aq_challenges_explain_weekly))
                text(panel, getString(R.string.aq_challenges_explain_waiting_weekly), small = true)
                text(panel, getString(R.string.aq_challenges_reply_by, dateTime(suggestion.expiresAt)), small = true)
                button(panel, getString(R.string.aq_challenges_weekly_invite), primary = true, enabled = !busy) {
                    change(
                        R.string.aq_challenges_create_failed,
                        { getString(R.string.aq_challenges_weekly_sent) },
                        { AnkiquestChallenges.weekly(it, suggestion, "invite") },
                    )
                }
                button(panel, getString(R.string.aq_challenges_weekly_dismiss), enabled = !busy) {
                    change(
                        R.string.aq_challenges_action_failed,
                        { getString(R.string.aq_challenges_weekly_dismissed) },
                        { AnkiquestChallenges.weekly(it, suggestion, "dismiss") },
                    )
                }
            }
        }
        text(panel, getString(R.string.aq_challenges_weekly_next, dateTime(suggestion.expiresAt)), small = true)
    }

    private fun renderDetail(
        shown: AnkiquestChallenges.Overview,
        goal: Long,
        user: String,
    ) {
        button(content, getString(R.string.aq_challenges_all)) { showList() }
        val challenge = shown.challenges.firstOrNull { it.id == goal }
        if (challenge == null) {
            if (loading) return
            val panel = card(content)
            text(panel, getString(R.string.aq_challenges_missing), heading = true)
            text(panel, getString(R.string.aq_challenges_missing_hint), small = true)
            button(panel, getString(R.string.aq_challenges_refresh)) {
                status = null
                reload()
            }
            return
        }
        val art = if (challenge.status == "complete") R.drawable.aki_challenge_won else R.drawable.aki_challenge
        content.addView(AnkiquestAki.image(this, art, 72).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12) })
        renderChallenge(challenge, user, detail = true)
    }

    private fun renderChallenge(
        challenge: AnkiquestChallenges.Challenge,
        user: String,
        detail: Boolean,
    ) {
        val now = now()
        val me = challenge.member(user)
        val ended = challenge.ended(now)
        val invited = challenge.invites(user, now)
        val waiting = !challenge.started
        val panel = card(content, tinted = invited)
        text(panel, title(challenge), heading = true)
        text(panel, getString(statusLabel(challenge, invited, ended)), small = true).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(if (challenge.status == "complete" || invited) R.color.aq_home_primary else R.color.aq_home_muted))
        }
        val mode = getString(if (challenge.cooperative) R.string.aq_challenges_shared else R.string.aq_challenges_individual)
        text(panel, "$mode · ${timing(challenge, waiting, ended)}", small = true)
        text(panel, getString(explanation(challenge, waiting)), small = true)
        if (invited) text(panel, getString(R.string.aq_challenges_invited_by, display(challenge, challenge.creator)))
        if (!waiting && (challenge.cooperative || me?.status == "accepted")) {
            val done = challenge.progressFor(user)
            val line =
                getString(
                    if (challenge.cooperative) R.string.aq_challenges_progress_together else R.string.aq_challenges_progress_you,
                    done,
                    amount(challenge, challenge.target),
                )
            panel.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = challenge.target.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                    progress = done.coerceIn(0, max.toLong()).toInt()
                    progressTintList = ColorStateList.valueOf(getColor(R.color.aq_home_primary))
                    contentDescription = line
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(6) },
            )
            text(panel, line, small = true)
        }
        for (member in challenge.members) {
            val name = if (member.user == user) getString(R.string.aq_challenges_you, member.display) else member.display
            text(panel, getString(R.string.aq_challenges_member, name, memberState(challenge, member, waiting)), small = !detail)
        }
        if (detail) {
            val rule = if (challenge.reviews) R.string.aq_challenges_rule_reviews else R.string.aq_challenges_rule_days
            val dates =
                if (waiting) {
                    getString(R.string.aq_challenges_rule_waiting)
                } else {
                    getString(R.string.aq_challenges_dates, dateTime(challenge.startAt), dateTime(challenge.endAt))
                }
            text(panel, "$dates ${getString(rule)}", small = true)
        }
        if (invited) {
            button(panel, getString(R.string.aq_challenges_accept), primary = true, enabled = !busy) { act(challenge, "accept") }
            button(panel, getString(R.string.aq_challenges_decline), enabled = !busy) { act(challenge, "decline") }
        }
        if (detail && me?.status == "accepted" && !ended && challenge.creator != user) {
            button(panel, getString(R.string.aq_challenges_leave), enabled = !busy) {
                confirm(R.string.aq_challenges_leave_confirm, R.string.aq_challenges_leave) { act(challenge, "leave") }
            }
        }
        if (detail && challenge.creator == user && !ended) {
            button(panel, getString(R.string.aq_challenges_cancel), enabled = !busy) {
                confirm(R.string.aq_challenges_cancel_confirm, R.string.aq_challenges_cancel) { act(challenge, "cancel") }
            }
        }
        if (!detail) button(panel, getString(R.string.aq_challenges_details)) { open(challenge.id) }
    }

    private fun act(
        challenge: AnkiquestChallenges.Challenge,
        action: String,
    ) = change(
        R.string.aq_challenges_action_failed,
        {
            getString(
                when (action) {
                    "accept" -> R.string.aq_challenges_accepted
                    "decline" -> R.string.aq_challenges_declined_done
                    "leave" -> R.string.aq_challenges_left
                    else -> R.string.aq_challenges_cancelled_done
                },
            )
        },
        { AnkiquestChallenges.act(it, challenge.id, action) },
    )

    private fun confirm(
        @StringRes question: Int,
        @StringRes yes: Int,
        go: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(this)
            .setMessage(question)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(yes) { _, _ -> go() }
            .show()
    }

    /** The form for a new goal: three study days each over seven days unless changed, as on the website. */
    private fun askNewGoal(people: List<AnkiquestChallenges.Recipient>) {
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
        label(form, R.string.aq_challenges_create_invite)
        val boxes =
            people.map { person ->
                CheckBox(this).apply {
                    text = person.display
                    tag = person.user
                    form.addView(this)
                }
            }
        label(form, R.string.aq_challenges_create_name)
        val name =
            field(form, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
                setText(R.string.aq_challenges_create_default_name)
                filters = arrayOf(InputFilter.LengthFilter(AnkiquestChallenges.MAX_TITLE))
            }
        label(form, R.string.aq_challenges_create_measure)
        val kind = radios(form, R.string.aq_challenges_kind_days, R.string.aq_challenges_kind_reviews)
        val mode = radios(form, R.string.aq_challenges_mode_each, R.string.aq_challenges_mode_shared)
        label(form, R.string.aq_challenges_create_target)
        val target = field(form, InputType.TYPE_CLASS_NUMBER).apply { setText("3") }
        label(form, R.string.aq_challenges_create_days)
        val days = field(form, InputType.TYPE_CLASS_NUMBER).apply { setText("7") }
        kind.setOnCheckedChangeListener { group, checked ->
            target.setText(if (checked == group.getChildAt(1).id) "50" else "3")
        }
        val ready =
            CheckBox(this).apply {
                setText(R.string.aq_challenges_create_when_ready)
                form.addView(this)
            }
        val problem = text(form, "", small = true).apply { setTextColor(getColor(R.color.aq_home_primary)) }
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.aq_challenges_start)
                .setView(ScrollView(this).apply { addView(form) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.aq_challenges_create_send, null)
                .show()
        // Checked before closing, so a goal the server would refuse keeps what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val goal =
                AnkiquestChallenges.NewGoal(
                    title = name.text.toString().trim(),
                    reviews = kind.checkedRadioButtonId == kind.getChildAt(1).id,
                    cooperative = mode.checkedRadioButtonId == mode.getChildAt(1).id,
                    target = target.text.toString().toLongOrNull() ?: 0,
                    days = days.text.toString().toIntOrNull() ?: 0,
                    recipients = boxes.filter { it.isChecked }.map { it.tag as String },
                    startWhenReady = ready.isChecked,
                )
            val refused = goal.problem()
            if (refused != null) {
                problem.text = getString(problemText(refused))
                return@setOnClickListener
            }
            dialog.dismiss()
            create(goal)
        }
    }

    private fun create(goal: AnkiquestChallenges.NewGoal) {
        val key = pending?.takeIf { it.first == goal }?.second ?: UUID.randomUUID().toString()
        pending = goal to key
        change(
            R.string.aq_challenges_create_failed,
            { getString(R.string.aq_challenges_create_sent) },
            { AnkiquestChallenges.create(it, goal, key) },
        ) { before ->
            pending = null
            val known = before.challenges.map { it.id }.toSet()
            overview?.challenges?.firstOrNull { it.id !in known }?.let { open(it.id) }
        }
    }

    private fun problemText(problem: AnkiquestChallenges.Problem): Int =
        when (problem) {
            AnkiquestChallenges.Problem.NO_TITLE -> R.string.aq_challenges_create_no_name
            AnkiquestChallenges.Problem.RECIPIENTS -> R.string.aq_challenges_create_no_friends
            AnkiquestChallenges.Problem.NUMBERS -> R.string.aq_challenges_create_bad_numbers
            AnkiquestChallenges.Problem.TOO_MANY_DAYS -> R.string.aq_challenges_create_days_too_many
        }

    private fun open(goal: Long) {
        selected = goal
        fromList = true
        backToList.isEnabled = true
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun showList() {
        selected = null
        fromList = false
        backToList.isEnabled = false
        render()
    }

    private fun title(challenge: AnkiquestChallenges.Challenge): String =
        if (challenge.weekly) getString(R.string.aq_challenges_weekly_title) else challenge.title

    private fun statusLabel(
        challenge: AnkiquestChallenges.Challenge,
        invited: Boolean,
        ended: Boolean,
    ): Int =
        when {
            invited -> R.string.aq_challenges_status_invited
            challenge.status == "complete" -> R.string.aq_challenges_status_complete
            ended && challenge.status == "declined" -> R.string.aq_challenges_status_declined
            ended && challenge.status == "cancelled" -> R.string.aq_challenges_status_cancelled
            ended -> R.string.aq_challenges_status_ended
            !challenge.started && challenge.weekly -> R.string.aq_challenges_status_waiting_weekly
            !challenge.started -> R.string.aq_challenges_status_waiting
            else -> R.string.aq_challenges_active
        }

    private fun timing(
        challenge: AnkiquestChallenges.Challenge,
        waiting: Boolean,
        ended: Boolean,
    ): String =
        when {
            waiting && ended -> getString(R.string.aq_challenges_not_started)
            waiting && challenge.weekly -> getString(R.string.aq_challenges_reply_by, dateTime(challenge.endAt))
            waiting -> getString(R.string.aq_challenges_starts_when_ready)
            else -> getString(R.string.aq_challenges_ends, dateTime(challenge.endAt))
        }

    private fun explanation(
        challenge: AnkiquestChallenges.Challenge,
        waiting: Boolean,
    ): Int =
        when {
            waiting && challenge.weekly -> R.string.aq_challenges_explain_waiting_weekly
            waiting -> R.string.aq_challenges_explain_waiting
            challenge.weekly -> R.string.aq_challenges_explain_weekly
            challenge.cooperative -> R.string.aq_challenges_explain_shared
            else -> R.string.aq_challenges_explain_individual
        }

    private fun memberState(
        challenge: AnkiquestChallenges.Challenge,
        member: AnkiquestChallenges.Member,
        waiting: Boolean,
    ): String =
        when (member.status) {
            "accepted" -> if (waiting) getString(R.string.aq_challenges_member_ready) else amount(challenge, member.progress)
            "invited" -> getString(R.string.aq_challenges_member_invited)
            "declined" -> getString(R.string.aq_challenges_member_declined)
            else -> getString(R.string.aq_challenges_member_left)
        }

    private fun amount(
        challenge: AnkiquestChallenges.Challenge,
        count: Long,
    ): String {
        val quantity = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val plural = if (challenge.reviews) R.plurals.aq_challenges_reviews else R.plurals.aq_challenges_days
        return resources.getQuantityString(plural, quantity, quantity)
    }

    private fun display(
        challenge: AnkiquestChallenges.Challenge,
        user: String,
    ): String = challenge.member(user)?.display ?: overview?.recipients?.firstOrNull { it.user == user }?.display ?: user

    private fun now(): Long = TimeManager.time.intTimeMS()

    private fun dateTime(millis: Long): String = DateUtils.formatDateTime(this, millis, DATE_AND_TIME)

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun section(
        title: String,
        hint: String,
    ) {
        text(content, title, heading = true).setPadding(0, dp(16), 0, 0)
        text(content, hint, small = true)
    }

    private fun label(
        parent: LinearLayout,
        @StringRes value: Int,
    ) {
        text(parent, getString(value), small = true).setPadding(0, dp(12), 0, 0)
    }

    private fun field(
        parent: LinearLayout,
        type: Int,
    ): EditText =
        EditText(this).apply {
            inputType = type
            isSingleLine = true
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** Two choices, the first checked. */
    private fun radios(
        parent: LinearLayout,
        @StringRes first: Int,
        @StringRes second: Int,
    ): RadioGroup =
        RadioGroup(this).apply {
            for (choice in listOf(first, second)) {
                addView(
                    RadioButton(this@AnkiquestChallengesActivity).apply {
                        id = View.generateViewId()
                        setText(choice)
                    },
                )
            }
            check(getChildAt(0).id)
            parent.addView(this)
        }

    private fun card(
        parent: LinearLayout,
        tinted: Boolean = false,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = shape(if (tinted) R.color.aq_home_tint else R.color.aq_home_surface)
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
        }

    private fun text(
        parent: LinearLayout,
        value: String,
        heading: Boolean = false,
        small: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize =
                when {
                    heading -> 17f
                    small -> 14f
                    else -> 16f
                }
            setTextColor(getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
            if (heading) {
                typeface = Typeface.DEFAULT_BOLD
                ViewCompat.setAccessibilityHeading(this, true)
            }
            setPadding(0, dp(3), 0, dp(3))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun button(
        parent: LinearLayout,
        label: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            minimumHeight = dp(48)
            cornerRadius = dp(12)
            isEnabled = enabled
            setTextColor(getColor(if (primary) R.color.aq_home_primary_text else R.color.aq_home_text))
            backgroundTintList = ColorStateList.valueOf(getColor(if (primary) R.color.aq_home_primary else R.color.aq_home_surface))
            if (!primary) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(getColor(R.color.aq_home_outline))
            }
            setOnClickListener { click() }
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(8)
                },
            )
        }

    private fun shape(
        @ColorRes color: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(getColor(color))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** The account a notification was for, as [HomeAccount.notificationAccount]; another account's goal does not open. */
        const val EXTRA_ACCOUNT = "ankiquest.challenges_account"

        /** One goal to open, by id. */
        const val EXTRA_CHALLENGE = "ankiquest.challenge"

        private const val STATE_SELECTED = "selected"
        private const val STATE_FROM_LIST = "fromList"
        private const val STATE_HISTORY = "history"

        /** Not a message: [attempt]'s answer when the account changed under a request. */
        private const val ACCOUNT_CHANGED = -1

        private const val DATE_AND_TIME = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH

        fun intent(
            context: Context,
            challenge: Long? = null,
            account: String? = null,
        ): Intent =
            Intent(context, AnkiquestChallengesActivity::class.java).apply {
                challenge?.takeIf { it > 0 }?.let { putExtra(EXTRA_CHALLENGE, it) }
                account?.let { putExtra(EXTRA_ACCOUNT, it) }
            }
    }
}
