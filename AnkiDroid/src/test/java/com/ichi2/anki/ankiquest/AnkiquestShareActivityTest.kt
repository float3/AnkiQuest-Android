// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnkiquestShareActivityTest : RobolectricTest() {
    private val account = HomeAccount("https://quest.example.test", "hill", "synthetic-token")
    private val link = AnkiquestShare.Link("https://quest.example.test/w/card", "https://quest.example.test/w/card.png", "A win")
    private val answer = CompletableDeferred<AnkiquestShare.Link>()
    private val prepared = AtomicBoolean(false)
    private var operation: Job? = null

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, Ankiquest, AnkiquestShare, AnkiquestSharing)
        every { AnkiquestHomeData.account(any()) } returns account
        coEvery { Ankiquest.shareWinLink(17, account.scope) } coAnswers {
            operation = currentCoroutineContext()[Job]
            answer.await()
        }
        every { AnkiquestShare.linkChooser(any(), any(), any(), any()) } answers {
            prepared.set(true)
            Intent(Intent.ACTION_CHOOSER)
        }
    }

    @After
    fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, Ankiquest, AnkiquestShare, AnkiquestSharing)
    }

    private fun intent() =
        Intent(targetContext, AnkiquestShareActivity::class.java)
            .putExtra(AnkiquestShare.EXTRA_TITLE, "A win")
            .putExtra(AnkiquestShare.EXTRA_BODY, "I reached my goal")
            .putExtra(AnkiquestShare.EXTRA_WIN, 17L)
            .putExtra(AnkiquestShare.EXTRA_SCOPE, account.scope)

    private fun ready() {
        advanceRobolectricLooperUntil { prepared.get() && operation?.children?.none { it.isActive } == true }
    }

    private fun delayLabel(): CompletableDeferred<String?> {
        val answer = CompletableDeferred<String?>()
        coEvery { AnkiquestSharing.winKey(account, 17) } returns "conquered:100"
        coEvery { AnkiquestSharing.conquestLabel(account, 100) } returns null
        coEvery { AnkiquestShare.conqueredCardText(100) } coAnswers {
            operation = currentCoroutineContext()[Job]
            answer.await()
        }
        return answer
    }

    @Test
    fun `a private label arriving after an account switch is never displayed`() {
        val label = delayLabel()
        val controller =
            Robolectric
                .buildActivity(
                    AnkiquestShareActivity::class.java,
                    intent().putExtra(AnkiquestShare.EXTRA_TOPIC, "conquered"),
                ).setup()
        saveControllerForCleanup(controller)
        advanceRobolectricLooperUntil { operation != null }
        every { AnkiquestHomeData.account(any()) } returns account.copy(user = "other")
        label.complete("PRIVATE LABEL FROM OLD ACCOUNT")
        advanceRobolectricLooperUntil { operation?.isCompleted == true }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `a private label arriving after stopping cannot open a dialog`() {
        val label = delayLabel()
        val controller =
            Robolectric
                .buildActivity(
                    AnkiquestShareActivity::class.java,
                    intent().putExtra(AnkiquestShare.EXTRA_TOPIC, "conquered"),
                ).setup()
        saveControllerForCleanup(controller)
        advanceRobolectricLooperUntil { operation != null }
        controller.pause().stop()
        label.complete("PRIVATE LABEL FROM STOPPED SCREEN")
        advanceRobolectricLooperUntil { operation?.isCompleted == true }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertTrue(controller.get().isFinishing)
    }

    private fun prompt(): Pair<AnkiquestShareActivity, AlertDialog> {
        coEvery { AnkiquestSharing.winKey(account, 17) } returns "conquered:100"
        coEvery { AnkiquestSharing.conquestLabel(account, 100) } returns null
        coEvery { AnkiquestShare.conqueredCardText(100) } returns "Private suggestion"
        val controller =
            Robolectric
                .buildActivity(
                    AnkiquestShareActivity::class.java,
                    intent().putExtra(AnkiquestShare.EXTRA_TOPIC, "conquered"),
                ).setup()
        saveControllerForCleanup(controller)
        advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
        return controller.get() to (ShadowDialog.getLatestDialog() as AlertDialog)
    }

    @Test
    fun `a card suggestion stays private unless its checkbox is chosen`() {
        val (activity, dialog) = prompt()
        val content =
            dialog.window!!
                .decorView.allViews
                .toList()
        assertFalse(content.filterIsInstance<CheckBox>().single().isChecked)
        val field = content.filterIsInstance<EditText>().single()
        assertEquals("Private suggestion", field.text.toString())
        assertFalse(field.isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        answer.complete(link)
        advanceRobolectricLooperUntil { shadowOf(activity).peekNextStartedActivity() != null }
        coVerify(exactly = 0) { AnkiquestSharing.setConquestLabel(any(), any(), any()) }
        assertEquals(Intent.ACTION_CHOOSER, assertNotNull(shadowOf(activity).nextStartedActivity).action)
    }

    @Test
    fun `a repeated Next cannot cancel or repeat the chosen label save`() {
        val saved = CompletableDeferred<Unit>()
        coEvery { AnkiquestSharing.setConquestLabel(account, 100, "Chosen public label") } coAnswers { saved.await() }
        val (activity, dialog) = prompt()
        val content =
            dialog.window!!
                .decorView.allViews
                .toList()
        content.filterIsInstance<CheckBox>().single().isChecked = true
        content.filterIsInstance<EditText>().single().setText("Chosen public label")
        val next = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        next.performClick()
        next.performClick()
        advanceRobolectricLooper()
        assertFalse(activity.isFinishing, "the second tap must not cancel the first save")
        saved.complete(Unit)
        answer.complete(link)
        advanceRobolectricLooperUntil { shadowOf(activity).peekNextStartedActivity() != null }
        coVerify(exactly = 1) { AnkiquestSharing.setConquestLabel(account, 100, "Chosen public label") }
        assertEquals(Intent.ACTION_CHOOSER, assertNotNull(shadowOf(activity).nextStartedActivity).action)
    }

    @Test
    fun `a retained Next cannot save a suggestion into a replacement account`() {
        val (activity, dialog) = prompt()
        val content =
            dialog.window!!
                .decorView.allViews
                .toList()
        content.filterIsInstance<CheckBox>().single().isChecked = true
        val next = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        every { AnkiquestHomeData.account(any()) } returns account.copy(user = "other")
        next.performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 0) { AnkiquestSharing.setConquestLabel(any(), any(), any()) }
        assertFalse(dialog.isShowing)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a notification share waits for the activity to resume`() {
        val controller = Robolectric.buildActivity(AnkiquestShareActivity::class.java, intent()).setup()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        advanceRobolectricLooperUntil { operation != null }
        controller.pause()
        answer.complete(link)
        ready()
        assertNull(shadowOf(activity).nextStartedActivity, "a background activity must not launch a share sheet")
        controller.resume()
        advanceRobolectricLooperUntil { operation?.isCompleted == true }
        assertEquals(Intent.ACTION_CHOOSER, assertNotNull(shadowOf(activity).nextStartedActivity).action)
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `a prepared notification picture is discarded after an account switch`() {
        val controller = Robolectric.buildActivity(AnkiquestShareActivity::class.java, intent()).setup()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        advanceRobolectricLooperUntil { operation != null }
        controller.pause()
        every { AnkiquestHomeData.account(any()) } returns account.copy(user = "other")
        answer.complete(link)
        ready()
        controller.resume()
        advanceRobolectricLooperUntil { operation?.isCompleted == true }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
