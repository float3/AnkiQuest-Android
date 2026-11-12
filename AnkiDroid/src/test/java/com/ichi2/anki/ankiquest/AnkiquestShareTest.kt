// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.service.chooser.ChooserAction
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.utils.ext.getParcelableExtraCompat
import com.ichi2.testutils.EmptyApplication
import com.ichi2.testutils.ShadowFileProviderPathStrategy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, shadows = [ShadowFileProviderPathStrategy::class])
@Category(EmptyApplicationCategory::class)
class AnkiquestShareTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val posted = CopyOnWriteArrayList<String>()
    private val asked = CopyOnWriteArrayList<String>()
    private val imageAuthorizations = CopyOnWriteArrayList<String?>()
    private val card = "not really a png".toByteArray()

    @Before
    fun startServer() {
        // Robolectric changes cache directories between tests; AndroidX caches their old roots.
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
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
        server.createContext("/api/wins/hill") { exchange ->
            asked.add("${exchange.requestMethod} ${exchange.requestURI}")
            val wins = JSONObject().put("wins", org.json.JSONArray().put(JSONObject().put("id", 17).put("key", "conquered:1793183400000")))
            respond(exchange, 200, wins.toString().toByteArray())
        }
        server.createContext("/api/conquests/hill/") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            asked.add("${exchange.requestMethod} ${exchange.requestURI} $body".trim())
            if (exchange.requestMethod == "GET") {
                respond(exchange, 200, JSONObject().put("label", "der Hund").toString().toByteArray())
            } else {
                respond(exchange, 204, ByteArray(0))
            }
        }
        server.createContext("/api/decks/hill/share") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            asked.add("${exchange.requestMethod} ${exchange.requestURI} $body")
            respond(exchange, 200, JSONObject().put("recipients", 2).toString().toByteArray())
        }
        server.createContext("/w/token.png") { exchange ->
            imageAuthorizations.add(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, card)
        }
        server.createContext("/w/huge.png") { exchange -> respond(exchange, 200, ByteArray(5 * 1024 * 1024)) }
        server.createContext("/w/gone.png") { exchange -> respond(exchange, 404, ByteArray(0)) }
        val png =
            ByteArrayOutputStream().use { out ->
                check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", out))
                out.toByteArray()
            }
        server.createContext("/w/valid.png") { exchange -> respond(exchange, 200, png) }
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
        SharedPreferencesTestHook.override = null
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
    fun `an unavailable server card gets a local picture and keeps the link`() {
        val link = AnkiquestShare.Link("$base/w/token", "$base/w/huge.png", "Level 54! $base/w/token")
        val chooser = AnkiquestShare.linkChooser(targetContext, link)
        val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
        assertEquals("image/png", send.type)
        assertEquals(link.text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.hasExtra(Intent.EXTRA_STREAM))
        val uri = assertNotNull(send.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM))
        val bitmap = targetContext.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        assertNotNull(bitmap, "the shared stream contains a readable picture")
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val download =
            assertIs<Intent>(
                assertNotNull(
                    IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_INITIAL_INTENTS, Intent::class.java),
                ).single(),
            )
        assertEquals(AnkiquestGalleryActivity::class.java.name, download.component?.className)
        assertEquals(uri, download.data, "Download picture uses the exact PNG offered to other apps")
        assertEquals(AnkiquestHomeData.account()!!.scope, download.getStringExtra(AnkiquestShare.EXTRA_SCOPE))
    }

    @Test
    fun `wins from older servers include a local picture`() {
        val chooser = AnkiquestShare.textChooser(targetContext, "Level 54", "You reached level 54.")
        val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
        assertEquals("image/png", send.type)
        assertTrue(send.hasExtra(Intent.EXTRA_STREAM))
        val text = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("Level 54") && text.contains(base))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an available canonical picture is preferred to the local render`() {
        val bitmap = AnkiquestShare.picture(targetContext, AnkiquestShare.Card("Local", "Fallback"), "$base/w/valid.png")
        assertEquals(2, bitmap.width)
        assertEquals(2, bitmap.height)
        bitmap.recycle()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `an invalid server PNG still produces a local picture`() {
        val bitmap = AnkiquestShare.picture(targetContext, AnkiquestShare.Card("A win", "Saved locally"), "$base/w/token.png")
        assertEquals(1080, bitmap.width)
        bitmap.recycle()
    }

    @Test
    fun `a malformed server image address still attaches the local picture`() {
        val chooser = AnkiquestShare.cardChooser(targetContext, "A public win", "not a valid image URL")
        val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
        assertEquals("image/png", send.type)
        assertTrue(send.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun `rendering errors preserve the text when a picture cannot be allocated`() {
        mockkObject(AnkiquestPicture)
        try {
            for (error in listOf(IllegalStateException("renderer unavailable"), OutOfMemoryError("synthetic allocation failure"))) {
                every { AnkiquestPicture.render(any(), any()) } throws error
                val chooser = AnkiquestShare.textChooser(targetContext, "A win", "Still shareable")
                val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
                assertEquals("text/plain", send.type)
                assertTrue(send.getStringExtra(Intent.EXTRA_TEXT).orEmpty().contains("Still shareable"))
                assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
            }
        } finally {
            unmockkObject(AnkiquestPicture)
        }
    }

    @Test
    fun `notification images retain their longer network deadline`() {
        server.createContext("/w/notification.png") { exchange ->
            Thread.sleep(2_000)
            respond(exchange, 200, card)
        }
        assertContentEquals(card, AnkiquestShare.image("$base/w/notification.png"))
    }

    @Test
    fun `a stalled image request promptly uses the local picture`() {
        val release = CountDownLatch(1)
        server.createContext("/w/stalled.png") { exchange ->
            release.await(15, TimeUnit.SECONDS)
            exchange.close()
        }
        // Warm the graphics runtime so this measures the network deadline, not test startup.
        val card = AnkiquestShare.Card("A win", "Saved locally")
        AnkiquestShare.picture(targetContext, card).recycle()
        try {
            val start = System.nanoTime()
            val bitmap = AnkiquestShare.picture(targetContext, card, "$base/w/stalled.png")
            assertEquals(1080, bitmap.width)
            bitmap.recycle()
            assertTrue(
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5,
                "a blocked server must not delay sharing for ten seconds",
            )
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `only a rendering failure leaves a text-only share`() {
        mockkObject(AnkiquestPicture)
        try {
            every { AnkiquestPicture.render(any(), any()) } throws IllegalArgumentException("cannot render")
            val chooser = AnkiquestShare.textChooser(targetContext, "A win", "Still shareable")
            val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
            assertEquals("text/plain", send.type)
            assertTrue(send.getStringExtra(Intent.EXTRA_TEXT).orEmpty().contains("Still shareable"))
            assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
            assertFalse(chooser.hasExtra(Intent.EXTRA_INITIAL_INTENTS), "there is no picture to download")
        } finally {
            unmockkObject(AnkiquestPicture)
        }
    }

    @Test
    fun `sharing removes only expired pictures from its own cache`() {
        val directory = File(targetContext.cacheDir, "ankiquest-share").apply { mkdirs() }
        val expired =
            File(directory, "old.png").apply {
                writeText("old picture")
                check(setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
            }
        val fresh = File(directory, "fresh.png").apply { writeText("still in use") }
        val unrelated =
            File(directory, "other.txt").apply {
                writeText("not a picture")
                check(setLastModified(expired.lastModified()))
            }
        AnkiquestShare.textChooser(targetContext, "A win", "A shared moment")
        assertFalse(expired.exists(), "share pictures must not accumulate indefinitely")
        assertEquals("still in use", fresh.readText())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `another share keeps the first granted picture readable`() {
        fun stream(title: String): Uri {
            val chooser = AnkiquestShare.textChooser(targetContext, title, "A shared moment")
            val send = assertNotNull(chooser.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT))
            return assertNotNull(send.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM))
        }
        val first = stream("First")
        val bytes = targetContext.contentResolver.openInputStream(first)!!.use { it.readBytes() }
        val second = stream("Second")
        assertTrue(first != second, "share grants must not point to an overwritten file")
        assertContentEquals(bytes, targetContext.contentResolver.openInputStream(first)!!.use { it.readBytes() })
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
    fun `a conquered card's win, label and a finished deck go to the server`() =
        runBlocking {
            val account = AnkiquestHomeData.account()!!
            assertEquals("conquered:1793183400000", AnkiquestSharing.winKey(account, 17))
            assertNull(AnkiquestSharing.winKey(account, 16), "a page that starts with another win")
            assertEquals("der Hund", AnkiquestSharing.conquestLabel(account, 1793183400000))
            AnkiquestSharing.setConquestLabel(account, 1793183400000, "der Hund – the dog")
            AnkiquestSharing.setConquestLabel(account, 1793183400000, null)
            assertEquals(2, AnkiquestSharing.shareFinishedDeck(account, 7, "Japanese::Kanji", listOf("cerro", "ana")))
            assertEquals(
                listOf(
                    "GET /api/wins/hill?limit=1&before=18",
                    "GET /api/wins/hill?limit=1&before=17",
                    "GET /api/conquests/hill/1793183400000",
                    "DELETE /api/conquests/hill/1793183400000/label",
                ),
                asked.take(5).filterIndexed { index, _ -> index != 3 },
            )
            assertTrue(asked[3].startsWith("POST /api/conquests/hill/1793183400000/label "))
            assertEquals("der Hund – the dog", JSONObject(asked[3].substringAfter("label ")).getString("label"))
            val deck = JSONObject(asked[5].substringAfter("share "))
            assertEquals("7", deck.getString("id"))
            assertEquals("Japanese::Kanji", deck.getString("name"))
            assertEquals(listOf("cerro", "ana"), deck.getJSONArray("recipients").strings())
        }

    @Test
    fun `a card's text is offered as one plain line, and shared only when chosen`() {
        assertEquals("der Hund the dog", AnkiquestShare.cardLabel("<b>der&nbsp;Hund</b>[sound:hund.mp3]<br>the  dog"))
        assertEquals("a < b & c", AnkiquestShare.cardLabel("a &lt; b &amp; c"))
        assertEquals(AnkiquestShare.MAX_LABEL, AnkiquestShare.cardLabel("x".repeat(500)).length)
        assertNull(AnkiquestShare.chosenLabel(false, "der Hund"), "the box is unticked by default")
        assertNull(AnkiquestShare.chosenLabel(true, " \n "))
        assertEquals("der Hund", AnkiquestShare.chosenLabel(true, "  der\nHund "))
        assertEquals(1793183400000, AnkiquestShare.conqueredAt("conquered:1793183400000"))
        assertNull(AnkiquestShare.conqueredAt("level:5"))
        assertNull(AnkiquestShare.conqueredAt(null))
    }

    @Test
    fun `a conquered card is labelled by its front, not by an id in its first field`() {
        val fields = listOf("B1L02", "der Hund", "the dog")
        assertEquals(
            "der Hund",
            AnkiquestShare.suggestedLabel("<style>.card { color: red }</style><div>der Hund</div>[anki:play:q:0]", fields, cloze = false),
        )
        assertEquals(
            "der Hund",
            AnkiquestShare.suggestedLabel("<style>.card {}</style>B1L02", fields, cloze = false),
            "a front that is the id gives way",
        )
        assertEquals("der Hund", AnkiquestShare.suggestedLabel("", fields, cloze = false), "an empty front falls back to the fields")
        assertEquals("the dog", AnkiquestShare.suggestedLabel("<img src=dog.jpg>", listOf("00412", "", "the dog"), cloze = false))
        assertNull(AnkiquestShare.suggestedLabel("", listOf("B1L02", "0042"), cloze = false), "only ids: no meaningful suggestion")
        assertNull(AnkiquestShare.suggestedLabel("", listOf("", "<br>"), cloze = false))
        assertEquals(
            "Paris is the capital of France",
            AnkiquestShare.suggestedLabel(
                "<style>.cloze {}</style>[...] is the capital of France",
                listOf("{{c1::Paris::city}} is the {{c2::capital}} of <b>France</b>", "Extra"),
                cloze = true,
            ),
            "a cloze shows its text",
        )
        assertEquals(AnkiquestShare.MAX_LABEL, AnkiquestShare.suggestedLabel("word ".repeat(100), fields, cloze = false)?.length)
        for (id in listOf("B1L02", "0042", "ID-17", "2024-05-01", "A12B", "3f2a9c1e-77d0")) assertTrue(AnkiquestShare.looksLikeId(id), id)
        for (word in listOf("Hund", "der Hund", "la ardilla", "Straße", "x")) assertFalse(AnkiquestShare.looksLikeId(word), word)
    }

    @Test
    fun `a label saved from an id is offered its front instead`() {
        val labels = AnkiquestShare.Labels(suggested = "der Hund", legacy = "B1L02")
        assertEquals("der Hund", labels.better("B1L02"))
        assertNull(labels.better("my own words"), "a label the player wrote stays")
        assertNull(labels.better(null))
        assertNull(AnkiquestShare.Labels("der Hund", "der Hund").better("der Hund"))
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun `the share sheet offers AnkiQuest friends next to the other apps`() {
        val share =
            Intent(targetContext, AnkiquestShareActivity::class.java)
                .putExtra(AnkiquestShare.EXTRA_WIN, 17L)
                .putExtra(AnkiquestShare.EXTRA_TITLE, "Level 54")
        val friends = AnkiquestShare.friendsTarget(targetContext, share)
        val target = shadowOf(friends).savedIntent
        assertEquals(AnkiquestShare.DESTINATION_FRIENDS, target.getStringExtra(AnkiquestShare.EXTRA_DESTINATION))
        assertEquals(17, target.getLongExtra(AnkiquestShare.EXTRA_WIN, 0))
        assertEquals("Level 54", target.getStringExtra(AnkiquestShare.EXTRA_TITLE))
        assertTrue(AnkiquestShare.sheetOffersFriends())
        val chooser = AnkiquestShare.textChooser(targetContext, "Level 54", "You reached level 54.", friends)
        val actions = chooser.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, ChooserAction::class.java)
        assertEquals(listOf("AnkiQuest friends"), actions?.map { it.label.toString() })
        assertEquals(friends, actions?.single()?.action)
        assertFalse(AnkiquestShare.textChooser(targetContext, "Level 54", "", null).hasExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS))
    }

    @Test
    @Config(sdk = [33])
    // The extra name is a compile-time string, so checking it is absent on Android 13 is safe.
    @SuppressLint("InlinedApi")
    fun `before Android 14 the share sheet is for other apps and friends are asked about first`() {
        assertFalse(AnkiquestShare.sheetOffersFriends())
        val friends = AnkiquestShare.friendsTarget(targetContext, Intent(targetContext, AnkiquestShareActivity::class.java))
        val chooser = AnkiquestShare.textChooser(targetContext, "Level 54", "", friends)
        assertFalse(chooser.hasExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS))
    }

    @Test
    fun `a conquered card's share button says so`() {
        val action = assertNotNull(AnkiquestShare.action(targetContext, 1, "Card conquered", "", 17, 42, null, null, "conquered"))
        assertEquals("conquered", shadowOf(action.actionIntent).savedIntent.getStringExtra(AnkiquestShare.EXTRA_TOPIC))
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
