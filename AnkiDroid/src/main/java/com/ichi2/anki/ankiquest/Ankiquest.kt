// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ProcessLifecycleOwner
import anki.collection.OpChanges
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.lang.ref.WeakReference
import java.net.UnknownHostException
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows XP, combo, quest and level feedback from an ankiquest server after each answer.
 *
 * With a token, new review log rows are uploaded to the server. Without one, they are only
 * sent to its preview endpoint, which stores nothing, when the server permits public access.
 */
object Ankiquest : ChangeManager.Subscriber, Application.ActivityLifecycleCallbacks {
    const val PREF_URL = "ankiquestUrl"
    const val PREF_USER = "ankiquestUser"
    const val PREF_TOKEN = "ankiquestToken"
    private const val PREF_MARK = "ankiquestUploadedThrough"
    private const val PREF_UPLOAD_AT = "ankiquestLastUploadAt"
    private const val PREF_RECENT = "ankiquestRecentUploads"
    private const val INITIAL_SYNC_KEY = "ankiquestInitialSyncDone"
    private const val PREF_RESYNCED_AT = "ankiquestResyncedAt"
    const val NUDGES = "nudges"
    const val CELEBRATIONS = "celebrations"

    private const val MAX_PENDING = 5000

    /** Undoing answers removes a few at a time; more than this many gone at once is never sent as deleted. */
    @VisibleForTesting
    internal const val MAX_DELETED = 100
    private const val BASELINE_MAX_AGE_MS = 10 * 60 * 1000L
    private const val RESUME_UPLOAD_INTERVAL_MS = 60 * 1000L
    private const val RESYNC_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    private const val DAILY_RESYNC_MS = 24 * 60 * 60 * 1000L
    private const val UNDO_WINDOW_MS = 2 * 24 * 60 * 60 * 1000L
    private const val BANNER_MS = 1800L
    private const val BANNER_LONG_MS = 3500L
    private const val BANNER_TAG = "ankiquest_banner"
    private const val MOMENTS_SETTLE_MS = 25 * 1000L
    private const val MOMENTS_CHECK_INTERVAL_MS = 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val uploadAllRunning = AtomicBoolean(false)

    private var app: Application? = null
    private var activity = WeakReference<Activity>(null)
    private var started = 0
    private var lastAnswerAt = 0L
    private var momentsCheckedAt = 0L
    private var syncedLastId = 0L
    private var baselineAt = 0L
    private var baselineAccount: String? = null
    private val refreshes = RefreshQueue()

    @VisibleForTesting
    internal var resumeUploadAt = 0L
    private var seenThrough: Pair<String, Long>? = null

    /**
     * Follows activities and collection changes. This singleton subscribes without a lifecycle owner,
     * so nothing takes it off the list: any earlier registration is dropped first, because a second
     * Application in the same process (every Robolectric test builds one) would subscribe it twice,
     * which ChangeManager refuses in a debug build. This also survives `resetForTesting()`.
     */
    fun init(app: Application) {
        this.app = app
        app.registerActivityLifecycleCallbacks(this)
        ChangeManager.unsubscribe(this)
        ChangeManager.subscribe(this, owner = null)
        ProcessLifecycleOwner.get().lifecycle.addObserver(AnkiquestIcon)
        AnkiquestPoll.schedule(app)
    }

    /** The player's profile, as served by `/api/profile/<user>`. */
    suspend fun profile(): JSONObject =
        withContext(Dispatchers.IO) {
            val account = configured()
            AnkiquestApi.json(account, AnkiquestApi.request(account, "api/profile/${account.encodedUser}").build())
        }

    /** Whether a server and a player are set, so that the profile can be asked for. */
    fun isConfigured(): Boolean = HomeAccount.current() != null

    /** The configured player name, or the sync username when none is set. */
    fun player(): String? = HomeAccount.current()?.user

    private fun configured(): HomeAccount = HomeAccount.current() ?: throw IllegalStateException("ankiquest is not configured")

    /** This week's standings, as served by `/api/leaderboard`. */
    suspend fun leaderboard(): JSONArray =
        withContext(Dispatchers.IO) {
            val account = configured()
            AnkiquestApi.call(account, AnkiquestApi.request(account, "api/leaderboard").build()) { AnkiquestApi.jsonArray(it) }
        }

    /** The weekly order and how this player moved since [previous], as judged by `/api/rank/<user>`. */
    suspend fun rank(previous: List<String>): JSONObject =
        withContext(Dispatchers.IO) {
            val account = configured()
            AnkiquestApi.json(
                account,
                AnkiquestApi
                    .request(account, "api/rank/${account.encodedUser}")
                    .post(AnkiquestApi.body(JSONObject().put("previous", JSONArray(previous))))
                    .build(),
            )
        }

    /** Silently refresh the complete local catalog before loading private preferences. */
    suspend fun deckNotificationSettings(): JSONObject =
        withContext(Dispatchers.IO) {
            val account = authenticated()
            mutex.withLock {
                upload(account, resync = false, catalog = true)
                val local =
                    CollectionManager.withCol {
                        decks.allNamesAndIds(includeFiltered = false).map { it.id.toString() }.toSet()
                    }
                fetchDeckNotificationSettings(account).also { settings ->
                    settings.put("decks", JSONArray(settings.getJSONArray("decks").objects().filter { it.getString("id") in local }))
                }
            }
        }

    private fun fetchDeckNotificationSettings(account: HomeAccount): JSONObject =
        AnkiquestApi.json(account, AnkiquestApi.request(account, "api/decks/${account.encodedUser}").build())

    /** Shares every deck in [shared] with [recipients] and stops sharing [unshared], in one request. */
    suspend fun saveDeckNotificationSettings(
        shared: List<String>,
        unshared: List<String>,
        recipients: List<String>,
    ) = withContext(Dispatchers.IO) {
        val account = authenticated()
        val decks = JSONArray()
        for (id in shared) {
            decks.put(JSONObject().put("id", id).put("enabled", true).put("recipients", JSONArray(recipients)))
        }
        for (id in unshared) {
            decks.put(JSONObject().put("id", id).put("enabled", false).put("recipients", JSONArray()))
        }
        AnkiquestApi.json(
            account,
            AnkiquestApi
                .request(account, "api/decks/${account.encodedUser}")
                .post(AnkiquestApi.body(JSONObject().put("decks", decks)))
                .build(),
        )
    }

    /**
     * The player's server-side notification switches, such as [NUDGES] and [CELEBRATIONS], in one
     * request and without touching deck progress.
     */
    suspend fun notificationSwitches(): JSONObject {
        val account = authenticated()
        return withContext(Dispatchers.IO) { fetchDeckNotificationSettings(account) }
    }

    /** Celebrations are on unless the player turned them off; older servers have no setting and always celebrate. */
    fun celebrations(switches: JSONObject): Boolean = switches.optBoolean(CELEBRATIONS, true)

    /** Turns one notification switch on or off and leaves deck sharing and the other switches as they are. */
    suspend fun setNotificationSwitch(
        name: String,
        enabled: Boolean,
    ): JSONObject {
        val account = authenticated()
        return withContext(Dispatchers.IO) {
            AnkiquestApi.json(
                account,
                AnkiquestApi
                    .request(account, "api/decks/${account.encodedUser}")
                    .post(AnkiquestApi.body(JSONObject().put("decks", JSONArray()).put(name, enabled)))
                    .build(),
            )
        }
    }

    /**
     * Shares a win with everyone in the player's circle through `/api/share`: one of their own
     * [notification]s, or their current [place] on this week's board. Answers how many friends got it.
     *
     * @param expectedScope the [HomeAccount.scope] the win belongs to; another signed-in account never shares it
     */
    suspend fun shareWin(
        notification: Long?,
        place: Int?,
        expectedScope: String,
    ): Int =
        share(
            JSONObject().apply {
                notification?.let { put("notification", it) }
                place?.let { put("place", it) }
            },
            expectedScope,
        ).optInt("recipients")

    /** Sends the server's [win] to everyone in the player's circle; answers how many friends got it. */
    suspend fun shareWinWithFriends(
        win: Long,
        expectedScope: String,
    ): Int = share(JSONObject().put("win", win).put("to", "friends"), expectedScope).optInt("recipients")

    /** The public link for the server's [win], with its card and invitation text; the server makes it once and reuses it. */
    internal suspend fun shareWinLink(
        win: Long,
        expectedScope: String,
    ): AnkiquestShare.Link {
        val answer = share(JSONObject().put("win", win).put("to", "link"), expectedScope)
        return AnkiquestShare.Link(answer.getString("url"), answer.getString("image"), answer.getString("text"))
    }

    private suspend fun share(
        body: JSONObject,
        expectedScope: String,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val account = AnkiquestHomeData.account()?.takeIf { it.scope == expectedScope } ?: throw HomeAccountChanged()
            check(account.token.isNotEmpty()) { "Set your ankiquest token to share a win." }
            AnkiquestApi.json(
                account,
                AnkiquestApi
                    .request(account, "api/share/${account.encodedUser}")
                    .post(AnkiquestApi.body(body))
                    .build(),
            )
        }

    /** The server's address, for inviting people met outside the app. */
    fun serverUrl(): String? = HomeAccount.current()?.server

    /** Streak protection is stored on the server; never infer it from a local default. */
    suspend fun streakProtectionEnabled(): Boolean {
        val account = authenticated()
        return withContext(Dispatchers.IO) {
            val request = AnkiquestApi.request(account, "api/streak-freezes/${account.encodedUser}").build()
            AnkiquestApi.json(account, request).getBoolean("enabled")
        }
    }

    suspend fun setStreakProtection(enabled: Boolean): Boolean {
        val account = authenticated()
        return withContext(Dispatchers.IO) {
            AnkiquestApi
                .json(
                    account,
                    AnkiquestApi
                        .request(account, "api/streak-freezes/${account.encodedUser}")
                        .post(AnkiquestApi.body(JSONObject().put("enabled", enabled)))
                        .build(),
                ).getBoolean("enabled")
        }
    }

    /**
     * Answers one inbox notification; returns the name of whoever will read it.
     *
     * @throws HomeAccountChanged when the notification belongs to an account that is no longer signed in
     * @throws AnkiquestHttpException when the server refuses; a 4xx refusal is final
     */
    suspend fun reply(
        notification: Long,
        message: String,
        expectedAccount: String,
        expectedScope: String,
    ): String =
        withContext(Dispatchers.IO) {
            val account = replyAccount(expectedAccount, expectedScope)
            AnkiquestApi
                .json(
                    account,
                    AnkiquestApi
                        .request(account, "api/reply/${account.encodedUser}")
                        .post(AnkiquestApi.body(JSONObject().put("notification", notification).put("message", message)))
                        .build(),
                ).getString("sent_to")
        }

    /**
     * Cheers 🎉 on a win a friend shared, one inbox [notification]; the friend is told who cheered.
     * Answers whether this player had already cheered it. Fails as [reply] does.
     */
    suspend fun react(
        notification: Long,
        expectedAccount: String,
        expectedScope: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val account = replyAccount(expectedAccount, expectedScope)
            AnkiquestApi
                .json(
                    account,
                    AnkiquestApi
                        .request(account, "api/react/${account.encodedUser}")
                        .post(AnkiquestApi.body(JSONObject().put("notification", notification)))
                        .build(),
                ).optBoolean("already")
        }

    private fun replyAccount(
        expectedAccount: String,
        expectedScope: String,
    ): HomeAccount {
        val account =
            AnkiquestHomeData.account()?.takeIf { it.notificationAccount == expectedAccount && it.scope == expectedScope }
                ?: throw HomeAccountChanged()
        if (account.token.isEmpty()) throw AnkiquestHttpException(401)
        return account
    }

    internal data class CompletionNotifications(
        val account: String,
        val notifications: JSONArray,
        val scope: String,
    )

    /** Preserve the exact fetch identity for both delivery cursors and queued reply actions. */
    internal suspend fun completionNotifications(): CompletionNotifications? =
        withContext(Dispatchers.IO) {
            val account = AnkiquestHomeData.account() ?: return@withContext null
            if (account.token.isEmpty()) return@withContext null
            val request = AnkiquestApi.request(account, "api/notifications/${account.encodedUser}").build()
            AnkiquestApi.call(account, request, recheck = false) { response ->
                CompletionNotifications(account.notificationAccount, AnkiquestApi.jsonArray(response), account.scope)
            }
        }

    private fun authenticated(): HomeAccount {
        val account = HomeAccount.current() ?: throw IllegalStateException("Set the server URL and player first.")
        check(account.token.isNotEmpty()) { "Set your ankiquest token to manage settings." }
        return account
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        if (!changes.studyQueues) return
        val reviewing = handler is Reviewer || handler is ReviewerViewModel || isReviewing(activity.get())
        if (!reviewing && handler != null) return
        val reviewOwner = if (reviewing) HomeAccount.current()?.scope else null
        if (reviewOwner != null) {
            lastAnswerAt = TimeManager.time.intTimeMS()
            scope.launch {
                recordAnswers()
                refreshLocalDay(reviewOwner)
                launchRefresh(showFeedback = true, reviewOwner = reviewOwner)
            }
            return
        }
        launchRefresh(showFeedback = reviewing)
    }

    /** Remembers the time zone of the answers just made here, before they are uploaded. */
    private suspend fun recordAnswers() {
        try {
            AnkiquestReviewOffsets.recordAnswers(AnkiDroidApp.sharedPrefs(), TimeManager.time.intTimeMS())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not record the time zone of an answer")
        }
    }

    /**
     * Collection -> day verdict -> launcher and widgets, even when the upload cannot run.
     * A new study day stays unknown offline until a poll supplies its server window.
     */
    private suspend fun refreshLocalDay(reviewOwner: String? = null) {
        val context = app ?: return
        val account = HomeAccount.current() ?: return
        if (account.token.isEmpty()) {
            return
        }
        if (reviewOwner != null && reviewOwner != account.scope) {
            return
        }
        val prefs = AnkiDroidApp.sharedPrefs()
        val now = TimeManager.time.intTimeMS()
        val server = AnkiquestDay.last(prefs)?.takeIf { it.dayEndsAt > now } ?: return
        try {
            val before = AnkiquestDay.judgeOwn(server, now, prefs) ?: return
            // Keep collection reads ordered, and discard work from a replaced account or profile.
            val day =
                CollectionManager.tryWithCol {
                    val reviews = AnkiquestDay.unsent(this, server)
                    if (HomeAccount.current()?.scope != account.scope || AnkiquestDay.last(prefs) != server) {
                        return@tryWithCol null
                    }
                    AnkiquestDay.rememberLocal(account.scope, server, reviews, prefs)
                    AnkiquestDay.judge(server, reviews, now)
                } ?: return
            if (HomeAccount.current()?.scope != account.scope) {
                return
            }
            if (day.label != before.label && AnkiquestIcon.enabled(prefs)) {
                AnkiquestIcon.showLastKnown(context, now, prefs)
            }
            if (day.label != before.label || day.streak != before.streak || day.minutes != before.minutes) {
                AnkiquestWidget.onLocalDay(context, account)
            }
            if (reviewOwner != null && !before.met && day.met) {
                minimumReached(account, prefs, server, before, day, now)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "ankiquest could not refresh the local streak")
        }
    }

    private suspend fun minimumReached(
        account: HomeAccount,
        prefs: SharedPreferences,
        server: AnkiquestDay.Server,
        before: AnkiquestDay.Verdict,
        day: AnkiquestDay.Verdict,
        now: Long,
    ): Unit =
        withContext(Dispatchers.Main) {
            val context = app ?: return@withContext
            if (!AnkiquestMoments.claimMinimum(context, prefs, account, server, day, now)) {
                return@withContext
            }
            val host = activity.get() ?: return@withContext
            if (isReviewing(host)) {
                val fraction = AnkiquestGoalBar.fraction(before.minutes, day.streakMinutes, before.met)
                val root = host.findViewById<FrameLayout>(android.R.id.content) ?: return@withContext
                val cue = AnkiquestStreakCue.show(host, day.streak, fraction, prefs) { showDeferredBanner(host, root) }
                if (cue != null) {
                    root.findViewWithTag<ReviewBannerView>(BANNER_TAG)?.takeIf { it.alpha > 0f }?.apply {
                        message?.let { defer(it) }
                        animate().cancel()
                        alpha = 0f
                    }
                }
            } else {
                AnkiquestMoments.present(host, prefs)
            }
        }

    private fun isReviewing(host: Activity?): Boolean =
        host is Reviewer ||
            (host as? FragmentActivity)?.supportFragmentManager?.fragments?.any { it is ReviewerFragment } == true

    /** Only the initiating account's answers can join its waiting upload. */
    private fun launchRefresh(
        showFeedback: Boolean,
        resync: Boolean = false,
        reviewOwner: String? = null,
    ) {
        val account = HomeAccount.current() ?: return
        if (reviewOwner != null && account.scope != reviewOwner) {
            return
        }
        if (!refreshes.offer(account.scope, showFeedback, resync)) return
        scope.launch { refresh(account, background = false) }
    }

    /**
     * Uploads the reviews the server does not have yet, for the background poll: reviews made offline
     * would otherwise wait until the app is next opened. It joins a refresh that is already waiting
     * instead of queueing another, and does nothing without a token or while the collection cannot be
     * used, such as during a sync.
     */
    internal suspend fun uploadPending() {
        val account = HomeAccount.current()?.takeIf { it.token.isNotEmpty() } ?: return
        if (!collectionAvailable()) return
        if (!refreshes.offer(account.scope, showFeedback = false, resync = false)) return
        refresh(account, background = true)
    }

    /**
     * After an AnkiWeb sync, sends again the last week of reviews, so rows that arrived with ids older
     * than the upload mark (answered on another device, or restored by a full download) reach the
     * server. The server keeps one copy of each review, so rows it already has change nothing.
     */
    internal suspend fun uploadAfterSync() {
        val account = HomeAccount.current()?.takeIf { it.token.isNotEmpty() } ?: return
        if (!collectionAvailable()) return
        refreshLocalDay()
        if (!refreshes.offer(account.scope, showFeedback = false, resync = true)) return
        refresh(account, background = true)
    }

    /** [uploadAfterSync] without waiting for it, for a sync that finished on screen. */
    fun onCollectionSynced() {
        scope.launch { uploadAfterSync() }
    }

    /**
     * Whether a resume should resend the last week: at most once a day per account, so opening the
     * app catches up on anything a sync brought in without sending the week on every resume.
     */
    @VisibleForTesting
    internal fun resyncDue(now: Long): Boolean {
        val account = HomeAccount.current()?.takeIf { it.token.isNotEmpty() } ?: return false
        val last = AnkiDroidApp.sharedPrefs().getLong(resyncedKey(account.notificationAccount), 0L)
        return now - last !in 0 until DAILY_RESYNC_MS
    }

    private suspend fun collectionAvailable(): Boolean =
        try {
            CollectionManager.tryWithCol { } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "ankiquest background upload skipped: collection unavailable")
            false
        }

    /** Runs the waiting refresh. A background one shows nothing unless an answer asked for feedback meanwhile. */
    private suspend fun refresh(
        account: HomeAccount,
        background: Boolean,
    ) {
        try {
            mutex.lock()
        } catch (e: CancellationException) {
            // A cancelled waiter must not prevent the next poll from claiming its work.
            if (HomeAccount.current()?.scope == account.scope) {
                refreshes.retry(account.scope)
            } else {
                refreshes.take(account.scope)
            }
            throw e
        }
        try {
            val (feedback, again) = refreshes.take(account.scope)
            // A queued callback may outlive sign-out, token replacement or an account switch.
            if (HomeAccount.current()?.scope != account.scope) {
                return
            }
            try {
                val profile =
                    if (account.token.isEmpty()) {
                        preview(account.server, account.encodedUser)
                    } else {
                        recordingProblem(account) { upload(account, again) }
                    }
                onOwnProfile(account, profile)
                if (!background || feedback) present(profile, feedback)
                if (account.token.isNotEmpty()) AnkiquestDailyGoal.afterUpload(account, profile)
                if (account.token.isNotEmpty()) queueConquests(account, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest refresh failed")
            }
        } finally {
            // The running upload has already consumed its slot; keep any newer waiting request.
            mutex.unlock()
        }
    }

    /**
     * Brings the app icon and the widgets up to date with [profile], the player's own as an upload or
     * preview just answered, as the poll does with the profile it fetches. The launcher icon waits
     * until the app is hidden. [account] must still be the one signed in.
     */
    private fun onOwnProfile(
        account: HomeAccount,
        profile: JSONObject,
    ) {
        val context = app ?: return
        if (HomeAccount.current()?.scope != account.scope) return
        try {
            val prefs = AnkiDroidApp.sharedPrefs()
            val now = TimeManager.time.intTimeMS()
            AnkiquestDailyGoal.remember(profile, prefs)
            AnkiquestIcon.update(context, profile, now, prefs)
            AnkiquestWidget.onOwnProfile(context, account, profile, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest icon and widgets could not follow the upload")
        }
    }

    /**
     * Queues the cards the upload's answer says were just conquered, to show once studying stops. With
     * no screen in front, as for the background poll, it waits for the next upload made in the app.
     */
    private suspend fun queueConquests(
        account: HomeAccount,
        profile: JSONObject,
    ) {
        if (!inForeground()) return
        val prefs = AnkiDroidApp.sharedPrefs()
        val fresh =
            AnkiquestMoments.freshConquests(
                prefs,
                account.notificationAccount,
                profile.optJSONArray("conquests"),
                TimeManager.time.intTimeMS(),
            )
        if (fresh.isEmpty()) return
        val language = AnkiquestLanguage.context(app ?: return)
        val moments =
            fresh.map { conquest ->
                val at = conquest.getLong("at")
                val lapses = conquest.optInt("lapses")
                val text = AnkiquestShare.conqueredCardText(at)
                val deck = AnkiquestShare.conqueredCardDeck(at)
                val detail =
                    listOfNotNull(
                        text?.let { language.getString(R.string.aq_conquest_popup_card, it) },
                        language.resources.getQuantityString(R.plurals.aq_conquest_lapses, lapses, lapses.toString()),
                        deck?.let { language.getString(R.string.aq_conquest_deck, it) },
                    ).joinToString(" · ")
                val title =
                    language.getString(
                        if (conquest.optBoolean("leech")) R.string.aq_profile_leech else R.string.aq_profile_conquest,
                    )
                AnkiquestMoments.conquest(conquest, title, detail, deck)
            }
        AnkiquestMoments.offer(prefs, account.notificationAccount, moments)
        presentMoments()
    }

    /** Whether any AnkiQuest screen is visible, so moments can wait for the app instead of notifying. */
    fun inForeground(): Boolean = started > 0

    /** Shows waiting moments over the screen in front, if studying has stopped there. */
    internal suspend fun presentMoments() =
        withContext(Dispatchers.Main) {
            activity.get()?.let { AnkiquestMoments.present(it, AnkiDroidApp.sharedPrefs()) }
        }

    /** [presentMoments] from any thread, without waiting for it. */
    fun presentMomentsSoon() {
        Handler(Looper.getMainLooper()).post {
            activity.get()?.let { AnkiquestMoments.present(it, AnkiDroidApp.sharedPrefs()) }
        }
    }

    /**
     * After a session ends, asks the inbox for moments the server found in it (a level, a streak
     * milestone, an achievement, the daily goal), which reach the inbox a little after the upload.
     */
    private fun checkMoments(host: Activity) {
        val now = TimeManager.time.intTimeMS()
        val wait = (lastAnswerAt + MOMENTS_SETTLE_MS - now).coerceAtLeast(0)
        if (wait == 0L && now - momentsCheckedAt < MOMENTS_CHECK_INTERVAL_MS) return
        momentsCheckedAt = now + wait
        val context = host.applicationContext
        scope.launch {
            delay(wait)
            try {
                completionNotifications()?.let { (account, notifications, owner) ->
                    AnkiquestNotifier.onDeckCompletions(context, account, notifications, owner)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "ankiquest moments check failed")
            }
            presentMoments()
        }
    }

    internal suspend fun preview(
        url: String,
        user: String,
    ): JSONObject {
        val now = TimeManager.time.intTimeMS()
        val account = "$url/$user"
        if (baselineAccount != account || now - baselineAt > BASELINE_MAX_AGE_MS) {
            syncedLastId = AnkiquestApi.anonymous(anonymousRequest("$url/api/profile/$user").build()).optLong("last_review_id")
            baselineAt = now
            baselineAccount = account
        }
        val reviews = pendingReviews(syncedLastId)
        val body = JSONObject().put("reviews", reviews)
        seenThrough?.takeIf { it.first == account }?.let { body.put("seen_through", it.second) }
        val profile = AnkiquestApi.anonymous(anonymousRequest("$url/api/preview/$user").post(AnkiquestApi.body(body)).build())
        val sent = if (reviews.length() == 0) 0L else reviews.getJSONObject(reviews.length() - 1).getLong("id")
        seenThrough = account to maxOf(syncedLastId, sent)
        return profile
    }

    /**
     * Uploads reviews after the account's mark in batches the server accepts. Only the final batch
     * carries deck progress, and connection tests never do. Sharing is fetched for the captured
     * account on every upload, so changed or revoked sharing is respected.
     *
     * A [resync] starts a week before the mark (or before now, should the mark lie ahead of the
     * clock) and [everything] at the first review, for rows that reached the collection with ids
     * below the mark. Neither moves the mark back; the server keeps one copy of each review.
     * [progress] hears how many of the reviews to send went out after each batch.
     */
    private suspend fun upload(
        owner: HomeAccount,
        resync: Boolean,
        onlyTest: Boolean = false,
        catalog: Boolean = false,
        everything: Boolean = false,
        progress: ((sent: Int, total: Int) -> Unit)? = null,
    ): JSONObject {
        val prefs = AnkiDroidApp.sharedPrefs()
        val account = owner.notificationAccount
        migrateUploadMark(prefs, account)
        val markKey = markKey(account)
        val mark = prefs.getLong(markKey, 0L)
        val initialSyncKey = initialSyncKey(account)
        val initialSyncDone = prefs.contains(markKey) && prefs.getBoolean(initialSyncKey, false)
        val startedAt = TimeManager.time.intTimeMS()
        if (!onlyTest) {
            prefs.edit { putLong(scopedKey(PREF_UPLOAD_AT, owner.scope), startedAt) }
        }
        var known =
            when {
                everything -> 0L
                resync -> maxOf(0L, minOf(mark, startedAt) - RESYNC_WINDOW_MS)
                else -> mark
            }
        val clock = clock()
        val stamps = AnkiquestReviewOffsets.read(prefs)

        val windowStart = startedAt - UNDO_WINDOW_MS
        val recent = recentUploads(prefs, recentKey(account), windowStart)
        val window = if (onlyTest) emptyList() else pendingReviews(windowStart).objects()
        val present = window.map { it.getLong("id") }
        val reconciled = AnkiquestCompletionPolicy.reconcile(present, recent, known, if (onlyTest) 0L else mark)
        val restoredIds = reconciled.restored.toSet()
        val restored = window.filter { it.getLong("id") in restoredIds }
        val deleted = gone(reconciled.deleted)

        val total = if (progress == null) 0 else countReviews(known) + restored.size
        var sent = 0
        var profile: JSONObject
        var first = true
        do {
            val limit = if (first) MAX_PENDING - restored.size else MAX_PENDING
            val batch = if (onlyTest) JSONArray() else pendingReviews(known, limit)
            val full = batch.length() == limit
            if (batch.length() > 0) known = batch.getJSONObject(batch.length() - 1).getLong("id")
            val body =
                JSONObject()
                    .put("clock", clock)
                    .put(
                        "silent",
                        catalog || everything || AnkiquestCompletionPolicy.silentUpload(initialSyncDone, full, onlyTest),
                    )
            if (first) {
                restored.forEach { batch.put(it) }
                body.put("deleted", JSONArray(deleted))
            }
            body.put("reviews", AnkiquestReviewOffsets.stamp(batch, stamps))
            if (!full && !onlyTest) {
                runCatching {
                    if (catalog) {
                        deckSnapshots(clock)
                    } else {
                        val enabled =
                            fetchDeckNotificationSettings(owner)
                                .getJSONArray("decks")
                                .objects()
                                .filter { it.getBoolean("enabled") }
                                .map { it.getString("id") }
                                .toSet()
                        if (enabled.isEmpty()) null else JSONArray(deckSnapshots(clock).objects().filter { it.getString("id") in enabled })
                    }
                }.onSuccess { decks ->
                    if (decks != null) body.put("decks", decks).put("catalog", catalog)
                }.onFailure { Timber.w(it, "ankiquest deck progress unavailable; uploading reviews only") }
            }
            val request =
                AnkiquestApi
                    .request(owner, "api/reviews/${owner.encodedUser}")
                    .post(AnkiquestApi.body(body))
                    .build()
            profile = AnkiquestApi.call(owner, request, recheck = false) { AnkiquestApi.jsonObject(it) }
            if (!onlyTest) prefs.edit { putLong(markKey, maxOf(known, mark)) }
            sent += batch.length()
            progress?.invoke(sent, maxOf(sent, total))
            first = false
        } while (full)
        if (!onlyTest) {
            prefs.edit {
                putString(recentKey(account), present.joinToString(","))
                putBoolean(initialSyncKey, true)
                if (resync || everything) putLong(resyncedKey(account), startedAt)
            }
            AnkiquestReviewOffsets.prune(prefs, prefs.getLong(markKey, 0L), TimeManager.time.intTimeMS())
        }
        return profile
    }

    /** Runs [send], an upload for [owner], keeping why it failed until one works, for the screens that wait on it. */
    private suspend fun <T> recordingProblem(
        owner: HomeAccount,
        send: suspend () -> T,
    ): T {
        val prefs = AnkiDroidApp.sharedPrefs()
        val result =
            try {
                send()
            } catch (e: Exception) {
                AnkiquestUploadProblem.of(e)?.let { AnkiquestUploadProblem.remember(prefs, owner.scope, it) }
                throw e
            }
        AnkiquestUploadProblem.remember(prefs, owner.scope, null)
        return result
    }

    private fun markKey(account: String) = "$PREF_MARK:$account"

    private fun recentKey(account: String) = "$PREF_RECENT:$account"

    private fun initialSyncKey(account: String) = "$INITIAL_SYNC_KEY:$account"

    private fun resyncedKey(account: String) = "$PREF_RESYNCED_AT:$account"

    /**
     * Gives the upload mark that older versions kept for every account to the [account] it belongs to.
     *
     * It is only known to be [account]'s when no other account has completed an upload on this device.
     * Otherwise [account] starts afresh: its first upload is then silent, and the server keeps one copy
     * of any review it already had. A mark that belongs to another single account is left for it.
     */
    @VisibleForTesting
    internal fun migrateUploadMark(
        prefs: SharedPreferences,
        account: String,
    ) {
        if (!prefs.contains(PREF_MARK) && !prefs.contains(PREF_RECENT)) return
        if (prefs.contains(markKey(account))) return
        val owner =
            prefs.all.keys
                .filter { it.startsWith("$INITIAL_SYNC_KEY:") }
                .singleOrNull()
        if (owner != null && owner != initialSyncKey(account)) return
        prefs.edit {
            if (owner != null) {
                putLong(markKey(account), prefs.getLong(PREF_MARK, 0L))
                prefs.getString(PREF_RECENT, null)?.let { putString(recentKey(account), it) }
            }
            remove(PREF_MARK)
            remove(PREF_RECENT)
        }
    }

    private fun recentUploads(
        prefs: SharedPreferences,
        key: String,
        windowStart: Long,
    ): Set<Long> =
        prefs
            .getString(key, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { it > windowStart }
            .toSet()

    private suspend fun present(
        profile: JSONObject,
        showFeedback: Boolean,
    ) {
        app?.let { AnkiquestWidget.requestUpdate(it) }
        val message = feedback(profile.optJSONObject("feedback"), showFeedback) ?: return
        withContext(Dispatchers.Main) { showBanner(message.first, message.second) }
    }

    /**
     * The banner text for the server's `feedback`, and whether it is notable. Headlines are
     * always shown; a bare XP status only while answering cards.
     */
    internal fun feedback(
        feedback: JSONObject?,
        showFeedback: Boolean,
    ): Pair<String, Boolean>? {
        if (feedback == null) return null
        val headlines = feedback.optJSONArray("headlines")?.strings().orEmpty()
        val status = if (feedback.isNull("status")) null else feedback.getString("status").ifEmpty { null }
        return when {
            headlines.isNotEmpty() -> (headlines + listOfNotNull(status)).joinToString("\n") to true
            status != null && showFeedback -> status to false
            else -> null
        }
    }

    private suspend fun clock(): JSONObject {
        val rollover = CollectionManager.withCol { AnkiquestDecks.rollover(this) }
        val now = TimeManager.time.intTimeMS()
        return JSONObject()
            .put("rollover_hour", rollover)
            .put("offset_west_min", -TimeZone.getDefault().getOffset(now) / 60_000)
    }

    private suspend fun pendingReviews(
        afterId: Long,
        limit: Int = MAX_PENDING,
    ): JSONArray =
        CollectionManager.withCol {
            val rows = JSONArray()
            db
                .query(
                    "select id, cid, lastIvl, ivl, time, type from revlog " +
                        "where id > ? and ease > 0 and type < 4 order by id limit ${limit.coerceAtLeast(0)}",
                    afterId,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.put(
                            AnkiquestCompletionPolicy.review(
                                cursor.getLong(0),
                                cursor.getLong(1),
                                cursor.getLong(2),
                                cursor.getLong(3),
                                cursor.getLong(4),
                                cursor.getInt(5),
                            ),
                        )
                    }
                }
            rows
        }

    /**
     * The ids among [candidates] that are really gone from the collection, so the server may forget them:
     * an id still in the revlog is never deleted, whatever the window comparison says. More than
     * [MAX_DELETED] at once is not an undo but the collection changing under us, so then none are.
     */
    @VisibleForTesting
    internal suspend fun gone(candidates: List<Long>): List<Long> {
        if (candidates.isEmpty()) return candidates
        val still =
            CollectionManager.withCol {
                candidates.chunked(500).flatMapTo(HashSet()) { chunk ->
                    db.queryLongList("select id from revlog where id in (${chunk.joinToString(",")})")
                }
            }
        val gone = candidates.filter { it !in still }
        if (still.isNotEmpty()) Timber.w("ankiquest kept %d reviews the upload window missed", still.size)
        if (gone.size > MAX_DELETED) {
            Timber.w("ankiquest suppressed deleting %d reviews in one upload", gone.size)
            return emptyList()
        }
        return gone
    }

    private suspend fun countReviews(afterId: Long): Int = CollectionManager.withCol { countPending(afterId) }

    private fun Collection.countPending(afterId: Long): Int =
        db.queryScalar("select count() from revlog where id > ? and ease > 0 and type < 4", afterId)

    internal data class UploadDiagnostics(
        val at: Long?,
        val problem: AnkiquestUploadProblem?,
        val pending: Int?,
    )

    /** Read the upload status without contacting the server or opening a closed collection. */
    internal suspend fun uploadDiagnostics(account: HomeAccount): UploadDiagnostics {
        val prefs = AnkiDroidApp.sharedPrefs()
        val cursor = prefs.getLong(markKey(account.notificationAccount), 0L)
        val count =
            try {
                CollectionManager.withOpenColOrNull { countPending(cursor) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest could not count pending reviews for debug info")
                null
            }
        return UploadDiagnostics(
            prefs.getLong(scopedKey(PREF_UPLOAD_AT, account.scope), 0L).takeIf { it > 0 },
            AnkiquestUploadProblem.last(prefs, account.scope),
            count,
        )
    }

    private suspend fun deckSnapshots(clock: JSONObject): JSONArray =
        CollectionManager.withCol {
            val now = TimeManager.time.intTimeMS()
            val offset = clock.getInt("offset_west_min")
            val rollover = clock.getInt("rollover_hour")
            val rows =
                AnkiquestDecks.snapshots(
                    this,
                    now,
                    offset,
                    rollover,
                )
            check(AnkiquestDecks.day(now, offset, rollover) == AnkiquestDecks.day(TimeManager.time.intTimeMS(), offset, rollover)) {
                "Study day changed while reading deck progress"
            }
            rows
        }

    private fun anonymousRequest(url: String): Request.Builder = AnkiquestApi.request(url.toHttpUrl(), "", AnkiquestLanguage.tag())

    private fun dashboardOf(account: HomeAccount): String = "${account.server}/#${account.encodedUser}"

    /**
     * Runs a connection test, or with [uploadAll] resends every review in batches, for the settings
     * screen. Only one upload of everything runs at a time; [progress] hears how far it got.
     *
     * @return a message describing the outcome, never throws
     */
    suspend fun runFromSettings(
        context: Context,
        uploadAll: Boolean,
        progress: ((sent: Int, total: Int) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            if (!uploadAll) return@withContext settingsAction(context, false, null)
            if (!uploadAllRunning.compareAndSet(false, true)) return@withContext context.getString(R.string.ankiquest_upload_all_busy)
            try {
                settingsAction(context, true, progress)
            } finally {
                uploadAllRunning.set(false)
            }
        }

    /** Whether the whole history is being uploaded right now. */
    fun uploadingAll(): Boolean = uploadAllRunning.get()

    private suspend fun settingsAction(
        context: Context,
        uploadAll: Boolean,
        progress: ((sent: Int, total: Int) -> Unit)?,
    ): String {
        val account = HomeAccount.current() ?: return unconfigured(context)
        val url = account.server
        val token = account.token

        var sent = 0
        return try {
            mutex.withLock {
                val profile =
                    if (token.isEmpty()) {
                        AnkiquestApi.json(account, AnkiquestApi.request(account, "api/profile/${account.encodedUser}").build())
                    } else {
                        recordingProblem(account) {
                            upload(account, resync = false, onlyTest = !uploadAll, everything = uploadAll) { done, total ->
                                sent = done
                                progress?.invoke(done, total)
                            }
                        }
                    }
                present(profile, showFeedback = false)
                val lifetime = profile.getJSONObject("lifetime")
                val summary =
                    context.getString(
                        R.string.ankiquest_check_ok,
                        profile.getString("display"),
                        profile.getInt("level"),
                        profile.getLong("xp_total"),
                        lifetime.getLong("reviews"),
                    )
                when {
                    token.isEmpty() -> summary + "\n\n" + context.getString(R.string.ankiquest_check_no_token)
                    uploadAll -> context.resources.getQuantityString(R.plurals.ankiquest_upload_all_done, sent, sent) + "\n\n" + summary
                    else -> summary
                }
            }
        } catch (e: AnkiquestHttpException) {
            when (e.code) {
                401 -> context.getString(R.string.ankiquest_check_unauthorized)
                404 -> context.getString(R.string.ankiquest_check_unknown_player)
                else -> context.getString(R.string.ankiquest_check_http, e.code)
            }
        } catch (e: UnknownHostException) {
            context.getString(R.string.ankiquest_check_unreachable, url)
        } catch (e: IllegalArgumentException) {
            context.getString(R.string.ankiquest_check_bad_url, url)
        } catch (e: Exception) {
            Timber.w(e, "ankiquest settings check failed")
            context.getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Why no account is configured. A typed server address that can never be used is reported as such
     * rather than as missing settings, and a plain http address on the internet as insecure.
     */
    fun unconfigured(context: Context): String {
        val typed =
            AnkiDroidApp
                .sharedPrefs()
                .getString(PREF_URL, "")
                .orEmpty()
                .trim()
        return when {
            AnkiquestHosts.insecure(typed) -> context.getString(R.string.ankiquest_check_insecure, typed)
            typed.isNotEmpty() && HomeAccount.from(mapOf(PREF_URL to typed), "player") == null ->
                context.getString(R.string.ankiquest_check_bad_url, typed)
            else -> context.getString(R.string.ankiquest_check_unconfigured)
        }
    }

    /** Feedback kept with the account that produced it while the streak cue has priority. */
    private data class ReviewBanner(
        val text: String,
        val important: Boolean,
        val owner: String?,
    )

    private class ReviewBannerView(
        host: Activity,
    ) : TextView(host) {
        var message: ReviewBanner? = null
        var pending: ReviewBanner? = null

        fun defer(next: ReviewBanner) {
            if (next.important || pending?.important != true) {
                pending = next
            }
        }
    }

    private fun showDeferredBanner(
        host: Activity,
        root: FrameLayout,
    ) {
        val banner = root.findViewWithTag<ReviewBannerView>(BANNER_TAG) ?: return
        val pending = banner.pending ?: return
        banner.pending = null
        if (activity.get() === host && HomeAccount.current()?.scope == pending.owner) {
            showBanner(pending.text, pending.important)
        }
    }

    /**
     * Shows [text] briefly over the current screen. It is discoverable with accessibility services
     * but not announced, because the session recap carries the same feedback without interrupting every answer.
     */
    private fun showBanner(
        text: String,
        important: Boolean,
    ) {
        val host = activity.get() ?: return
        if (host.isFinishing || host.isDestroyed) return
        val root = host.findViewById<FrameLayout>(android.R.id.content) ?: return
        val density = host.resources.displayMetrics.density
        val banner =
            root.findViewWithTag<ReviewBannerView>(BANNER_TAG) ?: ReviewBannerView(host).apply {
                tag = BANNER_TAG
                alpha = 0f
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
                importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
                val pad = (10 * density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                elevation = 8 * density
                root.addView(
                    this,
                    FrameLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                        ).apply { topMargin = (72 * density).toInt() },
                )
            }
        val message = ReviewBanner(text, important, HomeAccount.current()?.scope)
        if (AnkiquestStreakCue.showing(root)) {
            banner.defer(message)
            return
        }
        banner.message = message
        banner.background =
            GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(if (important) 0xF0B8860B.toInt() else 0xE0202830.toInt())
            }
        banner.text = text
        banner.animate().cancel()
        banner.alpha = 1f
        banner.bringToFront()
        banner
            .animate()
            .alpha(0f)
            .setStartDelay(if (important) BANNER_LONG_MS else BANNER_MS)
            .setDuration(400)
            .start()
    }

    fun dashboardUrl(): String? = HomeAccount.current()?.let(::dashboardOf)

    /** The configured server's privacy notice, `/privacy`, an HTML page with the operator's details; null without a server. */
    fun privacyUrl(): String? = HomeAccount.current()?.let { "${it.server}/privacy" }

    override fun onActivityResumed(activity: Activity) {
        this.activity = WeakReference(activity)
        val context = activity.applicationContext
        scope.launch { AnkiquestIcon.onAppStart(context) }
        AnkiquestUpdater.maybeCheck(activity)
        val now = TimeManager.time.intTimeMS()
        if (now - resumeUploadAt > RESUME_UPLOAD_INTERVAL_MS) {
            resumeUploadAt = now
            scope.launch { refreshLocalDay() }
            launchRefresh(showFeedback = false, resync = resyncDue(now))
        }
        // The activity callback can run before AndroidX dispatches ON_RESUME.
        activity.window.decorView.post {
            if (AnkiquestPostStudyPrompts.resumed(activity) && AnkiquestMoments.resting(activity)) {
                AnkiquestMoments.present(activity, AnkiDroidApp.sharedPrefs())
                if (HomeAccount.current()?.token?.isNotEmpty() == true) {
                    checkMoments(activity)
                }
            }
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (this.activity.get() === activity) this.activity.clear()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}
}

/** One waiting refresh per account; a replaced session cannot consume the next session's request. */
internal class RefreshQueue {
    private enum class State { WAITING, RETRY }

    private data class Waiting(
        val feedback: Boolean,
        val resync: Boolean,
        val state: State = State.WAITING,
    )

    private val waiting = mutableMapOf<String, Waiting>()

    /** Records a request; true when the caller must start a refresh, false when a waiting one will cover it. */
    @Synchronized
    fun offer(
        owner: String,
        showFeedback: Boolean,
        resync: Boolean,
    ): Boolean {
        val previous = waiting[owner]
        waiting[owner] = Waiting(previous?.feedback == true || showFeedback, previous?.resync == true || resync)
        return previous == null || previous.state == State.RETRY
    }

    /** Retains coalesced intent when a waiter is cancelled, letting the next caller claim it. */
    @Synchronized
    fun retry(owner: String) {
        waiting[owner]?.let { waiting[owner] = it.copy(state = State.RETRY) }
    }

    /** Starts the waiting refresh, so later requests queue another; answers whether to show feedback and whether to resync. */
    @Synchronized
    fun take(owner: String): Pair<Boolean, Boolean> = waiting.remove(owner)?.let { it.feedback to it.resync } ?: (false to false)
}
