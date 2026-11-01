// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the widget's refresh button. The widget providers must be exported for the launcher,
 * so the refresh lives here, unexported, where only the app's own PendingIntent can reach it.
 */
class AnkiquestWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        AnkiquestWidget.refresh(context)
    }
}
