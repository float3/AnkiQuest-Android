// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestServerMoveTest : RobolectricTest() {
    private lateinit var old: HttpServer
    private lateinit var main: HttpServer
    private lateinit var oldUrl: String
    private lateinit var mainUrl: String
    private val mainRequests = CopyOnWriteArrayList<Pair<String, String?>>()

    @Volatile private var mainStatus = 200

    @Volatile private var publicUrl: String? = null

    private val prefs get() = AnkiDroidApp.sharedPrefs()

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(1_750_000_000_000))
        AnkiquestCapabilities.forgetMemory()
        // The stand-in servers speak plain http; only the address check is relaxed for them.
        AnkiquestServerMove.acceptable = { true }
        main =
            serve { path, authorization ->
                mainRequests += path to authorization
                if (path == "/api/devices/cerro" && authorization == "Bearer secret") mainStatus to """{"devices":[]}""" else 401 to "{}"
            }
        mainUrl = "http://127.0.0.1:${main.address.port}"
        old =
            serve { path, _ ->
                if (path == "/api/capabilities") {
                    200 to
                        JSONObject()
                            .put("version", 1)
                            .put("features", JSONArray(listOf("wins")))
                            .put("public_url", publicUrl)
                            .toString()
                } else {
                    404 to "{}"
                }
            }
        oldUrl = "http://127.0.0.1:${old.address.port}"
        publicUrl = "$mainUrl/"
        prefs.edit {
            putString(Ankiquest.PREF_URL, oldUrl)
            putString(Ankiquest.PREF_USER, "cerro")
            putString(Ankiquest.PREF_TOKEN, "secret")
        }
    }

    @After
    fun stop() {
        runCatching { old.stop(0) }
        runCatching { main.stop(0) }
        AnkiquestServerMove.acceptable = { it.isHttps }
        AnkiquestCapabilities.forgetMemory()
        SharedPreferencesTestHook.override = null
    }

    private fun serve(answer: (String, String?) -> Pair<Int, String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (status, body) = answer(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"))
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }

    private fun account() = assertNotNull(HomeAccount.current(prefs))

    /** Per-account records as the app keeps them, keyed by address, account and hashes of both. */
    private fun remember(account: HomeAccount) {
        prefs.edit {
            putLong("ankiquestUploadedThrough:${account.notificationAccount}", 1234L)
            putBoolean("ankiquestInitialSyncDone:${account.notificationAccount}", true)
            putLong("ankiquestCompletionCursor:${account.notificationAccount}", 77L)
            putString("ankiquestCompanion:${account.scope}", "ankilope")
            putLong("ankiquestInboxSyncedAt:${account.scope}", 99L)
            putBoolean(scopedKey("ankiquestGoalSetupShown", sha256(account.server, account.user)), true)
            putString("ankiquestWidgetBoard:5", JSONObject().put("account", account.scope).put("board", "[]").toString())
            putStringSet("ankiquestDeckShareAsked", setOf("${account.scope}:20000:1"))
            putString(
                "ankiquestLastStudySession",
                JSONObject().put("account", AnkiquestNavigation.accountFingerprint(prefs.all)).put("reviews", 3).toString(),
            )
            putLong("ankiquestUploadedThrough:http://elsewhere.test/bo", 5L)
            putString("syncBaseUrl", oldUrl)
        }
    }

    @Test
    fun `only an https main address counts`() {
        AnkiquestServerMove.acceptable = { it.isHttps }
        assertEquals(
            "https://anki.example.test",
            AnkiquestServerMove.mainAddress(JSONObject("""{"public_url":" https://anki.example.test/ "}""")),
        )
        for (answer in listOf(
            """{"public_url":"http://anki.example.test"}""",
            """{"public_url":""}""",
            "{}",
            """{"public_url":"https://a@b.test"}""",
        )) {
            assertNull(AnkiquestServerMove.mainAddress(JSONObject(answer)), answer)
        }
    }

    @Test
    fun `the account moves to the main address once its token works there and keeps its records`() =
        runBlocking {
            val before = account()
            remember(before)
            val fingerprint = AnkiquestNavigation.accountFingerprint(prefs.all)

            AnkiquestCapabilities.refresh(before)

            assertEquals(mainUrl, prefs.getString(Ankiquest.PREF_URL, null))
            assertEquals(listOf("/api/devices/cerro" to "Bearer secret"), mainRequests.toList())
            val after = account()
            assertEquals(mainUrl, after.server)
            assertEquals(1234L, prefs.getLong("ankiquestUploadedThrough:${after.notificationAccount}", 0L))
            assertTrue(prefs.getBoolean("ankiquestInitialSyncDone:${after.notificationAccount}", false))
            assertEquals(77L, prefs.getLong("ankiquestCompletionCursor:${after.notificationAccount}", 0L))
            assertEquals("ankilope", AnkiquestCompanion.selected())
            assertEquals(99L, AnkiquestNotificationHealth.lastSuccessfulSync(prefs))
            assertTrue(prefs.getBoolean(scopedKey("ankiquestGoalSetupShown", sha256(after.server, after.user)), false))
            assertEquals(after.scope, JSONObject(prefs.getString("ankiquestWidgetBoard:5", null)!!).getString("account"))
            assertEquals(setOf("${after.scope}:20000:1"), prefs.getStringSet("ankiquestDeckShareAsked", null))
            val session = JSONObject(prefs.getString("ankiquestLastStudySession", null)!!)
            assertEquals(AnkiquestNavigation.accountFingerprint(prefs.all), session.getString("account"))
            assertFalse(session.getString("account") == fingerprint)
            assertTrue(AnkiquestCapabilities.has(after, "wins"))

            assertFalse(prefs.all.keys.any { it.contains(before.scope) || it.contains(":$oldUrl") })
            assertEquals(5L, prefs.getLong("ankiquestUploadedThrough:http://elsewhere.test/bo", 0L))
            assertEquals(oldUrl, prefs.getString("syncBaseUrl", null))
        }

    @Test
    fun `the account stays when its token fails at the main address`() =
        runBlocking {
            val before = account()
            remember(before)
            val saved = prefs.all
            mainStatus = 401
            AnkiquestCapabilities.refresh(before)
            assertEquals(1, mainRequests.size)
            assertEquals(saved, prefs.all.filterKeys { !it.startsWith(AnkiquestCapabilities.PREF_CAPABILITIES) })
        }

    @Test
    fun `the account stays while the main address cannot be reached`() =
        runBlocking {
            val before = account()
            remember(before)
            main.stop(0)
            AnkiquestCapabilities.refresh(before)
            assertEquals(oldUrl, prefs.getString(Ankiquest.PREF_URL, null))
            assertEquals(1234L, prefs.getLong("ankiquestUploadedThrough:${before.notificationAccount}", 0L))
        }

    @Test
    fun `an account already at the main address, or a plain http one, asks nothing more`() =
        runBlocking {
            publicUrl = "$oldUrl/"
            AnkiquestCapabilities.refresh(account())
            AnkiquestServerMove.acceptable = { it.isHttps }
            publicUrl = mainUrl
            AnkiquestCapabilities.refresh(account())
            assertEquals(emptyList(), mainRequests.toList())
            assertEquals(oldUrl, prefs.getString(Ankiquest.PREF_URL, null))
        }

    @Test
    fun `an account changed in the meantime is not moved`() {
        val before = account()
        prefs.edit { putString(Ankiquest.PREF_TOKEN, "another") }
        assertFalse(AnkiquestServerMove.move(prefs, before, before.copy(server = mainUrl)))
        assertEquals(oldUrl, prefs.getString(Ankiquest.PREF_URL, null))
    }
}
