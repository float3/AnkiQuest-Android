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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.pages.AnkiPackageImporterFragment
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The daily study surface is native and local. Friends, activity and progress are the
 * website's community pages, opened in [AnkiquestActivity].
 */
class AnkiquestHomeActivity : AnkiActivity(R.layout.activity_ankiquest_home) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var local: HomeLocal? = null
    private var localFailed = false
    private var profile: JSONObject? = null

    @StringRes private var profileFailure: Int? = null
    private var loading = false
    private var networkJob: Job? = null
    private var copyJob: Job? = null
    private var copyInbox: JSONObject? = null
    private var copyFailed = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.aq_home_content)
        scroll = findViewById(R.id.aq_home_scroll)
        findViewById<MaterialButton>(R.id.aq_home_activity).apply {
            setTextColor(getColor(R.color.aq_home_text))
            setOnClickListener { web(AnkiquestNavigation.ACTIVITY_PATH) }
        }
        findViewById<BottomNavigationView>(R.id.aq_home_navigation).apply {
            selectedItemId = R.id.ankiquest_nav_today
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.ankiquest_nav_today -> {}
                    R.id.ankiquest_nav_decks -> openDecks()
                    else -> AnkiquestNavigation.destination(this@AnkiquestHomeActivity, item.itemId)?.let(::startActivity)
                }
                item.itemId == R.id.ankiquest_nav_today
            }
        }
        findViewById<View>(R.id.aq_home_account).setOnClickListener { settings() }
        val root = findViewById<View>(R.id.aq_home_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        account = AnkiquestHomeData.account()
        render()
    }

    private var localRefreshJob: kotlinx.coroutines.Job? = null

    override fun onPause() {
        localRefreshJob?.cancel()
        super.onPause()
    }

    private fun scheduleLocalRefresh(nextAt: Long?) {
        localRefreshJob?.cancel()
        if (nextAt == null) return
        localRefreshJob =
            lifecycleScope.launch {
                kotlinx.coroutines.delay(
                    (
                        nextAt -
                            com.ichi2.anki.common.time.TimeManager.time
                                .intTimeMS()
                    ).coerceAtLeast(1_000L),
                )
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) refresh()
            }
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        refresh()
    }

    private fun refresh() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            profile = null
            profileFailure = null
            copyInbox = null
            copyStatus = null
            copyBusy = false
        }
        val turn = ++generation
        lifecycleScope.launch {
            try {
                val result = AnkiquestHomeData.local()
                if (turn == generation) {
                    local = result
                    scheduleLocalRefresh(result.nextUpdateAt)
                    localFailed = false
                    render()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest local study could not be loaded")
                if (turn == generation) {
                    localFailed = true
                    render()
                }
            }
        }
        networkJob?.cancel()
        copyJob?.cancel()
        if (current == null) {
            loading = false
            render()
            return
        }
        loading = true
        render()
        refreshCopies(current, turn)
        networkJob =
            lifecycleScope.launch {
                val failure =
                    try {
                        val result = AnkiquestHomeData.profile(current)
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
                    } catch (e: Ankiquest.HttpStatusException) {
                        if (e.code == 401 || e.code == 403) R.string.aq_home_auth else R.string.aq_home_server_error
                    } catch (_: IOException) {
                        R.string.aq_home_offline
                    } catch (_: org.json.JSONException) {
                        R.string.aq_home_server_error
                    }
                if (turn == generation) {
                    profileFailure = failure
                    if (failure == R.string.aq_home_auth) profile = null
                    loading = false
                    render()
                }
            }
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
                        AnkiquestDeckCopies.inbox(current)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "AnkiQuest deck copies could not be loaded")
                        null
                    }
                if (turn == generation && account?.scope == current.scope) {
                    copyInbox = result
                    copyFailed = result == null
                    render()
                }
            }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        AnkiquestCompanion.show(findViewById<ImageView>(R.id.aq_home_companion), R.drawable.aki_face)
        findViewById<View>(R.id.aq_home_loading).isVisible = loading
        renderToday()
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderToday() {
        title(R.string.ankiquest_nav_today, R.string.aq_home_today_subtitle)
        content.addView(AnkiquestAki.encouragement(this, AnkiquestAki.mood(local, profile)))
        profile?.let {
            text(content, getString(R.string.aq_home_streak_level, it.optInt("streak"), it.optInt("level")), small = true)
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
        local?.goal?.let { renderGoal(it) }
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
                text(content, getString(R.string.aq_home_quests), heading = true)
                quests.forEach { quest ->
                    val panel = card()
                    text(panel, quest.optString("title"), heading = true)
                    meter(panel, quest.optLong("progress"), quest.optLong("target"))
                    if (quest.optBoolean("done")) text(panel, getString(R.string.aq_home_quest_done, quest.optLong("reward")), small = true)
                }
            }
        }
        profileFailure?.let { failure ->
            text(content, getString(failure), small = true)
            if (failure == R.string.aq_home_auth) button(content, R.string.aq_home_settings) { settings() }
        }
        if (account != null) button(content, R.string.aq_home_retry, enabled = !loading) { refresh() }
    }

    private fun renderGoal(status: AnkiquestDailyGoal.Status) {
        val panel = card()
        text(panel, getString(R.string.aq_goal_today), heading = true)
        when {
            status.minimumDone && status.streakMinutes > 0 ->
                text(panel, getString(R.string.aq_goal_minimum_minutes_done, status.streakMinutes), small = true)
            status.minimumDone -> text(panel, getString(R.string.aq_goal_minimum_any_done), small = true)
            status.streakMinutes > 0 -> {
                text(panel, getString(R.string.aq_goal_minimum_minutes, status.streakMinutes), small = true)
                meter(panel, status.minutes.toLong(), status.streakMinutes.toLong())
            }
            else -> text(panel, getString(R.string.aq_goal_minimum_any), small = true)
        }
        text(
            panel,
            if (status.goalDone) {
                getString(R.string.aq_goal_done_title)
            } else {
                getString(R.string.aq_goal_daily, AnkiquestGoalCelebration.progress(this, status))
            },
            small = true,
        )
        meter(panel, status.progress.toLong(), status.goal.target.toLong())
        if (status.mode == AnkiquestDailyGoal.Mode.BACKLOG && status.goal.backlog > 0) {
            text(
                panel,
                getString(R.string.aq_goal_backlog_explained, status.goal.backlogShare, status.goal.backlog, status.backlogPercent),
                small = true,
            )
        }
    }

    private fun renderDeckCopies() {
        val panel = card()
        text(panel, getString(R.string.aq_copies_title), heading = true)
        text(panel, getString(R.string.aq_copies_description), small = true)
        copyStatus?.let { text(panel, it, small = true) }
        val data = copyInbox
        if (data == null) {
            text(panel, getString(if (copyFailed) R.string.aq_copies_unavailable else R.string.aq_home_loading_local), small = true)
            return
        }
        val friends = data.optJSONArray("friends")?.objects().orEmpty()
        val decks = local?.decks.orEmpty()
        button(panel, R.string.aq_copies_share, enabled = !copyBusy && friends.isNotEmpty() && decks.isNotEmpty()) {
            chooseDeckForCopy(decks, friends)
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
        decks: List<HomeDeck>,
        friends: List<JSONObject>,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aq_copies_choose_deck)
            .setItems(decks.map { it.name }.toTypedArray()) { _, position -> chooseFriendsForCopy(decks[position], friends) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFriendsForCopy(
        deck: HomeDeck,
        friends: List<JSONObject>,
    ) {
        val picked = BooleanArray(friends.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.aq_copies_choose_friends, deck.name))
            .setMultiChoiceItems(friends.map { it.optString("display") }.toTypedArray(), picked) { _, which, selected ->
                picked[which] = selected
            }.setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aq_copies_share) { _, _ ->
                val selected = friends.indices.filter { picked[it] }.map { friends[it].getString("user") }
                if (selected.isEmpty()) {
                    copyStatus = getString(R.string.aq_copies_choose_one)
                    render()
                } else {
                    shareCopy(deck, selected)
                }
            }.show()
    }

    private fun shareCopy(
        deck: HomeDeck,
        recipients: List<String>,
    ) {
        val current = account ?: return
        copyBusy = true
        copyStatus = getString(R.string.aq_copies_sending)
        render()
        lifecycleScope.launch {
            val status =
                try {
                    AnkiquestDeckCopies.share(current, deck.id, deck.name, recipients, cacheDir)
                    getString(R.string.aq_copies_sent)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AnkiquestDeckCopies.TooLarge) {
                    getString(R.string.aq_copies_too_large)
                } catch (error: Exception) {
                    Timber.w(error, "AnkiQuest deck copy could not be sent")
                    getString(R.string.aq_copies_failed)
                }
            if (account?.scope == current.scope) {
                copyStatus = status
                copyBusy = false
                render()
            }
        }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aq_home_change_deck)
            .setItems(rows.map { "${it.name} · ${it.daily.label(this)}" }.toTypedArray()) { _, index ->
                lifecycleScope.launch {
                    try {
                        CollectionManager.withCol {
                            if (decks.get(rows[index].id) != null) decks.select(rows[index].id)
                        }
                        local = AnkiquestHomeData.local()
                        scheduleLocalRefresh(local?.nextUpdateAt)
                        localFailed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "AnkiQuest selected deck could not be loaded")
                        localFailed = true
                        local = null
                    }
                    render()
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDecks(deck: Long? = null) {
        startActivity(
            Intent(this, DeckPicker::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_SKIP_HOME, true)
                deck?.let { putExtra(EXTRA_STUDY_DECK, it) }
            },
        )
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun web(path: String) = startActivity(AnkiquestActivity.intent(this, path))

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

        fun intent(context: Context): Intent = Intent(context, AnkiquestHomeActivity::class.java)
    }
}
