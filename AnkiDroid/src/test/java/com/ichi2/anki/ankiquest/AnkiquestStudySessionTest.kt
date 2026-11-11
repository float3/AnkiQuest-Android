// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.setIntroductionSlidesShown
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestStudySessionTest : RobolectricTest() {
    private val clock = MockTime(1_750_000_000_000)

    @Before
    fun configure() {
        setIntroductionSlidesShown(true)
        TimeManager.resetWith(clock)
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "member-token")
        }
    }

    private fun review(
        id: Long,
        ease: Int = 3,
        type: Int = 1,
    ) {
        col.db.execute(
            "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, 0, ?, 1, 1, 2500, 90000, ?)",
            id,
            ease,
            type,
        )
    }

    @Test
    fun `recap counts actual session reviews and honors undo and manual rescheduling`() =
        runBlocking {
            val began = clock.intTimeMS()
            review(began - 1000)
            AnkiquestStudySession.start()
            review(began + 1000, ease = 1)
            review(began + 2000)
            review(began + 3000, ease = 0)
            review(began + 4000, type = 4)
            col.db.execute("delete from revlog where id = ?", began + 2000)
            clock.addM(2)
            val summary = assertNotNull(AnkiquestStudySession.finish())
            assertEquals(1, summary.reviews)
            assertEquals(1, summary.minutes)
            assertEquals(summary, AnkiquestStudySession.latest())
            assertNull(AnkiquestStudySession.finish())
        }

    @Test
    fun `empty and previous-account sessions produce no recap`() =
        runBlocking {
            AnkiquestStudySession.start()
            assertNull(AnkiquestStudySession.finish())
            AnkiquestStudySession.start()
            review(clock.intTimeMS() + 1000)
            clock.addM(2)
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_USER, "someone-else") }
            assertNull(AnkiquestStudySession.finish())
            assertNull(AnkiquestStudySession.latest())
        }

    @Test
    fun `credential changes remove the persisted recap`() =
        runBlocking {
            AnkiquestStudySession.start()
            review(clock.intTimeMS() + 1000)
            clock.addM(2)
            assertNotNull(AnkiquestStudySession.finish())
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "new-token") }
            assertNull(AnkiquestStudySession.latest())
        }

    @Test
    fun `a recap cannot attach to the stopped deck picker`() {
        val result = CompletableDeferred<AnkiquestStudySession.Summary>()
        val reading = CompletableDeferred<Unit>()
        mockkObject(AnkiquestStudySession)
        try {
            coEvery { AnkiquestStudySession.finish() } coAnswers {
                reading.complete(Unit)
                result.await()
            }
            val intent = Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
            val controller = startActivityControllerNormallyOpenCollectionWithIntent(DeckPicker::class.java, intent)
            val deckPicker = controller.get()
            assertTrue(AnkiquestPostStudyPrompts.resumed(deckPicker))
            val before = ShadowDialog.getLatestDialog()
            AnkiquestDeckPickerHooks.onReviewResult(deckPicker, AbstractFlashcardViewer.RESULT_DEFAULT) {}
            advanceRobolectricLooperUntil { reading.isCompleted }

            controller.pause().stop()
            result.complete(AnkiquestStudySession.Summary(6, 1, 3, clock.intTimeMS()))
            advanceRobolectricLooper()

            assertEquals(before, ShadowDialog.getLatestDialog())
        } finally {
            result.cancel()
            unmockkObject(AnkiquestStudySession)
        }
    }

    @Test
    fun `a queued moment waits for the recap to close`() {
        AnkiDroidApp.sharedPrefs().edit { putBoolean(AnkiquestDeckShare.PREF_ASK, false) }
        mockkObject(AnkiquestStudySession)
        try {
            coEvery { AnkiquestStudySession.finish() } returns AnkiquestStudySession.Summary(6, 1, 3, clock.intTimeMS())
            val intent = Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
            val host = startActivityControllerNormallyOpenCollectionWithIntent(DeckPicker::class.java, intent).get()
            assertTrue(AnkiquestPostStudyPrompts.ready(host))
            AnkiquestDeckPickerHooks.onReviewResult(host, AbstractFlashcardViewer.RESULT_DEFAULT) {}
            advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
            val recap = assertNotNull(ShadowDialog.getLatestDialog())
            assertTrue(recap.isShowing)
            val account = assertNotNull(AnkiquestHomeData.account()).notificationAccount
            val moment = AnkiquestMoments.Moment("level:4", AnkiquestMoments.Kind.LEVEL, "Level 4", "", clock.intTimeMS())
            AnkiquestMoments.offer(AnkiDroidApp.sharedPrefs(), account, listOf(moment))

            AnkiquestMoments.present(host, AnkiDroidApp.sharedPrefs())
            assertSame(recap, ShadowDialog.getLatestDialog())
            assertEquals(listOf(moment), AnkiquestMoments.pending(AnkiDroidApp.sharedPrefs(), account))

            recap.dismiss()
            AnkiquestMoments.present(host, AnkiDroidApp.sharedPrefs())
            assertTrue(assertNotNull(ShadowDialog.getLatestDialog()).isShowing)
            assertTrue(AnkiquestMoments.pending(AnkiDroidApp.sharedPrefs(), account).isEmpty())
            ShadowDialog.getLatestDialog().dismiss()
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            unmockkObject(AnkiquestStudySession)
        }
    }

    @Test
    fun `a deck share response after stopping does not consume the daily question`() {
        val requested = CountDownLatch(1)
        val response = CountDownLatch(1)
        val intent = Intent().putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
        val controller = startActivityControllerNormallyOpenCollectionWithIntent(DeckPicker::class.java, intent)
        val host = controller.get()
        assertTrue(AnkiquestPostStudyPrompts.ready(host))
        val deck = col.decks.selected()
        val settings = JSONObject().put("decks", JSONArray()).put("recipients", JSONArray().put(JSONObject().put("user", "friend")))
        mockkObject(AnkiquestDecks, AnkiquestApi)
        try {
            every { AnkiquestDecks.dailyStates(any(), any(), any()) } returns mapOf(deck to DailyDeckState(0, 0, 1))
            every { AnkiquestApi.json(any(), match { it.url.encodedPath == "/api/decks/member" }, any()) } answers {
                requested.countDown()
                check(response.await(10, TimeUnit.SECONDS))
                settings
            }
            val before = ShadowDialog.getLatestDialog()
            val offer = CoroutineScope(Dispatchers.Main).async { AnkiquestDeckShare.offer(host) }
            advanceRobolectricLooperUntil { requested.count == 0L }
            controller.pause().stop()
            response.countDown()
            advanceRobolectricLooperUntil { offer.isCompleted }
            runBlocking { offer.await() }
            assertSame(before, ShadowDialog.getLatestDialog())

            controller.start().resume()
            val retry = CoroutineScope(Dispatchers.Main).async { AnkiquestDeckShare.offer(host) }
            advanceRobolectricLooperUntil { retry.isCompleted }
            runBlocking { retry.await() }
            assertTrue(assertNotNull(ShadowDialog.getLatestDialog()).isShowing, "The stopped host must not mark the deck already asked")
            ShadowDialog.getLatestDialog().dismiss()
        } finally {
            response.countDown()
            ShadowDialog.getLatestDialog()?.dismiss()
            unmockkObject(AnkiquestDecks, AnkiquestApi)
        }
    }
}
