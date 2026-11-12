// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.pages.AnkiPackageImporterFragment
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The daily study surface is native and local, like every other AnkiQuest screen: activity
 * ([AnkiquestFeedActivity]), friends, progress, study history ([AnkiquestHistoryActivity]) and
 * records ([AnkiquestRecordsActivity]).
 */
class AnkiquestHomeActivity : AnkiActivity(R.layout.activity_ankiquest_home) {
    private enum class GoalSetup { READY, SAVING, FAILED }

    private var goalSetup = GoalSetup.READY
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var local: HomeLocal? = null
    private var localFailed = false
    private var profile: JSONObject? = null

    @StringRes private var profileFailure: Int? = null
    private var loading = false
    private var networkJob: Job? = null
    private var avatarJob: Job? = null
    private var copyJob: Job? = null
    private var copyOwner: AnkiquestDialogOwner? = null
    private var copyInbox: CopyInbox = CopyInbox.Loading
    private var copyBusy = false
    private var copyStatus: String? = null
    private var importedCopyFile: File? = null
    private val copyImporter =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            importedCopyFile?.delete()
            importedCopyFile = null
            refresh()
        }
    private var generation = 0
    private var focus: String? = null
    private var questsHeading: View? = null

    private sealed interface CopyInbox {
        data object Loading : CopyInbox

        data object Unavailable : CopyInbox

        data class Ready(
            val data: JSONObject,
        ) : CopyInbox
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.aq_home_content)
        scroll = findViewById(R.id.aq_home_scroll)
        findViewById<MaterialButton>(R.id.aq_home_activity).apply {
            setTextColor(getColor(R.color.aq_home_text))
            setOnClickListener { startActivity(AnkiquestFeedActivity.intent(this@AnkiquestHomeActivity)) }
        }
        findViewById<View>(R.id.aq_home_account).apply {
            setOnClickListener { startActivity(AnkiquestProfileActivity.intent(this@AnkiquestHomeActivity)) }
            ViewCompat.setAccessibilityDelegate(
                this,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                },
            )
        }
        AnkiquestTabBar.attach(this, findViewById(R.id.aq_home_root), R.id.ankiquest_nav_today)
        account = AnkiquestHomeData.account()
        if (savedInstanceState == null) focus = intent.getStringExtra(EXTRA_FOCUS)
        render()
    }

    private var localRefreshJob: Job? = null

    override fun onPause() {
        localRefreshJob?.cancel()
        super.onPause()
    }

    private fun scheduleLocalRefresh(nextAt: Long?) {
        localRefreshJob?.cancel()
        if (nextAt == null) return
        localRefreshJob =
            lifecycleScope.launch {
                delay((nextAt - TimeManager.time.intTimeMS()).coerceAtLeast(1_000L))
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refresh()
            }
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        AnkiquestMoments.clearQuiet(AnkiDroidApp.sharedPrefs())
        refresh()
    }

    private fun refresh() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            // A new owner starts with empty caches and a fresh loading state.
            copyOwner?.close()
            copyOwner = null
            goalSetup = GoalSetup.READY
            account = current
            local = null
            localFailed = false
            scheduleLocalRefresh(null)
            profile = null
            profileFailure = null
            copyInbox = CopyInbox.Loading
            copyStatus = null
            copyBusy = false
        }
        val turn = ++generation
        lifecycleScope.launch {
            try {
                val result = AnkiquestHomeData.local()
                if (isCurrent(turn, current?.scope)) {
                    applyLocal(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest local study could not be loaded")
                if (isCurrent(turn, current?.scope)) {
                    applyLocal(null)
                }
            }
        }
        networkJob?.cancel()
        avatarJob?.cancel()
        copyJob?.cancel()
        if (current == null) {
            loading = false
            render()
            return
        }
        loading = true
        render()
        refreshAvatar(current, turn)
        refreshCopies(current, turn)
        networkJob =
            lifecycleScope.launch {
                val failure =
                    try {
                        val result = AnkiquestHomeData.profile(current)
                        if (AnkiquestHomeData.account()?.scope == current.scope) {
                            restoreOldGoal(current, result)
                        }
                        if (turn == generation && AnkiquestHomeData.account()?.scope == current.scope) {
                            profile = result
                            AnkiquestDailyGoal.remember(result)
                        }
                        if (current.token.isNotEmpty()) {
                            try {
                                val companion = AnkiquestHomeData.companion(current)
                                if (turn == generation && AnkiquestHomeData.account()?.scope == current.scope &&
                                    AnkiquestCompanion.selected() != companion && AnkiquestCompanion.remember(current, companion)
                                ) {
                                    AnkiquestWidget.requestUpdate(this@AnkiquestHomeActivity)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "AnkiQuest companion could not be refreshed")
                            }
                        }
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AnkiquestHttpException) {
                        if (e.code == 401 || e.code == 403) R.string.aq_home_auth else R.string.aq_home_server_error
                    } catch (_: IOException) {
                        R.string.aq_home_offline
                    } catch (_: JSONException) {
                        R.string.aq_home_server_error
                    }
                if (isCurrent(turn, current.scope)) {
                    profileFailure = failure
                    if (failure == R.string.aq_home_auth) profile = null
                    loading = false
                    render()
                }
            }
    }

    private fun isCurrent(
        turn: Int,
        scope: String?,
    ): Boolean = turn == generation && ownsScope(scope)

    private fun ownsScope(scope: String?): Boolean = account?.scope == scope && AnkiquestHomeData.account()?.scope == scope

    private fun applyLocal(value: HomeLocal?) {
        local = value
        localFailed = value == null
        scheduleLocalRefresh(value?.nextUpdateAt)
        render()
    }

    private fun refreshCopies(
        current: HomeAccount,
        turn: Int = generation,
    ) {
        copyJob?.cancel()
        copyJob =
            lifecycleScope.launch {
                val result =
                    try {
                        CopyInbox.Ready(AnkiquestDeckCopies.inbox(current))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "AnkiQuest deck copies could not be loaded")
                        CopyInbox.Unavailable
                    }
                if (isCurrent(turn, current.scope)) {
                    copyInbox = result
                    render()
                }
            }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        val face = findViewById<ImageView>(R.id.aq_home_companion)
        AnkiquestCompanion.show(face, R.drawable.aki_face, face.layoutParams.width)
        renderAvatar()
        findViewById<View>(R.id.aq_home_loading).isVisible = loading
        renderToday()
        val target = questsHeading?.takeIf { focus == FOCUS_QUESTS }
        if (target != null || profile != null) focus = null
        if (target != null) {
            target.doOnLayout { scroll.smoothScrollTo(0, it.top) }
        } else {
            scroll.post { scroll.scrollTo(0, position) }
        }
    }

    /** The same account-scoped photo or initial used on profiles, with a 48 dp touch target. */
    private fun renderAvatar() {
        val avatar = findViewById<LinearLayout>(R.id.aq_home_account)
        avatar.removeAllViews()
        val user = account?.user.orEmpty()
        val display = profile?.optString("display").orEmpty().ifBlank { user.ifBlank { getString(R.string.ankiquest_me) } }
        AnkiquestBoardViews(this).avatar(avatar, user, display, 32)
    }

    /** Optional photos must neither delay Today nor apply after its account changes. */
    private fun refreshAvatar(
        current: HomeAccount,
        turn: Int,
    ) {
        val captured = AnkiquestAvatars.account()?.takeIf { it.scope == current.scope } ?: return
        avatarJob =
            lifecycleScope.launch {
                boardAttempt { AnkiquestAvatars.refresh(listOf(current.user), captured) }
                if (isCurrent(turn, current.scope)) {
                    renderAvatar()
                }
            }
    }

    private fun renderToday() {
        questsHeading = null
        title(R.string.ankiquest_nav_today, R.string.aq_home_today_subtitle)
        content.addView(AnkiquestAki.encouragement(this, AnkiquestAki.mood(local, profile)))
        profile?.let {
            text(content, getString(R.string.aq_home_streak_level, it.optInt("streak"), it.optInt("level")), small = true)
            AnkiquestBoards.closingLine(this, AnkiquestBoards.parseClosingWeek(it), TimeManager.time.intTimeMS())?.let { line ->
                text(card(tinted = true), line)
            }
        }
        val study = card(tinted = true)
        text(study, getString(R.string.aq_home_next_step), small = true)
        val focus = local?.focus
        when {
            focus != null -> {
                text(study, focus.name, heading = true)
                text(study, getString(R.string.aq_home_counts, focus.review, focus.learning, focus.new), small = true)
                val daily = focus.daily
                val label = text(study, daily.label(this), small = true)
                label.setTextColor(getColor(daily.color))
                if (daily.icon != 0) {
                    label.setCompoundDrawablesRelativeWithIntrinsicBounds(daily.icon, 0, 0, 0)
                    TextViewCompat.setCompoundDrawableTintList(label, ColorStateList.valueOf(getColor(daily.color)))
                    label.compoundDrawablePadding = dp(6)
                }
                if (daily.status == DailyDeckStatus.DONE) study.background = shape(R.color.aq_deck_done_background)
                if (daily.status == DailyDeckStatus.REVIEW_NOW) {
                    button(study, getString(R.string.aq_home_study_due, daily.readyCount.toInt()), primary = true) { openDecks(focus.id) }
                }
                button(study, R.string.aq_home_change_deck) { chooseDeck() }
            }
            localFailed -> {
                text(study, getString(R.string.aq_home_local_unavailable))
                button(study, R.string.aq_home_open_decks) { openDecks() }
            }
            local == null -> text(study, getString(R.string.aq_home_loading_local))
            else -> {
                text(study, getString(R.string.aq_home_no_decks))
                button(study, R.string.aq_home_open_decks) { openDecks() }
            }
        }
        val signed = account?.takeIf { it.token.isNotEmpty() }
        val suggested =
            AnkiquestDailyGoal.ServerGoal
                .parse(profile?.optJSONObject("goal"))
                ?.takeIf { it.chosen == false }
                ?.choice
        if (signed != null && suggested != null) {
            renderGoalSetup(signed, suggested)
        }
        local?.goal?.let { renderGoal(AnkiquestDailyGoal.withProfile(it, profile), suggested) }
        if (account == null) connectionCard()
        if (account != null) renderDeckCopies()
        AnkiquestStudySession.latest()?.let { summary ->
            val panel = card()
            panel.addView(AnkiquestAki.image(this, if (summary.remaining == 0) R.drawable.aki_celebrate else R.drawable.aki_streak, 56))
            text(panel, getString(R.string.aq_home_last_session), heading = true)
            text(panel, getString(R.string.aq_home_session_summary, summary.reviews, summary.duration(this), summary.remaining))
        }
        profile?.optJSONArray("quests")?.objects()?.let { quests ->
            if (quests.isNotEmpty()) {
                if (quests.all { it.optBoolean("done") }) content.addView(AnkiquestAki.image(this, R.drawable.aki_winner, 56))
                questsHeading = text(content, getString(R.string.aq_home_quests), heading = true)
                quests.forEach { quest ->
                    val panel = card()
                    text(panel, quest.optString("title"), heading = true)
                    meter(panel, quest.optLong("progress"), quest.optLong("target"))
                    if (quest.optBoolean("done")) text(panel, getString(R.string.aq_home_quest_done, quest.optLong("reward")), small = true)
                }
            }
        }
        if (account != null) {
            button(content, R.string.aq_history_title) { startActivity(AnkiquestHistoryActivity.intent(this)) }
            button(content, R.string.aq_records_title) { startActivity(AnkiquestRecordsActivity.intent(this)) }
        }
        profileFailure?.let { failure ->
            text(content, getString(failure), small = true)
            if (failure == R.string.aq_home_auth) button(content, R.string.aq_home_settings) { settings() }
        }
        if (account != null) button(content, R.string.aq_home_retry, enabled = !loading) { refresh() }
    }

    private fun renderGoal(
        status: AnkiquestDailyGoal.Status,
        suggested: AnkiquestDailyGoal.Choice?,
    ) {
        val panel = card()
        text(panel, getString(R.string.aq_goal_today), heading = true)
        val secondsPerCard = local?.secondsPerCard ?: AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD
        AnkiquestGoalBar.display(this, status, secondsPerCard)?.let { display ->
            panel.addView(
                AnkiquestGoalProgress(this).apply { show(display, SLOT_TODAY) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                },
            )
        }
        panel.addView(
            AnkiquestStreakChip(this).apply { show(status) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )
        // The goal is set where it shows; there is no goal setting elsewhere.
        if (suggested == null) {
            account?.takeIf { it.token.isNotEmpty() }?.let { signed ->
                button(panel, R.string.aq_goal_change) { AnkiquestGoalDialog.edit(this, signed) { refresh() } }
            }
        }
    }

    private suspend fun restoreOldGoal(
        owner: HomeAccount,
        result: JSONObject,
    ) {
        if (owner.token.isEmpty()) return
        val prefs = AnkiDroidApp.sharedPrefs()
        if (AnkiquestDailyGoal.localChoice(prefs) == null || AnkiquestDailyGoal.cached(prefs, owner.scope) != null) return

        // Fetch once before caching the first profile, so a chosen device goal can migrate.
        try {
            val restored = AnkiquestDailyGoal.fetch(owner, prefs)
            if (restored.chosen == true) {
                result.put("goal", restored.json().put("chosen", true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest device goal could not be restored")
        }
    }

    private fun renderGoalSetup(
        owner: HomeAccount,
        choice: AnkiquestDailyGoal.Choice,
    ) {
        val panel = card()
        text(panel, getString(R.string.ankiquest_goal_setup_title), heading = true)
        text(panel, AnkiquestDailyGoal.describe(this, choice))
        if (goalSetup == GoalSetup.FAILED) {
            text(panel, getString(R.string.aq_goal_setup_failed), small = true)
        }
        if (goalSetup == GoalSetup.SAVING) {
            text(panel, getString(R.string.ankiquest_goal_saving), small = true)
            return
        }
        button(panel, CommonString.save, primary = true) { saveSuggestedGoal(owner, choice) }
        button(panel, R.string.ankiquest_goal_custom) {
            AnkiquestGoalDialog.show(this, owner, choice) { applyGoal(owner, it) }
        }
    }

    private fun saveSuggestedGoal(
        owner: HomeAccount,
        choice: AnkiquestDailyGoal.Choice,
    ) {
        if (goalSetup == GoalSetup.SAVING || AnkiquestHomeData.account()?.scope != owner.scope) return
        goalSetup = GoalSetup.SAVING
        render()
        lifecycleScope.launch {
            try {
                applyGoal(owner, AnkiquestDailyGoal.save(owner, choice, restart = choice.mode == AnkiquestDailyGoal.Mode.BACKLOG))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest suggested goal could not be saved")
                if (AnkiquestHomeData.account()?.scope == owner.scope) {
                    goalSetup = GoalSetup.FAILED
                    render()
                }
            }
        }
    }

    private fun applyGoal(
        owner: HomeAccount,
        goal: AnkiquestDailyGoal.ServerGoal,
    ) {
        if (AnkiquestHomeData.account()?.scope != owner.scope) return
        goalSetup = GoalSetup.READY
        profile?.put("goal", goal.json().put("chosen", true))
        refresh()
    }

    private fun renderDeckCopies() {
        val current = account ?: return
        val panel = card()
        // Both companions: sharing is something friends do together.
        if (AnkiquestCompanion.visible()) panel.addView(AnkiquestAki.image(this, R.drawable.duo_friends, 72))
        text(panel, getString(R.string.aq_copies_title), heading = true)
        text(panel, getString(R.string.aq_copies_description), small = true)
        copyStatus?.let { text(panel, it, small = true) }
        val inbox = copyInbox
        if (inbox !is CopyInbox.Ready) {
            val label = if (inbox == CopyInbox.Unavailable) R.string.aq_copies_unavailable else R.string.aq_home_loading_local
            text(panel, getString(label), small = true)
            return
        }
        val data = inbox.data
        val friends = data.optJSONArray("friends")?.objects().orEmpty()
        val decks = local?.decks.orEmpty()
        button(panel, R.string.aq_copies_share, enabled = !copyBusy && friends.isNotEmpty() && decks.isNotEmpty()) {
            chooseDeckForCopy(current, decks, friends)
        }
        if (friends.isEmpty()) text(panel, getString(R.string.aq_copies_no_friends), small = true)
        val offers = data.optJSONArray("offers")?.objects().orEmpty()
        if (offers.isEmpty()) {
            text(panel, getString(R.string.aq_copies_none), small = true)
        } else {
            text(panel, getString(R.string.aq_copies_received), heading = true)
            for (offer in offers) {
                val sender = offer.optString("sender")
                val display = friends.firstOrNull { it.optString("user") == sender }?.optString("display") ?: sender
                text(panel, getString(R.string.aq_copies_offer, display, offer.optString("deck")))
                val id = offer.optLong("id")
                button(panel, R.string.aq_copies_import, enabled = !copyBusy) { importCopy(id) }
                button(panel, R.string.aq_copies_dismiss, enabled = !copyBusy) { dismissCopy(id) }
            }
        }
    }

    private fun chooseDeckForCopy(
        current: HomeAccount,
        decks: List<HomeDeck>,
        friends: List<JSONObject>,
    ) {
        if (copyBusy || !ownsScope(current.scope)) {
            return
        }
        copyOwner?.close()
        val owner = AnkiquestDialogOwner(this, current)
        copyOwner = owner
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.aq_copies_choose_deck)
                .setItems(decks.map { it.name }.toTypedArray()) { dialog, position ->
                    if (owner.take(dialog)) {
                        chooseFriendsForCopy(current, owner, decks[position], friends)
                    }
                }.setNegativeButton(android.R.string.cancel, null)
                .create()
        owner.show(dialog)
    }

    private fun chooseFriendsForCopy(
        current: HomeAccount,
        owner: AnkiquestDialogOwner,
        deck: HomeDeck,
        friends: List<JSONObject>,
    ) {
        val picked = BooleanArray(friends.size)
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.aq_copies_choose_friends, deck.name))
                .setMultiChoiceItems(friends.map { it.optString("display") }.toTypedArray(), picked) { _, which, selected ->
                    picked[which] = selected
                }.setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.aq_copies_share) { dialog, _ ->
                    if (!owner.take(dialog)) {
                        return@setPositiveButton
                    }
                    val selected = friends.indices.filter { picked[it] }.map { friends[it].getString("user") }
                    if (selected.isEmpty()) {
                        copyStatus = getString(R.string.aq_copies_choose_one)
                        render()
                        owner.close()
                    } else {
                        shareCopy(current, owner, deck, selected)
                    }
                }.create()
        owner.show(dialog)
    }

    private fun shareCopy(
        current: HomeAccount,
        owner: AnkiquestDialogOwner,
        deck: HomeDeck,
        recipients: List<String>,
    ) {
        if (!owner.isCurrent()) {
            owner.close()
            return
        }
        copyBusy = true
        copyStatus = getString(R.string.aq_copies_sending)
        render()
        lifecycleScope.launch {
            if (!owner.isCurrent()) {
                finishCopy(current, owner, null)
                return@launch
            }
            val status =
                try {
                    AnkiquestDeckCopies.share(current, deck.id, deck.name, recipients, cacheDir)
                    getString(R.string.aq_copies_sent)
                } catch (error: CancellationException) {
                    finishCopy(current, owner, null)
                    throw error
                } catch (_: HomeAccountChanged) {
                    finishCopy(current, owner, null)
                    if (copyOwner === owner && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        refresh()
                    }
                    return@launch
                } catch (error: AnkiquestDeckCopies.TooLarge) {
                    getString(R.string.aq_copies_too_large)
                } catch (error: Exception) {
                    Timber.w(error, "AnkiQuest deck copy could not be sent")
                    getString(R.string.aq_copies_failed)
                }
            finishCopy(current, owner, status)
        }
    }

    private fun finishCopy(
        current: HomeAccount,
        owner: AnkiquestDialogOwner,
        status: String?,
    ) {
        if (copyOwner === owner && ownsScope(current.scope)) {
            copyStatus = status
            copyBusy = false
            if (owner.isCurrent()) {
                render()
            }
            copyOwner = null
        }
        owner.close()
    }

    private fun importCopy(id: Long) {
        val current = account ?: return
        copyBusy = true
        copyStatus = getString(R.string.aq_copies_downloading)
        render()
        lifecycleScope.launch {
            try {
                val file = AnkiquestDeckCopies.download(current, id, cacheDir)
                if (account?.scope == current.scope) {
                    copyStatus = null
                    importedCopyFile = file
                    copyImporter.launch(AnkiPackageImporterFragment.getIntent(this@AnkiquestHomeActivity, file.absolutePath))
                } else {
                    file.delete()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "AnkiQuest deck copy could not be imported")
                if (account?.scope == current.scope) copyStatus = getString(R.string.aq_copies_failed)
            }
            if (account?.scope == current.scope) {
                copyBusy = false
                render()
            }
        }
    }

    private fun dismissCopy(id: Long) {
        val current = account ?: return
        copyBusy = true
        render()
        lifecycleScope.launch {
            try {
                AnkiquestDeckCopies.dismiss(current, id)
                if (account?.scope == current.scope) {
                    copyStatus = null
                    copyBusy = false
                    refreshCopies(current)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "AnkiQuest deck copy could not be dismissed")
                if (account?.scope == current.scope) {
                    copyStatus = getString(R.string.aq_copies_failed)
                    copyBusy = false
                    render()
                }
            }
        }
    }

    private fun chooseDeck() {
        val rows = local?.decks.orEmpty()
        val owner = account?.scope
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aq_home_change_deck)
            .setItems(rows.map { "${it.name} · ${it.daily.label(this)}" }.toTypedArray()) { _, index ->
                if (ownsScope(owner)) {
                    selectDeck(rows[index].id, owner)
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectDeck(
        deck: Long,
        owner: String?,
    ) {
        lifecycleScope.launch {
            // Commit the choice first, then replace any refresh that saw the previous deck.
            try {
                CollectionManager.withCol {
                    if (AnkiquestHomeData.account()?.scope == owner && decks.get(deck) != null) {
                        decks.select(deck)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest focus deck could not be selected")
            }
            if (ownsScope(owner)) {
                refresh()
            }
        }
    }

    private fun openDecks(deck: Long? = null) {
        startActivity(AnkiquestNavigation.decks(this, deck))
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun connectionCard() {
        val panel = card()
        text(panel, getString(R.string.aq_home_connected))
        button(panel, R.string.aq_home_connect) { settings() }
    }

    private fun title(
        @StringRes title: Int,
        @StringRes subtitle: Int,
    ) {
        text(content, getString(title), heading = true, large = true)
        text(content, getString(subtitle), small = true)
    }

    private fun card(tinted: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(if (tinted) R.color.aq_home_tint else R.color.aq_home_surface)
            content.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(4)
                },
            )
        }

    private fun text(
        parent: LinearLayout,
        value: String,
        heading: Boolean = false,
        small: Boolean = false,
        large: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize =
                when {
                    large -> 26f
                    heading -> 18f
                    small -> 14f
                    else -> 16f
                }
            setTextColor(getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
            if (heading) {
                typeface = Typeface.DEFAULT_BOLD
                ViewCompat.setAccessibilityHeading(this, true)
            }
            setPadding(0, dp(5), 0, dp(5))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun button(
        parent: LinearLayout,
        @StringRes label: Int,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): MaterialButton = button(parent, getString(label), primary, enabled, click)

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
                    topMargin = dp(6)
                },
            )
        }

    private fun meter(
        parent: LinearLayout,
        value: Long,
        max: Long,
    ) {
        text(parent, getString(R.string.aq_home_progress_fraction, value, max), small = true)
        parent.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                this.max = 1000
                progress = if (max <= 0) 0 else (value.toDouble() / max * 1000).coerceIn(0.0, 1000.0).toInt()
                progressTintList = ColorStateList.valueOf(getColor(R.color.aq_home_primary))
                contentDescription = getString(R.string.aq_home_progress_fraction, value, max)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(6) },
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
        const val EXTRA_SKIP_HOME = "ankiquest.skip_home"
        const val EXTRA_STUDY_DECK = "ankiquest.study_deck"

        /** A section to scroll to once it is shown, such as [FOCUS_QUESTS]. */
        const val EXTRA_FOCUS = "ankiquest.focus"
        const val FOCUS_QUESTS = "quests"

        /** The goal bar memory of the Today screen, see [AnkiquestGoalBar.show]. */
        const val SLOT_TODAY = "today"

        fun intent(context: Context): Intent = Intent(context, AnkiquestHomeActivity::class.java)
    }
}
