// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.core.content.edit
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
import kotlin.test.assertNotNull

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
        openCard()
        captureScreen("session_card_recap")
        assertNotNull(ShadowDialog.getLatestDialog().findViewById<ViewPager2>(R.id.aq_moment_pager)).setCurrentItem(1, false)
        advanceRobolectricLooper()
        captureScreen("session_card_moment")
    }

    @Test
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        openCard()
        captureScreen("session_card_recap_200percent")
    }

    private fun openCard() {
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
    }
}
