// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.work.WorkManager
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.worker.SyncWorker

/** The mood alias starts here in its own task; the library and reviewer share DeckPicker's stable task. */
internal class AnkiquestLauncher : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preserve IntentHandler's cold-launch behavior without cancelling a sync in the existing task.
        if (WorkManager.isInitialized() && !hasRunningTask()) {
            SyncWorker.cancel(this)
        }

        startActivity(
            Intent(this, DeckPicker::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    @Suppress("DEPRECATION")
    private fun hasRunningTask(): Boolean =
        getSystemService(ActivityManager::class.java).appTasks.any { task ->
            val info = task.taskInfo ?: return@any false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isRunning && info.taskId != taskId
            } else {
                info.id >= 0 && info.id != taskId
            }
        }
}
