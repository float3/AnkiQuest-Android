// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestHomeDataTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var account: HomeAccount
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val languages = CopyOnWriteArrayList<String?>()

    @Volatile private var status = 200

    @Before
    fun prepare() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        account = HomeAccount("http://127.0.0.1:${server.address.port}", "member name", "secret")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
            languages += exchange.requestHeaders.getFirst("Accept-Language")
            if (status == 302) exchange.responseHeaders.set("Location", "/elsewhere")
            val bytes = "{\"user\":\"member name\",\"level\":2}".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `profile request carries the captured Anki language`() =
        runBlocking {
            AnkiquestHomeData.profile(account.copy(language = "es-ES"))
            assertEquals(listOf("es-ES"), languages.toList())
            assertEquals(account.scope, account.copy(language = "es-ES").scope)
        }

    @Test
    fun `profile reads are owner authenticated and user path is encoded`() =
        runBlocking {
            assertEquals(2, AnkiquestHomeData.profile(account).getInt("level"))
            assertEquals(listOf("/api/profile/member%20name" to "Bearer secret"), requests.toList())
            requests.clear()
            AnkiquestHomeData.profile(account.copy(token = ""))
            assertNull(requests.single().second, "a public server is read anonymously")
        }

    @Test
    fun `profile failures and redirects are reported, never followed`() =
        runBlocking {
            for (code in listOf(302, 401, 503)) {
                status = code
                assertEquals(code, assertFailsWith<AnkiquestHttpException> { AnkiquestHomeData.profile(account) }.code)
            }
            assertEquals(3, requests.size)
        }

    @Test
    fun `scope changes for server member and credential`() {
        assertFalse(account.toString().contains(account.token))
        listOf(account.copy(server = "https://other.example"), account.copy(user = "other"), account.copy(token = "new-token")).forEach {
            assertNotEquals(account.scope, it.scope)
        }
    }

    private val firstSettings =
        mapOf(
            Ankiquest.PREF_URL to "https://first.example",
            Ankiquest.PREF_USER to " ",
            Ankiquest.PREF_TOKEN to "first-token",
            "username" to "first member",
        )
    private val secondSettings =
        mapOf(
            Ankiquest.PREF_URL to "https://second.example",
            Ankiquest.PREF_USER to "",
            Ankiquest.PREF_TOKEN to "second-token",
            "username" to "second member",
        )

    @Test
    fun `fallback member and credentials are read from the current settings`() {
        var stored = firstSettings
        val preferences = mockk<SharedPreferences>()
        every { preferences.getString(any(), any()) } answers { stored[firstArg<String>()] }

        val first = AnkiquestHomeData.account(preferences)
        assertEquals(HomeAccount("https://first.example", "first member", "first-token"), first)
        assertSame(first, AnkiquestHomeData.account(preferences), "unchanged settings keep the account and its scope")
        stored = secondSettings
        assertEquals(HomeAccount("https://second.example", "second member", "second-token"), AnkiquestHomeData.account(preferences))
        verify(exactly = 0) { preferences.all }
    }

    @Test
    fun `settings changed while they are read never mix two accounts`() {
        var stored = firstSettings
        val preferences = mockk<SharedPreferences>()
        every { preferences.getString(any(), any()) } answers {
            val key = firstArg<String>()
            stored[key].also { if (key == Ankiquest.PREF_URL) stored = secondSettings }
        }

        assertEquals(HomeAccount("https://second.example", "second member", "second-token"), AnkiquestHomeData.account(preferences))
    }

    @Test
    // Synthetic credentials on a reserved example domain verify that URL userinfo is rejected.
    @SuppressLint("AuthLeak")
    fun `blank and ambiguous server URLs cannot carry credentials`() {
        fun settings(url: String) = mapOf(Ankiquest.PREF_URL to url, Ankiquest.PREF_USER to "member", Ankiquest.PREF_TOKEN to "secret")
        listOf(
            "",
            "not a url",
            "https://user:password@example.com",
            "https://example.com?other=host",
            "https://example.com#section",
        ).forEach {
            assertNull(HomeAccount.from(settings(it), ""))
        }
        assertEquals("https://example.com/member", HomeAccount.from(settings(" https://example.com/ "), "")!!.notificationAccount)
    }

    @Test
    fun `local deck focus retains native scheduler counts and selected deck`() {
        val selected = HomeDeck(12, "Spanish", 3, 2, 19)
        val other = HomeDeck(13, "Biology", 0, 0, 11)
        assertEquals(24, selected.due)
        assertEquals(selected, HomeLocal(listOf(other, selected), 12).focus)
        assertEquals(other, HomeLocal(listOf(other, selected), 999).focus)
        assertEquals(
            other,
            HomeLocal(listOf(selected.copy(new = 0, learning = 0, review = 0, daily = DailyDeckState(0, 0, 0)), other), 12).focus,
        )
    }
}
