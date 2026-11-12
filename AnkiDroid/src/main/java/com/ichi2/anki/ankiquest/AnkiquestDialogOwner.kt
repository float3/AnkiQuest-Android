// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/** Fetch -> dialog -> save stays with one account and one uninterrupted foreground visit. */
internal class AnkiquestDialogOwner(
    activity: FragmentActivity,
    private val account: HomeAccount,
    private val lifecycle: Lifecycle = activity.lifecycle,
) : DefaultLifecycleObserver {
    private sealed interface State {
        data object Awaiting : State

        data class Showing(
            val dialog: AlertDialog,
            val notice: AlertDialog? = null,
        ) : State

        data object Closed : State
    }

    private val host = WeakReference(activity)
    private var state: State = State.Awaiting

    init {
        lifecycle.addObserver(this)
    }

    fun isCurrent(): Boolean =
        state != State.Closed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            host.get()?.let(AnkiquestPostStudyPrompts::resumed) == true && AnkiquestHomeData.account()?.scope == account.scope

    fun show(dialog: AlertDialog) {
        if (!isCurrent()) {
            close()
            return
        }
        val previous = state as? State.Showing
        state = State.Showing(dialog)
        previous?.notice?.dismiss()
        previous?.dialog?.dismiss()
        dialog.setOnDismissListener {
            if ((state as? State.Showing)?.dialog === dialog) {
                close()
            }
        }
        dialog.show()
    }

    /** Validation can cover the picker without losing its selection or outliving its owner. */
    fun explain(dialog: AlertDialog) {
        val previous = state as? State.Showing ?: return
        if (!isCurrent()) {
            close()
            return
        }
        val showing = State.Showing(previous.dialog, dialog)
        state = showing
        previous.notice?.dismiss()
        dialog.setOnDismissListener {
            if (state === showing) {
                state = previous.copy(notice = null)
            }
        }
        dialog.show()
    }

    /** A retained or twice-tapped button cannot advance a different dialog or repeat a save. */
    fun take(dialog: DialogInterface): Boolean {
        val showing = state as? State.Showing ?: return false
        if (showing.dialog !== dialog || showing.notice != null) {
            return false
        }
        if (!isCurrent()) {
            close()
            return false
        }
        state = State.Awaiting
        showing.dialog.dismiss()
        return true
    }

    fun close() {
        val previous = state as? State.Showing
        state = State.Closed
        lifecycle.removeObserver(this)
        previous?.notice?.dismiss()
        previous?.dialog?.dismiss()
    }

    override fun onStop(owner: LifecycleOwner) = close()
}
