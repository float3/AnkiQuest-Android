// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.common.time.Time
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.util.Calendar

/** Friends -> independent sections; an unavailable overview does not block either one. */
internal class AnkiquestFriendsSections(
    private val activity: AnkiActivity,
    savedState: Bundle?,
) {
    enum class Destination { FEED, LEADERBOARD }

    /** Friends overview -> group picker -> scoped board; only ready membership can select a group. */
    sealed interface Groups {
        data object Loading : Groups

        data object Unavailable : Groups

        data class Ready(
            val values: List<AnkiquestSocial.Group>,
        ) : Groups
    }

    private val restored = savedState?.takeIf { it.getString(STATE_ACCOUNT) == AnkiquestHomeData.account()?.scope }
    private val initial = Destination.entries.firstOrNull { it.name == activity.intent.getStringExtra(EXTRA_SECTION) }
    private var pendingTarget = initial.takeIf { savedState == null }
    private val feed = FeedSection(activity, restored, initial)
    private val board =
        LeaderboardSection(
            activity,
            restored,
            initial,
            activity.intent.getStringExtra(AnkiquestLeaderboardActivity.EXTRA_PERIOD).takeIf { savedState == null },
        )
    private val root =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(feed.root)
            addView(board.root)
        }

    fun attach(
        parent: LinearLayout,
        groups: Groups,
    ) {
        (root.parent as? ViewGroup)?.removeView(root)
        parent.addView(root)
        board.updateGroups(groups)
    }

    fun refresh() {
        feed.refresh()
        board.refresh()
    }

    fun save(out: Bundle) {
        out.putString(STATE_ACCOUNT, AnkiquestHomeData.account()?.scope)
        feed.save(out)
        board.save(out)
    }

    fun showTarget() {
        when (pendingTarget) {
            Destination.FEED -> feed.showHeader()
            Destination.LEADERBOARD -> board.showHeader()
            null -> return
        }
        pendingTarget = null
    }

    companion object {
        private const val EXTRA_SECTION = "ankiquest.friends_section"
        private const val STATE_ACCOUNT = "ankiquest.friends_sections_account"

        fun intent(
            context: Context,
            destination: Destination,
        ): Intent = AnkiquestFriendsActivity.intent(context).putExtra(EXTRA_SECTION, destination.name)
    }
}

/** Expansion and filters survive parent rerenders; only expanded sections load. */
private abstract class FriendsSection(
    protected val activity: AnkiActivity,
    target: AnkiquestFriendsSections.Destination,
    @StringRes title: Int,
    @StringRes loadingLabel: Int,
    contentId: Int,
    loadingId: Int,
    saved: Bundle?,
    initial: AnkiquestFriendsSections.Destination?,
) : ContextWrapper(activity) {
    protected val scroll: NestedScrollView = activity.findViewById(R.id.aq_feed_scroll)
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
    private val stateKey = "ankiquest.friends_expanded.${target.name}"
    private var expanded = saved?.getBoolean(stateKey) ?: (initial == target)
    private val heading =
        AnkiquestBoardViews(activity).button(root, getString(title)) {
            expanded = !expanded
            showExpansion()
            if (expanded) {
                reload()
            }
        }

    init {
        ViewCompat.setAccessibilityHeading(heading, true)
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

private class FeedSection(
    activity: AnkiActivity,
    saved: Bundle?,
    initial: AnkiquestFriendsSections.Destination?,
) : FriendsSection(
        activity,
        AnkiquestFriendsSections.Destination.FEED,
        R.string.aq_home_activity,
        R.string.aq_feed_loading,
        R.id.aq_friends_feed_content,
        R.id.aq_friends_feed_loading,
        saved,
        initial,
    ) {
    private enum class Box { INBOX, SENT }

    private var account: HomeAccount? = AnkiquestHomeData.account()
    private var box = Box.INBOX
    private var category = AnkiquestFeed.Category.ALL
    private var unreadOnly = false
    private var page: AnkiquestFeed.Page? = null
    private var sent: AnkiquestFeed.SentPage? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null

    /** Rows with a cheer, reply or read request on its way, whose buttons wait for it. */
    private val busy = mutableSetOf<Long>()
    private var markingAll = false
    private val pictures = mutableMapOf<String, Bitmap?>()
    private var generation = 0
    private var loadJob: Job? = null

    init {
        box = Box.entries.firstOrNull { it.name == saved?.getString(STATE_BOX) } ?: Box.INBOX
        category = AnkiquestFeed.Category.entries.firstOrNull { it.name == saved?.getString(STATE_CATEGORY) }
            ?: AnkiquestFeed.Category.ALL
        unreadOnly = saved?.getBoolean(STATE_UNREAD) ?: false
        if (saved == null) {
            val addressed = activity.intent.getStringExtra(AnkiquestFeedActivity.EXTRA_ACCOUNT)
            if (addressed != null && addressed != account?.notificationAccount) {
                status = getString(R.string.aq_home_account_route_changed)
            }
        }
    }

    override fun saveFilters(out: Bundle) {
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            return
        }
        out.putString(STATE_BOX, box.name)
        out.putString(STATE_CATEGORY, category.name)
        out.putBoolean(STATE_UNREAD, unreadOnly)
    }

    override fun reload() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            pictures.clear()
            box = Box.INBOX
            category = AnkiquestFeed.Category.ALL
            unreadOnly = false
            status = null
            // The old account's changes no longer answer here.
            busy.clear()
            markingAll = false
        }
        page = null
        sent = null
        load(more = false)
    }

    private fun load(more: Boolean) {
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
                val problem =
                    attempt {
                        when (box) {
                            Box.INBOX -> {
                                val before = if (more) page?.nextBefore else null
                                val next = AnkiquestFeed.page(current, category, unreadOnly, before)
                                if (turn == generation && current.scope == AnkiquestHomeData.account()?.scope) {
                                    page = if (before == null) next else page?.let { append(it, next) } ?: next
                                    if (next.unfiltered || next.legacy) {
                                        category = AnkiquestFeed.Category.ALL
                                        unreadOnly = false
                                    }
                                }
                            }
                            Box.SENT -> {
                                val before = if (more) sent?.nextBefore else null
                                val next = AnkiquestFeed.sent(current, before)
                                if (turn == generation && current.scope == AnkiquestHomeData.account()?.scope) {
                                    sent =
                                        sent?.takeIf { before != null }?.let { old ->
                                            AnkiquestFeed.SentPage(
                                                old.items +
                                                    next.items.filter { new ->
                                                        old.items.none { it.item.id == new.item.id }
                                                    },
                                                next.nextBefore,
                                            )
                                        } ?: next
                                }
                            }
                        }
                    }
                if (turn != generation || current.scope != AnkiquestHomeData.account()?.scope) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    reload()
                    return@launch
                }
                failure = problem
                loading = false
                render()
                if (box == Box.INBOX) loadPictures(current)
            }
    }

    /** Runs [work] and answers the message for its failure, or null when it worked. */
    private suspend fun attempt(work: suspend () -> Unit): Int? =
        try {
            work()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: HomeAccountChanged) {
            R.string.aq_feed_account_changed
        } catch (e: AnkiquestHttpException) {
            Timber.w(e, "AnkiQuest activity request refused")
            if (e.code == 401 || e.code == 403) R.string.aq_home_auth else R.string.aq_feed_server_error
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest activity unreachable")
            R.string.aq_feed_offline
        } catch (e: JSONException) {
            Timber.w(e, "AnkiQuest activity unreadable")
            R.string.aq_feed_server_error
        }

    private fun append(
        old: AnkiquestFeed.Page,
        next: AnkiquestFeed.Page,
    ): AnkiquestFeed.Page =
        next.copy(
            items =
                old.items +
                    next.items.filter { new ->
                        old.items.none { it.id == new.id }
                    },
            attention = old.attention,
        )

    /** Fetches each friend's win card once, from the account's own server. */
    private fun loadPictures(current: HomeAccount) {
        val wanted =
            page
                ?.items
                .orEmpty()
                .mapNotNull { AnkiquestFeed.image(current, it) }
                .distinct()
                .filter { it !in pictures }
        if (wanted.isEmpty()) return
        wanted.forEach { pictures[it] = null }
        lifecycleScope.launch {
            for (url in wanted) {
                val picture = withContext(Dispatchers.IO) { AnkiquestShare.picture(url) } ?: continue
                if (account?.scope != current.scope) return@launch
                pictures[url] = picture
                render()
            }
        }
    }

    private fun render() {
        val position = scroll.scrollY
        content.removeAllViews()
        progress.isVisible = loading && content.isVisible
        status?.let { line ->
            text(content, line, small = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        }
        if (account == null) {
            val panel = card(content)
            text(panel, getString(R.string.aq_feed_connect))
            button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        choices(
            listOf(Box.INBOX to getString(R.string.aq_feed_inbox), Box.SENT to getString(R.string.aq_feed_sent)),
            selected = box,
            enabled = true,
        ) { choice ->
            box = choice
            if ((choice == Box.SENT && sent == null) || (choice == Box.INBOX && page == null)) load(more = false) else render()
        }
        when (box) {
            Box.INBOX -> renderInbox()
            Box.SENT -> renderSent()
        }
        failure?.let { problem ->
            text(content, getString(problem), small = true)
            if (problem == R.string.aq_home_auth) button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderInbox() {
        val current = page
        if (current?.legacy != true) {
            choices(
                AnkiquestFeed.Category.entries.map { it to categoryLabel(it, current) },
                selected = category,
                enabled = !loading,
            ) { choice ->
                if (choice != category) {
                    category = choice
                    page = null
                    load(more = false)
                } else {
                    render()
                }
            }
            chip(content, getString(R.string.aq_feed_unread_only), checked = unreadOnly, enabled = !loading) {
                unreadOnly = !unreadOnly
                page = null
                load(more = false)
            }
        }
        if (current == null) {
            if (loading) text(content, getString(R.string.aq_feed_loading), small = true)
            return
        }
        if (current.unread > 0 && !current.legacy) {
            button(content, getString(R.string.aq_feed_mark_all_read), enabled = !markingAll) { markAllRead() }
        }
        if (current.legacy) text(content, getString(R.string.aq_feed_legacy), small = true)
        val attention = current.attention.takeIf { category == AnkiquestFeed.Category.ALL }.orEmpty()
        if (attention.isNotEmpty() && current.actionCount > 0) {
            val panel = card(content, tinted = true)
            text(panel, getString(R.string.aq_feed_needs_action_count, current.actionCount), heading = true)
            text(panel, getString(R.string.aq_feed_needs_action_hint), small = true)
            for (item in attention) {
                text(panel, item.title, heading = true)
                if (item.body.isNotEmpty()) text(panel, item.body)
                item.challengeId?.let { goal ->
                    button(panel, getString(R.string.aq_feed_view_invitation), primary = true) { openGoal(item, goal) }
                }
            }
            if (current.actionCount > attention.size) {
                button(panel, getString(R.string.aq_feed_all_invitations)) {
                    category = AnkiquestFeed.Category.NEEDS_ACTION
                    page = null
                    load(more = false)
                }
            }
        }
        val shown = current.items.filter { item -> attention.none { it.id == item.id } }
        if (shown.isEmpty()) {
            if (!loading) {
                content.addView(AnkiquestAki.image(this, R.drawable.aki_celebrate, 72))
                text(content, getString(emptyMessage(attention.isNotEmpty())), heading = true)
                text(content, getString(R.string.aq_feed_empty_hint), small = true)
            }
        } else {
            var day: Long? = null
            for (item in shown) {
                val itemDay = dayOf(item.createdAt)
                if (itemDay != day) {
                    day = itemDay
                    text(content, dayLabel(item.createdAt), heading = true).setPadding(0, dp(16), 0, 0)
                }
                renderItem(item, current.legacy)
            }
        }
        if (current.nextBefore != null) {
            button(
                content,
                getString(if (loading) R.string.aq_feed_loading else R.string.aq_feed_more),
                enabled = !loading,
            ) { load(more = true) }
        }
    }

    private fun emptyMessage(attention: Boolean): Int =
        when {
            unreadOnly -> R.string.aq_feed_empty_unread
            category == AnkiquestFeed.Category.NEEDS_ACTION -> R.string.aq_feed_empty_invitations
            category != AnkiquestFeed.Category.ALL -> R.string.aq_feed_empty_category
            attention -> R.string.aq_feed_empty_rest
            else -> R.string.aq_feed_empty
        }

    private fun renderItem(
        item: AnkiquestFeed.Item,
        legacy: Boolean,
    ) {
        val unread = !legacy && item.unread
        val panel = card(content, tinted = unread)
        text(panel, item.heading, heading = true)
        val time = DateUtils.formatDateTime(this, item.createdAt * 1000, DateUtils.FORMAT_SHOW_TIME)
        text(panel, if (unread) getString(R.string.aq_feed_time_unread, time) else time, small = true)
        item.replyTo?.let { (title, body) -> quote(panel, title, body) }
        item.text?.let { text(panel, it) }
        account?.let { AnkiquestFeed.image(it, item) }?.let { url -> pictures[url] }?.let { picture ->
            panel.addView(
                ImageView(this).apply {
                    setImageBitmap(picture)
                    adjustViewBounds = true
                    contentDescription = getString(R.string.aq_feed_card, item.title)
                },
                LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
        val actions = actions(panel)
        val waiting = item.id in busy
        if (item.cheerable) {
            if (item.reacted) {
                chip(actions, getString(R.string.aq_feed_cheered), checked = true, enabled = false) {}
            } else {
                chip(actions, getString(R.string.ankiquest_cheer), enabled = !waiting) { cheer(item) }
            }
        }
        item.challengeId?.let { goal ->
            val label = if (item.actionRequired) R.string.aq_feed_view_invitation else R.string.aq_feed_open_goal
            chip(actions, getString(label), primary = item.actionRequired) { openGoal(item, goal) }
        }
        if (item.answerable) {
            AnkiquestFeedActivity.quickReply(item.kind)?.let { words ->
                chip(actions, getString(words), enabled = !waiting) { reply(item, getString(words)) }
            }
            chip(actions, getString(R.string.aq_feed_reply), enabled = !waiting) { askReply(item) }
        }
        if (unread) chip(actions, getString(R.string.aq_feed_mark_read), enabled = !waiting) { markRead(item) }
        if (actions.isEmpty()) panel.removeView(actions)
        if (item.replied) text(panel, getString(R.string.aq_feed_reply_sent), small = true)
    }

    private fun renderSent() {
        val current = sent
        if (current == null) {
            if (loading) text(content, getString(R.string.aq_feed_loading), small = true)
            return
        }
        if (current.items.isEmpty() && !loading) {
            text(content, getString(R.string.aq_feed_sent_empty), heading = true)
            text(content, getString(R.string.aq_feed_sent_empty_hint), small = true)
        }
        for ((recipient, item) in current.items) {
            val panel = card(content)
            text(panel, getString(R.string.aq_feed_sent_to, recipient), heading = true)
            val time = DateUtils.formatDateTime(this, item.createdAt * 1000, DATE_AND_TIME)
            val seen =
                item.readAt?.let {
                    getString(R.string.aq_feed_seen, DateUtils.formatDateTime(this, it * 1000, DATE_AND_TIME))
                } ?: getString(R.string.aq_feed_not_seen)
            text(panel, "$time · $seen", small = true)
            item.replyTo?.let { (title, body) -> quote(panel, title, body) }
            if (item.body.isNotEmpty()) text(panel, item.body)
        }
        if (current.nextBefore != null) {
            button(
                content,
                getString(if (loading) R.string.aq_feed_loading else R.string.aq_feed_more_sent),
                enabled = !loading,
            ) { load(more = true) }
        }
    }

    private fun categoryLabel(
        category: AnkiquestFeed.Category,
        page: AnkiquestFeed.Page?,
    ): String =
        when (category) {
            AnkiquestFeed.Category.ALL -> getString(R.string.aq_feed_all)
            AnkiquestFeed.Category.NEEDS_ACTION -> {
                val count = page?.actionCount ?: 0
                if (count > 0) getString(R.string.aq_feed_needs_action_count, count) else getString(R.string.aq_feed_needs_action)
            }
            AnkiquestFeed.Category.MESSAGES -> getString(R.string.aq_feed_messages)
            AnkiquestFeed.Category.CHALLENGES -> getString(R.string.aq_feed_challenges)
            AnkiquestFeed.Category.DECK_COMPLETIONS -> getString(R.string.aq_feed_deck_completions)
            AnkiquestFeed.Category.STUDY_UPDATES -> getString(R.string.aq_feed_study_updates)
        }

    /** Sends a 🎉 for a friend's win or finished deck. */
    private fun cheer(item: AnkiquestFeed.Item) =
        act(item, R.string.aq_feed_cheer_failed) { current ->
            AnkiquestFeed.cheer(current, item)
            change(item.id) { it.copy(reacted = true) }
            getString(R.string.aq_feed_cheer_sent)
        }

    private fun askReply(item: AnkiquestFeed.Item) {
        val field =
            EditText(this).apply {
                hint = getString(R.string.ankiquest_reply_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(InputFilter.LengthFilter(AnkiquestReply.MAX_MESSAGE))
                isSingleLine = true
            }
        val frame =
            FrameLayout(this).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(field)
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.aq_feed_reply_to, item.sender))
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aq_feed_send) { _, _ ->
                val message = AnkiquestReply.clean(field.text.toString())
                if (message.isNotEmpty()) reply(item, message)
            }.show()
        field.requestFocus()
    }

    /** Answers [item]; a reply also marks it read, which may fail without undoing the reply. */
    private fun reply(
        item: AnkiquestFeed.Item,
        message: String,
    ) = act(item, R.string.aq_feed_reply_failed) { current ->
        AnkiquestFeed.reply(current, item, message)
        change(item.id) { it.copy(replied = true) }
        if (item.unread && page?.legacy != true) {
            try {
                AnkiquestFeed.markRead(current, listOf(item.id))
                read(listOf(item.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest reply sent, read state not saved")
            }
        }
        getString(R.string.aq_feed_reply_done)
    }

    private fun markRead(item: AnkiquestFeed.Item) =
        act(item, R.string.aq_feed_read_failed) { current ->
            AnkiquestFeed.markRead(current, listOf(item.id))
            read(listOf(item.id))
            null
        }

    private fun markAllRead() {
        val current = account ?: return
        val shown = page ?: return
        val through = shown.latestId ?: (shown.items + shown.attention).maxOfOrNull { it.id } ?: return
        markingAll = true
        render()
        lifecycleScope.launch {
            val problem = attempt { AnkiquestFeed.markAllRead(current, through) }
            if (account?.scope != current.scope) return@launch
            markingAll = false
            if (problem == null) {
                val now = TimeManager.time.intTimeMS() / 1000
                page =
                    page?.let { latest ->
                        fun AnkiquestFeed.Item.marked() = if (unread && id <= through) copy(readAt = now) else this
                        latest.copy(
                            items = latest.items.map { it.marked() }.filter { !unreadOnly || it.unread },
                            attention = latest.attention.map { it.marked() },
                            unread = 0,
                        )
                    }
                status = getString(R.string.aq_feed_all_read)
            } else {
                status = getString(R.string.aq_feed_read_failed)
            }
            render()
        }
    }

    /** Opens the goal a row is about and marks the row read. */
    private fun openGoal(
        item: AnkiquestFeed.Item,
        goal: Long,
    ) {
        startActivity(AnkiquestChallengesActivity.intent(this, goal))
        val current = account ?: return
        if (!item.unread || page?.legacy == true) return
        lifecycleScope.launch {
            val problem = attempt { AnkiquestFeed.markRead(current, listOf(item.id)) }
            if (problem == null && account?.scope == current.scope) {
                read(listOf(item.id))
                render()
            }
        }
    }

    /**
     * Runs [work] for [item] while its buttons wait. [work] answers the line to show, or null for none;
     * a failure shows [failed] with why. Nothing is kept once the account changed.
     */
    private fun act(
        item: AnkiquestFeed.Item,
        @StringRes failed: Int,
        work: suspend (HomeAccount) -> String?,
    ) {
        val current = account ?: return
        if (!busy.add(item.id)) return
        render()
        lifecycleScope.launch {
            var line: String? = null
            val problem = attempt { line = work(current) }
            if (account?.scope != current.scope) return@launch
            busy.remove(item.id)
            if (problem == R.string.aq_feed_account_changed) {
                reload()
                return@launch
            }
            status = if (problem == null) line else getString(R.string.aq_feed_failed_because, getString(failed), getString(problem))
            render()
        }
    }

    private fun change(
        id: Long,
        update: (AnkiquestFeed.Item) -> AnkiquestFeed.Item,
    ) {
        page =
            page?.let { latest ->
                latest.copy(
                    items = latest.items.map { if (it.id == id) update(it) else it },
                    attention = latest.attention.map { if (it.id == id) update(it) else it },
                )
            }
    }

    private fun read(ids: List<Long>) {
        val latest = page ?: return
        val newlyRead = (latest.items + latest.attention).filter { it.id in ids && it.unread }.map { it.id }.distinct()
        val now = TimeManager.time.intTimeMS() / 1000
        ids.forEach { id -> change(id) { if (it.unread) it.copy(readAt = now) else it } }
        page =
            page?.let { changed ->
                changed.copy(
                    items = if (unreadOnly) changed.items.filter { it.id !in ids } else changed.items,
                    unread = (changed.unread - newlyRead.size).coerceAtLeast(0),
                )
            }
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun dayOf(seconds: Long): Long =
        Time
            .gregorianCalendar(seconds * 1000)
            .apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

    private fun dayLabel(seconds: Long): String {
        val day = dayOf(seconds)
        val today = dayOf(TimeManager.time.intTimeMS() / 1000)
        val yesterday =
            Time
                .gregorianCalendar(today)
                .apply { add(Calendar.DAY_OF_YEAR, -1) }
                .timeInMillis
        return when (day) {
            today -> getString(R.string.ankiquest_nav_today)
            yesterday -> getString(R.string.aq_feed_yesterday)
            else -> DateUtils.formatDateTime(this, seconds * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
        }
    }

    private fun <T> choices(
        options: List<Pair<T, String>>,
        selected: T,
        enabled: Boolean,
        pick: (T) -> Unit,
    ) {
        val group =
            ChipGroup(this).apply {
                isSingleLine = true
                isSingleSelection = true
            }
        for ((value, label) in options) chip(group, label, checked = value == selected, enabled = enabled) { pick(value) }
        content.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(group)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
    }

    private fun actions(parent: LinearLayout): ChipGroup =
        ChipGroup(this).also {
            parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun chip(
        parent: ViewGroup,
        label: String,
        checked: Boolean = false,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): Chip =
        Chip(this).apply {
            text = label
            textSize = 15f
            // Grows with the text size, so large text is never clipped.
            chipMinHeight = maxOf(dp(32).toFloat(), paint.fontMetrics.run { descent - ascent } + dp(14))
            isCheckable = checked
            isChecked = checked
            isCheckedIconVisible = false
            isEnabled = enabled
            val filled = checked || primary
            setTextColor(getColor(if (filled) R.color.aq_home_primary_text else R.color.aq_home_text))
            chipBackgroundColor = ColorStateList.valueOf(getColor(if (filled) R.color.aq_home_primary else R.color.aq_home_surface))
            chipStrokeWidth = if (filled) 0f else dp(1).toFloat()
            chipStrokeColor = ColorStateList.valueOf(getColor(R.color.aq_home_outline))
            // The state is the selection drawn above; clicking always asks [click], which re-renders.
            setOnClickListener { click() }
            if (parent is LinearLayout) {
                parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                parent.addView(this)
            }
        }

    private fun quote(
        parent: LinearLayout,
        title: String,
        body: String,
    ) {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = shape(R.color.aq_home_background, 10)
            }
        parent.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
        text(box, getString(R.string.aq_feed_in_reply_to), small = true)
        if (title.isNotEmpty()) text(box, title).typeface = Typeface.DEFAULT_BOLD
        if (body.isNotEmpty()) text(box, body, small = true)
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
        radius: Int = 18,
    ): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(getColor(color))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_BOX = "ankiquest.feed_box"
        private const val STATE_CATEGORY = "ankiquest.feed_category"
        private const val STATE_UNREAD = "ankiquest.feed_unread"
        private const val DATE_AND_TIME = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    }
}

private class LeaderboardSection(
    activity: AnkiActivity,
    saved: Bundle?,
    initial: AnkiquestFriendsSections.Destination?,
    launchPeriod: String?,
) : FriendsSection(
        activity,
        AnkiquestFriendsSections.Destination.LEADERBOARD,
        R.string.ankiquest_dashboard_title,
        R.string.aq_board_loading,
        R.id.aq_friends_board_content,
        R.id.aq_friends_board_loading,
        saved,
        initial,
    ) {
    private enum class Scope { FRIENDS, GROUP, GLOBAL }

    private enum class Support { UNKNOWN, AVAILABLE, UNAVAILABLE }

    private enum class Refresh { CACHED_SCOPES, CHECK_SCOPES }

    private sealed interface Selection {
        data object Friends : Selection

        data object Global : Selection

        data class Group(
            val id: Long?,
        ) : Selection
    }

    private val views = AnkiquestBoardViews(activity)
    private var account: HomeAccount? = AnkiquestHomeData.account()
    private var period = AnkiquestBoards.Period.WEEK
    private var selection: Selection = Selection.Friends
    private var groups: AnkiquestFriendsSections.Groups = AnkiquestFriendsSections.Groups.Loading
    private var scopes = Support.UNKNOWN
    private val availableGroups get() = (groups as? AnkiquestFriendsSections.Groups.Ready)?.values.orEmpty()
    private val group get() = (selection as? Selection.Group)?.id
    private val scope
        get() =
            when (selection) {
                Selection.Friends -> Scope.FRIENDS
                Selection.Global -> Scope.GLOBAL
                is Selection.Group -> Scope.GROUP
            }
    private var rows: List<AnkiquestBoards.Standing>? = null
    private var weekEnds: Long? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null
    private var generation = 0
    private var loadJob: Job? = null

    init {
        // Only a fresh link supplies a period; recreation uses this account's saved selection.
        period = AnkiquestBoards.Period.of(saved?.getString(STATE_PERIOD) ?: launchPeriod)
        selection =
            when (saved?.getString(STATE_SCOPE)) {
                Scope.GLOBAL.name -> Selection.Global
                Scope.GROUP.name -> Selection.Group(saved.getLong(STATE_GROUP).takeIf { it > 0 })
                else -> Selection.Friends
            }
        if (saved == null) {
            val addressed = activity.intent.getStringExtra(AnkiquestLeaderboardActivity.EXTRA_ACCOUNT)
            if (addressed != null && addressed != account?.notificationAccount) {
                status = getString(R.string.aq_board_account_changed)
            }
        }
    }

    override fun reload() = load(Refresh.CHECK_SCOPES)

    /** Reuses Friends' overview, including membership changes, without another social request. */
    fun updateGroups(next: AnkiquestFriendsSections.Groups) {
        if (account?.scope != AnkiquestHomeData.account()?.scope || groups == next) {
            return
        }
        groups = next
        if (scope == Scope.GROUP) {
            if (next is AnkiquestFriendsSections.Groups.Ready && group != null && availableGroups.none { it.id == group }) {
                selection = Selection.Friends
                status = getString(R.string.aq_board_group_missing)
            } else if (group == null) {
                selection = Selection.Group(availableGroups.firstOrNull()?.id)
            }
            rows = null
            refresh()
        } else {
            render()
        }
    }

    override fun saveFilters(out: Bundle) {
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            return
        }
        out.putString(STATE_PERIOD, period.query)
        out.putString(STATE_SCOPE, scope.name)
        group?.let { out.putLong(STATE_GROUP, it) }
    }

    /** Loads [period]'s board for the current account, and when the week resets. */
    private fun load(refresh: Refresh = Refresh.CACHED_SCOPES) {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            rows = null
            period = AnkiquestBoards.Period.WEEK
            selection = Selection.Friends
            groups = AnkiquestFriendsSections.Groups.Loading
            scopes = Support.UNKNOWN
            weekEnds = null
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
                var groupDenied = false
                val problem =
                    boardAttempt {
                        if (scopes == Support.UNKNOWN || refresh == Refresh.CHECK_SCOPES) {
                            val supported = optionalScopes(current)
                            if (turn != generation || current.scope != AnkiquestHomeData.account()?.scope) {
                                return@boardAttempt
                            }
                            if (supported != null) {
                                scopes = supported
                            }
                        }
                        if (scopes == Support.UNAVAILABLE && scope != Scope.FRIENDS) {
                            selection = Selection.Friends
                            rows = null
                            status = getString(R.string.ankiquest_widget_global_unavailable)
                        }
                        if (scope != Scope.FRIENDS && scopes != Support.AVAILABLE) {
                            throw IOException("Leaderboard scopes unavailable")
                        }
                        val source =
                            when (scope) {
                                Scope.FRIENDS -> AnkiquestBoards.Source.Friends
                                Scope.GLOBAL -> AnkiquestBoards.Source.Global
                                Scope.GROUP -> {
                                    val chosen = availableGroups.firstOrNull { it.id == group }
                                    if (chosen == null) {
                                        rows = null
                                        return@boardAttempt
                                    }
                                    AnkiquestBoards.Source.Group(chosen.id)
                                }
                            }
                        try {
                            val board = AnkiquestBoards.leaderboard(current, asked, source)
                            val ends = if (asked == AnkiquestBoards.Period.WEEK) optionalWeekEnds(current) else weekEnds
                            if (turn == generation && current.scope == AnkiquestHomeData.account()?.scope) {
                                rows = board
                                weekEnds = ends
                            }
                        } catch (e: AnkiquestHttpException) {
                            when {
                                e.code == HTTP_FORBIDDEN && source is AnkiquestBoards.Source.Group -> {
                                    groupDenied = true
                                }
                                e.code == HTTP_NOT_FOUND -> gone = true
                                else -> throw e
                            }
                        }
                    }
                if (turn != generation || current.scope != AnkiquestHomeData.account()?.scope) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    load()
                    return@launch
                }
                // A superseded group's failure must not clear the current selection's rows.
                if (groupDenied) {
                    rows = null
                }
                failure =
                    when {
                        groupDenied -> R.string.aq_board_group_unavailable
                        gone -> R.string.aq_server_outdated
                        else -> problem
                    }
                loading = false
                render()
                if (problem == null) loadPictures(current)
            }
    }

    /** Optional controls must not prevent a normal Friends board from loading. */
    private suspend fun optionalScopes(current: HomeAccount): Support? =
        try {
            if (AnkiquestBoards.hasScopes(current)) {
                Support.AVAILABLE
            } else {
                Support.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: AnkiquestHttpException) {
            if (e.code == HTTP_UNAUTHORIZED || e.code == HTTP_FORBIDDEN) {
                throw e
            }
            if (e.code == HTTP_NOT_FOUND) {
                Support.UNAVAILABLE
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
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
        val position = scroll.scrollY
        content.removeAllViews()
        progress.isVisible = loading && content.isVisible
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
        if (scopes == Support.AVAILABLE) {
            scopeChoices()
            if (scope == Scope.GROUP) {
                val available = availableGroups
                if (available.isEmpty()) {
                    val explanation =
                        when (groups) {
                            AnkiquestFriendsSections.Groups.Loading -> R.string.ankiquest_widget_settings_groups_loading
                            AnkiquestFriendsSections.Groups.Unavailable -> R.string.ankiquest_widget_settings_groups_failed
                            is AnkiquestFriendsSections.Groups.Ready -> R.string.aq_friends_groups_empty
                        }
                    views.text(content, getString(explanation), small = true)
                } else {
                    views.choices(content, available.map { it.id to it.name }, selected = group, enabled = true) { chosen ->
                        if (group != chosen) {
                            selection = Selection.Group(chosen)
                            rows = null
                            load()
                        } else {
                            render()
                        }
                    }
                }
            }
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
        views.section(content, getString(R.string.aq_board_xp_in, getString(AnkiquestLeaderboardActivity.periodPhrase(period))))
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
            board.all { it.xp == 0L } && scope != Scope.GLOBAL -> if (!loading) empty(R.string.aq_board_no_xp)
            else -> {
                standing(current, board)
                board.forEachIndexed { index, row -> row(row.rank ?: (index + 1), row, row.user == current.user, board.first().xp) }
            }
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (problem == R.string.aq_home_auth) views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        views.button(content, getString(R.string.aq_board_my_profile)) { openProfile(current.user) }
        views.button(content, getString(R.string.aq_calendar_title)) { startActivity(AnkiquestCalendarActivity.intent(this)) }
        scroll.post { scroll.scrollTo(0, position) }
    }

    /** All three scopes stay visible at large text sizes instead of hiding the active choice offscreen. */
    private fun scopeChoices() {
        val choices = ChipGroup(this).apply { isSingleSelection = true }
        val labels =
            listOf(
                Scope.FRIENDS to R.string.ankiquest_nav_friends,
                Scope.GROUP to R.string.aq_board_scope_group,
                Scope.GLOBAL to R.string.ankiquest_widget_who_global,
            )
        for ((choice, label) in labels) {
            choices.addView(
                Chip(this).apply {
                    text = getString(label)
                    textSize = 15f
                    minimumHeight = views.dp(48)
                    chipMinHeight = maxOf(views.dp(32).toFloat(), paint.fontMetrics.run { descent - ascent } + views.dp(14))
                    isCheckable = true
                    isChecked = choice == scope
                    isCheckedIconVisible = false
                    setTextColor(getColor(if (isChecked) R.color.aq_home_primary_text else R.color.aq_home_text))
                    chipBackgroundColor =
                        ColorStateList.valueOf(getColor(if (isChecked) R.color.aq_home_primary else R.color.aq_home_surface))
                    chipStrokeWidth = if (isChecked) 0f else views.dp(1).toFloat()
                    chipStrokeColor = ColorStateList.valueOf(getColor(R.color.aq_home_outline))
                    setOnClickListener {
                        if (scope != choice) {
                            selection =
                                when (choice) {
                                    Scope.FRIENDS -> Selection.Friends
                                    Scope.GLOBAL -> Selection.Global
                                    Scope.GROUP -> Selection.Group(availableGroups.firstOrNull()?.id)
                                }
                            rows = null
                            status = null
                            load()
                        } else {
                            render()
                        }
                    }
                },
            )
        }
        content.addView(choices)
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
        val own = board[index]
        val place = own.rank ?: (index + 1)
        val panel = views.card(content, tinted = true, horizontal = true)
        AnkiquestArt.place(place)?.takeIf { board[index].xp > 0 && AnkiquestCompanion.visible() }?.let { art ->
            panel.addView(AnkiquestAki.image(this, art, 56))
        }
        views.column(panel).also { column ->
            val position =
                if (own.rank != null) {
                    getString(R.string.aq_board_your_rank, place)
                } else {
                    getString(R.string.aq_board_your_place, place, board.size)
                }
            views.text(column, position, heading = true)
            if (own.shown == false) {
                views.text(column, getString(R.string.aq_board_private_row), small = true)
            }
        }
    }

    private fun row(
        place: Int,
        row: AnkiquestBoards.Standing,
        mine: Boolean,
        leader: Long,
    ) {
        // Large text needs the full card width for study status and XP.
        val stacked = resources.configuration.fontScale >= STACKED_TEXT_SCALE
        val panel = views.card(content, tinted = mine, horizontal = !stacked)
        val identity =
            if (stacked) {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    panel.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            } else {
                panel
            }
        val medal = AnkiquestArt.medal(place)
        if (medal != null) {
            identity.addView(
                ImageView(this).apply {
                    setImageResource(medal)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(views.dp(28), views.dp(28)),
            )
        } else {
            identity.addView(
                TextView(this).apply {
                    text = views.number(place.toLong())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    minWidth = views.dp(28)
                    maxLines = 1
                    setTextColor(getColor(R.color.aq_home_muted))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        views.avatar(identity, row.user, row.display, 40)
        val details = views.column(identity)
        val name = if (mine) getString(R.string.aq_challenges_you, row.display) else row.display
        views.text(details, name, heading = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, false)
        }
        val streak = streakLine(row)
        val summary = if (stacked) panel else details
        views
            .text(
                summary,
                listOfNotNull(getString(R.string.ankiquest_widget_level, row.level), streak).joinToString(" · "),
                small = true,
            ).apply {
                if (row.streak > 0 && row.hasToday) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(streakIcon(row.state), 0, 0, 0)
                    compoundDrawablePadding = views.dp(4)
                }
            }
        views.bar(summary, AnkiquestBoards.percent(row.xp, leader.coerceAtLeast(1)))
        val alignment = if (stacked) Gravity.START else Gravity.END
        val score =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = alignment
            }
        panel.addView(
            score,
            LinearLayout
                .LayoutParams(
                    if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = if (stacked) 0 else views.dp(8)
                },
        )
        views.text(score, getString(R.string.aq_board_xp, views.number(row.xp)), heading = true).apply {
            gravity = alignment
            ViewCompat.setAccessibilityHeading(this, false)
        }
        if (row.hasToday) {
            views.text(score, getString(R.string.aq_board_today_reviews, views.number(row.todayReviews)), small = true).gravity = alignment
        }
        val closing = row.closingXp(TimeManager.time.intTimeMS())?.takeIf { period == AnkiquestBoards.Period.WEEK }
        closing?.let { xp ->
            views.text(score, getString(R.string.aq_board_closing_xp, views.number(xp)), small = true).gravity = alignment
        }
        panel.contentDescription =
            listOfNotNull(
                getString(R.string.aq_board_row_description, place, name, row.level, views.number(row.xp)),
                streak?.let {
                    if (row.hasToday) {
                        getString(
                            R.string.ankiquest_streak_description,
                            views.number(row.streak.toLong()),
                            it,
                        )
                    } else {
                        it
                    }
                },
                closing?.let { getString(R.string.aq_board_closing_xp, views.number(it)) },
                getString(R.string.aq_board_private_row).takeIf { mine && row.shown == false },
            ).joinToString(". ")
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        panel.foreground = AppCompatResources.getDrawable(this, ripple.resourceId)
        panel.setOnClickListener { openProfile(row.user) }
    }

    /** Today's status for a row with a streak, in words; null without a streak. */
    private fun streakLine(row: AnkiquestBoards.Standing): String? {
        if (row.streak <= 0) return null
        if (!row.hasToday) {
            return getString(R.string.aq_board_streak_count, views.number(row.streak.toLong()))
        }
        val status = AnkiquestDay.words(this, row.state, row.todayMinutes, row.streakMinutes)
        return "${views.number(row.streak.toLong())} · $status"
    }

    private fun streakIcon(state: AnkiquestDay.State): Int =
        when (state) {
            AnkiquestDay.State.STUDIED -> R.drawable.ankiquest_streak_studied
            AnkiquestDay.State.PROTECTED -> R.drawable.ankiquest_streak_protected
            else -> R.drawable.ankiquest_streak_pending
        }

    private fun openProfile(user: String) = startActivity(AnkiquestProfileActivity.intent(this, user))

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    companion object {
        private const val STATE_PERIOD = "ankiquest.board_period"
        private const val STATE_SCOPE = "ankiquest.board_scope"
        private const val STATE_GROUP = "ankiquest.board_group"
        private const val STACKED_TEXT_SCALE = 1.5f
    }
}
