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

/** Contextual notification replies, your own words, or a 🎉 cheer on a friend's win. */
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

    /** The notification is a friend's win kept by the server, which is cheered with 🎉 rather than suggested words. */
    const val EXTRA_WIN = "ankiquest.win"

    /** The queued work is a cheer rather than a message. */
    const val EXTRA_REACT = "ankiquest.react"
    const val ACTION_REPLY_CUSTOM = "com.ichi2.anki.ankiquest.REPLY_CUSTOM"
    const val ACTION_CHEER = "com.ichi2.anki.ankiquest.CHEER"

    const val ATTEMPTS = 3
    const val MAX_MESSAGE = 200

    private const val WORK_NAME = "ankiquestReply"

    /**
     * One Reply action: its first suggestion is the one-tap answer, and free text is always allowed.
     * A friend's [win] kept by the server is answered in the player's own words, and while [cheer] it
     * also gets a 🎉 button in place of the suggested words; without [reply] that button is all there is.
     */
    fun actions(
        context: Context,
        notification: Long,
        tag: Int,
        title: String,
        body: String,
        account: String?,
        scope: String?,
        kind: String = "completion",
        win: Boolean = false,
        cheer: Boolean = win,
        reply: Boolean = true,
    ): List<NotificationCompat.Action> {
        val current = AnkiquestHomeData.account() ?: return emptyList()
        if (scope.isNullOrEmpty() || current.scope != scope || current.notificationAccount != account) return emptyList()
        val language = AnkiquestLanguage.context(context)
        val quick =
            when {
                win -> null
                kind == "completion" || kind == "share" -> language.getString(R.string.ankiquest_reply_cheer)
                kind == "reply" || kind == "reaction" -> language.getString(R.string.ankiquest_reply_thanks)
                kind == "nudge" -> language.getString(R.string.ankiquest_reply_on_it)
                else -> null
            }
        val suggestions =
            when {
                win -> emptyArray()
                kind == "completion" || kind == "share" -> language.resources.getStringArray(R.array.ankiquest_reply_choices)
                kind == "reply" || kind == "reaction" -> language.resources.getStringArray(R.array.ankiquest_reply_thanks_choices)
                kind == "nudge" -> language.resources.getStringArray(R.array.ankiquest_reply_nudge_choices)
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
                .putExtra(EXTRA_WIN, win)
        val cheerAction =
            if (cheer) {
                NotificationCompat.Action
                    .Builder(
                        R.drawable.ic_star_notify,
                        language.getString(R.string.ankiquest_cheer),
                        PendingIntent.getBroadcast(
                            context,
                            tag,
                            Intent(intent)
                                .setAction(ACTION_CHEER)
                                .putExtra(EXTRA_MESSAGE, language.getString(R.string.ankiquest_cheer)),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).build()
            } else {
                null
            }
        if (!reply) return listOfNotNull(cheerAction)
        val input =
            RemoteInput
                .Builder(EXTRA_MESSAGE)
                .setLabel(language.getString(R.string.ankiquest_reply_hint))
                .setChoices(choices)
                .build()
        return listOfNotNull(
            cheerAction,
            NotificationCompat.Action
                .Builder(
                    R.drawable.ic_star_notify,
                    language.getString(R.string.ankiquest_reply),
                    PendingIntent.getBroadcast(
                        context,
                        tag,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ).addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build(),
        )
    }

    fun send(
        context: Context,
        data: Data,
    ) {
        val name = if (data.getBoolean(EXTRA_REACT, false)) "$WORK_NAME:react" else WORK_NAME
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$name:${data.getString(EXTRA_REPLY_SCOPE)}:${data.getInt(EXTRA_TAG, 0)}",
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

    /** The chosen or typed text; the 🎉 button, and notifications posted by older versions, carry it as an extra instead. */
    fun message(intent: Intent): String =
        clean(
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_MESSAGE)?.toString()
                ?: intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
        )

    /**
     * [text] as the server accepts a message: control characters such as line breaks become spaces,
     * and it is cut to [MAX_MESSAGE] UTF-8 bytes without splitting a character.
     */
    fun clean(text: String): String {
        val spaced =
            buildString {
                var i = 0
                while (i < text.length) {
                    val point = text.codePointAt(i)
                    if (Character.isISOControl(point)) append(' ') else appendCodePoint(point)
                    i += Character.charCount(point)
                }
            }.trim()
        var bytes = 0
        var end = 0
        while (end < spaced.length) {
            val point = spaced.codePointAt(end)
            bytes +=
                when {
                    point < 0x80 -> 1
                    point < 0x800 -> 2
                    point < 0x10000 -> 3
                    else -> 4
                }
            if (bytes > MAX_MESSAGE) break
            end += Character.charCount(point)
        }
        return spaced.substring(0, end).trimEnd()
    }

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
            .putBoolean(EXTRA_WIN, intent.getBooleanExtra(EXTRA_WIN, false))
            .putBoolean(EXTRA_REACT, intent.action == ACTION_CHEER)
            .build()

    /**
     * Sends one queued reply or cheer. A refused one stays refused; only a broken connection is worth
     * another try. After an account switch the old account's private notification is never shown again.
     */
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
            if (data.getBoolean(EXTRA_REACT, false)) {
                Ankiquest.react(notification, account, scope)
                AnkiquestNotifier.onCheered(context, data)
            } else {
                AnkiquestNotifier.onReplySent(context, data, Ankiquest.reply(notification, message, account, scope))
            }
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!current(data)) return Outcome.FAILED
            Timber.w(e, "ankiquest reply failed")
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
