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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import anki.collection.OpChanges
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.observability.ChangeManager
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.TimeZone
import java.util.concurrent.TimeUnit
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
    private const val PREF_RECENT = "ankiquestRecentUploads"
    private const val INITIAL_SYNC_KEY = "ankiquestInitialSyncDone"
    const val NUDGES = "nudges"
    const val CELEBRATIONS = "celebrations"

    private const val MAX_PENDING = 5000
    private const val BASELINE_MAX_AGE_MS = 10 * 60 * 1000L
    private const val RESUME_UPLOAD_INTERVAL_MS = 60 * 1000L
    private const val RESYNC_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    private const val UNDO_WINDOW_MS = 2 * 24 * 60 * 60 * 1000L
    private const val BANNER_MS = 1800L
    private const val BANNER_LONG_MS = 3500L
    private const val BANNER_TAG = "ankiquest_banner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client =
        AnkiquestHttp.client
            .newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .header("Accept-Language", AnkiquestLanguage.tag())
                        .build(),
                )
            }.build()
    private val json = "application/json".toMediaType()
    private val sessionClient =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    private var app: Application? = null
    private var activity = WeakReference<Activity>(null)
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
        AnkiquestPoll.schedule(app)
    }

    /** The player's profile, as served by `/api/profile/<user>`. */
    suspend fun profile(): JSONObject =
        withContext(Dispatchers.IO) {
            val (url, user, token) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            get("$url/api/profile/$user", token)
        }

    /** Whether a server and a player are set, so that the profile can be asked for. */
    fun isConfigured(): Boolean = endpoint() != null

    /** The configured player name, or the sync username when none is set. */
    fun player(): String? = HomeAccount.current()?.user

    /** The server, encoded member and token of one settings snapshot, so a queued request never mixes accounts. */
    private fun endpoint(): Triple<String, String, String>? = HomeAccount.current()?.let { Triple(it.server, it.encodedUser, it.token) }

    /** This week's standings, as served by `/api/leaderboard`. */
    suspend fun leaderboard(): JSONArray =
        withContext(Dispatchers.IO) {
            val (url, _, token) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            client.newCall(readRequest("$url/api/leaderboard", token)).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                JSONArray(response.body.string())
            }
        }

    /** The weekly order and how this player moved since [previous], as judged by `/api/rank/<user>`. */
    suspend fun rank(previous: List<String>): JSONObject =
        withContext(Dispatchers.IO) {
            val (url, user, token) = endpoint() ?: throw IllegalStateException("ankiquest is not configured")
            execute(
                readRequest("$url/api/rank/$user", token)
                    .newBuilder()
                    .post(JSONObject().put("previous", JSONArray(previous)).toString().toRequestBody(json))
                    .build(),
            )
        }

    /** Silently refresh the complete local catalog before loading private preferences. */
    suspend fun deckNotificationSettings(): JSONObject =
        withContext(Dispatchers.IO) {
            val (url, user, token) = authenticatedEndpoint()
            mutex.withLock {
                upload(url, user, token, resync = false, catalog = true)
                val local =
                    CollectionManager.withCol {
                        decks.allNamesAndIds(includeFiltered = false).map { it.id.toString() }.toSet()
                    }
                fetchDeckNotificationSettings(url, user, token).also { settings ->
                    settings.put("decks", JSONArray(settings.getJSONArray("decks").objects().filter { it.getString("id") in local }))
                }
            }
        }

    private fun fetchDeckNotificationSettings(
        url: String,
        user: String,
        token: String,
    ): JSONObject =
        execute(
            Request
                .Builder()
                .url("$url/api/decks/$user")
                .header("Authorization", "Bearer $token")
                .build(),
        )

    /** Shares every deck in [shared] with [recipients] and stops sharing [unshared], in one request. */
    suspend fun saveDeckNotificationSettings(
        shared: List<String>,
        unshared: List<String>,
        recipients: List<String>,
    ) = withContext(Dispatchers.IO) {
        val (url, user, token) = authenticatedEndpoint()
        val decks = JSONArray()
        for (id in shared) {
            decks.put(JSONObject().put("id", id).put("enabled", true).put("recipients", JSONArray(recipients)))
        }
        for (id in unshared) {
            decks.put(JSONObject().put("id", id).put("enabled", false).put("recipients", JSONArray()))
        }
        execute(
            Request
                .Builder()
                .url("$url/api/decks/$user")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("decks", decks).toString().toRequestBody(json))
                .build(),
        )
    }

    /**
     * The player's server-side notification switches, such as [NUDGES] and [CELEBRATIONS], in one
     * request and without touching deck progress.
     */
    suspend fun notificationSwitches(): JSONObject {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) { fetchDeckNotificationSettings(url, user, token) }
    }

    /** Celebrations are on unless the player turned them off; older servers have no setting and always celebrate. */
    fun celebrations(switches: JSONObject): Boolean = switches.optBoolean(CELEBRATIONS, true)

    /** Turns one notification switch on or off and leaves deck sharing and the other switches as they are. */
    suspend fun setNotificationSwitch(
        name: String,
        enabled: Boolean,
    ): JSONObject {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url("$url/api/decks/$user")
                    .header("Authorization", "Bearer $token")
                    .post(
                        JSONObject()
                            .put("decks", JSONArray())
                            .put(name, enabled)
                            .toString()
                            .toRequestBody(json),
                    ).build(),
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
        withContext(Dispatchers.IO) {
            val account = AnkiquestHomeData.account()?.takeIf { it.scope == expectedScope } ?: throw HomeAccountChanged()
            check(account.token.isNotEmpty()) { "Set your ankiquest token to share a win." }
            execute(
                Request
                    .Builder()
                    .url(account.url("api/share/${account.encodedUser}"))
                    .header("Authorization", "Bearer ${account.token}")
                    .post(
                        JSONObject()
                            .apply {
                                notification?.let { put("notification", it) }
                                place?.let { put("place", it) }
                            }.toString()
                            .toRequestBody(json),
                    ).build(),
            ).optInt("recipients")
        }

    /** The server's address, for inviting people met outside the app. */
    fun serverUrl(): String? = endpoint()?.first

    /** Streak protection is stored on the server; never infer it from a local default. */
    suspend fun streakProtectionEnabled(): Boolean {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url("$url/api/streak-freezes/$user")
                    .header("Authorization", "Bearer $token")
                    .build(),
            ).getBoolean("enabled")
        }
    }

    suspend fun setStreakProtection(enabled: Boolean): Boolean {
        val (url, user, token) = authenticatedEndpoint()
        return withContext(Dispatchers.IO) {
            execute(
                Request
                    .Builder()
                    .url("$url/api/streak-freezes/$user")
                    .header("Authorization", "Bearer $token")
                    .post(JSONObject().put("enabled", enabled).toString().toRequestBody(json))
                    .build(),
            ).getBoolean("enabled")
        }
    }

    /** A reply the server will never take, such as one already answered elsewhere. */
    class Rejected(
        val code: Int,
    ) : IOException("HTTP $code")

    /** Answers one inbox notification; returns the name of whoever will read it. */
    suspend fun reply(
        notification: Long,
        message: String,
        expectedAccount: String,
        expectedScope: String,
    ): String =
        withContext(Dispatchers.IO) {
            val account = AnkiquestHomeData.account() ?: throw Rejected(401)
            if (account.token.isEmpty() || account.notificationAccount != expectedAccount ||
                account.scope != expectedScope
            ) {
                throw Rejected(401)
            }
            val request =
                Request
                    .Builder()
                    .url(account.url("api/reply/${account.encodedUser}"))
                    .header("Authorization", "Bearer ${account.token}")
                    .post(
                        JSONObject()
                            .put("notification", notification)
                            .put("message", message)
                            .toString()
                            .toRequestBody(json),
                    ).build()
            try {
                val recipient = execute(request, sessionClient).getString("sent_to")
                if (AnkiquestHomeData.account()?.scope != account.scope) throw Rejected(401)
                recipient
            } catch (e: HttpStatusException) {
                throw if (e.code in 400..499) Rejected(e.code) else e
            }
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
            client
                .newCall(
                    Request
                        .Builder()
                        .url(account.url("api/notifications/${account.encodedUser}"))
                        .header("Authorization", "Bearer ${account.token}")
                        .build(),
                ).execute()
                .use { response ->
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    CompletionNotifications(account.notificationAccount, JSONArray(response.body.string()), account.scope)
                }
        }

    private fun authenticatedEndpoint(): Triple<String, String, String> {
        val (url, user, token) = endpoint() ?: throw IllegalStateException("Set the server URL and player first.")

        check(token.isNotEmpty()) { "Set your ankiquest token to manage settings." }
        return Triple(url, user, token)
    }

    override fun opExecuted(
        changes: OpChanges,
        handler: Any?,
    ) {
        if (!changes.studyQueues) return
        val reviewing = handler is Reviewer || handler is ReviewerViewModel || isReviewing(activity.get())
        if (!reviewing && handler != null) return
        launchRefresh(showFeedback = reviewing)
    }

    private fun isReviewing(host: Activity?): Boolean =
        host is Reviewer ||
            (host as? FragmentActivity)?.supportFragmentManager?.fragments?.any { it is ReviewerFragment } == true

    /** Answers arriving while a refresh waits for the running one join it instead of each queueing an upload. */
    private fun launchRefresh(
        showFeedback: Boolean,
        resync: Boolean = false,
    ) {
        if (endpoint() == null || !refreshes.offer(showFeedback, resync)) return
        scope.launch {
            mutex.withLock {
                val (feedback, again) = refreshes.take()
                try {
                    val (url, user, token) = endpoint() ?: return@withLock
                    val profile = if (token.isEmpty()) preview(url, user) else upload(url, user, token, again)
                    present(profile, feedback)
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest refresh failed")
                }
            }
        }
    }

    internal suspend fun preview(
        url: String,
        user: String,
    ): JSONObject {
        val now = TimeManager.time.intTimeMS()
        val account = "$url/$user"
        if (baselineAccount != account || now - baselineAt > BASELINE_MAX_AGE_MS) {
            syncedLastId = get("$url/api/profile/$user").optLong("last_review_id")
            baselineAt = now
            baselineAccount = account
        }
        val reviews = pendingReviews(syncedLastId)
        val body = JSONObject().put("reviews", reviews)
        seenThrough?.takeIf { it.first == account }?.let { body.put("seen_through", it.second) }
        val profile =
            execute(
                Request
                    .Builder()
                    .url("$url/api/preview/$user")
                    .post(body.toString().toRequestBody(json))
                    .build(),
            )
        val sent = if (reviews.length() == 0) 0L else reviews.getJSONObject(reviews.length() - 1).getLong("id")
        seenThrough = account to maxOf(syncedLastId, sent)
        return profile
    }

    /**
     * Uploads reviews after the account's mark in batches the server accepts. Only the final batch
     * carries deck progress, and connection tests never do. Sharing is fetched for the captured
     * account on every upload, so changed or revoked sharing is respected.
     */
    private suspend fun upload(
        url: String,
        user: String,
        token: String,
        resync: Boolean,
        onlyTest: Boolean = false,
        catalog: Boolean = false,
    ): JSONObject {
        val prefs = AnkiDroidApp.sharedPrefs()
        val account = "$url/$user"
        migrateUploadMark(prefs, account)
        val markKey = markKey(account)
        val mark = prefs.getLong(markKey, 0L)
        val initialSyncKey = initialSyncKey(account)
        val initialSyncDone = prefs.contains(markKey) && prefs.getBoolean(initialSyncKey, false)
        var known = if (resync) maxOf(0L, mark - RESYNC_WINDOW_MS) else mark
        val clock = clock()

        val windowStart = TimeManager.time.intTimeMS() - UNDO_WINDOW_MS
        val recent = recentUploads(prefs, recentKey(account), windowStart)
        val window = if (onlyTest) emptyList() else pendingReviews(windowStart).objects()
        val present = window.map { it.getLong("id") }
        val reconciled = AnkiquestCompletionPolicy.reconcile(present, recent, known, if (onlyTest) 0L else mark)
        val restoredIds = reconciled.restored.toSet()
        val restored = window.filter { it.getLong("id") in restoredIds }

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
                    .put("silent", catalog || AnkiquestCompletionPolicy.silentUpload(mark, initialSyncDone, full, onlyTest))
            if (first) {
                restored.forEach { batch.put(it) }
                body.put("deleted", JSONArray(reconciled.deleted))
            }
            body.put("reviews", batch)
            if (!full && !onlyTest) {
                runCatching {
                    if (catalog) {
                        deckSnapshots(clock)
                    } else {
                        val enabled =
                            fetchDeckNotificationSettings(url, user, token)
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
            profile =
                execute(
                    Request
                        .Builder()
                        .url("$url/api/reviews/$user")
                        .header("Authorization", "Bearer $token")
                        .post(body.toString().toRequestBody(json))
                        .build(),
                )
            if (!onlyTest) prefs.edit { putLong(markKey, maxOf(known, mark)) }
            first = false
        } while (full)
        if (!onlyTest) {
            prefs.edit {
                putString(recentKey(account), present.joinToString(","))
                putBoolean(initialSyncKey, true)
            }
        }
        return profile
    }

    private fun markKey(account: String) = "$PREF_MARK:$account"

    private fun recentKey(account: String) = "$PREF_RECENT:$account"

    private fun initialSyncKey(account: String) = "$INITIAL_SYNC_KEY:$account"

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
        val rollover =
            CollectionManager.withCol {
                runCatching {
                    db.queryString("select cast(val as text) from config where key = 'rollover'").trim().toInt()
                }.getOrDefault(4)
            }
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

    private fun readRequest(
        url: String,
        token: String,
    ): Request =
        Request
            .Builder()
            .url(url)
            .apply {
                token.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
            }.build()

    private fun get(
        url: String,
        token: String = "",
    ): JSONObject = execute(readRequest(url, token))

    /**
     * Exchange the configured token for an HttpOnly browser cookie without exposing it to the page.
     * Older public servers have no session endpoint and answer null.
     */
    internal suspend fun dashboardSession(dashboard: String): String? =
        withContext(Dispatchers.IO) {
            val (url, user, token) = endpoint() ?: return@withContext null
            if (dashboard != "$url/#$user") return@withContext null
            if (token.isEmpty()) return@withContext null
            val request =
                Request
                    .Builder()
                    .url("$url/auth/session")
                    .header("Authorization", "Bearer $token")
                    .post(ByteArray(0).toRequestBody())
                    .build()
            sessionClient.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                response.headers.values("Set-Cookie").firstOrNull { value ->
                    val cookie = Cookie.parse(request.url, value)
                    cookie != null && cookie.name == "ankiquest_session" && cookie.hostOnly &&
                        cookie.path == "/" && cookie.httpOnly && (!request.url.isHttps || cookie.secure)
                } ?: throw IOException("The ankiquest server did not return a valid session cookie")
            }
        }

    private fun execute(
        request: Request,
        requestClient: OkHttpClient = client,
    ): JSONObject =
        requestClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            JSONObject(response.body.string())
        }

    internal class HttpStatusException(
        val code: Int,
    ) : IOException("HTTP $code")

    /**
     * Runs a connection test, or with [uploadAll] resends every review, for the settings screen.
     *
     * @return a message describing the outcome, never throws
     */
    suspend fun runFromSettings(
        context: Context,
        uploadAll: Boolean,
    ): String = withContext(Dispatchers.IO) { settingsAction(context, uploadAll) }

    private suspend fun settingsAction(
        context: Context,
        uploadAll: Boolean,
    ): String {
        val (url, user, token) = endpoint() ?: return unconfigured(context)

        return try {
            mutex.withLock {
                val profile =
                    if (token.isEmpty()) {
                        get("$url/api/profile/$user", token)
                    } else {
                        if (uploadAll) {
                            AnkiDroidApp.sharedPrefs().edit {
                                remove(markKey("$url/$user"))
                                remove(initialSyncKey("$url/$user"))
                            }
                        }
                        upload(url, user, token, resync = false, onlyTest = !uploadAll)
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
                if (token.isEmpty()) summary + "\n\n" + context.getString(R.string.ankiquest_check_no_token) else summary
            }
        } catch (e: HttpStatusException) {
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

    /** A typed server address that can never be used is reported as such rather than as missing settings. */
    private fun unconfigured(context: Context): String {
        val typed =
            AnkiDroidApp
                .sharedPrefs()
                .getString(PREF_URL, "")
                .orEmpty()
                .trim()
        return if (typed.isNotEmpty() && HomeAccount.from(mapOf(PREF_URL to typed), "player") == null) {
            context.getString(R.string.ankiquest_check_bad_url, typed)
        } else {
            context.getString(R.string.ankiquest_check_unconfigured)
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
            root.findViewWithTag<TextView>(BANNER_TAG) ?: TextView(host).apply {
                tag = BANNER_TAG
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

    fun dashboardUrl(): String? = endpoint()?.let { (url, user) -> "$url/#$user" }

    internal fun webSession(): AnkiquestWebSession? =
        endpoint()?.let { (url, user, token) ->
            AnkiquestWebSession(
                "$url/#$user",
                URLDecoder.decode(user, "UTF-8"),
                token,
                AnkiquestLanguage.tag(),
            )
        }

    override fun onActivityResumed(activity: Activity) {
        this.activity = WeakReference(activity)
        AnkiquestUpdater.maybeCheck(activity)
        val now = TimeManager.time.intTimeMS()
        if (now - resumeUploadAt > RESUME_UPLOAD_INTERVAL_MS) {
            resumeUploadAt = now
            launchRefresh(showFeedback = false, resync = true)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (this.activity.get() === activity) this.activity.clear()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}
}

/** At most one refresh waits behind the running one; requests made meanwhile join it, keeping any feedback or resync they ask for. */
internal class RefreshQueue {
    private val queued = AtomicBoolean(false)
    private val feedback = AtomicBoolean(false)
    private val resync = AtomicBoolean(false)

    /** Records a request; true when the caller must start a refresh, false when a waiting one will cover it. */
    fun offer(
        showFeedback: Boolean,
        resync: Boolean,
    ): Boolean {
        if (showFeedback) feedback.set(true)
        if (resync) this.resync.set(true)
        return queued.compareAndSet(false, true)
    }

    /** Starts the waiting refresh, so later requests queue another; answers whether to show feedback and whether to resync. */
    fun take(): Pair<Boolean, Boolean> {
        queued.set(false)
        return feedback.getAndSet(false) to resync.getAndSet(false)
    }
}
