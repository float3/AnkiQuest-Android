// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.R
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Contextual notification replies, or your own words. */
object AnkiquestReply {
    enum class Outcome { SENT, RETRY, FAILED }

    const val EXTRA_NOTIFICATION = "notification"
    const val EXTRA_TAG = "tag"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_REPLY_ACCOUNT = "ankiquest.reply_account"
    const val EXTRA_REPLY_SCOPE = "ankiquest.reply_scope"
    const val ACTION_REPLY_CUSTOM = "com.ichi2.anki.ankiquest.REPLY_CUSTOM"

    const val ATTEMPTS = 3
    const val MAX_MESSAGE = 200

    private const val WORK_NAME = "ankiquestReply"

    /** One Reply action: its first suggestion is the one-tap answer, and free text is always allowed. */
    fun actions(
        context: Context,
        notification: Long,
        tag: Int,
        title: String,
        body: String,
        account: String?,
        scope: String?,
        kind: String = "completion",
    ): List<NotificationCompat.Action> {
        val current = AnkiquestHomeData.account() ?: return emptyList()
        if (scope.isNullOrEmpty() || current.scope != scope || current.notificationAccount != account) return emptyList()
        val quick =
            when (kind) {
                "completion", "share" -> AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_cheer)
                "reply" -> AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_thanks)
                "nudge" -> AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_on_it)
                else -> null
            }
        val suggestions =
            when (kind) {
                "completion", "share" -> AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_choices)
                "reply" -> AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_thanks_choices)
                "nudge" -> AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_nudge_choices)
                else -> emptyArray()
            }
        val choices = listOfNotNull(quick).toTypedArray<CharSequence>() + suggestions
        val intent =
            Intent(context, AnkiquestReplyReceiver::class.java)
                .setAction(ACTION_REPLY_CUSTOM)
                .putExtra(EXTRA_NOTIFICATION, notification)
                .putExtra(EXTRA_TAG, tag)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_REPLY_ACCOUNT, account)
                .putExtra(EXTRA_REPLY_SCOPE, scope)
        val reply =
            RemoteInput
                .Builder(EXTRA_MESSAGE)
                .setLabel(AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_hint))
                .setChoices(choices)
                .build()
        return listOf(
            NotificationCompat.Action
                .Builder(
                    R.drawable.ic_star_notify,
                    AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply),
                    PendingIntent.getBroadcast(
                        context,
                        tag,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ).addRemoteInput(reply)
                .setAllowGeneratedReplies(false)
                .build(),
        )
    }

    fun send(
        context: Context,
        data: Data,
    ) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME:${data.getString(EXTRA_REPLY_SCOPE)}:${data.getInt(EXTRA_TAG, 0)}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AnkiquestReplyWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(data)
                    .build(),
            )
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not queue a reply")
        }
    }

    /** The chosen or typed text; notifications posted by older versions carry a cheer as an extra instead. */
    fun message(intent: Intent): String =
        (
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_MESSAGE)?.toString()
                ?: intent.getStringExtra(EXTRA_MESSAGE)
        ).orEmpty()
            .trim()
            .take(MAX_MESSAGE)

    fun data(
        intent: Intent,
        message: String,
    ): Data =
        Data
            .Builder()
            .putLong(EXTRA_NOTIFICATION, intent.getLongExtra(EXTRA_NOTIFICATION, 0))
            .putInt(EXTRA_TAG, intent.getIntExtra(EXTRA_TAG, 0))
            .putString(EXTRA_TITLE, intent.getStringExtra(EXTRA_TITLE))
            .putString(EXTRA_BODY, intent.getStringExtra(EXTRA_BODY))
            .putString(EXTRA_KIND, intent.getStringExtra(EXTRA_KIND) ?: "completion")
            .putString(EXTRA_MESSAGE, message)
            .putString(EXTRA_REPLY_ACCOUNT, intent.getStringExtra(EXTRA_REPLY_ACCOUNT))
            .putString(EXTRA_REPLY_SCOPE, intent.getStringExtra(EXTRA_REPLY_SCOPE))
            .build()

    suspend fun run(
        context: Context,
        data: Data,
        attempt: Int,
    ): Outcome {
        val message = data.getString(EXTRA_MESSAGE).orEmpty()
        val notification = data.getLong(EXTRA_NOTIFICATION, 0)
        val account = data.getString(EXTRA_REPLY_ACCOUNT).orEmpty()
        val scope = data.getString(EXTRA_REPLY_SCOPE).orEmpty()
        if (message.isEmpty() || notification <= 0 || account.isEmpty() || scope.isEmpty() || !current(data)) return Outcome.FAILED
        return try {
            AnkiquestNotifier.onReplySent(context, data, Ankiquest.reply(notification, message, account, scope))
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never republish the old account's private notification after a switch.
            if (!current(data)) return Outcome.FAILED
            Timber.w(e, "ankiquest reply failed")
            // A refused reply stays refused; only a broken connection is worth another try.
            val done = e is Ankiquest.Rejected || attempt + 1 >= ATTEMPTS
            if (done) AnkiquestNotifier.onReplyFailed(context, data)
            if (done) Outcome.FAILED else Outcome.RETRY
        }
    }

    internal fun current(data: Data): Boolean {
        val account = AnkiquestHomeData.account() ?: return false
        return account.token.isNotEmpty() && account.notificationAccount == data.getString(EXTRA_REPLY_ACCOUNT) &&
            account.scope == data.getString(EXTRA_REPLY_SCOPE)
    }
}

class AnkiquestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val message = AnkiquestReply.message(intent)
        if (message.isEmpty()) return
        AnkiquestReply.send(context, AnkiquestReply.data(intent, message))
    }
}

class AnkiquestReplyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (AnkiquestReply.run(applicationContext, inputData, runAttemptCount)) {
            AnkiquestReply.Outcome.SENT -> Result.success()
            AnkiquestReply.Outcome.RETRY -> Result.retry()
            AnkiquestReply.Outcome.FAILED -> Result.failure()
        }
}
