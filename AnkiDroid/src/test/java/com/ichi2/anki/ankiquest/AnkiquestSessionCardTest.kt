// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import com.ichi2.testutils.ShadowFileProviderPathStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestSessionCardTest : RobolectricTest() {
    private val prefs get() = AnkiDroidApp.sharedPrefs()
    private val clock = MockTime(1_750_000_000_000)
    private var continued = 0

    @Before
    fun configure() {
        setIntroductionSlidesShown(true)
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        TimeManager.resetWith(clock)
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "member-token")
        }
        mockkObject(AnkiquestStudySession, AnkiquestDeckShare)
        coEvery { AnkiquestStudySession.finish() } returns summary()
        coEvery { AnkiquestDeckShare.offer(any()) } returns Unit
    }

    @After
    fun cleanup() {
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        BackupManagerTestUtilities.reset()
        unmockkObject(AnkiquestStudySession, AnkiquestDeckShare)
    }

    private fun summary(remaining: Int = 3) = AnkiquestStudySession.Summary(6, 1, remaining, clock.intTimeMS())

    private fun host(): ActivityController<DeckPicker> =
        startActivityControllerNormallyOpenCollectionWithIntent(
            DeckPicker::class.java,
            Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true),
        )

    private fun finish(host: DeckPicker) {
        assertTrue(AnkiquestDeckPickerHooks.onReviewResult(host, AbstractFlashcardViewer.RESULT_DEFAULT) { continued++ })
    }

    private fun card(): AlertDialog {
        advanceRobolectricLooperUntil {
            ShadowDialog.getLatestDialog()?.let { it.isShowing && it.findViewById<ViewPager2>(R.id.aq_moment_pager) != null } == true
        }
        return assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
    }

    private fun queue(
        key: String = "level:4",
        win: Long? = null,
    ): AnkiquestMoments.Moment {
        val moment = AnkiquestMoments.Moment(key, AnkiquestMoments.Kind.LEVEL, "Level 4", "", clock.intTimeMS(), win = win)
        AnkiquestMoments.offer(prefs, assertNotNull(AnkiquestHomeData.account()).notificationAccount, listOf(moment))
        return moment
    }

    @Test
    fun `queued moments and recap use one card with Continue and Progress`() {
        val host = host().get()
        queue()
        finish(host)
        val card = card()
        val pager = assertNotNull(card.findViewById<ViewPager2>(R.id.aq_moment_pager))
        assertEquals(2, pager.adapter?.itemCount)
        assertTrue(card.getButton(AlertDialog.BUTTON_POSITIVE).isVisible)
        assertTrue(card.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible)
        assertEquals(host.getString(R.string.ankiquest_summary_title), ViewCompat.getAccessibilityPaneTitle(pager.parent as View))
        pager.setCurrentItem(1, false)
        advanceRobolectricLooper()
        assertEquals("Level 4", ViewCompat.getAccessibilityPaneTitle(pager.parent as View))
        coVerify(exactly = 0) { AnkiquestDeckShare.offer(any()) }

        card.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        card.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        assertEquals(1, continued)
        assertFalse(card.isShowing)
        coVerify(exactly = 0) { AnkiquestDeckShare.offer(any()) }
    }

    @Test
    fun `a recap joins a moment that appeared before the summary finished`() {
        val host = host().get()
        queue()
        AnkiquestMoments.present(host, prefs)
        val moment = card()
        finish(host)
        advanceRobolectricLooper()
        assertSame(moment, ShadowDialog.getLatestDialog())
        assertEquals(2, moment.findViewById<ViewPager2>(R.id.aq_moment_pager)?.adapter?.itemCount)
        assertTrue(moment.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible)
    }

    @Test
    fun `Progress opens the native page without starting deck sharing`() {
        val host = host().get()
        finish(host)
        val card = card()
        card.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        advanceRobolectricLooper()
        assertEquals(AnkiquestProfileActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        assertEquals(0, continued)
        coVerify(exactly = 0) { AnkiquestDeckShare.offer(any()) }
    }

    @Test
    fun `a finished deck has no Continue and hands off once after Done`() {
        val host = host().get()
        coEvery { AnkiquestStudySession.finish() } returns summary(remaining = 0)
        finish(host)
        val card = card()
        assertFalse(card.getButton(AlertDialog.BUTTON_POSITIVE).isVisible)
        card.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 1) { AnkiquestDeckShare.offer(host) }
        assertEquals(0, continued)
    }

    @Test
    fun `recap opt out leaves moments enabled`() {
        val host = host().get()
        finish(host)
        val first = card()
        advanceRobolectricLooper()
        val checkbox = assertNotNull(views(first.window!!.decorView).filterIsInstance<CheckBox>().singleOrNull())
        checkbox.isChecked = true
        first.dismiss()
        advanceRobolectricLooper()
        assertFalse(prefs.getBoolean(AnkiquestNavigation.PREF_SESSION_SUMMARY, true))

        queue()
        finish(host)
        val second = card()
        assertEquals(1, second.findViewById<ViewPager2>(R.id.aq_moment_pager)?.adapter?.itemCount)
        assertFalse(second.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible)
        coVerify(exactly = 1) { AnkiquestDeckShare.offer(host) }
        second.dismiss()
        advanceRobolectricLooper()
        coVerify(exactly = 2) { AnkiquestDeckShare.offer(host) }
    }

    @Test
    fun `pausing keeps the card and waits to append new moments until resume`() {
        val controller = host()
        val host = controller.get()
        finish(host)
        val card = card()
        val pager = assertNotNull(card.findViewById<ViewPager2>(R.id.aq_moment_pager))
        controller.pause().stop()
        val moment = queue()
        AnkiquestMoments.present(host, prefs)
        assertTrue(card.isShowing)
        assertEquals(1, pager.adapter?.itemCount)
        val owner = assertNotNull(AnkiquestHomeData.account()).notificationAccount
        assertEquals(listOf(moment), AnkiquestMoments.pending(prefs, owner))
        controller.start().resume()
        AnkiquestMoments.present(host, prefs)
        val latest = assertNotNull(ShadowDialog.getLatestDialog())
        val text = views(latest.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }.joinToString(" | ")
        assertSame(card, latest, "Original showing=${card.isShowing}; latest showing=${latest.isShowing}; $text")
        assertEquals(2, pager.adapter?.itemCount)
        assertTrue(AnkiquestMoments.pending(prefs, owner).isEmpty())
    }

    @Test
    fun `a moment picture waits for resume before opening the share sheet`() {
        mockkObject(Ankiquest, AnkiquestShare, AnkiquestSharing)
        val answer = CompletableDeferred<AnkiquestShare.Link>()
        val prepared = AtomicBoolean(false)
        var operation: Job? = null
        coEvery { AnkiquestSharing.win(any(), 17) } returns null
        coEvery { Ankiquest.shareWinLink(17, any()) } coAnswers {
            operation = currentCoroutineContext()[Job]
            answer.await()
        }
        every { AnkiquestShare.linkChooser(any(), any(), any(), any()) } answers {
            prepared.set(true)
            Intent(Intent.ACTION_CHOOSER)
        }
        val controller = host()
        try {
            val host = controller.get()
            queue(win = 17)
            finish(host)
            val card = card()
            card.findViewById<ViewPager2>(R.id.aq_moment_pager)!!.setCurrentItem(1, false)
            advanceRobolectricLooper()
            assertNotNull(card.findViewById<View>(R.id.aq_moment_share)).performClick()
            advanceRobolectricLooperUntil { operation != null }
            controller.pause()
            answer.complete(AnkiquestShare.Link("https://quest.example.test/w/card", "https://quest.example.test/w/card.png", "A win"))
            advanceRobolectricLooperUntil { prepared.get() && operation?.children?.none { it.isActive } == true }
            assertNull(shadowOf(host).nextStartedActivity, "a paused moment must not open a share sheet")
            controller.resume()
            advanceRobolectricLooperUntil { operation?.isCompleted == true }
            assertEquals(Intent.ACTION_CHOOSER, assertNotNull(shadowOf(host).nextStartedActivity).action)
            assertTrue(card.isShowing, "sharing must keep the moment available")
        } finally {
            controller.close()
            unmockkObject(Ankiquest, AnkiquestShare, AnkiquestSharing)
        }
    }

    @Test
    fun `with share_moments a moment's Share opens AnkiQuest's share sheet and Undo shows on the card`() {
        mockkObject(AnkiquestShare, AnkiquestSharing, AnkiquestMomentShare, AnkiquestSocial)
        coEvery { AnkiquestSharing.win(any(), 17) } returns null
        every { AnkiquestMomentShare.available(any()) } returns true
        every { AnkiquestShare.render(any(), any()) } returns createBitmap(1, 1)
        coEvery { AnkiquestSocial.overview(any()) } returns
            AnkiquestSocial.Overview(listOf(AnkiquestSocial.Person("ana", "Ana")), emptyList(), emptyList(), emptyList(), emptyList(), true)
        coEvery { AnkiquestSharing.send(any(), AnkiquestSharing.MomentRef.Win(17), AnkiquestSharing.Audience.Circle, null) } returns
            AnkiquestSharing.Sent(1, 17, clock.intTimeMS() + 60_000)
        val controller = host()
        try {
            val host = controller.get()
            queue(win = 17)
            finish(host)
            val card = card()
            card.findViewById<ViewPager2>(R.id.aq_moment_pager)!!.setCurrentItem(1, false)
            advanceRobolectricLooper()
            assertNotNull(card.findViewById<View>(R.id.aq_moment_share)).performClick()
            val sheet = assertIs<BottomSheetDialog>(ShadowDialog.getLatestDialog())
            assertNull(shadowOf(host).nextStartedActivity, "nothing leaves the app before a choice")

            val everyone = host.getString(R.string.ankiquest_share_all_friends)
            advanceRobolectricLooperUntil { views(sheet.window!!.decorView).any { it is TextView && it.text.toString() == everyone } }
            views(sheet.window!!.decorView).first { it is TextView && it.text.toString() == everyone }.performClick()
            sheet.findViewById<View>(R.id.aq_share_send)!!.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

            coVerify(
                exactly = 1,
            ) { AnkiquestSharing.send(any(), AnkiquestSharing.MomentRef.Win(17), AnkiquestSharing.Audience.Circle, null) }
            assertFalse(sheet.isShowing)
            assertTrue(card.isShowing, "sending keeps the moment open")
            assertNotNull(
                card.window!!.decorView.findViewById<View>(com.google.android.material.R.id.snackbar_action),
                "Undo shows on the card",
            )
        } finally {
            controller.close()
            unmockkObject(AnkiquestShare, AnkiquestSharing, AnkiquestMomentShare, AnkiquestSocial)
        }
    }

    @Test
    fun `an offline conquest picture leaves unsaved card text private`() {
        val picture = conquestPicture(null)
        assertFalse((picture.body + picture.details.joinToString()).contains("PRIVATE UNSHARED FIELD"))
        assertTrue((picture.body + picture.details.joinToString()).contains("4"), "the public lapse count still appears")
    }

    @Test
    fun `a conquest picture can include the label the player already shared`() {
        val picture = conquestPicture("A public label")
        assertTrue((picture.body + picture.details.joinToString()).contains("A public label"))
        assertFalse((picture.body + picture.details.joinToString()).contains("PRIVATE UNSHARED FIELD"))
    }

    @Test
    fun `returning from the conquest forgets a label the player has removed there`() {
        val picture = conquestPicture("Previously shared label", LabelChange.REMOVE_ON_RESUME)
        assertFalse((picture.body + picture.details.joinToString()).contains("Previously shared label"))
    }

    @Test
    fun `returning from the share sheet keeps what the moment already loaded`() {
        mockkObject(AnkiquestSharing)
        val fetched = AtomicInteger()
        coEvery { AnkiquestSharing.win(any(), 17) } coAnswers {
            fetched.incrementAndGet()
            null
        }
        val controller = host()
        try {
            val host = controller.get()
            queue(win = 17)
            finish(host)
            val card = card()
            card.findViewById<ViewPager2>(R.id.aq_moment_pager)!!.setCurrentItem(1, false)
            advanceRobolectricLooperUntil { fetched.get() == 1 }

            // The chooser, a permission dialog and an app switch all pause and resume the host.
            controller.pause().stop()
            controller.start().resume()
            advanceRobolectricLooper()
            assertEquals(1, fetched.get(), "resuming must not refetch, clear pictures or forget Undo")
        } finally {
            controller.close()
            unmockkObject(AnkiquestSharing)
        }
    }

    @Test
    fun `a moment's picture says what its caption says`() {
        mockkObject(AnkiquestApi, AnkiquestShare, AnkiquestSharing)
        val prepared = AtomicReference<AnkiquestShare.Card?>()
        coEvery { AnkiquestSharing.win(any(), 17) } returns null
        every { AnkiquestApi.json(any(), any(), any()) } returns
            JSONObject()
                .put("url", "https://anki.example.test/w/card")
                .put("image", "https://anki.example.test/w/card.png")
                .put("text", "I reached level 4! https://anki.example.test/w/card")
        every { AnkiquestShare.linkChooser(any(), any(), any(), any()) } answers {
            prepared.set(arg(3))
            Intent(Intent.ACTION_CHOOSER)
        }
        val controller = host()
        try {
            val host = controller.get()
            val moment =
                AnkiquestMoments.Moment(
                    "level:4",
                    AnkiquestMoments.Kind.LEVEL,
                    "Level 4",
                    "You reached level 4.",
                    clock.intTimeMS(),
                    win = 17,
                    shareTitle = "Level 4!",
                    shareBody = "I reached level 4.",
                )
            AnkiquestMoments.offer(prefs, assertNotNull(AnkiquestHomeData.account()).notificationAccount, listOf(moment))
            finish(host)
            val card = card()
            card.findViewById<ViewPager2>(R.id.aq_moment_pager)!!.setCurrentItem(1, false)
            advanceRobolectricLooper()
            assertNotNull(card.findViewById<View>(R.id.aq_moment_share)).performClick()
            advanceRobolectricLooperUntil { prepared.get() != null }
            val picture = assertNotNull(prepared.get())
            assertEquals("Level 4!", picture.title)
            assertEquals("I reached level 4.", picture.body)
        } finally {
            controller.close()
            unmockkObject(AnkiquestApi, AnkiquestShare, AnkiquestSharing)
        }
    }

    @Test
    @Config(shadows = [ShadowFileProviderPathStrategy::class])
    fun `downloading a moment's picture publishes nothing`() {
        ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache").clear()
        mockkObject(AnkiquestApi, AnkiquestSharing)
        val requests = CopyOnWriteArrayList<String>()
        coEvery { AnkiquestSharing.win(any(), 17) } returns null
        every { AnkiquestApi.json(any(), any(), any()) } answers {
            requests.add(arg<Request>(1).url.encodedPath)
            throw IOException("offline")
        }
        val controller = host()
        try {
            val host = controller.get()
            queue(win = 17)
            finish(host)
            val card = card()
            card.findViewById<ViewPager2>(R.id.aq_moment_pager)!!.setCurrentItem(1, false)
            advanceRobolectricLooper()
            val download = host.getString(R.string.ankiquest_download_picture)
            val button =
                assertNotNull(
                    views(card.window!!.decorView).firstOrNull { it.isShown && it.contentDescription == download },
                    "the moment offers Download picture outside the share sheet",
                )
            button.performClick()
            advanceRobolectricLooperUntil { shadowOf(host).peekNextStartedActivity() != null }
            val saving = assertNotNull(shadowOf(host).nextStartedActivity)
            assertEquals(AnkiquestGalleryActivity::class.java.name, saving.component?.className)
            assertNotNull(saving.data, "the locally drawn picture")
            assertEquals(emptyList<String>(), requests.filter { it.startsWith("/api/share/") }, "saving must not make a public link")
        } finally {
            controller.close()
            unmockkObject(AnkiquestApi, AnkiquestSharing)
        }
    }

    private enum class LabelChange { KEEP, REMOVE_ON_RESUME }

    private fun conquestPicture(
        label: String?,
        change: LabelChange = LabelChange.KEEP,
    ): AnkiquestShare.Card {
        mockkObject(Ankiquest, AnkiquestShare, AnkiquestSharing, AnkiquestCommunity)
        val prepared = AtomicReference<AnkiquestShare.Card?>()
        var sharedLabel = label
        val recorded =
            AnkiquestCommunity.parseConquest(
                JSONObject(assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/conquest.json")).readText()),
            )
        coEvery { AnkiquestCommunity.conquest(any(), any(), any()) } coAnswers {
            if (label == null) {
                throw IOException("offline")
            }
            recorded.copy(label = sharedLabel, win = null)
        }
        coEvery { Ankiquest.shareWinLink(any(), any()) } throws IOException("offline")
        every { AnkiquestShare.cardChooser(any(), any(), any(), any(), any()) } answers {
            prepared.set(arg(4))
            Intent(Intent.ACTION_CHOOSER)
        }
        val controller = host()
        try {
            val host = controller.get()
            val moment =
                AnkiquestMoments.Moment(
                    "conquered:100",
                    AnkiquestMoments.Kind.CONQUERED,
                    "Card conquered",
                    "PRIVATE UNSHARED FIELD",
                    clock.intTimeMS(),
                    conquest = 100,
                    lapses = 4,
                    deck = "Spanish",
                )
            AnkiquestMoments.offer(prefs, assertNotNull(AnkiquestHomeData.account()).notificationAccount, listOf(moment))
            finish(host)
            val card = card()
            card.findViewById<ViewPager2>(R.id.aq_moment_pager)!!.setCurrentItem(1, false)
            advanceRobolectricLooper()
            if (change == LabelChange.REMOVE_ON_RESUME) {
                assertNotNull(card.findViewById<View>(R.id.aq_moment_open)).performClick()
                assertEquals(AnkiquestConquestActivity::class.java.name, shadowOf(host).nextStartedActivity?.component?.className)
                controller.pause().stop()
                sharedLabel = null
                controller.start().resume()
                advanceRobolectricLooper()
            }
            assertNotNull(card.findViewById<View>(R.id.aq_moment_share)).performClick()
            advanceRobolectricLooperUntil { shadowOf(host).peekNextStartedActivity() != null }
            return assertNotNull(prepared.get())
        } finally {
            controller.close()
            unmockkObject(Ankiquest, AnkiquestShare, AnkiquestSharing, AnkiquestCommunity)
        }
    }

    @Test
    fun `a completed recap waits for resume without starting deck sharing while stopped`() {
        val controller = host()
        val result = CompletableDeferred<AnkiquestStudySession.Summary>()
        val reading = CompletableDeferred<Unit>()
        coEvery { AnkiquestStudySession.finish() } coAnswers {
            reading.complete(Unit)
            result.await()
        }
        finish(controller.get())
        advanceRobolectricLooperUntil { reading.isCompleted }
        val before = ShadowDialog.getLatestDialog()
        controller.pause().stop()
        result.complete(summary())
        advanceRobolectricLooper()
        assertSame(before, ShadowDialog.getLatestDialog())
        coVerify(exactly = 0) { AnkiquestDeckShare.offer(any()) }
        controller.start().resume()
        assertTrue(card().isShowing)
    }

    @Test
    fun `an account change closes the old card without applying opt out or handing off`() {
        val host = host().get()
        finish(host)
        val card = card()
        advanceRobolectricLooper()
        val checkbox = assertNotNull(views(card.window!!.decorView).filterIsInstance<CheckBox>().singleOrNull())
        checkbox.isChecked = true
        prefs.edit { putString(Ankiquest.PREF_TOKEN, "different-token") }
        advanceRobolectricLooper()
        assertFalse(card.isShowing)
        assertTrue(prefs.getBoolean(AnkiquestNavigation.PREF_SESSION_SUMMARY, true))
        coVerify(exactly = 0) { AnkiquestDeckShare.offer(any()) }
    }

    @Test
    fun `a waiting recap is discarded when credentials change before resume`() {
        val controller = host()
        val result = CompletableDeferred<AnkiquestStudySession.Summary>()
        val reading = CompletableDeferred<Unit>()
        coEvery { AnkiquestStudySession.finish() } coAnswers {
            reading.complete(Unit)
            result.await()
        }
        finish(controller.get())
        advanceRobolectricLooperUntil { reading.isCompleted }
        controller.pause().stop()
        result.complete(summary())
        advanceRobolectricLooper()
        prefs.edit { putString(Ankiquest.PREF_TOKEN, "different-token") }
        controller.start().resume()
        advanceRobolectricLooper()
        assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing && it.findViewById<ViewPager2>(R.id.aq_moment_pager) != null })
        coVerify(exactly = 0) { AnkiquestDeckShare.offer(any()) }
    }

    private fun views(view: View): Sequence<View> =
        sequence {
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    yieldAll(views(view.getChildAt(index)))
                }
            }
        }
}
