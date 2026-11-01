// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Notification
import android.app.NotificationManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feeds the server's recorded answers in `src/test/resources/ankiquest-api`, which CI replaces with
 * the server's current ones, to the code the app reads them with, so a server change that would
 * break the app fails here before it is deployed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestApiContractTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = CopyOnWriteArrayList<String>()
    private val manager get() = targetContext.getSystemService<NotificationManager>()!!

    @Before
    fun serveExamples() {
        mockkObject(AnkiquestWidget, AnkiquestShare)
        every { AnkiquestWidget.requestUpdate(any()) } just Runs
        every { AnkiquestShare.picture(any()) } returns null
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(EXAMPLE_NOW))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += "${exchange.requestMethod} $path"
            val body = route(exchange.requestMethod, path)?.let(::example)?.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, url)
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
    }

    @After
    fun stopServer() {
        try {
            if (::server.isInitialized) server.stop(0)
        } finally {
            unmockkObject(AnkiquestWidget, AnkiquestShare)
            AnkiDroidApp.sharedPreferencesTestingOverride = null
        }
    }

    private val account: HomeAccount get() = assertNotNull(AnkiquestHomeData.account())

    @Test
    fun `every example is either read by the app or known to be unused`() {
        val directory = File(assertNotNull(javaClass.classLoader?.getResource(EXAMPLES)).toURI())
        val examples = assertNotNull(directory.list()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toSet()
        assertEquals(CONSUMED + UNUSED, examples, "a new or removed example needs a check here")
        assertTrue((CONSUMED intersect UNUSED).isEmpty())
    }

    @Test
    fun `signing up and signing in keep the returned user and token`() =
        runBlocking {
            val created = AnkiquestAccount.submit(AnkiquestAccount.Credentials(url, "ana", "password", "Ana", create = true), "Pixel 9")
            assertEquals(AnkiquestAccount.Signed(url, "ana", "example-token-1"), created)
            val signed = AnkiquestAccount.submit(AnkiquestAccount.Credentials(url, "ana", "password", "", create = false), "Pixel 9")
            assertEquals(AnkiquestAccount.Signed(url, "ana", "example-token-2"), signed)
            assertTrue(requests.containsAll(listOf("POST /api/accounts", "POST /api/accounts/tokens")))
        }

    @Test
    fun `the profile gives the goal, the streak and the connection summary`() =
        runBlocking {
            val profile = Ankiquest.profile()
            AnkiquestNotifier.onProfile(targetContext, profile)
            assertEquals(
                AnkiquestDailyGoal.ServerGoal(AnkiquestDailyGoal.Choice(AnkiquestDailyGoal.Mode.BACKLOG, 14), since = 20754),
                AnkiquestDailyGoal.cached(AnkiDroidApp.sharedPrefs(), account.scope),
            )
            assertEquals(AnkiquestAki.Mood.STREAK, AnkiquestAki.mood(null, profile))
            assertTrue(assertNotNull(profile.optJSONArray("quests")).objects().all { it.optString("title").isNotEmpty() })

            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "") }
            assertEquals(
                targetContext.getString(R.string.ankiquest_check_ok, "Hill", 2, 101L, 5L) + "\n\n" +
                    targetContext.getString(R.string.ankiquest_check_no_token),
                Ankiquest.runFromSettings(targetContext, uploadAll = false),
            )
        }

    @Test
    fun `an upload answers the profile after reading which decks are shared`() =
        runBlocking {
            assertEquals(
                targetContext.getString(R.string.ankiquest_check_ok, "Hill", 2, 101L, 5L),
                Ankiquest.runFromSettings(targetContext, uploadAll = true),
            )
            assertTrue("GET /api/decks/hill" in requests)
            assertTrue("POST /api/reviews/hill" in requests)
            assertNull(Ankiquest.feedback(JSONObject(example("upload")).optJSONObject("feedback"), showFeedback = true))
        }

    @Test
    fun `deck settings list the friends to share with and the switches`() =
        runBlocking {
            val settings = Ankiquest.deckNotificationSettings()
            val people = settings.getJSONArray("recipients").objects()
            assertEquals(listOf("cerro", "friend"), people.map { it.getString("user") })
            assertTrue(people.all { it.getString("display").isNotEmpty() })
            settings.getJSONArray("decks").objects().forEach { deck ->
                deck.getString("id")
                deck.getBoolean("enabled")
                deck.getJSONArray("recipients")
            }
            val switches = Ankiquest.notificationSwitches()
            assertTrue(Ankiquest.celebrations(switches))
            assertFalse(switches.getBoolean(Ankiquest.NUDGES))
        }

    @Test
    fun `the leaderboard orders the widget rows`() =
        runBlocking {
            val board = Ankiquest.leaderboard()
            val week = AnkiquestWidget.forPeriod(board, "week")
            assertEquals(listOf("hill", "cerro"), week.objects().map { it.getString("user") })
            val hour = AnkiquestWidget.forPeriod(board, "hour").objects()
            assertEquals(listOf(51L, 30L), hour.map { it.getLong("xp") })
            assertEquals(2, AnkiquestWidget.collection(targetContext, week).itemCount)
        }

    @Test
    fun `a rank change is announced`() =
        runBlocking {
            AnkiquestNotifier.onLeaderboard(targetContext)
            val announced = shadowOf(manager).allNotifications.single()
            assertEquals("👑 You took the crown", announced.title())
            assertTrue("You passed Cerro." in announced.text())
        }

    @Test
    fun `the inbox shows a celebration, a friend's win with its card and a cheer`() =
        runBlocking {
            val inbox = assertNotNull(Ankiquest.completionNotifications())
            AnkiquestNotifier.onDeckCompletions(targetContext, inbox.account, inbox.notifications, inbox.scope)
            val celebration = shadowOf(manager).getNotification(5_140_001)
            assertEquals("Daily goal reached!", celebration.title())
            assertEquals(listOf("Share"), celebration.actions.map { it.title.toString() })
            val share = shadowOf(manager).getNotification(5_140_006)
            assertEquals("🎉 Cerro shared a win", share.title())
            assertEquals(listOf("Reply"), share.actions.map { it.title.toString() }, "the example was already cheered")
            verify { AnkiquestShare.picture("https://anki.example.test/w/example-link-2.png") }
            val reaction = shadowOf(manager).getNotification(5_140_008)
            assertEquals("🎉 Cerro cheered", reaction.title())
            assertEquals(8L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:${inbox.account}", 0))
        }

    @Test
    fun `sharing a win, cheering one and the stored settings are read`() =
        runBlocking {
            assertEquals(2, Ankiquest.shareWinWithFriends(1, account.scope))
            assertFalse(Ankiquest.react(6, account.notificationAccount, account.scope))
            val sharing = AnkiquestSharing.settings(account)
            assertEquals(3, sharing.dailyCap)
            assertEquals("ask", sharing.policies["daily_goal"])
            assertTrue(sharing.policies.values.all { it in setOf("private", "ask", "auto") })
            assertEquals(
                AnkiquestDailyGoal.ServerGoal(AnkiquestDailyGoal.Choice(AnkiquestDailyGoal.Mode.BACKLOG, 14), since = 20754, chosen = true),
                AnkiquestDailyGoal.ServerGoal.parse(AnkiquestSharing.goal(account)),
            )
            assertEquals("aki", AnkiquestHomeData.companion(account))
            assertFalse(Ankiquest.streakProtectionEnabled())
        }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun route(
        method: String,
        path: String,
    ): String? {
        val parts = path.removePrefix("/api/").split('/')
        return when {
            path == "/api/accounts" && method == "POST" -> "sign_up"
            path == "/api/accounts/tokens" && method == "POST" -> "sign_in"
            path == "/api/leaderboard" -> "leaderboard"
            parts.size == 2 && path.startsWith("/api/") -> ROUTES[parts[0]]
            else -> null
        }
    }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("$EXAMPLES/$name.json"), "$name.json").readText(Charsets.UTF_8)

    private companion object {
        const val EXAMPLES = "ankiquest-api"
        const val EXAMPLE_NOW = 1_793_183_460_000L

        val ROUTES =
            mapOf(
                "profile" to "profile",
                "reviews" to "upload",
                "rank" to "rank",
                "notifications" to "notifications",
                "share" to "share_to_friends",
                "react" to "react",
                "goal" to "goal",
                "sharing" to "sharing",
                "decks" to "decks",
                "companion" to "companion",
                "streak-freezes" to "streak_freezes",
            )

        val CONSUMED = ROUTES.values.toSet() + setOf("sign_up", "sign_in", "leaderboard")

        val UNUSED = setOf("capabilities", "devices", "wins")
    }
}
