// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
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
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [32])
@Category(EmptyApplicationCategory::class)
class AnkiquestFeedTest : RobolectricTest() {
    private class Answer(
        val status: Int,
        val body: String,
    )

    private data class Seen(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    private lateinit var server: HttpServer
    private lateinit var account: HomeAccount
    private val requests = CopyOnWriteArrayList<Seen>()

    /** Answers by path; anything else is 404. */
    @Volatile private var answer: (Seen) -> Answer = { Answer(404, "") }

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val seen =
                Seen(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.use { it.readBytes().decodeToString() },
                )
            requests += seen
            val reply = answer(seen)
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "member name")
            putString(Ankiquest.PREF_TOKEN, "member-test-token")
        }
        account = AnkiquestHomeData.account()!!
    }

    @After
    fun stop() {
        server.stop(0)
        SharedPreferencesTestHook.override = null
    }

    @Test
    fun `a page reads every row the website shows`() {
        val page = AnkiquestFeed.parsePage(JSONObject(PAGE))
        assertEquals(listOf(42L, 6L, 8L, 9L), page.items.map { it.id })
        assertEquals(listOf(42L), page.attention.map { it.id })
        assertEquals(3, page.unread)
        assertEquals(1, page.actionCount)
        assertEquals(42L, page.latestId)
        assertEquals(5L, page.nextBefore)
        assertFalse(page.legacy)

        val (invite, share, cheer, reply) = page.items
        assertEquals(8L, invite.challengeId)
        assertTrue(invite.actionRequired)
        assertFalse(invite.answerable, "challenge news is answered on the goal")
        assertFalse(invite.cheerable)

        assertEquals("https://anki.example.test/w/example-link-2.png", share.image)
        assertTrue(share.reacted)
        assertTrue(share.cheerable)
        assertTrue(share.answerable)
        assertTrue(share.unread)
        assertNull(share.challengeId, "null challenge ids stay null")

        assertEquals("🎉 Cerro cheered: “Daily goal reached!”", cheer.heading)
        assertNull(cheer.text, "a cheer says what it cheered in its heading")
        assertFalse(cheer.cheerable)
        assertTrue(cheer.answerable)

        assertEquals("Deck complete" to "Spanish is done for today.", reply.replyTo)
        assertEquals(1793183500L, reply.readAt)
        assertFalse(reply.unread)
        assertTrue(reply.replied)
        assertFalse(reply.answerable)
    }

    @Test
    fun `rows without a usable id are skipped`() {
        val page =
            AnkiquestFeed.parsePage(
                JSONObject().put("items", JSONArray().put(JSONObject().put("title", "x")).put(JSONObject().put("id", 3))),
            )
        assertEquals(listOf(3L), page.items.map { it.id })
        assertNull(page.latestId)
        assertNull(page.nextBefore)
    }

    @Test
    fun `the feed asks for the owner's page with their token and the chosen filters`() =
        runBlocking {
            answer = { Answer(200, PAGE) }
            val page = AnkiquestFeed.page(account, AnkiquestFeed.Category.DECK_COMPLETIONS, unreadOnly = true, before = 40)
            assertEquals(4, page.items.size)
            val seen = requests.single()
            assertEquals("GET", seen.method)
            assertEquals("/api/activity/member%20name", seen.path)
            assertEquals("limit=50&category=deck_completions&unread_only=true&before=40", seen.query)
            assertEquals("Bearer member-test-token", seen.authorization)

            requests.clear()
            assertFalse(AnkiquestFeed.page(account).unfiltered)
            assertEquals("limit=50", requests.single().query)
        }

    @Test
    fun `a server without the feed answers the last week of notifications, newest first`() =
        runBlocking {
            answer = { seen ->
                if (seen.path == "/api/notifications/member%20name") Answer(200, LEGACY) else Answer(404, "")
            }
            val page = AnkiquestFeed.page(account, AnkiquestFeed.Category.MESSAGES, unreadOnly = true)
            assertTrue(page.legacy)
            assertEquals(listOf(8L, 6L, 1L), page.items.map { it.id })
            assertEquals(0, page.unread)
            assertNull(page.nextBefore)
            assertEquals(listOf("/api/activity/member%20name", "/api/notifications/member%20name"), requests.map { it.path })
        }

    @Test
    fun `an older page that is gone is an error, not the legacy list`() {
        answer = { Answer(404, "") }
        val error = assertFailsWith<AnkiquestHttpException> { runBlocking { AnkiquestFeed.page(account, before = 7) } }
        assertEquals(404, error.code)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a server that does not know a filter answers the whole feed`() =
        runBlocking {
            answer = { seen -> if (seen.query.orEmpty().contains("category")) Answer(400, "") else Answer(200, PAGE) }
            val page = AnkiquestFeed.page(account, AnkiquestFeed.Category.STUDY_UPDATES)
            assertEquals(4, page.items.size)
            assertTrue(page.unfiltered)
            assertEquals(listOf("limit=50&category=study_updates", "limit=50"), requests.map { it.query })
        }

    @Test
    fun `refusals keep the server's explanation`() {
        answer = { Answer(401, """{"error":"Sign in again."}""") }
        val error = assertFailsWith<AnkiquestHttpException> { runBlocking { AnkiquestFeed.page(account) } }
        assertEquals(401, error.code)
        assertEquals("Sign in again.", error.explanation)
    }

    @Test
    fun `read state is sent in batches the server accepts`() =
        runBlocking {
            answer = { Answer(204, "") }
            AnkiquestFeed.markRead(account, (1L..450L).toList() + listOf(0L, -3L, 7L))
            assertEquals(listOf(200, 200, 50), requests.map { JSONObject(it.body).getJSONArray("ids").length() })
            assertTrue(requests.all { it.method == "POST" && it.path == "/api/activity/member%20name/read" })

            requests.clear()
            AnkiquestFeed.markAllRead(account, 42)
            assertEquals("""{"through":42}""", requests.single().body)
        }

    @Test
    fun `a reply and a cheer go to the owner's endpoints`() =
        runBlocking {
            answer = { seen ->
                when (seen.path) {
                    "/api/reply/member%20name" -> Answer(200, """{"sent_to":"Cerro"}""")
                    "/api/react/member%20name" -> Answer(200, """{"already":false}""")
                    else -> Answer(404, "")
                }
            }
            val share = AnkiquestFeed.parsePage(JSONObject(PAGE)).items[1]
            assertEquals("Cerro", AnkiquestFeed.reply(account, share, "  Well\ndone  "))
            assertFalse(AnkiquestFeed.cheer(account, share))
            assertEquals(JSONObject().put("notification", 6).put("message", "Well done").toString(), requests[0].body)
            assertEquals("""{"notification":6}""", requests[1].body)
        }

    @Test
    fun `sent messages say who they went to and when they were seen`() {
        val sent =
            AnkiquestFeed.parseSent(
                JSONObject(
                    """{"items":[{"recipient":"cerro","id":12,"title":"Reply","body":"Thanks!","day":20754,"created_at":1793183400,
                    "sender":"member name","replied":false,"kind":"reply","read_at":1793183600,"challenge_id":null,"route":null,
                    "action_required":false}],"next_before":12}""",
                ),
            )
        val (recipient, item) = sent.items.single()
        assertEquals("cerro", recipient)
        assertEquals("Thanks!", item.body)
        assertEquals(1793183600L, item.readAt)
        assertEquals(12L, sent.nextBefore)
    }

    @Test
    fun `card images are shown only from the account's own server`() {
        val share = AnkiquestFeed.parsePage(JSONObject(PAGE)).items[1]
        val own = HomeAccount("https://anki.example.test", "member", "token")
        assertEquals("https://anki.example.test/w/example-link-2.png", AnkiquestFeed.image(own, share))
        assertNull(AnkiquestFeed.image(HomeAccount("https://other.example.test", "member", "token"), share))
        assertNull(AnkiquestFeed.image(own, share.copy(image = "https://anki.example.test/w/example-link-2")))
        assertNull(AnkiquestFeed.image(own, share.copy(image = "http://anki.example.test/w/example-link-2.png")))
        assertNull(AnkiquestFeed.image(own, share.copy(kind = "completion")))
    }

    companion object {
        /** The documented page shape (docs/mobile-api.md), with rows as the server's examples record them. */
        val PAGE =
            """
            {
              "items": [
                {"id": 42, "title": "Challenge invitation", "body": "alice invited you to Study together.", "day": 20718,
                 "created_at": 1793183600, "sender": "alice", "replied": false, "kind": "challenge_invite", "read_at": null,
                 "challenge_id": 8, "route": "/community#challenge-8", "action_required": true},
                {"action_required": false, "body": "7 day streak!: “A week in a row.”", "challenge_id": null, "created_at": 1793183400,
                 "day": 20754, "id": 6, "image": "https://anki.example.test/w/example-link-2.png", "kind": "share", "reacted": true,
                 "read_at": null, "replied": false, "route": null, "sender": "cerro", "title": "🎉 Cerro shared a win",
                 "topic": "streak", "win": 2},
                {"action_required": false, "body": "“Daily goal reached!”", "challenge_id": null, "created_at": 1793183400,
                 "day": 20754, "id": 8, "kind": "reaction", "read_at": null, "replied": false, "route": null, "sender": "cerro",
                 "title": "🎉 Cerro cheered", "win": 1},
                {"id": 9, "title": "Cerro replied", "body": "Thanks!", "day": 20754, "created_at": 1793180000, "sender": "cerro",
                 "replied": true, "kind": "reply", "read_at": 1793183500, "challenge_id": null, "route": null,
                 "action_required": false, "reply_to": {"title": "Deck complete", "body": "Spanish is done for today."}}
              ],
              "attention": [
                {"id": 42, "title": "Challenge invitation", "body": "alice invited you to Study together.", "day": 20718,
                 "created_at": 1793183600, "sender": "alice", "replied": false, "kind": "challenge_invite", "read_at": null,
                 "challenge_id": 8, "route": "/community#challenge-8", "action_required": true}
              ],
              "unread_count": 3,
              "action_count": 1,
              "latest_id": 42,
              "retention_days": 90,
              "window_days": 90,
              "next_before": 5
            }
            """.trimIndent()

        /** The older oldest-first notification array. */
        val LEGACY =
            """
            [
              {"id": 1, "title": "Daily goal reached!", "body": "5 cards today.", "created_at": 1793183400, "sender": "",
               "replied": false, "kind": "celebration", "win": 1},
              {"id": 6, "title": "🎉 Cerro shared a win", "body": "7 day streak!", "created_at": 1793183400, "sender": "cerro",
               "replied": false, "kind": "share"},
              {"id": 8, "title": "🎉 Cerro cheered", "body": "“Daily goal reached!”", "created_at": 1793183400, "sender": "cerro",
               "replied": false, "kind": "reaction"}
            ]
            """.trimIndent()
    }
}
