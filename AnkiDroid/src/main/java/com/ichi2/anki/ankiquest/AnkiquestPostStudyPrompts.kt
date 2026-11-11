// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference

/** The recap, moments, deck share and automatic update offer share one dialog slot on the main thread. */
internal object AnkiquestPostStudyPrompts {
    private var showing = WeakReference<Dialog>(null)

    fun resumed(host: Activity): Boolean =
        host is LifecycleOwner && host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !host.isFinishing && !host.isDestroyed

    fun ready(host: Activity): Boolean = resumed(host) && showing.get()?.isShowing != true

    fun track(dialog: Dialog) {
        // A pause must not discard a moment already consumed from its queue.
        showing = WeakReference(dialog)
    }
}
