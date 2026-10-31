// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
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
    private val posted = CopyOnWriteArrayList<String>()

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/share/hill") { exchange ->
            posted.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = """{"recipients":2}""".toByteArray()
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
    fun `a win is sent to the server by notification or by place`() =
        runBlocking {
            val scope = AnkiquestHomeData.account()!!.scope
            assertEquals(2, Ankiquest.shareWin(42, null, scope))
            assertEquals(2, Ankiquest.shareWin(null, 1, scope))
            assertEquals(42, JSONObject(posted[0]).getLong("notification"))
            assertFalse(JSONObject(posted[0]).has("place"))
            assertEquals(1, JSONObject(posted[1]).getInt("place"))
        }

    @Test
    fun `a win is never shared by an account it does not belong to`() =
        runBlocking {
            val scope = AnkiquestHomeData.account()!!.scope
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "another-credential") }
            assertFailsWith<HomeAccountChanged> { Ankiquest.shareWin(42, null, scope) }
            assertTrue(posted.isEmpty())
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
                AnkiquestShare.action(targetContext, 1, "Level 54", "You reached level 54.", 42, null, null, R.drawable.aki_winner),
            )
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(AnkiquestShareActivity::class.java.name, intent.component?.className)
        assertEquals("Level 54", intent.getStringExtra(AnkiquestShare.EXTRA_TITLE))
        assertEquals(42, intent.getLongExtra(AnkiquestShare.EXTRA_NOTIFICATION, 0))
        assertEquals(AnkiquestHomeData.account()!!.scope, intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `no share button for another account or a signed-out player`() {
        assertNull(AnkiquestShare.action(targetContext, 1, "Level 54", "", 42, null, "someone-else", R.drawable.aki_winner))
        AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }
        assertNull(AnkiquestShare.action(targetContext, 1, "Level 54", "", 42, null, null, R.drawable.aki_winner))
    }

    @Test
    fun `the card is a square picture`() {
        val card = AnkiquestShare.card(targetContext, "Level 54", "You reached level 54 with 837447 XP.", R.drawable.aki_winner)
        assertEquals(1080, card.width)
        assertEquals(1080, card.height)
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
