// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class AnkiquestLoginHintTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private var deletionStatus = 204

    @Before
    fun prepare() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/accounts/member") { exchange ->
            exchange.requestBody.close()
            if (deletionStatus == 204) {
                exchange.sendResponseHeaders(204, -1)
            } else {
                val bytes = "{\"error\":\"refused\"}".toByteArray()
                exchange.sendResponseHeaders(deletionStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        configure()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun configure(
        name: String = "member",
        address: String = url,
    ) {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, address)
            putString(Ankiquest.PREF_USER, name)
            putString(Ankiquest.PREF_TOKEN, "test-token-$name")
        }
    }

    @Test
    fun `sign-out keeps a username hint without keeping active credentials`() {
        AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))

        assertEquals("member", AnkiquestAccountSettings.signInUsername("$url/"))
        assertNull(AnkiDroidApp.sharedPrefs().getString(Ankiquest.PREF_USER, null))
        assertNull(AnkiDroidApp.sharedPrefs().getString(Ankiquest.PREF_TOKEN, null))
    }

    @Test
    fun `hints belong to the exact server including its path`() {
        AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))
        configure("other", "$url/nested")
        AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))

        assertEquals("member", AnkiquestAccountSettings.signInUsername(url))
        assertEquals("other", AnkiquestAccountSettings.signInUsername("$url/nested/"))
        assertNull(AnkiquestAccountSettings.signInUsername("https://different.example"))
        assertNull(AnkiquestAccountSettings.signInUsername("$url/another-path"))
    }

    @Test
    fun `a stale sign-out cannot replace the current username or credentials`() {
        val old = assertNotNull(HomeAccount.current())
        configure("other")
        AnkiquestAccountSettings.signOut(old)

        assertEquals("other", AnkiquestAccountSettings.signInUsername(url))
        assertEquals("other", AnkiDroidApp.sharedPrefs().getString(Ankiquest.PREF_USER, null))
        assertEquals("test-token-other", AnkiDroidApp.sharedPrefs().getString(Ankiquest.PREF_TOKEN, null))
    }

    @Test
    fun `deleting the account forgets its saved username too`() =
        runBlocking {
            AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))
            configure()
            AnkiquestAccountSettings.delete(assertNotNull(HomeAccount.current()), "password")

            assertNull(AnkiquestAccountSettings.signInUsername(url))
            assertNull(AnkiDroidApp.sharedPrefs().getString(Ankiquest.PREF_TOKEN, null))
        }

    @Test
    fun `a failed deletion keeps the account available`() =
        runBlocking {
            deletionStatus = 403
            val account = assertNotNull(HomeAccount.current())
            assertFailsWith<AnkiquestHttpException> { AnkiquestAccountSettings.delete(account, "password") }

            assertEquals(account, HomeAccount.current())
            assertEquals("member", AnkiquestAccountSettings.signInUsername(url))
        }
}
