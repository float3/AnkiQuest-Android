// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CommonString
import com.ichi2.anki.R

/** Offers the inverse only after success, while the same account and screen still own the action. */
internal class AnkiquestUndo(
    private val activity: AnkiActivity,
) : DefaultLifecycleObserver {
    private var generation = 0
    private var snackbar: Snackbar? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    fun apply(
        account: HomeAccount,
        message: String,
        perform: (() -> Unit) -> Unit,
        inverse: () -> Unit,
    ) {
        clear()
        if (!owns(account)) {
            return
        }
        val turn = generation
        perform {
            if (turn == generation && owns(account)) {
                val notice = Snackbar.make(activity.findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_LONG)
                notice.anchorView = activity.findViewById<View>(R.id.ankiquest_tab_bar)?.takeIf { it.isShown }
                snackbar = notice
                notice.setAction(CommonString.undo) {
                    if (turn == generation && snackbar === notice && notice.isShown && owns(account)) {
                        clear()
                        inverse()
                    }
                }
                notice.addCallback(
                    object : Snackbar.Callback() {
                        override fun onDismissed(
                            transientBottomBar: Snackbar?,
                            event: Int,
                        ) {
                            if (snackbar === transientBottomBar) {
                                snackbar = null
                                generation++
                            }
                        }
                    },
                )
                notice.show()
            }
        }
    }

    fun clear() {
        generation++
        snackbar?.dismiss()
        snackbar = null
    }

    override fun onStop(owner: LifecycleOwner) = clear()

    private fun owns(account: HomeAccount): Boolean =
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && AnkiquestHomeData.account()?.scope == account.scope
}
