// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The recap, moments and navigation actions must fit one card, including large text. */
class AnkiquestSessionScreenshotTest : ScreenshotTest() {
    @Before
    fun prepare() {
        setIntroductionSlidesShown(true)
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "screenshot-token")
        }
        mockkObject(AnkiquestStudySession, AnkiquestDeckShare)
        coEvery { AnkiquestStudySession.finish() } returns AnkiquestStudySession.Summary(24, 6, 12, 1_750_000_000_000)
        coEvery { AnkiquestDeckShare.offer(any()) } returns Unit
    }

    @After
    fun cleanup() {
        ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() }
        BackupManagerTestUtilities.reset()
        unmockkObject(AnkiquestStudySession, AnkiquestDeckShare)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun session() {
        openCard("session_card_recap")
        selectMoment()
        captureScreen("session_card_moment")
    }

    @Test
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        openCard("session_card_recap_200percent")
        revealOptOut()
        captureScreen("session_card_opt_out_200percent")
        selectMoment()
        captureScreen("session_card_moment_200percent")
    }

    @Test
    fun smallPhone() {
        RuntimeEnvironment.setQualifiers("+w320dp-h568dp")
        openCard("session_card_small_phone")
        revealOptOut()
        captureScreen("session_card_small_opt_out")
        selectMoment()
        captureScreen("session_card_small_moment")
    }

    private fun revealOptOut() {
        val checkbox = assertNotNull(ShadowDialog.getLatestDialog().findViewById<CheckBox>(R.id.aq_recap_opt_out))
        checkbox.requestRectangleOnScreen(Rect(0, 0, checkbox.width, checkbox.height), true)
        advanceRobolectricLooper()
        assertFullyVisible(checkbox)
    }

    private fun assertFullyVisible(view: View) {
        val bounds = Rect()
        assertTrue(view.getGlobalVisibleRect(bounds), "Control must be visible")
        assertEquals(view.height, bounds.height(), "Control must not be clipped")
        assertTrue(bounds.top >= 0 && bounds.bottom <= view.resources.displayMetrics.heightPixels)
    }

    private fun selectMoment() {
        val pager = assertNotNull(ShadowDialog.getLatestDialog().findViewById<ViewPager2>(R.id.aq_moment_pager))
        val list = pager.getChildAt(0) as RecyclerView
        // Exercise the same animated page change offered to accessibility services.
        assertTrue(pager.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        advanceRobolectricLooperUntil {
            pager.currentItem == 1 && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                list.findViewHolderForAdapterPosition(1) != null
        }
        val page = assertNotNull(list.findViewHolderForAdapterPosition(1)).itemView
        assertEquals(0, page.left, "Selected page must fill the pager")
        assertEquals(pager.width, page.width)
    }

    private fun openCard(screenshot: String) {
        val host =
            startActivityNormallyOpenCollectionWithIntent(
                DeckPicker::class.java,
                Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true),
            )
        val prefs = AnkiDroidApp.sharedPrefs()
        val account = assertNotNull(AnkiquestHomeData.account()).notificationAccount
        AnkiquestMoments.offer(
            prefs,
            account,
            listOf(AnkiquestMoments.Moment("level:4", AnkiquestMoments.Kind.LEVEL, "Level 4", "You reached level 4.", 1_750_000_000_000)),
        )
        AnkiquestDeckPickerHooks.onReviewResult(host, AbstractFlashcardViewer.RESULT_DEFAULT) {}
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        advanceRobolectricLooper()
        captureScreen(screenshot)
        val card = ShadowDialog.getLatestDialog() as AlertDialog
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
            assertFullyVisible(card.getButton(it))
        }
        val pager = assertNotNull(card.findViewById<ViewPager2>(R.id.aq_moment_pager))
        val recap = assertNotNull((pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(0)).itemView
        assertEquals(0, recap.left)
        assertEquals(pager.width, recap.width, "The recap must fill the pager before any swipe")
    }
}
