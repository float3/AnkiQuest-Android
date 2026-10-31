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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestCelebrationsTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private val posted = CopyOnWriteArrayList<String>()

    @Volatile
    private var settings = """{"decks":[],"recipients":[],"nudges":false}"""

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/decks/hill") { exchange ->
            if (exchange.requestMethod == "POST") {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                posted.add(body)
                settings = JSONObject(settings).put("celebrations", JSONObject(body).getBoolean("celebrations")).toString()
            }
            val response = settings.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
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

    @Test
    fun `celebrations default to on for servers without the setting`() =
        runBlocking {
            assertTrue(Ankiquest.celebrations(Ankiquest.notificationSwitches()))
        }

    @Test
    fun `turning celebrations off only sends that choice`() =
        runBlocking {
            assertFalse(Ankiquest.setNotificationSwitch(Ankiquest.CELEBRATIONS, false).getBoolean("celebrations"))
            assertFalse(Ankiquest.celebrations(Ankiquest.notificationSwitches()))
            val body = JSONObject(posted.single())
            assertEquals(0, body.getJSONArray("decks").length())
            assertFalse(body.has("nudges"), "leaves nudges as they were")
        }
}
