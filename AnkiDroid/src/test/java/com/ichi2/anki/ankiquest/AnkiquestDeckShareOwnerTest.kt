// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestDeckShareOwnerTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "test-token")
    private var account: HomeAccount? = identity
    private val settings =
        JSONObject(
            """{"decks":[{"id":"1","enabled":true,"recipients":["ana"]}],
               "recipients":[{"user":"ana","display":"Ana"},{"user":"cerro","display":"Cerro"}]}""",
        )

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, Ankiquest)
        every { AnkiquestHomeData.account(any()) } answers { account }
        coEvery { Ankiquest.deckNotificationSettings(any()) } returns settings
        coEvery { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) } returns JSONObject()
    }

    @After
    fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, Ankiquest)
    }

    @Test
    fun `a delayed picker cannot open for a changed account`() {
        val waiting = CompletableDeferred<JSONObject>()
        coEvery { Ankiquest.deckNotificationSettings(any()) } coAnswers { waiting.await() }
        val screen = launch().get()
        val work = startPicker(screen)
        account = identity.copy(user = "other", token = "other-token")
        waiting.complete(settings)
        advanceRobolectricLooperUntil { work.isCompleted }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `a delayed picker cannot open after its activity stops`() {
        val waiting = CompletableDeferred<JSONObject>()
        coEvery { Ankiquest.deckNotificationSettings(any()) } coAnswers { waiting.await() }
        val controller = launch()
        val work = startPicker(controller.get())
        controller.pause().stop()
        waiting.complete(settings)
        advanceRobolectricLooperUntil { work.isCompleted }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `returning to an activity does not revive its delayed picker`() {
        val waiting = CompletableDeferred<JSONObject>()
        coEvery { Ankiquest.deckNotificationSettings(any()) } coAnswers { waiting.await() }
        val controller = launch()
        val work = startPicker(controller.get())
        controller
            .pause()
            .stop()
            .start()
            .resume()
        waiting.complete(settings)
        advanceRobolectricLooperUntil { work.isCompleted }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `the current owner can save the preselected recipient`() {
        val screen = launch().get()
        AnkiquestDeckShare.choose(screen, 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertTrue(dialog.listView.isItemChecked(0))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 1) { Ankiquest.saveDeckNotificationSettings(listOf("1"), emptyList(), listOf("ana"), identity) }
    }

    @Test
    fun `an old picker cannot save into a new account`() {
        val screen = launch().get()
        AnkiquestDeckShare.choose(screen, 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertTrue(dialog.listView.isItemChecked(0), "the existing recipient stays selected")
        assertFalse(dialog.listView.isItemChecked(1))
        account = identity.copy(user = "other", token = "other-token")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 0) { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) }
    }

    @Test
    fun `a stopped picker cannot save through a retained button`() {
        val controller = launch()
        AnkiquestDeckShare.choose(controller.get(), 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        controller.pause().stop()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 0) { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) }
    }

    @Test
    fun `a retained Save button cannot submit twice`() {
        AnkiquestDeckShare.choose(launch().get(), 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        repeat(2) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
        advanceRobolectricLooper()
        coVerify(exactly = 1) { Ankiquest.saveDeckNotificationSettings(listOf("1"), emptyList(), listOf("ana"), identity) }
    }

    @Test
    fun `a cancelled picker cannot save through a retained button`() {
        AnkiquestDeckShare.choose(launch().get(), 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        advanceRobolectricLooper()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 0) { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) }
    }

    @Test
    fun `clearing recipients still stops sharing that deck`() {
        settings.getJSONArray("decks").getJSONObject(0).put("recipients", org.json.JSONArray())
        AnkiquestDeckShare.choose(launch().get(), 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 1) { Ankiquest.saveDeckNotificationSettings(emptyList(), listOf("1"), emptyList(), identity) }
    }

    @Test
    fun `cancelling a pending picker remains cancellation`() {
        val waiting = CompletableDeferred<JSONObject>()
        coEvery { Ankiquest.deckNotificationSettings(any()) } coAnswers { waiting.await() }
        val work = startPicker(launch().get())
        waiting.completeExceptionally(CancellationException("screen left"))
        advanceRobolectricLooperUntil { work.isCompleted }
        assertTrue(work.isCancelled)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `save success cannot announce itself to another account`() {
        val waiting = CompletableDeferred<JSONObject>()
        coEvery { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) } coAnswers { waiting.await() }
        AnkiquestDeckShare.choose(launch().get(), 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        val work = startSave(dialog)
        val toasts = ShadowToast.shownToastCount()
        account = identity.copy(token = "new-token")
        waiting.complete(JSONObject())
        advanceRobolectricLooperUntil { work.isCompleted }
        assertEquals(toasts, ShadowToast.shownToastCount())
        coVerify(exactly = 1) { Ankiquest.saveDeckNotificationSettings(listOf("1"), emptyList(), listOf("ana"), identity) }
    }

    @Test
    fun `save failure cannot announce itself after the activity stops`() {
        val waiting = CompletableDeferred<JSONObject>()
        coEvery { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) } coAnswers { waiting.await() }
        val controller = launch()
        AnkiquestDeckShare.choose(controller.get(), 1)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        val work = startSave(dialog)
        val toasts = ShadowToast.shownToastCount()
        controller
            .pause()
            .stop()
            .start()
            .resume()
        waiting.completeExceptionally(java.io.IOException("offline"))
        advanceRobolectricLooperUntil { work.isCompleted }
        assertEquals(toasts, ShadowToast.shownToastCount())
    }

    private fun startSave(dialog: AlertDialog): Job {
        val scope = assertNotNull(applicationScope.coroutineContext[Job])
        val before = scope.children.toSet()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        return scope.children.first { it !in before }
    }

    private fun startPicker(screen: EmptyAnkiActivity): Job {
        val scope = assertNotNull(screen.lifecycleScope.coroutineContext[Job])
        val before = scope.children.toSet()
        AnkiquestDeckShare.choose(screen, 1)
        return scope.children.first { it !in before }
    }

    private fun launch() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            EmptyAnkiActivity::class.java,
            Intent(targetContext, EmptyAnkiActivity::class.java),
        )
}
