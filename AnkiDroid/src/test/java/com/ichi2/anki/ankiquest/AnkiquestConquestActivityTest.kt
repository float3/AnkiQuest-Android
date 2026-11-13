// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.ShadowFileProviderPathStrategy
import com.ichi2.utils.openInputStreamSafe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp", shadows = [ShadowFileProviderPathStrategy::class])
class AnkiquestConquestActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private var labelJob: Job? = null

    /** The server's recorded card: Hill forgot "el murciélago" four times; friends not told yet. */
    private val recorded =
        AnkiquestCommunity.parseConquest(
            JSONObject(assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/conquest.json")).readText(Charsets.UTF_8)),
        )

    @Before
    fun prepareCard() {
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        mockkObject(AnkiquestHomeData, AnkiquestCommunity, AnkiquestSharing, AnkiquestShare, Ankiquest)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestShare.conqueredCardDeck(any()) } returns null
        coEvery { AnkiquestShare.conqueredCardLabels(any()) } returns null
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(friends = 2)
    }

    @After
    fun clearCardMocks() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, AnkiquestCommunity, AnkiquestSharing, AnkiquestShare, Ankiquest)
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun `the owner sees the card, its label and numbers, and can tell friends once`() {
        coEvery { AnkiquestCommunity.tell(identity, AT) } returns 2
        val card = launch()
        assertEquals(card.getString(R.string.aq_profile_conquest), card.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertTrue(card.hasText("el murciélago"))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_lead_mine, 4, 4)))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_lapses, 4, "4")))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_answers, 11, "11")))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_days, 10, "10")))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_label_shown)))

        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(told = true, friends = 2)
        card.click(card.getString(R.string.aq_conquest_tell))
        coVerify(exactly = 1) { AnkiquestCommunity.tell(identity, AT) }
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_told_count, 2, 2)))
        val told = card.views<TextView>().first { it.text.toString() == card.getString(R.string.aq_conquest_told) }
        assertFalse(told.isEnabled, "friends are told once")
    }

    @Test
    fun `the owner can take back telling friends while the server allows it`() {
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns
            recorded.copy(told = true, friends = 2, undoUntil = Long.MAX_VALUE)
        coEvery { AnkiquestCommunity.untell(identity, AT) } returns 2
        val card = launch()
        val undo = card.getString(com.ichi2.anki.common.android.R.string.undo)
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_told)))
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(told = false, friends = 2)
        card.click(undo)
        coVerify(exactly = 1) { AnkiquestCommunity.untell(identity, AT) }
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_untold)))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_tell)), "friends can be told again")
        assertFalse(card.hasText(undo))
    }

    @Test
    fun `too late to take back telling friends is said as such, and no undo is offered once expired`() {
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns
            recorded.copy(told = true, friends = 2, undoUntil = Long.MAX_VALUE)
        coEvery { AnkiquestCommunity.untell(identity, AT) } throws AnkiquestHttpException(409)
        val card = launch()
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(told = true, friends = 2, undoUntil = 1)
        card.click(card.getString(com.ichi2.anki.common.android.R.string.undo))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_untell_late)))
        assertFalse(card.hasText(card.getString(com.ichi2.anki.common.android.R.string.undo)))
    }

    @Test
    fun `a label saved from an id is offered the card's front instead`() {
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = "B1L02")
        coEvery { AnkiquestShare.conqueredCardLabels(AT) } returns AnkiquestShare.Labels("der Hund", "B1L02")
        coEvery { AnkiquestSharing.setConquestLabel(identity, AT, "der Hund") } just runs
        val card = launch()
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = "der Hund")
        card.click(card.getString(R.string.aq_conquest_label_refresh, "der Hund"))
        coVerify(exactly = 1) { AnkiquestSharing.setConquestLabel(identity, AT, "der Hund") }
        assertTrue(card.hasText("der Hund"))
        assertFalse(card.hasText(card.getString(R.string.aq_conquest_label_refresh, "der Hund")), "once is enough")
    }

    @Test
    fun `the owner can stop showing the label`() {
        coEvery { AnkiquestSharing.setConquestLabel(identity, AT, null) } just runs
        val card = launch()
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = null)
        card.click(card.getString(R.string.aq_conquest_label_remove))
        coVerify { AnkiquestSharing.setConquestLabel(identity, AT, null) }
        assertFalse(card.hasText("el murciélago"))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_numbers_only)))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_label_add)))
    }

    @Test
    fun `showing the card uses its front from the collection without asking`() {
        mockkObject(AnkiquestShare)
        try {
            coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = null)
            coEvery { AnkiquestShare.conqueredCardText(AT) } returns "la ardilla"
            coEvery { AnkiquestSharing.setConquestLabel(identity, AT, "la ardilla") } just runs
            val card = launch()
            coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = "la ardilla")
            card.click(card.getString(R.string.aq_conquest_label_add))
            coVerify(exactly = 1) { AnkiquestSharing.setConquestLabel(identity, AT, "la ardilla") }
            assertTrue(card.hasText("la ardilla"))
            assertTrue(card.hasText(card.getString(R.string.aq_conquest_label_remove)))
        } finally {
            unmockkObject(AnkiquestShare)
        }
    }

    @Test
    fun `another player's card has no owner's choices and leads to their profile`() {
        coEvery { AnkiquestCommunity.conquest(identity, "cerro", AT) } returns
            recorded.copy(user = "cerro", display = "Cerro", told = null, friends = null, label = null)
        val card = launch("cerro")
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_lead, 4, "Cerro", 4)))
        assertFalse(card.hasText(card.getString(R.string.aq_conquest_tell)))
        assertFalse(card.hasText(card.getString(R.string.aq_conquest_label_add)))
        card.click(card.getString(R.string.aq_conquest_profile, "Cerro"))
        val opened = assertNotNull(shadowOf(card).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, opened.component?.className)
        assertEquals("cerro", opened.getStringExtra(AnkiquestProfileActivity.EXTRA_USER))
    }

    @Test
    fun `the owner shares the card's words, deck, picture and page`() {
        val picture = "https://anki.example.test/w/tok.png"
        coEvery { AnkiquestShare.conqueredCardDeck(AT) } returns "Español::Animales"
        coEvery { Ankiquest.shareWinLink(6, identity.scope, AnkiquestApi.Timeout.PICTURE) } returns
            AnkiquestShare.Link("https://anki.example.test/w/tok", picture, "I just conquered a card")
        every { AnkiquestShare.picture(any(), any(), picture) } answers { createBitmap(2, 2) }
        // Robolectric's cache folder is not always under the provider's root, so the picture's address is made up.
        val stream = "content://com.ichi2.anki.debug.apkgfileprovider/cache-directory/ankiquest-share/win.png".toUri()
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns stream
        val card = launch()
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_deck, "Español::Animales")))
        card.click(card.getString(R.string.ankiquest_share))
        val sent = card.sent()
        assertEquals("image/png", sent.type)
        assertEquals(stream, IntentCompat.getParcelableExtra(sent, Intent.EXTRA_STREAM, Uri::class.java))
        val text = sent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.startsWith(card.resources.getQuantityString(R.plurals.aq_conquest_share_label_mine, 4, "el murciélago", 4)))
        assertTrue(text.contains(card.getString(R.string.aq_conquest_share_deck, "Español::Animales")))
        assertTrue(text.endsWith("https://anki.example.test/conquered/hill/$AT"))
    }

    @Test
    fun `a conquest without a server win shares a local picture`() {
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(win = null)
        val card = launch()
        card.click(card.getString(R.string.ankiquest_share))
        val sent = card.sent()
        assertEquals("image/png", sent.type)
        assertTrue(sent.hasExtra(Intent.EXTRA_STREAM))
        val uri = assertNotNull(IntentCompat.getParcelableExtra(sent, Intent.EXTRA_STREAM, Uri::class.java))
        assertNotNull(card.contentResolver.openInputStreamSafe(uri).use { BitmapFactory.decodeStream(it) })
        coVerify(exactly = 1) { AnkiquestCommunity.conquest(identity, "hill", AT) }
        coVerify(exactly = 0) { Ankiquest.shareWinLink(any(), any(), any()) }
    }

    @Test
    fun `someone else's public card shares a picture without a private deck`() {
        coEvery { AnkiquestCommunity.conquest(identity, "cerro", AT) } returns
            recorded.copy(user = "cerro", display = "Cerro", told = null, friends = null, label = null, win = null)
        val card = launch("cerro")
        card.click(card.getString(R.string.ankiquest_share))
        val sent = card.sent()
        assertEquals("image/png", sent.type)
        assertTrue(sent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(
            card.resources.getQuantityString(R.plurals.aq_conquest_share, 4, "Cerro", 4) + " https://anki.example.test/conquered/cerro/$AT",
            sent.getStringExtra(Intent.EXTRA_TEXT),
        )
        coVerify(exactly = 0) { AnkiquestShare.conqueredCardDeck(any()) }
        coVerify(exactly = 0) { Ankiquest.shareWinLink(any(), any(), any()) }
    }

    private fun AnkiquestConquestActivity.sent(): Intent {
        val chooser = assertNotNull(shadowOf(this).nextStartedActivity)
        return assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
    }

    @Test
    fun `a card that cannot be found says so`() {
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } throws AnkiquestCommunity.Missing()
        val card = launch()
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_missing)))
    }

    @Test
    fun `a conquest on a profile opens its card`() {
        mockkObject(AnkiquestBoards)
        try {
            val profile =
                AnkiquestBoards.parseProfile(
                    JSONObject(
                        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/player_profile.json")).readText(Charsets.UTF_8),
                    ).put("user", "cerro")
                        .put("conquests", org.json.JSONArray().put(JSONObject().put("at", AT).put("lapses", 5).put("leech", false))),
                )
            coEvery { AnkiquestBoards.player(identity, "cerro") } returns
                AnkiquestBoards.PlayerPage(profile, own = false, wins = emptyList())
            val page = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, "cerro"))
            advanceRobolectricLooperUntil { !page.findViewById<View>(R.id.aq_board_loading).isVisible }
            advanceRobolectricLooper()
            val lapses = page.resources.getQuantityString(R.plurals.aq_profile_lapses, 5, 5)
            page
                .findViewById<View>(R.id.aq_board_content)
                .allViews
                .filterIsInstance<TextView>()
                .first { it.text.toString() == lapses }
                .let { (it.parent as View).performClick() }
            val opened = assertNotNull(shadowOf(page).nextStartedActivity)
            assertEquals(AnkiquestConquestActivity::class.java.name, opened.component?.className)
            assertEquals("cerro", opened.getStringExtra(AnkiquestConquestActivity.EXTRA_USER))
            assertEquals(AT, opened.getLongExtra(AnkiquestConquestActivity.EXTRA_AT, 0))
        } finally {
            unmockkObject(AnkiquestBoards)
        }
    }

    @Test
    fun `refreshing while friends are being told keeps the buttons waiting and shows the answer`() {
        val answer = CompletableDeferred<Int>()
        coEvery { AnkiquestCommunity.tell(identity, AT) } coAnswers { answer.await() }
        val card = launch()
        card.views<TextView>().first { it.text.toString() == card.getString(R.string.aq_conquest_tell) }.performClick()
        advanceRobolectricLooper()

        card.findViewById<View>(R.id.aq_board_refresh).performClick()
        advanceRobolectricLooperUntil { card.hasText("el murciélago") }
        val tell = card.views<TextView>().first { it.text.toString() == card.getString(R.string.aq_conquest_tell) }
        assertFalse(tell.isEnabled, "friends could be told twice")

        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(told = true, friends = 2)
        answer.complete(2)
        card.settle()
        coVerify(exactly = 1) { AnkiquestCommunity.tell(identity, AT) }
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_told_count, 2, 2)), "the answer is shown")
    }

    private fun launch(user: String = "hill"): AnkiquestConquestActivity =
        startRegularActivity<AnkiquestConquestActivity>(AnkiquestConquestActivity.intent(targetContext, user, AT)).also { it.settle() }

    private fun lateLabel(): Pair<ActivityController<AnkiquestConquestActivity>, CompletableDeferred<String?>> {
        val answer = CompletableDeferred<String?>()
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = null)
        coEvery { AnkiquestShare.conqueredCardText(AT) } coAnswers {
            labelJob = currentCoroutineContext()[Job]
            answer.await()
        }
        coEvery { AnkiquestSharing.setConquestLabel(any(), any(), any()) } returns Unit
        val controller =
            Robolectric
                .buildActivity(
                    AnkiquestConquestActivity::class.java,
                    AnkiquestConquestActivity.intent(targetContext, "hill", AT),
                ).setup()
        saveControllerForCleanup(controller)
        val card = controller.get()
        card.settle()
        card.views<TextView>().first { it.text.toString() == card.getString(R.string.aq_conquest_label_add) }.performClick()
        advanceRobolectricLooperUntil { labelJob != null }
        return controller to answer
    }

    @Test
    fun `a conquest's late private label cannot be sent as a replacement account`() {
        val (controller, answer) = lateLabel()
        controller.pause().stop()
        val other = identity.copy(user = "other", token = "other-synthetic-token")
        every { AnkiquestHomeData.account(any()) } returns other
        coEvery { AnkiquestCommunity.conquest(other, "hill", AT) } returns recorded.copy(told = null, friends = null)
        controller.start().resume()
        controller.get().settle()
        answer.complete("PRIVATE LABEL FROM OLD ACCOUNT")
        advanceRobolectricLooperUntil { labelJob?.isCompleted == true }
        coVerify(exactly = 0) { AnkiquestSharing.setConquestLabel(any(), any(), any()) }
    }

    @Test
    fun `a stopped conquest cannot open a late label dialog`() {
        val (controller, answer) = lateLabel()
        controller.pause().stop()
        answer.complete(null)
        advanceRobolectricLooperUntil { labelJob?.isCompleted == true }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        coVerify(exactly = 0) { AnkiquestSharing.setConquestLabel(any(), any(), any()) }
    }

    @Test
    fun `a share finishing while paused leaves the screen ready after resume`() {
        val answer = CompletableDeferred<AnkiquestShare.Link>()
        val prepared = AtomicBoolean(false)
        coEvery { Ankiquest.shareWinLink(6, identity.scope, AnkiquestApi.Timeout.PICTURE) } coAnswers { answer.await() }
        every { AnkiquestShare.cardChooser(any(), any(), any(), any(), any()) } answers {
            prepared.set(true)
            Intent(Intent.ACTION_CHOOSER)
        }
        val controller =
            Robolectric
                .buildActivity(
                    AnkiquestConquestActivity::class.java,
                    AnkiquestConquestActivity.intent(targetContext, "hill", AT),
                ).setup()
        val card = controller.get()
        saveControllerForCleanup(controller)
        card.settle()
        card.views<TextView>().first { it.text.toString() == card.getString(R.string.ankiquest_share) }.performClick()
        advanceRobolectricLooper()
        controller.pause()
        answer.complete(AnkiquestShare.Link("https://example.test/w/link", "https://example.test/w/link.png", "A win"))
        advanceRobolectricLooperUntil { prepared.get() }
        advanceRobolectricLooperUntil { !ReflectionHelpers.getField<Boolean>(card, "busy") }
        assertNull(shadowOf(card).nextStartedActivity, "a paused screen must not open a chooser")
        controller.resume()
        advanceRobolectricLooper()
        assertFalse(card.findViewById<View>(R.id.aq_board_loading).isVisible, "returning must clear the finished share's progress")
        assertTrue(card.views<TextView>().first { it.text.toString() == card.getString(R.string.ankiquest_share) }.isEnabled)
        controller.pause().stop().destroy()
    }

    private fun AnkiquestConquestActivity.settle() {
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
    }

    private inline fun <reified T : View> AnkiquestConquestActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestConquestActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestConquestActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        settle()
    }

    private companion object {
        const val AT = 1_793_097_000_000L
    }
}
