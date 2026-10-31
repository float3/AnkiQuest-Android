// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Choice
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daily goal and the sharing settings are kept by the server; this device migrates, caches and reports. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestServerGoalTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Triple<String, String, JSONObject?>>()

    @Volatile private var goal = JSONObject().put("mode", "cards").put("value", 100)

    @Volatile private var reachedStatus = 200

    @Volatile private var sharing =
        JSONObject()
            .put("policies", JSONObject().put("daily_goal", "ask").put("level", "ask").put("quest", "private"))
            .put("daily_cap", 3)

    private val prefs get() = AnkiDroidApp.sharedPrefs()

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val text = exchange.requestBody.bufferedReader().use { it.readText() }
            val body = text.takeIf { it.isNotBlank() }?.let(::JSONObject)
            val route = "${exchange.requestMethod} ${exchange.requestURI.path}"
            requests += Triple(exchange.requestMethod, exchange.requestURI.path, body)
            val (status, answer) =
                when (route) {
                    "GET /api/goal/hill" -> 200 to goal
                    "PUT /api/goal/hill" ->
                        if (body!!.optInt("value", 1) > 5000) {
                            400 to JSONObject().put("error", "Pick 1 to 5000 cards.")
                        } else {
                            goal = body
                            200 to body
                        }
                    "POST /api/goal/hill/reached" -> reachedStatus to JSONObject()
                    "GET /api/sharing/hill" -> 200 to sharing
                    "PUT /api/sharing/hill" -> {
                        val policies = sharing.getJSONObject("policies")
                        body!!.getJSONObject("policies").let { changed -> changed.keys().forEach { policies.put(it, changed.get(it)) } }
                        if (body.has("daily_cap")) sharing.put("daily_cap", body.getInt("daily_cap"))
                        200 to sharing
                    }
                    else -> 404 to JSONObject()
                }
            val bytes = answer.toString().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        prefs.edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    private fun account() = AnkiquestHomeData.account()!!

    private fun puts(path: String) = requests.filter { it.first == "PUT" && it.second == path }

    @Test
    fun `a goal that is off is sent without a value`() {
        assertEquals("""{"mode":"off"}""", Choice(Mode.OFF, 100).json().toString())
        assertEquals(Choice(Mode.MINUTES, 25), Choice.parse(JSONObject().put("mode", "minutes").put("value", 25)))
        assertEquals(Mode.OFF, Choice.parse(JSONObject().put("mode", "off"))?.mode)
        assertNull(Choice.parse(JSONObject().put("mode", "weekly")))
        assertFalse(Choice(Mode.CARDS, 5001).valid)
        assertFalse(Choice(Mode.MINUTES, 0).valid)
        assertTrue(Choice(Mode.BACKLOG, 100).valid)
    }

    @Test
    fun `a goal chosen on this device moves to a server that still has its default, once`() =
        runBlocking {
            prefs.edit {
                putString(AnkiquestDailyGoal.PREF_MODE, "minutes")
                putInt(AnkiquestDailyGoal.PREF_MINUTES, 25)
            }
            assertEquals(Choice(Mode.MINUTES, 25), AnkiquestDailyGoal.fetch(account()))
            assertEquals(1, puts("/api/goal/hill").size)
            assertEquals(25, puts("/api/goal/hill").single().third!!.getInt("value"))
            goal = JSONObject().put("mode", "cards").put("value", 100)
            assertEquals(Choice(Mode.CARDS, 100), AnkiquestDailyGoal.fetch(account()), "the server is the source of truth afterwards")
            assertEquals(1, puts("/api/goal/hill").size)
            assertEquals(Mode.CARDS, AnkiquestDailyGoal.settings(prefs).mode)
        }

    @Test
    fun `a goal set on another client is never overwritten`() =
        runBlocking {
            prefs.edit { putString(AnkiquestDailyGoal.PREF_MODE, "backlog") }
            goal = JSONObject().put("mode", "minutes").put("value", 30)
            assertEquals(Choice(Mode.MINUTES, 30), AnkiquestDailyGoal.fetch(account()))
            assertTrue(puts("/api/goal/hill").isEmpty())
            assertEquals(30, AnkiquestDailyGoal.settings(prefs).minutes)
        }

    @Test
    fun `the profile's goal is shown offline once nothing waits to move`() {
        val profile = JSONObject().put("goal", JSONObject().put("mode", "minutes").put("value", 40).put("progress", 12))
        prefs.edit { putString(AnkiquestDailyGoal.PREF_MODE, "cards") }
        AnkiquestDailyGoal.remember(profile, prefs)
        assertEquals(Mode.CARDS, AnkiquestDailyGoal.settings(prefs).mode, "a local goal waits for its migration")
        prefs.edit { remove(AnkiquestDailyGoal.PREF_MODE) }
        AnkiquestDailyGoal.remember(profile, prefs)
        assertEquals(Choice(Mode.MINUTES, 40), AnkiquestDailyGoal.choice(prefs))
        prefs.edit { putString(Ankiquest.PREF_USER, "cerro") }
        assertNull(AnkiquestDailyGoal.cached(prefs, AnkiquestHomeData.account(prefs)?.scope), "another account never inherits it")
    }

    @Test
    fun `the server judges goals in cards and minutes`() {
        val local =
            AnkiquestDailyGoal.Status(5, 3, 0, 4, Mode.BACKLOG, 10, AnkiquestDailyGoal.Goal(40))
        val now = TimeManager.time.intTimeMS()
        AnkiquestDailyGoal.remember(JSONObject().put("goal", JSONObject().put("mode", "cards").put("value", 50)), prefs)
        val profile =
            JSONObject()
                .put("day_ends_at", now + 3_600_000)
                .put(
                    "goal",
                    JSONObject()
                        .put("mode", "cards")
                        .put("value", 50)
                        .put("progress", 64)
                        .put("reached", true),
                )
        val shown = AnkiquestDailyGoal.withProfile(local, profile, prefs, now)
        assertEquals(Mode.CARDS, shown.mode)
        assertEquals(50, shown.goal.target)
        assertEquals(64, shown.progress)
        assertTrue(shown.goalDone)
        assertEquals(local, AnkiquestDailyGoal.withProfile(local, profile.put("day_ends_at", now - 1), prefs, now), "yesterday's profile")
        val off = JSONObject().put("goal", JSONObject().put("mode", "off"))
        assertFalse(AnkiquestDailyGoal.withProfile(local, off, prefs, now).goalDone)
    }

    @Test
    fun `a reached backlog goal is reported once a day`() =
        runBlocking {
            addBasicNote()
            col.sched.answerCard(col.sched.card!!, Rating.EASY)
            val profile = JSONObject().put("goal", JSONObject().put("mode", "backlog").put("value", 100).put("reached", false))
            AnkiquestDailyGoal.afterUpload(account(), profile, prefs)
            val reports = requests.filter { it.second == "/api/goal/hill/reached" }
            assertEquals(1, reports.size)
            assertEquals(AnkiquestDecks.studyDay(col, TimeManager.time.intTimeMS()), reports.single().third!!.getLong("day"))
            AnkiquestDailyGoal.afterUpload(account(), profile, prefs)
            assertEquals(1, requests.count { it.second == "/api/goal/hill/reached" })
        }

    @Test
    fun `a backlog goal is not reported when the server already knows or judges the goal itself`() =
        runBlocking {
            addBasicNote()
            col.sched.answerCard(col.sched.card!!, Rating.EASY)
            val reached = JSONObject().put("goal", JSONObject().put("mode", "backlog").put("value", 100).put("reached", true))
            AnkiquestDailyGoal.afterUpload(account(), reached, prefs)
            AnkiquestDailyGoal.afterUpload(account(), JSONObject().put("goal", JSONObject().put("mode", "cards").put("value", 1)), prefs)
            AnkiquestDailyGoal.afterUpload(account(), JSONObject(), prefs)
            assertTrue(requests.none { it.second == "/api/goal/hill/reached" })
        }

    @Test
    fun `a report the server will not take is not sent again`() =
        runBlocking {
            reachedStatus = 409
            assertFalse(AnkiquestSharing.goalReached(account(), 20391))
            reachedStatus = 200
            assertTrue(AnkiquestSharing.goalReached(account(), 20391))
            reachedStatus = 500
            assertFailsWith<AnkiquestSharing.Refusal> { AnkiquestSharing.goalReached(account(), 20391) }
        }

    @Test
    fun `a refused goal carries the server's explanation`() =
        runBlocking {
            val refusal =
                assertFailsWith<AnkiquestSharing.Refusal> {
                    AnkiquestSharing.saveGoal(
                        account(),
                        JSONObject().put("mode", "cards").put("value", 9000),
                    )
                }
            assertEquals(400, refusal.code)
            assertEquals("Pick 1 to 5000 cards.", refusal.text)
            assertFailsWith<IllegalArgumentException> { AnkiquestDailyGoal.save(account(), Choice(Mode.CARDS, 9000)) }
        }

    @Test
    fun `sharing settings change one kind at a time`() =
        runBlocking {
            val loaded = AnkiquestSharing.settings(account())
            assertEquals("private", loaded.policies["quest"])
            assertEquals(3, loaded.dailyCap)
            val saved = AnkiquestSharing.saveSettings(account(), mapOf("level" to "auto"), loaded.dailyCap)
            assertEquals("auto", saved.policies["level"])
            assertEquals("ask", saved.policies["daily_goal"])
            val sent = puts("/api/sharing/hill").single().third!!
            assertEquals(
                setOf("level"),
                sent
                    .getJSONObject("policies")
                    .keys()
                    .asSequence()
                    .toSet(),
            )
            assertEquals(3, sent.getInt("daily_cap"))
            assertFailsWith<IllegalArgumentException> { AnkiquestSharing.saveSettings(account(), emptyMap(), 11) }
        }

    @Test
    fun `settings are never read or written for an account that is no longer signed in`() =
        runBlocking {
            val captured = account()
            prefs.edit { putString(Ankiquest.PREF_TOKEN, "another-credential") }
            assertFailsWith<HomeAccountChanged> { AnkiquestSharing.settings(captured) }
            assertFailsWith<HomeAccountChanged> { AnkiquestDailyGoal.fetch(captured) }
            assertTrue(requests.isEmpty())
        }
}
