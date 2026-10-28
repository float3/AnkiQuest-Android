// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestStreakProtectionTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()

    @Volatile private var response = """{"enabled":true,"freezes":2,"capacity":3}"""

    @Volatile private var status = 200

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(
                Triple(
                    "${exchange.requestMethod} ${exchange.requestURI.rawPath}",
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.bufferedReader().readText(),
                ),
            )
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "cerro two")
            putString(Ankiquest.PREF_TOKEN, "saved-token")
        }
    }

    @After
    fun stopServer() {
        server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `native settings load streak protection with saved credentials without uploading reviews`() =
        runBlocking {
            assertTrue(Ankiquest.streakProtectionEnabled())
            assertEquals(Triple("GET /api/streak-freezes/cerro%20two", "Bearer saved-token", ""), requests.single())
        }

    @Test
    fun `native toggle sends only enabled and reflects the server response`() =
        runBlocking {
            response = """{"enabled":false,"freezes":2,"capacity":3}"""
            assertFalse(Ankiquest.setStreakProtection(true))
            val sent = requests.single()
            assertEquals("POST /api/streak-freezes/cerro%20two", sent.first)
            assertEquals("Bearer saved-token", sent.second)
            assertEquals(setOf("enabled"), JSONObject(sent.third).keys().asSequence().toSet())
            assertTrue(JSONObject(sent.third).getBoolean("enabled"))
        }

    @Test
    fun `protection can be paused without discarding saved freezes`() =
        runBlocking {
            response = """{"enabled":false,"freezes":2,"capacity":3}"""
            assertFalse(Ankiquest.setStreakProtection(false))
            assertFalse(JSONObject(requests.single().third).getBoolean("enabled"))
        }

    @Test
    fun `rejected or malformed settings never become an off default`() =
        runBlocking<Unit> {
            status = 401
            assertFailsWith<Exception> { Ankiquest.streakProtectionEnabled() }
            assertFailsWith<Exception> { Ankiquest.setStreakProtection(true) }
            status = 200
            response = "{}"
            assertFailsWith<Exception> { Ankiquest.streakProtectionEnabled() }
        }

    @Test
    fun `missing token does not send a request`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }
            assertFailsWith<IllegalStateException> { Ankiquest.setStreakProtection(true) }
            assertTrue(requests.isEmpty())
        }
}
