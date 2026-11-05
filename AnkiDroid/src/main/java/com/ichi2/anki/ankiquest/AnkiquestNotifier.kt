// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Data
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject

/** Leaderboard position and streak notifications. */
object AnkiquestNotifier {
    const val PREF_RANK = "ankiquestNotifyRank"
    const val PREF_STREAK_HOURS = "ankiquestStreakReminderHours"
    const val DEFAULT_STREAK_HOURS = "2"
    private const val STREAK_REMINDER_OFF = "0"

    private const val CHANNEL = "ankiquest"

    /** Keep nudges independently configurable, including channels saved by older versions. */
    private const val NUDGE_CHANNEL = "ankiquestNudges"
    private val BUZZ = longArrayOf(0, 250, 150, 250)
    private const val PREF_ORDER = "ankiquestLastOrder"

    @VisibleForTesting
    internal const val PREF_STREAK_DAY = "ankiquestStreakNotifiedDay"
    private const val RANK_ID = 5_130_001
    private const val STREAK_ID = 5_130_002
    private const val HOUR_MS = 60 * 60 * 1000L

    /**
     * Posts new inbox messages for [account]. Only the first contact discards an old backlog; unseen
     * messages never age out while offline or waiting for notification permission.
     */
    @Synchronized
    fun onDeckCompletions(
        context: Context,
        account: String,
        notifications: JSONArray,
        scope: String? = null,
    ) {
        if (scope != null && AnkiquestHomeData.account()?.scope != scope) return
        val prefs = AnkiDroidApp.sharedPrefs()
        val key = "ankiquestCompletionCursor:$account"
        val firstPollKey = "ankiquestCompletionFirstPollAt:$account"
        val entries = notifications.objects().sortedBy { it.getLong("id") }
        var previous = prefs.getLong(key, 0L)
        val language by lazy { AnkiquestLanguage.context(context) }
        val firstPoll =
            if (prefs.contains(firstPollKey)) {
                prefs.getLong(firstPollKey, 0L)
            } else {
                val start = if (prefs.contains(key)) 0L else TimeManager.time.intTimeMS() / 1000
                prefs.edit { putLong(firstPollKey, start) }
                start
            }
        var queued = false
        for (entry in entries) {
            val id = entry.getLong("id")
            if (id <= previous) continue
            val fresh = AnkiquestCompletionPolicy.freshNotification(entry.optLong("created_at"), firstPoll)
            // The player's own moments wait for the app while it is open, and are never shown twice.
            val route =
                if (fresh) {
                    AnkiquestMoments.route(prefs, account, entry, Ankiquest.inForeground())
                } else {
                    AnkiquestMoments.Route.NOTIFY
                }
            if (route != AnkiquestMoments.Route.NOTIFY) {
                queued = queued || route == AnkiquestMoments.Route.QUEUED
                previous = id
                prefs.edit { putLong(key, previous) }
                continue
            }
            val tag = 5_140_000 + (id % 1_000_000).toInt()
            val title = entry.getString("title")
            val body = entry.getString("body")
            val shownBody =
                entry.optJSONObject("reply_to")?.optString("body")?.takeIf { it.isNotBlank() }?.let { original ->
                    language.getString(R.string.ankiquest_reply_context, body, original)
                } ?: body
            val answerable = entry.optString("sender").isNotEmpty() && !entry.optBoolean("replied")
            val kind = entry.optString("kind", "completion")
            val win = entry.optLong("win").takeIf { it > 0 }
            val friendsWin = kind == "share" && win != null
            val cheerable = friendsWin && !entry.optBoolean("reacted")
            val card = entry.optString("image").takeIf { fresh && kind == "share" && it.isNotEmpty() }
            if (fresh &&
                notify(
                    context,
                    tag,
                    title,
                    shownBody,
                    notificationIntent(context, entry, account),
                    if (answerable || cheerable) {
                        AnkiquestReply.actions(
                            context,
                            id,
                            tag,
                            title,
                            shownBody,
                            account,
                            scope,
                            kind,
                            win = friendsWin,
                            cheer = cheerable,
                            reply = answerable,
                        )
                    } else if (AnkiquestShare.shareable(kind)) {
                        val (shareTitle, shareBody) = shareText(entry)
                        listOfNotNull(
                            AnkiquestShare.action(
                                context,
                                tag,
                                shareTitle,
                                shareBody,
                                win,
                                id,
                                null,
                                scope,
                                if (entry.isNull("topic")) null else entry.optString("topic"),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    kind == "nudge",
                    picture = card?.let { AnkiquestShare.picture(it) },
                    art = AnkiquestArt.forNotice(kind, entry.optString("topic").ifEmpty { null }),
                ) == Delivery.DISABLED
            ) {
                break
            }
            if (fresh) AnkiquestMoments.fromNotice(entry)?.let { AnkiquestMoments.markShown(prefs, account, listOf(it.key)) }
            previous = id
            prefs.edit { putLong(key, previous) }
        }
        if (queued) Ankiquest.presentMomentsSoon()
    }

    /** Asks the server how this player moved since the order stored here, and says so when they did. */
    suspend fun onLeaderboard(context: Context) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val scope = AnkiquestHomeData.account(prefs)?.scope ?: return
        val orderKey = scopedKey(PREF_ORDER, scope)
        val previous =
            prefs
                .getString(orderKey, null)
                ?.split(',')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val result = Ankiquest.rank(previous)
        val order = result.getJSONArray("order").strings()
        prefs.edit { putString(orderKey, order.joinToString(",")) }
        if (!rankAlerts(prefs)) return
        val change = result.optJSONObject("change") ?: return
        val title = change.getString("title")
        val body = change.getString("body")
        val climbed = climbedTo(previous, order, Ankiquest.player())
        val place = climbed?.takeIf { it <= 3 }
        val account = AnkiquestHomeData.account(prefs)?.notificationAccount
        if (climbed != null && account != null && Ankiquest.inForeground()) {
            // A climb while the app is open waits for the session to end, like every other moment.
            val now = TimeManager.time.intTimeMS()
            AnkiquestMoments.offer(prefs, account, listOf(AnkiquestMoments.rank(change, climbed, result.optLong("week"), now)))
            Ankiquest.presentMomentsSoon()
            return
        }
        val (shareTitle, shareBody) = shareText(change)
        notify(
            context,
            RANK_ID,
            title,
            body,
            AnkiquestLeaderboardActivity
                .intent(context, account = AnkiquestHomeData.account(prefs)?.notificationAccount)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            listOfNotNull(place?.let { AnkiquestShare.action(context, RANK_ID, shareTitle, shareBody, null, null, it, scope) }),
            art = place?.let(AnkiquestArt::place) ?: R.drawable.aki_face,
        )
    }

    /**
     * What sharing a rank change or one of the player's own [notice]s tells others: the server's
     * first-person `share_title` and `share_body` ("👑 I took the crown", "I reached level 4 …"),
     * or, when the server sends none, the notice itself.
     */
    internal fun shareText(notice: JSONObject): Pair<String, String> {
        val title = notice.getString("title")
        val body = notice.getString("body")
        return notice.optString("share_title").ifEmpty { title } to notice.optString("share_body").ifEmpty { body }
    }

    /** The 1-based place [me] climbed to between two leaderboard orders, or null when they did not climb. */
    internal fun climbedTo(
        previous: List<String>,
        order: List<String>,
        me: String?,
    ): Int? {
        val now = order.indexOf(me)
        val before = previous.indexOf(me)
        return if (now >= 0 && before > now) now + 1 else null
    }

    /** Posts the server's streak warning once per study day, within the chosen hours before it ends. */
    fun onProfile(
        context: Context,
        profile: JSONObject,
    ) {
        val prefs = AnkiDroidApp.sharedPrefs()
        AnkiquestDailyGoal.remember(profile, prefs)
        AnkiquestIcon.update(context, profile, TimeManager.time.intTimeMS(), prefs)
        val hours = prefs.getString(PREF_STREAK_HOURS, DEFAULT_STREAK_HOURS)?.toIntOrNull() ?: 0
        val warning = profile.optJSONObject("streak_warning")
        if (hours <= 0 || warning == null) return
        val dayEndsAt = profile.optLong("day_ends_at")
        val remaining = dayEndsAt - TimeManager.time.intTimeMS()
        if (remaining <= 0 || remaining > hours * HOUR_MS) return
        val dayKey = scopedKey(PREF_STREAK_DAY, AnkiquestHomeData.account(prefs)?.scope)
        if (prefs.getLong(dayKey, Long.MIN_VALUE) == dayEndsAt) return
        val open = AnkiquestHomeActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (notify(context, STREAK_ID, warning.getString("title"), warning.getString("body"), open) != Delivery.DISABLED) {
            prefs.edit { putLong(dayKey, dayEndsAt) }
        }
    }

    /** Replaces the answered notification with what was said, so the reply is visibly gone. */
    fun onReplySent(
        context: Context,
        data: Data,
        who: String,
    ) {
        if (!AnkiquestReply.current(data)) return
        val message = data.getString(AnkiquestReply.EXTRA_MESSAGE).orEmpty()
        notify(
            context,
            data.getInt(AnkiquestReply.EXTRA_TAG, 0),
            data.getString(AnkiquestReply.EXTRA_TITLE).orEmpty(),
            AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_sent, who, message),
            replyIntent(context, data),
            silent = true,
        )
    }

    /** Says the cheer went out; the friend's win can still be answered in words. */
    fun onCheered(
        context: Context,
        data: Data,
    ) {
        if (!AnkiquestReply.current(data)) return
        val tag = data.getInt(AnkiquestReply.EXTRA_TAG, 0)
        val title = data.getString(AnkiquestReply.EXTRA_TITLE).orEmpty()
        val body = data.getString(AnkiquestReply.EXTRA_BODY).orEmpty()
        notify(
            context,
            tag,
            title,
            AnkiquestLanguage.context(context).getString(R.string.ankiquest_cheer_sent, body),
            replyIntent(context, data),
            AnkiquestReply.actions(
                context,
                data.getLong(AnkiquestReply.EXTRA_NOTIFICATION, 0),
                tag,
                title,
                body,
                data.getString(AnkiquestReply.EXTRA_REPLY_ACCOUNT),
                data.getString(AnkiquestReply.EXTRA_REPLY_SCOPE),
                data.getString(AnkiquestReply.EXTRA_KIND) ?: "share",
                win = true,
                cheer = false,
            ),
            silent = true,
        )
    }

    /** Keeps the buttons so a reply that never left can be sent again. */
    fun onReplyFailed(
        context: Context,
        data: Data,
    ) {
        if (!AnkiquestReply.current(data)) return
        val tag = data.getInt(AnkiquestReply.EXTRA_TAG, 0)
        val title = data.getString(AnkiquestReply.EXTRA_TITLE).orEmpty()
        val body = data.getString(AnkiquestReply.EXTRA_BODY).orEmpty()
        val message = data.getString(AnkiquestReply.EXTRA_MESSAGE).orEmpty()
        notify(
            context,
            tag,
            title,
            AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_failed, message),
            replyIntent(context, data),
            AnkiquestReply.actions(
                context,
                data.getLong(AnkiquestReply.EXTRA_NOTIFICATION, 0),
                tag,
                title,
                body,
                data.getString(AnkiquestReply.EXTRA_REPLY_ACCOUNT),
                data.getString(AnkiquestReply.EXTRA_REPLY_SCOPE),
                data.getString(AnkiquestReply.EXTRA_KIND) ?: "completion",
                win = data.getBoolean(AnkiquestReply.EXTRA_WIN, false),
            ),
            silent = true,
        )
    }

    private fun replyIntent(
        context: Context,
        data: Data,
    ): Intent =
        AnkiquestFeedActivity
            .intent(context, data.getString(AnkiquestReply.EXTRA_REPLY_ACCOUNT))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** IDs and kinds become known screens; server-supplied URLs never become arbitrary app destinations. */
    internal fun notificationIntent(
        context: Context,
        entry: JSONObject,
        account: String,
    ): Intent {
        val destination =
            AnkiquestNavigation.notificationDestination(
                entry.optString("kind", "completion"),
                if (entry.isNull("topic")) null else entry.optString("topic"),
                entry.optLong("challenge_id"),
            )
        val open =
            when (destination) {
                is AnkiquestNavigation.Destination.Today ->
                    AnkiquestHomeActivity
                        .intent(context)
                        .apply { destination.focus?.let { putExtra(AnkiquestHomeActivity.EXTRA_FOCUS, it) } }
                AnkiquestNavigation.Destination.Activity -> AnkiquestFeedActivity.intent(context, account)
                AnkiquestNavigation.Destination.Friends -> AnkiquestFriendsActivity.intent(context, account)
                is AnkiquestNavigation.Destination.Challenges -> AnkiquestChallengesActivity.intent(context, destination.id, account)
                is AnkiquestNavigation.Destination.Leaderboard -> AnkiquestLeaderboardActivity.intent(context, destination.period, account)
            }
        return open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Existing channel behavior belongs to Android settings, not app updates. */
    fun alertSettingsIntent(
        context: Context,
        nudge: Boolean,
    ): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = ensureChannel(context, nudge)
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }

    /** Whether this phone warns before the streak ends; it does unless the player turned reminders off. */
    fun streakReminder(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean =
        prefs.getString(PREF_STREAK_HOURS, DEFAULT_STREAK_HOURS) != STREAK_REMINDER_OFF

    /** Turns the streak warning on, at the hours chosen before or the default, or off. */
    fun setStreakReminder(
        on: Boolean,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (on == streakReminder(prefs)) return
        prefs.edit { putString(PREF_STREAK_HOURS, if (on) DEFAULT_STREAK_HOURS else STREAK_REMINDER_OFF) }
    }

    /** Whether this phone tells the player when their weekly leaderboard place changes. */
    fun rankAlerts(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_RANK, true)

    fun setRankAlerts(
        on: Boolean,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) = prefs.edit { putBoolean(PREF_RANK, on) }

    /** Whether alerts can reach the player at all: notifications allowed, and both of AnkiQuest's channels on. */
    fun deliverable(context: Context): Boolean {
        val permission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permission && alertsEnabled(context, nudge = false) && alertsEnabled(context, nudge = true)
    }

    fun alertsEnabled(
        context: Context,
        nudge: Boolean,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return manager.getNotificationChannel(ensureChannel(context, nudge))?.importance?.let {
            it != NotificationManagerCompat.IMPORTANCE_NONE
        } ?: false
    }

    private fun ensureChannel(
        context: Context,
        nudge: Boolean,
    ): String {
        val channel = if (nudge) NUDGE_CHANNEL else CHANNEL
        val manager = NotificationManagerCompat.from(context)
        if (manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(channel, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(
                        AnkiquestLanguage
                            .context(context)
                            .getString(if (nudge) R.string.ankiquest_nudges_title else R.string.ankiquest_screen_title),
                    ).setVibrationEnabled(true)
                    .setVibrationPattern(BUZZ)
                    .build(),
            )
        }
        return channel
    }

    enum class Delivery {
        POSTED,
        CHANNEL_BLOCKED,
        DISABLED,
    }

    /**
     * Before channels, Android reads alert behaviour from the notification itself; the phone's ringer
     * and Do Not Disturb still apply. A permission revoked after the check reports [Delivery.DISABLED],
     * which keeps the inbox pending. A [picture], such as a friend's win card, is shown expanded with
     * the text beneath it.
     */
    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        id: Int,
        title: String,
        body: String,
        open: Intent,
        actions: List<NotificationCompat.Action> = emptyList(),
        nudge: Boolean = false,
        silent: Boolean = false,
        picture: Bitmap? = null,
        @DrawableRes art: Int = R.drawable.aki_face,
    ): Delivery {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Delivery.DISABLED
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Delivery.DISABLED
        val channel = ensureChannel(context, nudge)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            return Delivery.CHANNEL_BLOCKED
        }
        val style =
            picture?.let { NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(body) }
                ?: NotificationCompat.BigTextStyle().bigText(body)
        val notification =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_aki_notify)
                .setLargeIcon(AnkiquestAki.notificationIcon(context, art))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(style)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setVibrate(BUZZ)
                .setSilent(silent)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        id,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).apply { actions.forEach { addAction(it) } }
                .build()
        return try {
            manager.notify(id, notification)
            Delivery.POSTED
        } catch (_: SecurityException) {
            Delivery.DISABLED
        }
    }
}
