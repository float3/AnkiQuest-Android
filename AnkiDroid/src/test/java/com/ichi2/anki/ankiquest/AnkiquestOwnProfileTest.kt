// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.collection.OpChanges
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestIcon.Mood
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The icon and the widgets follow the profile each upload answers with, without waiting for the poll. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestOwnProfileTest : RobolectricTest() {
    private val hour = 3_600_000L
    private lateinit var server: HttpServer
    private val paths = mutableListOf<String>()

    @Volatile
    private var answer: JSONObject = JSONObject()

    @Before
    fun startServer() {
        mockkObject(AnkiquestPoll)
        every { AnkiquestPoll.refreshNow(any()) } just Runs
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        AnkiquestWidget.forgetFresh()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val path = exchange.requestURI.path
            synchronized(paths) { paths.add(path) }
            val body = if (path.startsWith("/api/decks/")) JSONObject().put("decks", JSONArray()) else answer
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "token")
            putBoolean(AnkiquestIcon.PREF_ENABLED, true)
        }
    }

    @After
    fun stopServer() {
        try {
            // The application scope outlives an individual test's server and preferences.
            runBlocking { drainReviewJobs() }
        } finally {
            if (::server.isInitialized) server.stop(0)
            AnkiquestWidget.forgetFresh()
            unmockkObject(AnkiquestPoll)
            SharedPreferencesTestHook.override = null
        }
    }

    private fun profile(
        state: String,
        streak: Int,
        hoursLeft: Long,
        minutes: Int = 0,
    ) = JSONObject()
        .put("display", "Hill")
        .put("level", 3)
        .put("xp_total", 120)
        .put("week_xp", 40)
        .put("periods", JSONObject().put("week", 40).put("day", 10))
        .put("streak", streak)
        .put("streak_state", state)
        .put("day_ends_at", TimeManager.time.intTimeMS() + hoursLeft * hour)
        .put("today", JSONObject().put("reviews", if (state == "studied") 12 else 0).put("minutes", minutes).put("streak_minutes", 0))
        .put("lifetime", JSONObject().put("reviews", 1))

    @Test
    fun `an upload that makes the day count turns a worried icon happy without a poll`() =
        runBlocking {
            registerAliases()
            answer = profile("pending", 12, 2)
            Ankiquest.uploadPending()
            assertEquals(listOf(Mood.WORRIED), enabled(), "the last hours of an open day worry Aki")

            answer = profile("studied", 13, 2)
            Ankiquest.uploadPending()

            assertEquals(listOf(Mood.HAPPY), enabled())
            assertEquals("studied", AnkiquestIcon.lastKnown()!!.getString("streak_state"), "the next app start begins from it")
            assertEquals(0, synchronized(paths) { paths.count { it.startsWith("/api/profile") } }, "the upload's answer is enough")
            verify(exactly = 0) { AnkiquestPoll.refreshNow(any()) }
        }

    @Test
    fun `offline reviews survive a restart and update their own icon and widget`() {
        registerAliases()
        val now = TimeManager.time.intTimeMS()
        val pending = profile("pending", 12, 2).put("last_review_id", now - 10)
        pending.getJSONObject("today").put("starts_at", now - hour).put("streak_minutes", 10)
        val scope = checkNotNull(HomeAccount.current()).scope
        AnkiquestDay.remember(pending)
        val day = checkNotNull(AnkiquestDay.Server.profile(pending))
        AnkiquestDay.rememberLocal(scope, day, listOf(AnkiquestDay.Review(now, 600_000)))

        // Launcher and widget read the persisted unsent totals.
        AnkiquestIcon.showLastKnown(targetContext, now)
        assertEquals(listOf(Mood.HAPPY), enabled())
        val widget =
            AnkiquestStreakWidget
                .views(targetContext, JSONArray(), "hill", now = now)
                .apply(targetContext, FrameLayout(targetContext))
        assertEquals("13", widget.findViewById<TextView>(R.id.ankiquest_streak_number).text.toString())
        assertEquals(10, widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress).progress)
        assertEquals(
            targetContext.getString(R.string.aq_goal_streak_safe_unsent),
            widget.findViewById<TextView>(R.id.ankiquest_streak_status).text.toString(),
        )

        // A new token for the same player cannot reuse the previous session's offline verdict.
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "new-token") }
        AnkiquestDay.remember(pending)
        AnkiquestIcon.showLastKnown(targetContext, now)
        assertEquals(listOf(Mood.WORRIED), enabled())
    }

    @Test
    fun `two review answers only claim the local daily moment once`() {
        val now = TimeManager.time.intTimeMS()
        val pending = profile("pending", 12, 2).put("last_review_id", now - 1000)
        pending.getJSONObject("today").put("starts_at", now - hour).put("streak_minutes", 10)
        AnkiquestDay.remember(pending)
        val account = checkNotNull(HomeAccount.current()).notificationAccount
        val prefs = AnkiDroidApp.sharedPrefs()
        val reviewer = mockk<ReviewerViewModel>()
        val changes = OpChanges.newBuilder().setStudyQueues(true).build()

        fun answer(id: Long) {
            col.db.execute(
                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, 0, 3, 1, 1, 2500, 600000, 1)",
                id,
            )
            Ankiquest.opExecuted(changes, reviewer)
        }

        answer(now - 100)
        advanceRobolectricLooperUntil { AnkiquestMoments.pending(prefs, account).isNotEmpty() }
        assertEquals(1, AnkiquestMoments.take(prefs, account).size)
        answer(now - 50)
        advanceRobolectricLooperUntil { AnkiquestDay.own(now)?.unsent == 2 }
        advanceRobolectricLooper()
        assertEquals(emptyList(), AnkiquestMoments.pending(prefs, account))
    }

    @Test
    fun `a queued review cannot start an upload for the next account`() =
        runBlocking {
            drainReviewJobs()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            mockkObject(AnkiquestReviewOffsets)
            coEvery { AnkiquestReviewOffsets.recordAnswers(any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
            }
            try {
                val changes = OpChanges.newBuilder().setStudyQueues(true).build()
                Ankiquest.opExecuted(changes, mockk<ReviewerViewModel>())
                withTimeout(10_000) { entered.await() }
                AnkiDroidApp.sharedPrefs().edit {
                    putString(Ankiquest.PREF_USER, "other")
                    putString(Ankiquest.PREF_TOKEN, "other-token")
                }
                release.complete(Unit)
                drainReviewJobs()
                val requested = synchronized(paths) { paths.toList() }
                assertTrue(requested.none { it.contains("other") }, "old review work requested the next account: $requested")
            } finally {
                release.complete(Unit)
                try {
                    drainReviewJobs()
                } finally {
                    unmockkObject(AnkiquestReviewOffsets)
                }
            }
        }

    @Test
    fun `an upload waiting behind another request keeps its original account`() =
        runBlocking {
            drainReviewJobs()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mutex = ReflectionHelpers.getField<Mutex>(Ankiquest, "mutex")
            val owner = Any()
            mockkObject(AnkiquestReviewOffsets)
            coEvery { AnkiquestReviewOffsets.recordAnswers(any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
            }
            mutex.lock(owner)
            try {
                Ankiquest.opExecuted(OpChanges.newBuilder().setStudyQueues(true).build(), mockk<ReviewerViewModel>())
                withTimeout(10_000) { entered.await() }
                val callback = reviewParent().children.single()
                release.complete(Unit)
                // The review callback has queued its upload, which cannot pass the mutex yet.
                withTimeout(10_000) { callback.join() }
                AnkiDroidApp.sharedPrefs().edit {
                    putString(Ankiquest.PREF_USER, "other")
                    putString(Ankiquest.PREF_TOKEN, "other-token")
                }
                mutex.unlock(owner)
                drainReviewJobs()
                val requested = synchronized(paths) { paths.toList() }
                assertTrue(requested.none { it.contains("other") }, "waiting upload changed accounts: $requested")
            } finally {
                release.complete(Unit)
                if (mutex.holdsLock(owner)) {
                    mutex.unlock(owner)
                }
                try {
                    drainReviewJobs()
                } finally {
                    unmockkObject(AnkiquestReviewOffsets)
                }
            }
        }

    /** Wait for the callback and any upload it schedules, without exposing a production test hook. */
    private suspend fun drainReviewJobs() {
        val parent = reviewParent()
        withTimeout(10_000) {
            do {
                val pending = parent.children.toList()
                pending.joinAll()
            } while (parent.children.any())
        }
    }

    private fun reviewParent(): Job = checkNotNull(ReflectionHelpers.getField<CoroutineScope>(Ankiquest, "scope").coroutineContext[Job])

    @Test
    fun `cancelling a waiting upload does not block the next poll`() =
        runBlocking {
            drainReviewJobs()
            val mutex = ReflectionHelpers.getField<Mutex>(Ankiquest, "mutex")
            val queue = ReflectionHelpers.getField<RefreshQueue>(Ankiquest, "refreshes")
            val account = checkNotNull(HomeAccount.current()).scope
            val owner = Any()
            mutex.lock(owner)
            val upload = launch(Dispatchers.IO) { Ankiquest.uploadPending() }
            try {
                // Wait for the queue barrier, then cancel while the upload still cannot run.
                withTimeout(10_000) {
                    while (!synchronized(queue) { ReflectionHelpers.getField<Map<String, *>>(queue, "waiting").containsKey(account) }) {
                        delay(1)
                    }
                }
                upload.cancelAndJoin()
                mutex.unlock(owner)
                Ankiquest.uploadPending()
                val requested = synchronized(paths) { paths.toList() }
                assertTrue(requested.any { it == "/api/reviews/hill" }, "the next poll must still upload after cancellation: $requested")
            } finally {
                upload.cancelAndJoin()
                if (mutex.holdsLock(owner)) {
                    mutex.unlock(owner)
                }
                queue.take(account)
            }
        }

    @Test
    fun `the streak widget is drawn again right after an upload`() =
        runBlocking {
            val manager = AppWidgetManager.getInstance(targetContext)
            val id = shadowOf(manager).createWidget(AnkiquestStreakWidget::class.java, R.layout.widget_ankiquest_streak)
            val row =
                JSONObject()
                    .put("user", "hill")
                    .put("display", "Hill")
                    .put("streak", 12)
                    .put("streak_state", "pending")
                    .put("today_reviews", 0)
                    .put("today_minutes", 0)
                    .put("streak_minutes", 0)
                    .put("day_ends_at", TimeManager.time.intTimeMS() + 2 * hour)
            val fetchedAt = TimeManager.time.intTimeMS() - 60_000
            AnkiquestWidget.render(targetContext, JSONArray().put(row), fetchedAt, offline = false)
            val text = { view: Int ->
                shadowOf(manager)
                    .getViewFor(id)
                    .findViewById<TextView>(view)
                    .text
                    .toString()
            }
            assertEquals("12", text(R.id.ankiquest_streak_number))
            assertEquals("Not studied yet", text(R.id.ankiquest_streak_status))

            answer = profile("studied", 13, 2)
            Ankiquest.uploadPending()

            assertEquals("13", text(R.id.ankiquest_streak_number))
            assertEquals(targetContext.getString(R.string.aq_goal_streak_safe), text(R.id.ankiquest_streak_status))
            assertEquals(
                1,
                synchronized(paths) { paths.count { it.startsWith("/api/") && !it.startsWith("/api/decks/") } },
                "only the upload",
            )
        }

    @Test
    fun `a newer poll board wins over the row from an older upload`() {
        val account = AnkiquestHomeData.account()!!
        val now = TimeManager.time.intTimeMS()
        AnkiquestWidget.onOwnProfile(targetContext, account, profile("studied", 13, 2), now)
        val row =
            JSONObject()
                .put("user", "hill")
                .put("streak", 20)
                .put("period", "week")
                .put("xp", 5)

        val older = AnkiquestWidget.withFreshRow(JSONArray().put(row), now - 1)
        assertEquals(13, older.getJSONObject(0).getInt("streak"))
        assertEquals(40, older.getJSONObject(0).getInt("xp"), "the row's period takes the profile's XP")

        val newer = AnkiquestWidget.withFreshRow(JSONArray().put(row), now + 1)
        assertEquals(20, newer.getJSONObject(0).getInt("streak"))

        val ranked = JSONArray().put(JSONObject(row.toString()).put("rank", 4))
        assertEquals(20, AnkiquestWidget.withFreshRow(ranked, now - 1).getJSONObject(0).getInt("streak"), "a ranked board keeps its rows")
    }

    /** The aliases come from the quest build's manifest, which unit tests do not merge. */
    private fun registerAliases() {
        for (mood in Mood.entries) {
            shadowOf(targetContext.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = targetContext.packageName
                    name = component(mood).className
                    targetActivity = "com.ichi2.anki.IntentHandler"
                    enabled = mood == Mood.DEFAULT
                },
            )
        }
    }

    private fun component(mood: Mood) = ComponentName(targetContext.packageName, "com.ichi2.anki.ankiquest.${mood.alias}")

    private fun enabled(): List<Mood> =
        Mood.entries.filter { mood ->
            val state = targetContext.packageManager.getComponentEnabledSetting(component(mood))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && mood == Mood.DEFAULT)
        }
}
