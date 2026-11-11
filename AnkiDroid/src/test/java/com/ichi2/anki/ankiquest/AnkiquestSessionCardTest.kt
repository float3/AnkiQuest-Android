// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    private fun queue(key: String = "level:4"): AnkiquestMoments.Moment {
        val moment = AnkiquestMoments.Moment(key, AnkiquestMoments.Kind.LEVEL, "Level 4", "", clock.intTimeMS())
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
