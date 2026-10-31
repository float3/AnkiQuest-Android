// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.utils.ext.getParcelableExtraCompat
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestShareTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val posted = CopyOnWriteArrayList<String>()
    private val imageAuthorizations = CopyOnWriteArrayList<String?>()
    private val card = "not really a png".toByteArray()

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/share/hill") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            posted.add(body)
            val request = JSONObject(body)
            val answer =
                when (request.optString("to")) {
                    "link" ->
                        JSONObject()
                            .put("url", "$base/w/token")
                            .put("image", "$base/w/token.png")
                            .put("text", "Level 54! Study with me: $base/w/token")
                    "friends" -> JSONObject().put("recipients", 3).put("link", JSONObject().put("url", "$base/w/token"))
                    else -> JSONObject().put("recipients", 2)
                }
            respond(exchange, 200, answer.toString().toByteArray())
        }
        server.createContext("/w/token.png") { exchange ->
            imageAuthorizations.add(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, card)
        }
        server.createContext("/w/huge.png") { exchange -> respond(exchange, 200, ByteArray(5 * 1024 * 1024)) }
        server.createContext("/w/gone.png") { exchange -> respond(exchange, 404, ByteArray(0)) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, base)
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        bytes: ByteArray,
    ) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        exchange.close()
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `older wins are sent to the server by notification or by place`() =
        runBlocking {
            val scope = AnkiquestHomeData.account()!!.scope
            assertEquals(2, Ankiquest.shareWin(42, null, scope))
            assertEquals(2, Ankiquest.shareWin(null, 1, scope))
            assertEquals(42, JSONObject(posted[0]).getLong("notification"))
            assertFalse(JSONObject(posted[0]).has("place"))
            assertEquals(1, JSONObject(posted[1]).getInt("place"))
        }

    @Test
    fun `a win the server keeps is shared by its id`() =
        runBlocking {
            val scope = AnkiquestHomeData.account()!!.scope
            assertEquals(3, Ankiquest.shareWinWithFriends(17, scope))
            val link = Ankiquest.shareWinLink(17, scope)
            assertEquals(
                listOf(17L to "friends", 17L to "link"),
                posted.map {
                    JSONObject(it).let { body ->
                        body.getLong("win") to
                            body.getString("to")
                    }
                },
            )
            assertEquals("$base/w/token", link.url)
            assertEquals("$base/w/token.png", link.image)
            assertTrue(link.text.contains(link.url))
        }

    @Test
    fun `a win is never shared by an account it does not belong to`() =
        runBlocking {
            val scope = AnkiquestHomeData.account()!!.scope
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "another-credential") }
            assertFailsWith<HomeAccountChanged> { Ankiquest.shareWin(42, null, scope) }
            assertFailsWith<HomeAccountChanged> { Ankiquest.shareWinWithFriends(17, scope) }
            assertFailsWith<HomeAccountChanged> { Ankiquest.shareWinLink(17, scope) }
            assertTrue(posted.isEmpty())
        }

    @Test
    fun `the card is fetched without credentials and within its size`() {
        assertContentEquals(card, AnkiquestShare.image("$base/w/token.png"))
        assertEquals(listOf<String?>(null), imageAuthorizations.toList())
        assertFailsWith<IOException> { AnkiquestShare.image("$base/w/huge.png") }
        assertFailsWith<IOException> { AnkiquestShare.image("$base/w/gone.png") }
        assertFailsWith<IOException> { AnkiquestShare.image("file:///data/secret.png") }
        assertNull(AnkiquestShare.picture("$base/w/gone.png"))
    }

    @Test
    fun `without its card a link is shared as text, which carries the link`() {
        val link = AnkiquestShare.Link("$base/w/token", "$base/w/huge.png", "Level 54! $base/w/token")
        val chooser = AnkiquestShare.linkChooser(targetContext, link)
        val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
        assertEquals("text/plain", send.type)
        assertEquals(link.text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun `wins from older servers are shared as a line of text`() {
        val chooser = AnkiquestShare.textChooser(targetContext, "Level 54", "You reached level 54.")
        val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("Level 54") && text.contains(base))
    }

    @Test
    fun `only your own wins are shareable`() {
        assertTrue(AnkiquestShare.shareable("celebration"))
        assertTrue(AnkiquestShare.shareable("challenge_complete"))
        assertTrue(AnkiquestShare.shareable("reminder_weekly_recap"))
        assertFalse(AnkiquestShare.shareable("completion"))
        assertFalse(AnkiquestShare.shareable("share"))
    }

    @Test
    fun `the share button opens the chooser with the win`() {
        val action =
            assertNotNull(
                AnkiquestShare.action(targetContext, 1, "Level 54", "You reached level 54.", 17, 42, null, null),
            )
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(AnkiquestShareActivity::class.java.name, intent.component?.className)
        assertEquals("Level 54", intent.getStringExtra(AnkiquestShare.EXTRA_TITLE))
        assertEquals(17, intent.getLongExtra(AnkiquestShare.EXTRA_WIN, 0))
        assertEquals(42, intent.getLongExtra(AnkiquestShare.EXTRA_NOTIFICATION, 0))
        assertEquals(AnkiquestHomeData.account()!!.scope, intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `no share button for another account or a signed-out player`() {
        assertNull(AnkiquestShare.action(targetContext, 1, "Level 54", "", 17, 42, null, "someone-else"))
        AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }
        assertNull(AnkiquestShare.action(targetContext, 1, "Level 54", "", 17, 42, null, null))
    }

    @Test
    fun `place sharing is for climbing, not for slipping or staying`() {
        val before = listOf("a", "b", "c", "me")
        assertEquals(1, AnkiquestNotifier.climbedTo(before, listOf("me", "a", "b", "c"), "me"))
        assertEquals(3, AnkiquestNotifier.climbedTo(before, listOf("a", "b", "me", "c"), "me"))
        assertNull(AnkiquestNotifier.climbedTo(listOf("me", "a"), listOf("a", "me"), "me"))
        assertNull(AnkiquestNotifier.climbedTo(before, before, "me"))
        assertNull(AnkiquestNotifier.climbedTo(emptyList(), listOf("me"), "me"))
    }
}
