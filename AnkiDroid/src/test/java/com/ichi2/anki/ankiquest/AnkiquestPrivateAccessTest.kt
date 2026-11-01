// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestPrivateAccessTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = CopyOnWriteArrayList<ReceivedRequest>()
    private val sessionCookie = "ankiquest_session=opaque-session; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800"

    @Volatile
    private var sessionStatus = 204

    @Volatile
    private var responseCookie: String? = sessionCookie

    private data class ReceivedRequest(
        val path: String,
        val method: String,
        val authorization: String?,
        val body: String,
    )

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests.add(
                ReceivedRequest(
                    path,
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.bufferedReader().use { it.readText() },
                ),
            )
            if (path == "/auth/session") {
                responseCookie?.let { exchange.responseHeaders.add("Set-Cookie", it) }
                if (sessionStatus == 302) exchange.responseHeaders.add("Location", "$url/redirected")
                exchange.sendResponseHeaders(sessionStatus, -1)
            } else {
                val body = (if (path == "/api/leaderboard") "[]" else "{}").toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, url)
            putString(Ankiquest.PREF_USER, "member name")
            putString(Ankiquest.PREF_TOKEN, " member-secret ")
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `private leaderboard and profile reads send the member token`() =
        runBlocking {
            Ankiquest.leaderboard()
            Ankiquest.profile()

            assertEquals(setOf("/api/leaderboard", "/api/profile/member%20name"), requests.map { it.path }.toSet())
            assertTrue(requests.all { it.authorization == "Bearer member-secret" })
            assertTrue(requests.all { it.method == "GET" && it.body.isEmpty() })
        }

    @Test
    fun `public server reads still work without a configured token`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }

            Ankiquest.leaderboard()
            Ankiquest.profile()

            assertEquals(setOf("/api/leaderboard", "/api/profile/member%20name"), requests.map { it.path }.toSet())
            assertTrue(requests.all { it.authorization == null })
        }

    private val readingUnderTest = ThreadLocal<Boolean>()

    private fun switchServerDuringSettingsRead() {
        val preferences = targetContext.sharedPrefs()
        val changingPreferences = mockk<SharedPreferences>()

        fun <T> switchServerAfter(read: () -> T): T {
            val captured = read()
            if (readingUnderTest.get() == true) {
                preferences.edit {
                    putString(Ankiquest.PREF_URL, "https://private.example.test")
                    putString(Ankiquest.PREF_TOKEN, "new-private-secret")
                }
            }
            return captured
        }
        every { changingPreferences.getString(any(), any()) } answers { preferences.getString(firstArg(), secondArg()) }
        every { changingPreferences.getString(Ankiquest.PREF_URL, any()) } answers {
            switchServerAfter { preferences.getString(Ankiquest.PREF_URL, secondArg()) }
        }
        every { changingPreferences.all } answers { switchServerAfter { preferences.all } }
        AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
    }

    private suspend fun <T> readingSettings(block: suspend () -> T): T = withContext(readingUnderTest.asContextElement(true)) { block() }

    private fun assertServerSwitched() =
        assertEquals("https://private.example.test", targetContext.sharedPrefs().getString(Ankiquest.PREF_URL, ""))

    @Test
    fun `account changes never send new server credentials to the captured server`() =
        runBlocking {
            switchServerDuringSettingsRead()

            readingSettings { runCatching { Ankiquest.profile() } }

            assertServerSwitched()
            assertTrue(requests.filter { it.path == "/api/profile/member%20name" }.all { it.authorization == "Bearer member-secret" })
            assertTrue(requests.none { it.authorization == "Bearer new-private-secret" })
        }

    @Test
    fun `tokenless settings checks remain anonymous after account changes`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }
            switchServerDuringSettingsRead()

            readingSettings { Ankiquest.runFromSettings(targetContext, uploadAll = false) }

            assertServerSwitched()
            assertTrue(requests.all { it.authorization == null })
        }

    @Test
    fun `browser session exchanges bearer only in the request header`() =
        runBlocking {
            val dashboard = Ankiquest.dashboardUrl()!!

            assertEquals(sessionCookie, Ankiquest.dashboardSession(dashboard))
            assertEquals(ReceivedRequest("/auth/session", "POST", "Bearer member-secret", ""), requests.toSet().single())
            assertEquals("$url/#member%20name", dashboard)
            assertFalse(dashboard.contains("member-secret"))
        }

    @Test
    fun `browser session skips an unconfigured token`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }

            assertNull(Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!))
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `browser session never sends credentials for a stale dashboard`() =
        runBlocking {
            val dashboard = Ankiquest.dashboardUrl()!!
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_URL, "https://new.example.test") }

            assertNull(Ankiquest.dashboardSession(dashboard))
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `older servers without a session endpoint remain supported`() =
        runBlocking {
            sessionStatus = 404

            assertNull(Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!))
            assertEquals(setOf(ReceivedRequest("/auth/session", "POST", "Bearer member-secret", "")), requests.toSet())
        }

    @Test
    fun `invalid member token preserves unauthorized status`() =
        runBlocking {
            sessionStatus = 401

            val error = assertFailsWith<AnkiquestHttpException> { Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!) }

            assertEquals(401, error.code)
        }

    @Test
    fun `session exchange refuses redirects`() =
        runBlocking {
            sessionStatus = 302

            val error = assertFailsWith<AnkiquestHttpException> { Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!) }

            assertEquals(302, error.code)
            assertEquals(listOf("/auth/session"), requests.map { it.path })
        }

    @Test
    fun `browser accepts only its host scoped HttpOnly session cookie`() =
        runBlocking {
            for (cookie in listOf(
                null,
                "other=value; Path=/; HttpOnly",
                "ankiquest_session=value; Path=/",
                "ankiquest_session=value; Path=/; HttpOnly; Domain=127.0.0.1",
                "ankiquest_session=value; Path=/other; HttpOnly",
            )) {
                responseCookie = cookie
                assertFailsWith<IOException> { Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!) }
            }
        }
}
