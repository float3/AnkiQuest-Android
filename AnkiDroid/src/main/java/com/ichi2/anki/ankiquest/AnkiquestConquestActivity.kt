// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A conquered card, natively, as the website's `/conquered/{user}/{at}` shows it: a card that was
 * forgotten again and again and is learned at last, with how often and for how long. Its owner can
 * tell friends, share it and choose to say what the card was. Only these numbers are shared, never
 * the card itself, unless the owner adds its label.
 */
class AnkiquestConquestActivity : AnkiActivity(R.layout.activity_ankiquest_board) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var views: AnkiquestBoardViews
    private var account: HomeAccount? = null
    private var user = ""
    private var at = 0L
    private var conquest: AnkiquestCommunity.Conquest? = null

    /** The card's deck in this phone's collection, for its owner only; it is never sent to the server. */
    private var deck: String? = null

    /** What this phone's collection offers as the card's label, for its owner only. */
    private var labels: AnkiquestShare.Labels? = null
    private var loading = false
    private var busy = false

    @StringRes private var failure: Int? = null
    private var status: String? = null
    private var generation = 0
    private var job: Job? = null
    private var labelOwner: AnkiquestDialogOwner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        views = AnkiquestBoardViews(this)
        content = findViewById(R.id.aq_board_content)
        scroll = findViewById(R.id.aq_board_scroll)
        findViewById<TextView>(R.id.aq_board_title).apply {
            setText(R.string.aq_profile_conquest)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<View>(R.id.aq_board_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.aq_board_refresh).apply {
            contentDescription = getString(R.string.aq_conquest_refresh)
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
        user = intent.getStringExtra(EXTRA_USER).orEmpty()
        at = intent.getLongExtra(EXTRA_AT, 0L)
        account = AnkiquestHomeData.account()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        if (AnkiquestHomeData.account()?.scope != account?.scope) {
            load()
        } else if (!busy && !loading) {
            render()
        }
    }

    private fun load() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            conquest = null
            deck = null
            labels = null
            status = null
            // The old account's change no longer answers here.
            busy = false
        }
        job?.cancel()
        val turn = ++generation
        failure = null
        if (current == null || user.isEmpty() || at <= 0) {
            conquest = null
            loading = false
            if (current != null) failure = R.string.aq_conquest_missing
            render()
            return
        }
        loading = true
        render()
        job =
            lifecycleScope.launch {
                var answer: AnkiquestCommunity.Conquest? = null
                val problem = attempt { answer = AnkiquestCommunity.conquest(current, user, at) }
                if (turn != generation) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    load()
                    return@launch
                }
                answer?.let { conquest = it }
                if (problem == R.string.aq_conquest_missing) conquest = null
                if (answer?.let { owns(current, it) } == true) {
                    if (deck == null) deck = AnkiquestShare.conqueredCardDeck(at)
                    if (labels == null) labels = AnkiquestShare.conqueredCardLabels(at)
                }
                if (turn != generation) return@launch
                failure = problem
                loading = false
                render()
            }
    }

    /** Runs an owner's action, then shows [done]'s message and the card as the server now has it. */
    private fun act(
        @StringRes working: Int,
        work: suspend (HomeAccount) -> String?,
    ) {
        val current = account ?: return
        if (busy) return
        busy = true
        status = getString(working)
        render()
        lifecycleScope.launch {
            var message: String? = null
            var refreshed: AnkiquestCommunity.Conquest? = null
            val problem =
                attempt {
                    message = work(current)
                    refreshed = AnkiquestCommunity.conquest(current, user, at)
                }
            if (account?.scope != current.scope) return@launch
            busy = false
            if (problem == R.string.aq_feed_account_changed) {
                load()
                return@launch
            }
            refreshed?.let { conquest = it }
            status = if (problem == null) message else getString(R.string.aq_conquest_action_failed)
            render()
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_board_loading).isVisible = loading || busy
        val current = account
        if (current == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_board_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) {
                startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))
            }
            return
        }
        val card = conquest
        when {
            card != null -> show(current, card)
            loading -> views.text(content, getString(R.string.aq_conquest_loading), small = true)
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun show(
        current: HomeAccount,
        card: AnkiquestCommunity.Conquest,
    ) {
        val headline = getString(if (card.leech) R.string.aq_profile_leech else R.string.aq_profile_conquest)
        findViewById<TextView>(R.id.aq_board_title).text = headline
        val hero = views.card(content, tinted = true)
        hero.gravity = Gravity.CENTER_HORIZONTAL
        if (AnkiquestCompanion.visible()) {
            hero.addView(AnkiquestAki.image(this, if (card.leech) R.drawable.aki_winner else R.drawable.aki_deck_conquered, 120))
        } else {
            views.text(hero, "🏆").apply {
                textSize = 56f
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        views
            .text(
                hero,
                getString(if (card.leech) R.string.aq_conquest_badge_leech else R.string.aq_conquest_badge),
                small = true,
            ).gravity =
            Gravity.CENTER
        views.text(hero, headline, heading = true).apply {
            textSize = 24f
            gravity = Gravity.CENTER
        }
        views.text(hero, "${card.display} · ${DateUtils.formatDateTime(this, card.at, DATE)}", small = true).gravity = Gravity.CENTER
        deck?.takeIf { owns(current, card) }?.let { name ->
            views.text(hero, getString(R.string.aq_conquest_deck, name), small = true).gravity = Gravity.CENTER
        }
        card.label?.let { label ->
            val panel = views.card(content)
            views.text(panel, label, heading = true).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                ViewCompat.setAccessibilityHeading(this, false)
            }
        }
        views
            .text(
                content,
                if (card.mine) {
                    resources.getQuantityString(R.plurals.aq_conquest_lead_mine, card.lapses, card.lapses)
                } else {
                    resources.getQuantityString(R.plurals.aq_conquest_lead, card.lapses, card.display, card.lapses)
                },
            ).setPadding(0, views.dp(12), 0, 0)
        stats(card)
        status?.let { line ->
            views.text(content, line, small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        views.button(content, getString(R.string.ankiquest_share), primary = true) { share(current, card) }
        if (card.mine) owner(card)
        views.text(
            content,
            getString(if (card.label != null) R.string.aq_conquest_label_shown else R.string.aq_conquest_numbers_only),
            small = true,
        )
        views.button(
            content,
            if (card.user ==
                current.user
            ) {
                getString(R.string.aq_board_my_profile)
            } else {
                getString(R.string.aq_conquest_profile, card.display)
            },
        ) {
            startActivity(AnkiquestProfileActivity.intent(this, card.user))
        }
    }

    /** Lapses, answers and days, side by side. */
    private fun stats(card: AnkiquestCommunity.Conquest) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        content.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = views.dp(4)
            },
        )
        val items =
            listOf(
                card.lapses.toLong() to R.plurals.aq_conquest_lapses,
                card.answers.toLong() to R.plurals.aq_conquest_answers,
                card.days to R.plurals.aq_conquest_days,
            )
        items.forEachIndexed { index, (value, label) ->
            val cell =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(views.dp(4), views.dp(12), views.dp(4), views.dp(12))
                    background = views.shape(R.color.aq_home_surface)
                }
            row.addView(
                cell,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = views.dp(8)
                },
            )
            val count = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            cell.addView(
                TextView(this).apply {
                    text = resources.getQuantityString(label, count, views.number(value))
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.aq_home_primary))
                },
            )
        }
    }

    /** What only the card's owner can do: tell friends, take that back for a while, and say what the card was. */
    private fun owner(card: AnkiquestCommunity.Conquest) {
        when {
            card.told == true -> {
                views.button(content, getString(R.string.aq_conquest_told)) {}.isEnabled = false
                if (card.undoable(TimeManager.time.intTimeMS())) {
                    views.button(content, getString(CommonString.undo)) { untell(card) }.isEnabled = !busy
                }
            }
            (card.friends ?: 0) > 0 ->
                views
                    .button(content, getString(R.string.aq_conquest_tell)) {
                        act(R.string.aq_conquest_telling) { current ->
                            val told = AnkiquestCommunity.tell(current, card.at)
                            resources.getQuantityString(R.plurals.aq_conquest_told_count, told, told)
                        }
                    }.isEnabled = !busy
        }
        val better = labels?.better(card.label)
        if (better != null) {
            // A label saved from the card's first field, which can be an id; its front reads better.
            views
                .button(content, getString(R.string.aq_conquest_label_refresh, better)) {
                    act(R.string.ankiquest_goal_saving) { current ->
                        AnkiquestSharing.setConquestLabel(current, card.at, better)
                        null
                    }
                }.isEnabled = !busy
        }
        if (card.label != null) {
            views
                .button(content, getString(R.string.aq_conquest_label_remove)) {
                    act(R.string.ankiquest_goal_saving) { current ->
                        AnkiquestSharing.setConquestLabel(current, card.at, null)
                        null
                    }
                }.isEnabled = !busy
        } else {
            views.button(content, getString(R.string.aq_conquest_label_add)) { showLabel(card) }.isEnabled = !busy
        }
    }

    /** Takes back telling friends, from those who have not read it yet; too late is said as such. */
    private fun untell(card: AnkiquestCommunity.Conquest) {
        act(R.string.aq_conquest_untelling) { current ->
            try {
                AnkiquestCommunity.untell(current, card.at)
                getString(R.string.aq_conquest_untold)
            } catch (e: AnkiquestHttpException) {
                if (e.code != 409) throw e
                getString(R.string.aq_conquest_untell_late)
            }
        }
    }

    /** Shows the card's front from this phone's collection; asks only when the card is not here. */
    private fun showLabel(card: AnkiquestCommunity.Conquest) {
        val current = account ?: return
        if (busy || !owns(current, card)) return
        labelOwner?.close()
        val owner = AnkiquestDialogOwner(this, current).also { labelOwner = it }
        lifecycleScope.launch {
            val text = AnkiquestShare.conqueredCardText(card.at)
            if (!owner.isCurrent()) {
                owner.close()
                return@launch
            }
            if (text == null) {
                askForLabel(card, owner)
                return@launch
            }
            owner.close()
            act(R.string.ankiquest_goal_saving) { current ->
                AnkiquestSharing.setConquestLabel(current, card.at, text)
                null
            }
        }
    }

    private fun askForLabel(
        card: AnkiquestCommunity.Conquest,
        owner: AnkiquestDialogOwner,
    ) {
        if (!owner.isCurrent()) return
        val field =
            EditText(this).apply {
                hint = getString(R.string.ankiquest_share_card_hint)
                filters = arrayOf(InputFilter.LengthFilter(AnkiquestShare.MAX_LABEL))
                isSingleLine = true
            }
        val frame =
            FrameLayout(this).apply {
                setPadding(views.dp(20), views.dp(8), views.dp(20), 0)
                addView(field)
            }
        owner.show(
            AlertDialog
                .Builder(this)
                .setTitle(R.string.aq_conquest_label_add)
                .setMessage(R.string.aq_conquest_label_explanation)
                .setView(frame)
                .setPositiveButton(R.string.aq_conquest_label_save) { dialog, _ ->
                    if (!owner.take(dialog)) return@setPositiveButton
                    owner.close()
                    val label = AnkiquestShare.chosenLabel(include = true, text = field.text.toString())
                    if (label != null) {
                        act(R.string.ankiquest_goal_saving) { current ->
                            AnkiquestSharing.setConquestLabel(current, card.at, label)
                            null
                        }
                    }
                }.setNegativeButton(android.R.string.cancel) { _, _ -> owner.close() }
                .create(),
        )
    }

    /** Share the public card locally; only its owner can add a private deck name. */
    private fun share(
        current: HomeAccount,
        card: AnkiquestCommunity.Conquest,
    ) {
        if (busy) return
        val mine = card.user == current.user
        val words =
            when {
                card.label != null && mine ->
                    resources.getQuantityString(
                        R.plurals.aq_conquest_share_label_mine,
                        card.lapses,
                        card.label,
                        card.lapses,
                    )
                card.label != null ->
                    resources.getQuantityString(R.plurals.aq_conquest_share_label, card.lapses, card.display, card.label, card.lapses)
                mine -> resources.getQuantityString(R.plurals.aq_conquest_share_mine, card.lapses, card.lapses)
                else -> resources.getQuantityString(R.plurals.aq_conquest_share, card.lapses, card.display, card.lapses)
            }
        val from = deck?.takeIf { owns(current, card) }?.let { " " + getString(R.string.aq_conquest_share_deck, it) }.orEmpty()
        val text = "$words$from ${AnkiquestCommunity.conquestLink(current.server, card.user, card.at)}"
        val picture =
            AnkiquestShare.Card(
                getString(if (card.leech) R.string.aq_profile_leech else R.string.aq_profile_conquest),
                words,
                if (card.leech) R.drawable.aki_winner else R.drawable.aki_deck_conquered,
                listOfNotNull(
                    card.label,
                    from.trim().takeIf(String::isNotEmpty),
                    resources.getQuantityString(R.plurals.aq_conquest_answers, card.answers, views.number(card.answers.toLong())),
                    resources.getQuantityString(
                        R.plurals.aq_conquest_days,
                        card.days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        views.number(card.days),
                    ),
                ),
            )
        busy = true
        render()
        lifecycleScope.launch {
            try {
                val image = if (owns(current, card)) winImage(current, card) else null
                val chooser =
                    withContext(Dispatchers.IO) { AnkiquestShare.cardChooser(applicationContext, text, image, fallback = picture) }
                AnkiquestApi.requireCurrent(current)
                if (account?.scope == current.scope && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    openChooser(chooser)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HomeAccountChanged) {
                Timber.d(e, "AnkiQuest conquest share account changed")
            } finally {
                if (account?.scope == current.scope) {
                    busy = false
                    if (AnkiquestHomeData.account()?.scope == current.scope && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        render()
                    }
                }
            }
        }
    }

    /** Optional canonical image metadata; a new conquest uses its local picture immediately. */
    private suspend fun winImage(
        current: HomeAccount,
        card: AnkiquestCommunity.Conquest,
    ): String? =
        try {
            card.win?.let { Ankiquest.shareWinLink(it, current.scope, AnkiquestApi.Timeout.PICTURE).image }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "AnkiQuest conquest: drawing the share picture locally")
            null
        }

    private fun openChooser(send: Intent) {
        val chooser = if (send.action == Intent.ACTION_CHOOSER) send else Intent.createChooser(send, getString(R.string.ankiquest_share))
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "AnkiQuest conquest: nothing to share with")
        }
    }

    /** Whether [card] is the signed-in player's own, so its deck and win may be used. */
    private fun owns(
        current: HomeAccount,
        card: AnkiquestCommunity.Conquest,
    ) = card.mine && card.user == current.user

    /** [boardAttempt], with a card that cannot be found said as such. */
    private suspend fun attempt(work: suspend () -> Unit): Int? =
        try {
            boardAttempt(work)
        } catch (_: AnkiquestCommunity.Missing) {
            R.string.aq_conquest_missing
        }

    companion object {
        const val EXTRA_USER = "ankiquest.conquest_user"
        const val EXTRA_AT = "ankiquest.conquest_at"
        private const val DATE = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR

        /** The card [user] conquered with the answer at [at] (Unix ms). */
        fun intent(
            context: Context,
            user: String,
            at: Long,
        ): Intent =
            Intent(context, AnkiquestConquestActivity::class.java)
                .putExtra(EXTRA_USER, user)
                .putExtra(EXTRA_AT, at)
    }
}
