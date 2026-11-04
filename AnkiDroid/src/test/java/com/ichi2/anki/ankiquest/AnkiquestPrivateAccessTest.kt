// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
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
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
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
            val body = (if (path == "/api/leaderboard") "[]" else "{}").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
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
    fun `a saved plain http address on the internet is signed out with the reason shown`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_URL, "http://quest.example/") }

            assertNull(HomeAccount.current())
            assertFalse(Ankiquest.isConfigured())
            assertNull(Ankiquest.dashboardUrl())
            assertEquals(
                targetContext.getString(R.string.ankiquest_check_insecure, "http://quest.example/"),
                Ankiquest.runFromSettings(targetContext, uploadAll = false),
            )
            assertTrue(requests.isEmpty())
        }
}
