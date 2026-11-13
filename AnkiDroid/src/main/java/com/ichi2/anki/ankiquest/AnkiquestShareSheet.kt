// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Bitmap
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestMomentShare.Choice
import com.ichi2.anki.ankiquest.AnkiquestMomentShare.Target
import com.ichi2.anki.ankiquest.AnkiquestSharing.MomentRef
import com.ichi2.anki.ankiquest.AnkiquestSharing.Sent
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection.HTTP_CONFLICT

/**
 * The one share sheet every Share button opens on servers with [AnkiquestSharing.FEATURE_MOMENTS]:
 * the moment's picture and words, AnkiQuest friends and groups to send it to with a note, and
 * "More apps…" for Android's share sheet ([elsewhere]). Sending closes the sheet and offers Undo
 * while the server allows it.
 */
internal class AnkiquestShareSheet(
    private val host: FragmentActivity,
    private val account: HomeAccount,
    private val moment: MomentRef,
    private val card: AnkiquestShare.Card,
    private val elsewhere: () -> Unit,
    private val sent: (Sent) -> Unit = {},
    private val closed: (Exit) -> Unit = {},
    root: () -> View = { host.findViewById(android.R.id.content) },
) {
    /** How the sheet was left; [SENT] is reported once its Undo is gone. */
    enum class Exit { CANCELLED, ELSEWHERE, SENT }

    /** Where sending stands while friends and groups are being picked. */
    enum class Step { CHOOSING, SENDING, FAILED, ACCOUNT_CHANGED }

    /** Everything the sheet shows apart from typed text. */
    sealed class State {
        data object Loading : State()

        /** No friends or groups yet: only an invitation and other apps. */
        data object Lonely : State()

        /** Friends could not be loaded; other apps still work. */
        data object Offline : State()

        data class Picking(
            val targets: List<Target>,
            val choice: Choice,
            val step: Step,
        ) : State()
    }

    private val language = AnkiquestLanguage.context(host)
    private val views = AnkiquestBoardViews(host)
    private val jobs = SupervisorJob(host.lifecycleScope.coroutineContext[Job])
    private val scope = CoroutineScope(host.lifecycleScope.coroutineContext + jobs)
    private val undo = AnkiquestUndo(host, root)
    private val dialog = BottomSheetDialog(host)
    private val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val preview = ImageView(host)
    private val targets = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val search = field(R.id.aq_share_search, R.string.ankiquest_share_search)
    private val note = field(R.id.aq_share_note, R.string.ankiquest_share_note_hint)
    private lateinit var status: TextView
    private lateinit var send: MaterialButton
    private lateinit var more: MaterialButton
    private var exit = Exit.CANCELLED

    var state: State = State.Loading
        private set

    fun show() {
        build()
        dialog.setContentView(NestedScrollView(host).apply { addView(content) })
        dialog.setOnDismissListener {
            jobs.cancel()
            if (exit == Exit.CANCELLED) {
                closed(Exit.CANCELLED)
            }
        }
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        render()
        drawPreview()
        load()
    }

    /** The sheet's fixed parts: preview, the place for friends, the note, the status and the actions. */
    private fun build() {
        val padding = views.dp(PADDING_DP)
        content.setPadding(padding, padding, padding, padding)
        content.setBackgroundColor(host.getColor(R.color.aq_home_background))
        views.text(content, language.getString(R.string.ankiquest_share_title), heading = true)

        // The picture as it will be shared, beside its words.
        val row = views.card(content, horizontal = true)
        val side = views.dp(PREVIEW_DP)
        preview.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(preview, LinearLayout.LayoutParams(side, side))
        val words = views.column(row)
        views.text(words, card.title, heading = true).apply { ViewCompat.setAccessibilityHeading(this, false) }
        views.text(words, card.body, small = true).isVisible = card.body.isNotEmpty()

        // Friends and groups, then the note.
        views.section(content, language.getString(R.string.ankiquest_share_send_to))
        content.addView(search, wide())
        search.doAfterTextChanged { render() }
        content.addView(targets, wide())
        note.filters = arrayOf(InputFilter.LengthFilter(AnkiquestSharing.MAX_NOTE))
        note.imeOptions = EditorInfo.IME_ACTION_DONE
        content.addView(note, wide())

        status =
            views.text(content, "", small = true).apply {
                id = R.id.aq_share_status
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        send = views.button(content, language.getString(R.string.aq_feed_send), primary = true) { submit() }
        send.id = R.id.aq_share_send
        more = views.button(content, language.getString(R.string.ankiquest_share_more_apps)) { leave() }
        more.id = R.id.aq_share_more
    }

    /** A one-line text field with [hint] as its label. */
    private fun field(
        id: Int,
        @StringRes hint: Int,
    ): AppCompatEditText =
        AppCompatEditText(host).apply {
            this.id = id
            this.hint = language.getString(hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Draws the picture on the phone; the server's picture only goes to other apps. */
    private fun drawPreview() {
        scope.launch {
            val picture: Bitmap? =
                withContext(Dispatchers.Default) {
                    try {
                        AnkiquestShare.render(host, card)
                    } catch (e: RuntimeException) {
                        Timber.w(e, "AnkiQuest share sheet: no preview")
                        null
                    }
                }
            preview.setImageBitmap(picture)
            preview.isVisible = picture != null
        }
    }

    /** Friends and groups from the server, those chosen lately first. */
    private fun load() {
        scope.launch {
            state =
                try {
                    val found = AnkiquestMomentShare.targets(account)
                    if (found.isEmpty()) State.Lonely else State.Picking(found, Choice.Nobody, Step.CHOOSING)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest share sheet: friends unavailable")
                    State.Offline
                }
            render()
        }
    }

    private fun update(next: State) {
        state = next
        render()
    }

    /** Shows [state]: the friends' part, the status line and which actions can be used. */
    private fun render() {
        val current = state
        val picking = current as? State.Picking
        targets.removeAllViews()
        when (current) {
            is State.Loading -> views.text(targets, language.getString(R.string.ankiquest_share_loading), small = true)
            is State.Offline -> views.text(targets, language.getString(R.string.ankiquest_share_targets_failed), small = true)
            is State.Lonely -> lonely()
            is State.Picking -> chips(current)
        }
        search.isVisible = picking != null
        note.isVisible = picking != null
        send.isVisible = picking != null

        // While sending, nothing can change and the sheet stays open until the server answers.
        val sending = picking?.step == Step.SENDING
        dialog.setCancelable(!sending)
        search.isEnabled = !sending
        note.isEnabled = !sending
        more.isEnabled = !sending
        send.isEnabled = picking != null && picking.choice != Choice.Nobody && !sending
        val message =
            when (picking?.step) {
                Step.SENDING -> R.string.ankiquest_share_sending
                Step.FAILED -> R.string.ankiquest_share_failed
                Step.ACCOUNT_CHANGED -> R.string.ankiquest_share_account_changed
                Step.CHOOSING, null -> null
            }
        status.text = message?.let { language.getString(it) }.orEmpty()
        status.isVisible = message != null
    }

    /** "All friends", then every friend and group that matches the search. */
    private fun chips(picking: State.Picking) {
        val enabled = picking.step != Step.SENDING
        val group = ChipGroup(host).apply { id = R.id.aq_share_targets }
        group.addView(
            views.toggle(language.getString(R.string.ankiquest_share_all_friends), picking.choice == Choice.Everyone, enabled) {
                choose(picking.choice.toggleEveryone())
            },
        )
        val query = search.text?.toString().orEmpty()
        val shown = picking.targets.filter { AnkiquestMomentShare.matches(it, query) }
        val picked = (picking.choice as? Choice.Picked)?.targets.orEmpty()
        for (target in shown) {
            val chip = views.toggle(target.label, target in picked, enabled) { choose(picking.choice.toggle(target)) }
            if (target is Target.Group) {
                chip.contentDescription = language.getString(R.string.ankiquest_share_group, target.label)
            }
            group.addView(chip)
        }
        targets.addView(group, wide())
        if (shown.isEmpty()) {
            views.text(targets, language.getString(R.string.ankiquest_share_no_match), small = true)
        }
    }

    private fun choose(choice: Choice) {
        val picking = state as? State.Picking ?: return
        if (picking.step == Step.SENDING) {
            return
        }
        update(picking.copy(choice = choice, step = Step.CHOOSING))
    }

    /** Nobody to send to yet: friends are added on the Friends screen. */
    private fun lonely() {
        views.text(targets, language.getString(R.string.ankiquest_share_lonely), small = true)
        views.button(targets, language.getString(R.string.ankiquest_share_invite)) {
            host.startActivity(AnkiquestFriendsActivity.intent(host))
            dialog.dismiss()
        }
    }

    /** "More apps…": Android's share sheet, as before. */
    private fun leave() {
        exit = Exit.ELSEWHERE
        dialog.dismiss()
        elsewhere()
        closed(Exit.ELSEWHERE)
    }

    private fun submit() {
        val picking = state as? State.Picking ?: return
        if (picking.choice == Choice.Nobody || picking.step == Step.SENDING) {
            return
        }
        val words = note.text?.toString().orEmpty()
        update(picking.copy(step = Step.SENDING))
        scope.launch {
            val step =
                try {
                    val answer = AnkiquestMomentShare.send(account, moment, picking.choice, words)
                    exit = Exit.SENT
                    dialog.dismiss()
                    sent(answer)
                    announce(answer)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HomeAccountChanged) {
                    Step.ACCOUNT_CHANGED
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest share sheet: sending failed")
                    Step.FAILED
                }
            update(picking.copy(step = step))
        }
    }

    /** Says who got it, with Undo while the server can still take it back. */
    private fun announce(answer: Sent) {
        val message =
            if (answer.recipients == 0) {
                language.getString(R.string.ankiquest_share_nobody)
            } else {
                language.resources.getQuantityString(R.plurals.ankiquest_share_sent, answer.recipients, answer.recipients)
            }
        if (!answer.undoable(TimeManager.time.intTimeMS())) {
            showThemedToast(host, message, shortLength = false)
            closed(Exit.SENT)
            return
        }
        undo.apply(
            account,
            message,
            perform = { done -> done() },
            inverse = { retract(answer.win) },
            closed = { closed(Exit.SENT) },
        )
    }

    /** Takes the moment back; it outlives the screen, which may close as Undo is pressed. */
    private fun retract(win: Long) {
        val context = host.applicationContext
        applicationScope.launch {
            val message =
                try {
                    AnkiquestMomentShare.retract(account, win)
                    R.string.aq_conquest_untold
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AnkiquestHttpException) {
                    // Too late once every row sent by hand is older than a day.
                    Timber.w(e, "AnkiQuest share sheet: taking back refused")
                    if (e.code == HTTP_CONFLICT) R.string.aq_conquest_untell_late else R.string.ankiquest_share_failed
                } catch (_: HomeAccountChanged) {
                    R.string.ankiquest_share_account_changed
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest share sheet: taking back failed")
                    R.string.ankiquest_share_failed
                }
            withContext(Dispatchers.Main) {
                showThemedToast(context, AnkiquestLanguage.context(context).getString(message), shortLength = false)
            }
        }
    }

    private companion object {
        const val PADDING_DP = 16
        const val PREVIEW_DP = 96
    }
}
