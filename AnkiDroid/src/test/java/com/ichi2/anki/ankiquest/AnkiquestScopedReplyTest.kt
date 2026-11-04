// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [32])
@Category(EmptyApplicationCategory::class)
class AnkiquestScopedReplyTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var captured: HomeAccount
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()

    @Volatile private var switchDuringResponse = false

    @Volatile private var redirect = false

    @Before
    fun prepare() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to exchange.requestHeaders.getFirst("Authorization")
            exchange.requestBody.close()
            if (switchDuringResponse) AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_USER, "bob") }
            if (redirect) exchange.responseHeaders.set("Location", "/other-destination")
            val response = "{\"sent_to\":\"Friend\"}".toByteArray()
            exchange.sendResponseHeaders(if (redirect) 307 else 200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "alice")
            putString(Ankiquest.PREF_TOKEN, "alice-test-token")
        }
        captured = AnkiquestHomeData.account()!!
    }

    @After
    fun stop() {
        server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    private fun replyData(): Data =
        AnkiquestReply.data(
            Intent()
                .putExtra(AnkiquestReply.EXTRA_NOTIFICATION, 7L)
                .putExtra(AnkiquestReply.EXTRA_TAG, 5_140_007)
                .putExtra(AnkiquestReply.EXTRA_REPLY_ACCOUNT, captured.notificationAccount)
                .putExtra(AnkiquestReply.EXTRA_REPLY_SCOPE, captured.scope),
            "Well done",
        )

    @Test
    fun `reply carries captured owner credentials and never persists the token`() =
        runBlocking {
            val data = replyData()
            assertEquals(captured.scope, data.getString(AnkiquestReply.EXTRA_REPLY_SCOPE))
            assertFalse(data.toString().contains(captured.token))
            assertEquals("Friend", Ankiquest.reply(7, "Well done", captured.notificationAccount, captured.scope))
            assertEquals(listOf("/api/reply/alice" to "Bearer alice-test-token"), requests.toList())
        }

    @Test
    fun `missing owner metadata and changed account or credentials never send`() =
        runBlocking {
            val missing =
                Data
                    .Builder()
                    .putLong(AnkiquestReply.EXTRA_NOTIFICATION, 7L)
                    .putString(AnkiquestReply.EXTRA_MESSAGE, "Well done")
                    .build()
            assertEquals(AnkiquestReply.Outcome.FAILED, AnkiquestReply.run(targetContext, missing, 0))
            val original = AnkiDroidApp.sharedPrefs().all
            for ((key, value) in listOf(
                Ankiquest.PREF_USER to "bob",
                Ankiquest.PREF_TOKEN to "new-token",
                Ankiquest.PREF_URL to "http://127.0.0.1:9",
            )) {
                AnkiDroidApp.sharedPrefs().edit { putString(key, value) }
                assertEquals(AnkiquestReply.Outcome.FAILED, AnkiquestReply.run(targetContext, replyData(), 0))
                assertFailsWith<HomeAccountChanged> { Ankiquest.reply(7, "Well done", captured.notificationAccount, captured.scope) }
                AnkiDroidApp.sharedPrefs().edit { putString(key, original[key] as String) }
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a midflight switch cannot change the request owner or publish its response to the next account`() =
        runBlocking {
            switchDuringResponse = true
            val manager = targetContext.getSystemService<NotificationManager>()!!
            manager.cancelAll()
            assertEquals(AnkiquestReply.Outcome.FAILED, AnkiquestReply.run(targetContext, replyData(), 0))
            assertEquals(listOf("/api/reply/alice" to "Bearer alice-test-token"), requests.toList())
            assertEquals(0, shadowOf(manager).size())
            AnkiquestNotifier.onDeckCompletions(
                targetContext,
                captured.notificationAccount,
                JSONArray().put(JSONObject().put("id", 8)),
                captured.scope,
            )
            assertEquals(0, shadowOf(manager).size())
            assertFalse(AnkiDroidApp.sharedPrefs().contains("ankiquestCompletionCursor:${captured.notificationAccount}"))
        }

    @Test
    fun `a reply redirect cannot forward the message to a new destination`() =
        runBlocking {
            redirect = true
            assertFailsWith<AnkiquestHttpException> { Ankiquest.reply(7, "Well done", captured.notificationAccount, captured.scope) }
            assertEquals(1, requests.size)
            assertEquals("/api/reply/alice", requests.single().first)
        }
}
