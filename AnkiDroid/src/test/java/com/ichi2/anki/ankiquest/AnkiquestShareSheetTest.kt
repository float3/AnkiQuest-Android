// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestShareSheet.Exit
import com.ichi2.anki.ankiquest.AnkiquestShareSheet.State
import com.ichi2.anki.ankiquest.AnkiquestShareSheet.Step
import com.ichi2.anki.ankiquest.AnkiquestSharing.Audience
import com.ichi2.anki.ankiquest.AnkiquestSharing.MomentRef
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The share sheet: picking friends and groups, the note, sending with Undo, and the ways it can fail. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnkiquestShareSheetTest : RobolectricTest() {
    private val account = HomeAccount("https://quest.example.test", "hill", "synthetic-token")
    private val moment = MomentRef.Win(17)
    private val card = AnkiquestShare.Card("Level 54", "I reached level 54.")
    private val members = listOf(AnkiquestSocial.Person("hill", "Hill"), AnkiquestSocial.Person("ana", "Ana"))
    private var overview =
        AnkiquestSocial.Overview(
            friends = listOf(AnkiquestSocial.Person("ana", "Ana"), AnkiquestSocial.Person("cerro", "Cerro")),
            incoming = emptyList(),
            outgoing = emptyList(),
            muted = emptyList(),
            groups = listOf(AnkiquestSocial.Group(3, "Spanish club", owner = false, invite = null, members = members)),
            public = true,
        )
    private val exits = mutableListOf<Exit>()
    private var elsewhere = 0

    @Before
    fun prepare() {
        TimeManager.resetWith(MockTime(NOW))
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestSharing, AnkiquestShare)
        every { AnkiquestHomeData.account(any()) } returns account
        coEvery { AnkiquestSocial.overview(account) } answers { overview }
        every { AnkiquestShare.render(any(), any()) } returns Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    @After
    fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestSharing, AnkiquestShare)
        TimeManager.reset()
    }

    @Test
    fun `All friends comes first, then friends and groups, and search narrows them`() {
        val (_, dialog) = open()
        assertEquals(listOf("All friends", "Ana", "Cerro", "Spanish club"), chips(dialog).map { it.text.toString() })
        assertEquals("Group: Spanish club", chips(dialog).last().contentDescription)

        dialog.findViewById<EditText>(R.id.aq_share_search)!!.setText("SPA")
        assertEquals(listOf("All friends", "Spanish club"), chips(dialog).map { it.text.toString() })

        dialog.findViewById<EditText>(R.id.aq_share_search)!!.setText("nobody")
        assertTrue(texts(dialog).contains(targetContext.getString(R.string.ankiquest_share_no_match)))
    }

    @Test
    fun `Send waits for a choice, and All friends and chosen people replace each other`() {
        val (sheet, dialog) = open()
        val send = dialog.findViewById<View>(R.id.aq_share_send)!!
        assertFalse(send.isEnabled)

        chip(dialog, "Ana").performClick()
        chip(dialog, "Spanish club").performClick()
        assertTrue(send.isEnabled)
        assertEquals(listOf("Ana", "Spanish club"), chips(dialog).filter { it.isChecked }.map { it.text.toString() })

        chip(dialog, "All friends").performClick()
        assertEquals(listOf("All friends"), chips(dialog).filter { it.isChecked }.map { it.text.toString() })
        assertEquals(AnkiquestMomentShare.Choice.Everyone, assertIs<State.Picking>(sheet.state).choice)

        chip(dialog, "All friends").performClick()
        assertFalse(send.isEnabled)
    }

    @Test
    fun `the note is held to the server's length and sent as one line`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(1, 17, null)
        val (_, dialog) = open()
        val note = dialog.findViewById<EditText>(R.id.aq_share_note)!!
        note.setText("x".repeat(200))
        assertEquals(AnkiquestSharing.MAX_NOTE, note.text.length)

        note.setText("Took me\na month!")
        chip(dialog, "Cerro").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        coVerify { AnkiquestSharing.send(account, moment, Audience.Chosen(listOf("cerro"), emptyList()), "Took me a month!") }
    }

    @Test
    fun `sending posts in the app and offers Undo, which takes it back`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(2, 17, NOW + DAY)
        coEvery { AnkiquestSharing.retract(account, 17) } returns 2
        var sent: AnkiquestSharing.Sent? = null
        val (_, dialog, screen) = open { sent = it }
        chip(dialog, "Ana").performClick()
        chip(dialog, "Spanish club").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()

        coVerify { AnkiquestSharing.send(account, moment, Audience.Chosen(listOf("ana"), listOf(3L)), null) }
        assertFalse(dialog.isShowing)
        assertEquals(17L, sent?.win)
        assertEquals(
            "Shared with 2 friends",
            screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text.toString(),
        )
        val undo = assertNotNull(screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_action))
        assertEquals(targetContext.getString(CommonString.undo), undo.text.toString())
        assertTrue(exits.isEmpty(), "the sheet is not done while Undo is offered")

        undo.performClick()
        advanceRobolectricLooperUntil { ShadowToast.getTextOfLatestToast() != null }
        coVerify(exactly = 1) { AnkiquestSharing.retract(account, 17) }
        assertEquals(targetContext.getString(R.string.aq_conquest_untold), ShadowToast.getTextOfLatestToast())
        idle()
        assertEquals(listOf(Exit.SENT), exits)
    }

    @Test
    fun `Undo too late says so`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(1, 17, NOW + DAY)
        coEvery { AnkiquestSharing.retract(account, 17) } throws AnkiquestHttpException(409)
        val (_, dialog, screen) = open()
        chip(dialog, "All friends").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        coVerify { AnkiquestSharing.send(account, moment, Audience.Circle, null) }
        screen.findViewById<View>(com.google.android.material.R.id.snackbar_action)!!.performClick()
        advanceRobolectricLooperUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals(targetContext.getString(R.string.aq_conquest_untell_late), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `nobody new getting it has no Undo`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } returns AnkiquestSharing.Sent(0, 17, null)
        val (_, dialog, screen) = open()
        chip(dialog, "Ana").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        assertNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action))
        assertEquals(targetContext.getString(R.string.ankiquest_share_nobody), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(Exit.SENT), exits)
    }

    @Test
    fun `a failed send keeps the sheet and the choice, and can be tried again`() {
        coEvery { AnkiquestSharing.send(account, moment, any(), any()) } throws IOException("offline")
        val (sheet, dialog) = open()
        chip(dialog, "Ana").performClick()
        dialog.findViewById<View>(R.id.aq_share_send)!!.performClick()
        idle()
        assertTrue(dialog.isShowing)
        val failed = assertIs<State.Picking>(sheet.state)
        assertEquals(Step.FAILED, failed.step)
        assertEquals(listOf("Ana"), chips(dialog).filter { it.isChecked }.map { it.text.toString() })
        val status = dialog.findViewById<TextView>(R.id.aq_share_status)!!
        assertEquals(targetContext.getString(R.string.ankiquest_share_failed), status.text.toString())
        assertTrue(dialog.findViewById<View>(R.id.aq_share_send)!!.isEnabled)
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `More apps opens Android's share sheet, even when friends cannot be loaded`() {
        coEvery { AnkiquestSocial.overview(account) } throws IOException("offline")
        val (sheet, dialog) = open()
        assertEquals(State.Offline, sheet.state)
        assertFalse(dialog.findViewById<View>(R.id.aq_share_send)!!.isVisible)
        dialog.findViewById<View>(R.id.aq_share_more)!!.performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, elsewhere)
        assertEquals(listOf(Exit.ELSEWHERE), exits)
    }

    @Test
    fun `with no friends yet the sheet invites one`() {
        overview = overview.copy(friends = emptyList(), groups = emptyList())
        val (sheet, dialog, screen) = open()
        assertEquals(State.Lonely, sheet.state)
        assertFalse(dialog.findViewById<View>(R.id.aq_share_send)!!.isVisible)
        texts(dialog).first { it == targetContext.getString(R.string.ankiquest_share_invite) }
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text.toString() == targetContext.getString(R.string.ankiquest_share_invite) }
            .performClick()
        idle()
        assertEquals(AnkiquestFriendsActivity::class.java.name, shadowOf(screen).nextStartedActivity?.component?.className)
        assertEquals(listOf(Exit.CANCELLED), exits)
    }

    @Test
    fun `cancelling the sheet sends nothing`() {
        val (_, dialog) = open()
        chip(dialog, "Ana").performClick()
        dialog.cancel()
        idle()
        coVerify(exactly = 0) { AnkiquestSharing.send(any(), any(), any(), any()) }
        assertEquals(listOf(Exit.CANCELLED), exits)
    }

    private data class Opened(
        val sheet: AnkiquestShareSheet,
        val dialog: BottomSheetDialog,
        val screen: EmptyAnkiActivity,
    )

    private fun open(sent: (AnkiquestSharing.Sent) -> Unit = {}): Opened {
        val screen =
            startActivityControllerNormallyOpenCollectionWithIntent(
                EmptyAnkiActivity::class.java,
                Intent(targetContext, EmptyAnkiActivity::class.java),
            ).get()
        screen.setContentView(FrameLayout(screen))
        val sheet =
            AnkiquestShareSheet(
                screen,
                account,
                moment,
                card,
                elsewhere = { elsewhere++ },
                sent = sent,
                closed = { exits += it },
            )
        sheet.show()
        idle()
        advanceRobolectricLooperUntil { sheet.state != State.Loading }
        return Opened(sheet, assertIs<BottomSheetDialog>(ShadowDialog.getLatestDialog()), screen)
    }

    private fun chips(dialog: BottomSheetDialog): List<Chip> =
        dialog
            .findViewById<ChipGroup>(R.id.aq_share_targets)!!
            .allViews
            .filterIsInstance<Chip>()
            .toList()

    private fun chip(
        dialog: BottomSheetDialog,
        label: String,
    ): Chip = chips(dialog).first { it.text.toString() == label }

    private fun texts(dialog: BottomSheetDialog): List<String> =
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
            .toList()

    /** Finish the snackbar's animation without reaching its timeout. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

    private companion object {
        const val NOW = 1_793_183_460_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
