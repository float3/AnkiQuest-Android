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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import kotlin.math.abs

/**
 * Friends and groups, natively: friend requests both ways, friends, muted players, groups with
 * their members and invite links, joining a group from a `/join/<code>` link, and the way to the
 * friend goals. The bottom navigation's Friends tab opens here.
 */
class AnkiquestFriendsActivity : AnkiActivity(R.layout.activity_ankiquest_feed) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var overview: AnkiquestSocial.Overview? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null

    /** An invite code to confirm, from a `/join/<code>` link, and what it shows once read. */
    private var invite: String? = null
    private var preview: AnkiquestSocial.Invite? = null
    private var inviteGone = false

    /** A change on its way; every button that would change something waits for it. */
    private var busy = false
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.aq_feed_content)
        scroll = findViewById(R.id.aq_feed_scroll)
        findViewById<TextView>(R.id.aq_feed_title).apply {
            setText(R.string.ankiquest_nav_friends)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<View>(R.id.aq_feed_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.aq_feed_refresh).apply {
            contentDescription = getString(R.string.aq_friends_refresh)
            setOnClickListener {
                status = null
                reload()
            }
        }
        findViewById<View>(R.id.aq_feed_loading).contentDescription = getString(R.string.aq_friends_loading)
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
                status = getString(R.string.aq_friends_account_route_changed)
            } else {
                invite = intent.getStringExtra(EXTRA_INVITE)?.takeIf { it.isNotBlank() }
            }
        } else {
            invite = savedInstanceState.getString(STATE_INVITE)
        }
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_INVITE, invite)
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        // Another account's friends must never stay on screen after signing out or switching.
        if (AnkiquestHomeData.account()?.scope != account?.scope) reload()
    }

    private fun reload() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            overview = null
            status = null
            // An invite link was opened for the account that was signed in then.
            invite = null
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
                val code = invite
                val problem =
                    attempt {
                        val next =
                            try {
                                AnkiquestSocial.overview(current)
                            } catch (e: AnkiquestHttpException) {
                                if (e.code != 404) throw e
                                missing = true
                                return@attempt
                            }
                        if (turn == generation) overview = next
                        if (code != null) readInvite(current, code, turn)
                    }
                if (turn != generation) return@launch
                if (problem == ACCOUNT_CHANGED) {
                    reload()
                    return@launch
                }
                failure = if (missing) R.string.aq_server_outdated else problem
                loading = false
                render()
                if (problem == null) loadPictures(current, turn)
            }
    }

    /** Reads what [code] would join; a code that is not valid any more says so instead. */
    private suspend fun readInvite(
        current: HomeAccount,
        code: String,
        turn: Int,
    ) {
        val read =
            try {
                AnkiquestSocial.invite(current, code)
            } catch (e: AnkiquestHttpException) {
                if (e.code != 404) throw e
                null
            }
        if (turn != generation || invite != code) return
        preview = read
        inviteGone = read == null
    }

    /** Profile pictures are optional: the rows show initials until they arrive. */
    private fun loadPictures(
        current: HomeAccount,
        turn: Int,
    ) {
        val shown = overview ?: return
        val users = (shown.incoming + shown.friends + shown.outgoing + shown.groups.flatMap { it.members } + shown.muted).map { it.user }
        if (users.isEmpty()) return
        lifecycleScope.launch {
            try {
                AnkiquestAvatars.refresh(users.distinct())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "AnkiQuest friends' pictures unavailable")
                return@launch
            }
            if (turn == generation && account?.scope == current.scope && !loading) render()
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
            ACCOUNT_CHANGED
        } catch (e: AnkiquestHttpException) {
            Timber.w(e, "AnkiQuest friends request refused")
            if (e.code == 401) R.string.aq_home_auth else R.string.aq_friends_server_error
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest friends unreachable")
            R.string.aq_friends_offline
        } catch (e: JSONException) {
            Timber.w(e, "AnkiQuest friends unreadable")
            R.string.aq_friends_server_error
        }

    /**
     * Sends [change] and shows the overview the server answers. [done] is the line for success; a
     * refusal shows why, in the server's own words when it gave them.
     */
    private fun change(
        change: AnkiquestSocial.Change,
        done: (AnkiquestSocial.Overview) -> String,
        after: () -> Unit = {},
    ) {
        val current = account ?: return
        if (busy) return
        busy = true
        status = getString(R.string.ankiquest_goal_saving)
        render()
        lifecycleScope.launch {
            var answer: AnkiquestSocial.Overview? = null
            var reason: String? = null
            val problem =
                attempt {
                    try {
                        answer = AnkiquestSocial.change(current, change)
                    } catch (e: AnkiquestHttpException) {
                        if (e.code in REFUSALS) reason = e.explanation
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
                overview = result
                status = done(result)
                after()
            } else {
                status =
                    getString(R.string.aq_feed_failed_because, getString(R.string.aq_friends_change_failed), reason ?: getString(problem!!))
            }
            render()
            if (result != null) loadPictures(current, generation)
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
            text(panel, getString(R.string.aq_friends_connect))
            button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        failure?.let { problem ->
            text(content, getString(problem), small = true)
            if (problem == R.string.aq_home_auth) button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        val shown = overview
        if (shown == null) {
            if (loading) text(content, getString(R.string.aq_friends_loading), small = true)
            return
        }
        renderInvite()
        val goals = card(content)
        text(goals, getString(R.string.aq_challenges_title), heading = true)
        text(goals, getString(R.string.aq_friends_goals_hint), small = true)
        button(goals, getString(R.string.aq_friends_goals_open)) { openFriendGoals() }
        renderFriends(shown)
        renderGroups(shown, current.user)
        if (shown.muted.isNotEmpty()) {
            section(getString(R.string.aq_friends_muted), getString(R.string.aq_friends_muted_hint))
            for (person in shown.muted) {
                val row = person(content, person)
                chip(actions(row), getString(R.string.aq_friends_unmute), enabled = !busy) { unmute(person) }
            }
        }
        renderVisibility(shown)
        scroll.post { scroll.scrollTo(0, position) }
    }

    /** The confirmation a `/join/<code>` link asks for, as the website's join page shows it. */
    private fun renderInvite() {
        val code = invite ?: return
        val panel = card(content, tinted = true)
        if (inviteGone) {
            text(panel, getString(R.string.aq_friends_invite_gone), heading = true)
            text(panel, getString(R.string.aq_friends_invite_gone_hint), small = true)
            button(panel, getString(android.R.string.ok)) { dismissInvite() }
            return
        }
        val group = preview ?: return
        text(panel, getString(R.string.aq_friends_invite), small = true)
        text(panel, group.name, heading = true)
        text(panel, members(group.members), small = true)
        text(panel, getString(R.string.aq_friends_invite_hint))
        button(panel, getString(R.string.aq_friends_join), primary = true, enabled = !busy) {
            change(AnkiquestSocial.Change.Join(code), { getString(R.string.aq_friends_joined, group.name) }) { dismissInvite() }
        }
        button(panel, getString(R.string.ankiquest_deck_share_not_now), enabled = !busy) { dismissInvite() }
    }

    private fun renderFriends(shown: AnkiquestSocial.Overview) {
        if (shown.incoming.isNotEmpty()) {
            section(getString(R.string.aq_friends_requests), getString(R.string.aq_friends_requests_hint))
            for (person in shown.incoming) {
                val row = person(content, person, tinted = true)
                val actions = actions(row)
                chip(actions, getString(R.string.aq_friends_accept), primary = true, enabled = !busy) {
                    change(AnkiquestSocial.Change.Befriend(person.user), { getString(R.string.aq_friends_accepted, person.display) })
                }
                chip(actions, getString(R.string.aq_challenges_decline), enabled = !busy) {
                    change(AnkiquestSocial.Change.Unfriend(person.user), { getString(R.string.aq_friends_declined) })
                }
            }
        }
        section(getString(R.string.aq_friends_friends), getString(R.string.aq_friends_friends_hint))
        button(content, getString(R.string.aq_friends_add), primary = true, enabled = !busy) { askFriend() }
        if (shown.friends.isEmpty()) {
            content.addView(AnkiquestAki.image(this, R.drawable.aki_celebrate, 72))
            text(content, getString(R.string.aq_friends_empty), heading = true)
            text(content, getString(R.string.aq_friends_empty_hint), small = true)
        }
        for (person in shown.friends) {
            val row = person(content, person)
            val actions = actions(row)
            chip(actions, getString(R.string.aq_profile_title)) { openProfile(person) }
            muteChip(actions, shown, person)
            chip(actions, getString(R.string.aq_friends_remove), enabled = !busy) {
                confirm(getString(R.string.aq_friends_remove_confirm, person.display), R.string.aq_friends_remove) {
                    change(AnkiquestSocial.Change.Unfriend(person.user), { getString(R.string.aq_friends_removed, person.display) })
                }
            }
        }
        if (shown.outgoing.isNotEmpty()) {
            text(content, getString(R.string.aq_friends_waiting), heading = true).setPadding(0, dp(12), 0, 0)
            for (person in shown.outgoing) {
                val row = person(content, person)
                chip(actions(row), getString(R.string.aq_friends_cancel_request), enabled = !busy) {
                    change(AnkiquestSocial.Change.Unfriend(person.user), { getString(R.string.aq_friends_request_cancelled) })
                }
            }
        }
    }

    private fun renderGroups(
        shown: AnkiquestSocial.Overview,
        me: String,
    ) {
        section(getString(R.string.aq_friends_groups), getString(R.string.aq_friends_groups_hint))
        button(content, getString(R.string.aq_friends_group_create), enabled = !busy) { askGroupName(null) }
        button(content, getString(R.string.aq_friends_group_join), enabled = !busy) { askInvite() }
        if (shown.groups.isEmpty()) text(content, getString(R.string.aq_friends_groups_empty), small = true)
        for (group in shown.groups) {
            val panel = card(content)
            text(panel, group.name, heading = true)
            text(panel, members(group.members.size), small = true)
            val code = group.invite
            if (group.owner && code != null) {
                val link = AnkiquestSocial.inviteLink(checkNotNull(account), code)
                text(panel, getString(R.string.aq_friends_invite_link), small = true).setPadding(0, dp(8), 0, 0)
                text(panel, link).setTextIsSelectable(true)
                text(panel, getString(R.string.aq_friends_invite_link_hint), small = true)
                val owner = actions(panel)
                chip(owner, getString(R.string.aq_friends_share_link), primary = true) { shareInvite(group, link) }
                chip(owner, getString(R.string.aq_friends_new_link), enabled = !busy) {
                    confirm(getString(R.string.aq_friends_new_link_confirm), R.string.aq_friends_new_link) {
                        change(AnkiquestSocial.Change.NewInvite(group.id), { getString(R.string.aq_friends_new_link_done) })
                    }
                }
                chip(owner, getString(R.string.aq_friends_rename), enabled = !busy) { askGroupName(group) }
            }
            for (member in group.members) {
                val name = if (member.user == me) getString(R.string.aq_friends_you, member.display) else member.display
                val row = person(panel, member.copy(display = name), tinted = true)
                if (member.user == me) continue
                val actions = actions(row)
                muteChip(actions, shown, member)
                if (group.owner) {
                    chip(actions, getString(R.string.aq_friends_remove), enabled = !busy) {
                        confirm(
                            getString(R.string.aq_friends_member_remove_confirm, member.display, group.name),
                            R.string.aq_friends_remove,
                        ) {
                            change(
                                AnkiquestSocial.Change.Remove(group.id, member.user),
                                { getString(R.string.aq_friends_member_removed, member.display, group.name) },
                            )
                        }
                    }
                }
            }
            button(panel, getString(R.string.aq_friends_leave), enabled = !busy) {
                confirm(getString(R.string.aq_friends_leave_confirm, group.name), R.string.aq_friends_leave) {
                    change(AnkiquestSocial.Change.Leave(group.id), { getString(R.string.aq_friends_left, group.name) })
                }
            }
        }
    }

    private fun renderVisibility(shown: AnkiquestSocial.Overview) {
        val panel = card(content)
        text(panel, getString(R.string.aq_board_my_profile), heading = true)
        text(
            panel,
            getString(if (shown.public) R.string.aq_friends_visibility_public else R.string.aq_friends_visibility_private),
            small = true,
        )
        val choice = actions(panel)
        chip(choice, getString(R.string.aq_friends_public), checked = shown.public, enabled = !busy) {
            val public = !shown.public
            change(AnkiquestSocial.Change.Visibility(public), {
                getString(if (public) R.string.aq_friends_visibility_public else R.string.aq_friends_visibility_private)
            })
        }
    }

    private fun muteChip(
        parent: ChipGroup,
        shown: AnkiquestSocial.Overview,
        person: AnkiquestSocial.Person,
    ) {
        if (shown.isMuted(person.user)) {
            chip(parent, getString(R.string.aq_friends_unmute), enabled = !busy) { unmute(person) }
        } else {
            chip(parent, getString(R.string.aq_friends_mute), enabled = !busy) {
                confirm(getString(R.string.aq_friends_mute_confirm, person.display), R.string.aq_friends_mute) {
                    change(AnkiquestSocial.Change.Mute(person.user), { getString(R.string.aq_friends_muted_done, person.display) })
                }
            }
        }
    }

    private fun unmute(person: AnkiquestSocial.Person) =
        change(AnkiquestSocial.Change.Unmute(person.user), { getString(R.string.aq_friends_unmuted, person.display) })

    /** Asks for a username and sends a request, or accepts theirs if they already asked. */
    private fun askFriend() {
        val field =
            field(R.string.aq_friends_add_hint, AnkiquestSocial.MAX_USER).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        ask(R.string.aq_friends_add, field, R.string.aq_friends_add_send) { typed ->
            val user = AnkiquestSocial.userName(typed) ?: return@ask R.string.aq_friends_add_empty
            change(AnkiquestSocial.Change.Befriend(user), { answer ->
                answer.friends
                    .firstOrNull {
                        it.user.equals(
                            user,
                            ignoreCase = true,
                        )
                    }?.let { getString(R.string.aq_friends_accepted, it.display) }
                    ?: getString(R.string.aq_friends_request_sent, user)
            })
            null
        }
    }

    /** Asks for a new group's name, or a new name for [group]. */
    private fun askGroupName(group: AnkiquestSocial.Group?) {
        val field = field(R.string.aq_friends_group_name, AnkiquestSocial.MAX_GROUP_NAME).apply { group?.let { setText(it.name) } }
        val title = if (group == null) R.string.aq_friends_group_create else R.string.aq_friends_rename
        ask(title, field, if (group == null) R.string.aq_friends_group_create_send else R.string.aq_friends_rename) { typed ->
            val name = AnkiquestSocial.groupName(typed) ?: return@ask R.string.aq_friends_group_name_bad
            if (group == null) {
                change(AnkiquestSocial.Change.CreateGroup(name), { getString(R.string.aq_friends_group_created, name) })
            } else {
                change(AnkiquestSocial.Change.RenameGroup(group.id, name), { getString(R.string.aq_friends_renamed, name) })
            }
            null
        }
    }

    /** Asks for an invite link or code, then shows what it joins before joining. */
    private fun askInvite() {
        val current = account ?: return
        val field =
            field(R.string.aq_friends_group_join_hint, 512).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        ask(R.string.aq_friends_group_join, field, R.string.aq_friends_group_join_next) { typed ->
            val code = AnkiquestSocial.inviteCode(current, typed) ?: return@ask R.string.aq_friends_group_join_bad
            invite = code
            preview = null
            inviteGone = false
            status = null
            load()
            scroll.post { scroll.scrollTo(0, 0) }
            null
        }
    }

    /**
     * A dialog around [field] that closes only once [go] accepts what was typed; otherwise it
     * shows the message [go] answers and keeps the text.
     */
    private fun ask(
        @StringRes title: Int,
        field: EditText,
        @StringRes yes: Int,
        go: (String) -> Int?,
    ) {
        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(field)
            }
        val problem = text(form, "", small = true).apply { setTextColor(getColor(R.color.aq_home_primary)) }
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(yes, null)
                .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val refused = go(field.text.toString())
            if (refused != null) {
                problem.text = getString(refused)
            } else {
                dialog.dismiss()
            }
        }
        field.requestFocus()
    }

    private fun field(
        @StringRes hint: Int,
        limit: Int,
    ): EditText =
        EditText(this).apply {
            setHint(hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(limit))
        }

    private fun confirm(
        question: String,
        @StringRes yes: Int,
        go: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(this)
            .setMessage(question)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(yes) { _, _ -> go() }
            .show()
    }

    private fun dismissInvite() {
        invite = null
        preview = null
        inviteGone = false
        render()
    }

    private fun shareInvite(
        group: AnkiquestSocial.Group,
        link: String,
    ) {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.aq_friends_invite_share_text, group.name, link))
        startActivity(Intent.createChooser(send, getString(R.string.aq_friends_share_link)))
    }

    private fun openProfile(person: AnkiquestSocial.Person) = startActivity(AnkiquestProfileActivity.intent(this, person.user))

    private fun openFriendGoals() = startActivity(AnkiquestChallengesActivity.intent(this))

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun members(count: Int): String = resources.getQuantityString(R.plurals.aq_friends_members, count, count)

    private fun section(
        title: String,
        hint: String,
    ) {
        text(content, title, heading = true).setPadding(0, dp(16), 0, 0)
        text(content, hint, small = true)
    }

    /** A card for [person]: picture or initials, display name and username; actions go below. */
    private fun person(
        parent: LinearLayout,
        person: AnkiquestSocial.Person,
        tinted: Boolean = false,
    ): LinearLayout {
        val panel = card(parent, tinted)
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(avatar(person), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        text(names, person.display).typeface = Typeface.DEFAULT_BOLD
        if (person.display != person.user) text(names, person.user, small = true)
        return panel
    }

    /** The profile picture, or initials on a ring coloured from the username as on the website. */
    private fun avatar(person: AnkiquestSocial.Person): View {
        AnkiquestAvatars.bitmap(person.user)?.let { picture ->
            return ImageView(this).apply {
                setImageBitmap(picture)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        return TextView(this).apply {
            text = initials(person.display)
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.aq_home_text))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.aq_home_background))
                    setStroke(dp(2), ColorUtils.HSLToColor(floatArrayOf(hue(person.user), 0.55f, 0.55f)))
                }
        }
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
            setOnClickListener { click() }
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
                    topMargin = dp(8)
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
        /** The account a notification was for, as [HomeAccount.notificationAccount]; another account's list says so. */
        const val EXTRA_ACCOUNT = "ankiquest.friends_account"

        /** An invite code from a `/join/<code>` link, to confirm before joining. */
        const val EXTRA_INVITE = "ankiquest.invite"

        private const val STATE_INVITE = "invite"

        /** Not a message: [attempt]'s answer when the account changed under a request. */
        private const val ACCOUNT_CHANGED = -1

        /** Refusals whose `{"error"}` explains what to do. */
        private val REFUSALS = setOf(400, 403, 404, 409)

        fun intent(
            context: Context,
            account: String? = null,
            invite: String? = null,
        ): Intent =
            Intent(context, AnkiquestFriendsActivity::class.java).apply {
                account?.let { putExtra(EXTRA_ACCOUNT, it) }
                invite?.let { putExtra(EXTRA_INVITE, it) }
            }

        /** One or two letters for [display], as the website's avatars show. */
        internal fun initials(display: String): String =
            display
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .take(2)
                .joinToString("") {
                    it
                        .codePoints()
                        .findFirst()
                        .orElse('?'.code)
                        .let(Character::toChars)
                        .concatToString()
                }.uppercase()
                .ifEmpty { "?" }

        /** The website's hue for [user]: a 31-based hash of its characters. */
        internal fun hue(user: String): Float {
            var hash = 0
            for (char in user) hash = hash * 31 + char.code
            return (abs(hash.toLong()) % 360).toFloat()
        }
    }
}
