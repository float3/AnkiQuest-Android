// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BuildConfig
import com.ichi2.widget.getAppWidgetManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

/** Share only local diagnostic fields that are safe to put in a public report. */
internal object AnkiquestDiagnostics {
    suspend fun describe(context: Context): String =
        buildString {
            appendLine("AnkiQuest Release = ${BuildConfig.ANKIQUEST_RELEASE}")
            appendLine("AnkiQuest Nightly = ${BuildConfig.ANKIQUEST_NIGHTLY}")
            appendLine("AnkiQuest Commit = ${BuildConfig.GIT_COMMIT_HASH}")

            // Android can request the crash report before application startup during backup recovery.
            val prefs = AnkiDroidApp.sharedPrefsOrNull()
            if (prefs == null) {
                append("AnkiQuest settings = unavailable before startup")
                return@buildString
            }

            // Print derived values only. Server responses and exceptions can contain account fields.
            val account = HomeAccount.current(prefs)
            val host = prefs.getString(Ankiquest.PREF_URL, null)?.toHttpUrlOrNull()?.host
            val tokenPresent = !prefs.getString(Ankiquest.PREF_TOKEN, null).isNullOrBlank()
            val signedIn = account != null && tokenPresent
            val upload = account?.takeIf { signedIn }?.let { Ankiquest.uploadDiagnostics(it) }
            val version = account?.let(AnkiquestCapabilities::apiVersion)
            val poll = account?.let { AnkiquestPoll.lastSuccessfulPoll(prefs, it.scope) }

            appendLine("Update channel = ${AnkiquestUpdater.channel().name.lowercase()}")
            appendLine("Server host = ${host ?: "not configured"}")
            appendLine("Signed in = $signedIn")
            appendLine("Device token present = $tokenPresent")
            appendLine("Server API version = ${version ?: "unknown"}")
            appendLine("Last upload attempt = ${timestamp(upload?.at)}")
            appendLine("Last upload problem = ${upload?.problem?.name ?: "none recorded"}")
            appendLine("Unsent reviews = ${upload?.pending ?: "unknown"}")
            appendLine("Last successful background poll = ${timestamp(poll)}")
            appendLine("Notification permission = ${notificationPermission(context)}")
            appendLine(
                "Notification channels = messages: ${AnkiquestNotifier.alertsEnabled(context, nudge = false)}, " +
                    "nudges: ${AnkiquestNotifier.alertsEnabled(context, nudge = true)}",
            )
            appendLine("Mood icon enabled = ${AnkiquestIcon.available(context) && AnkiquestIcon.enabled(prefs)}")
            appendLine("Current icon mood = ${AnkiquestIcon.current(prefs)?.name ?: "unknown"}")
            appendLine("Widgets = ${widgets(context)}")
            append("Companion = ${AnkiquestCompanion.selected()}")
        }

    private fun timestamp(at: Long?): String = at?.let { Instant.ofEpochMilli(it).toString() } ?: "not recorded"

    private fun notificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun widgets(context: Context): String {
        val manager = getAppWidgetManager(context) ?: return "unavailable"
        val providers =
            mapOf(
                "streak" to AnkiquestStreakWidget::class.java,
                "leaderboard" to AnkiquestWidget::class.java,
                "transparent leaderboard" to AnkiquestTransparentWidget::class.java,
            )
        return providers.entries.joinToString(", ") { (name, provider) ->
            "$name: ${manager.getAppWidgetIds(ComponentName(context, provider)).size}"
        }
    }
}
