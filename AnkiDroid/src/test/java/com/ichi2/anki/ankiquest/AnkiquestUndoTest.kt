// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestUndoTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private var account: HomeAccount? = identity

    @Before
    fun prepareAccount() {
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } answers { account }
    }

    @After
    fun cleanupAccount() {
        unmockkObject(AnkiquestHomeData)
    }

    @Test
    fun `Undo appears only after success and can be used once`() {
        val screen = launch().get()
        val undo = AnkiquestUndo(screen)
        var completed: (() -> Unit)? = null
        var reversed = 0
        undo.apply(identity, "Muted", perform = { completed = it }, inverse = { reversed++ })
        idle()
        assertNull(action(screen))
        assertNotNull(completed).invoke()
        idle()
        val button = assertNotNull(action(screen))
        button.performClick()
        button.performClick()
        assertEquals(1, reversed)
    }

    @Test
    fun `failed work has no Undo and cannot report success`() {
        val screen = launch().get()
        val undo = AnkiquestUndo(screen)
        assertFailsWith<IOException> {
            undo.apply(identity, "Muted", perform = { throw IOException("offline") }, inverse = { error("no successful change") })
        }
        idle()
        assertNull(action(screen))
    }

    @Test
    fun `a result arriving after the screen stopped does not offer Undo on return`() {
        val controller = launch()
        val undo = AnkiquestUndo(controller.get())
        var completed: (() -> Unit)? = null
        undo.apply(identity, "Muted", perform = { completed = it }, inverse = { error("stale action") })
        controller
            .pause()
            .stop()
            .start()
            .resume()
        assertNotNull(completed).invoke()
        idle()
        assertNull(action(controller.get()))
    }

    @Test
    fun `Undo never crosses an account change`() {
        val screen = launch().get()
        val undo = AnkiquestUndo(screen)
        var reversed = 0
        undo.apply(identity, "Muted", perform = { it() }, inverse = { reversed++ })
        idle()
        val button = assertNotNull(action(screen))
        account = identity.copy(user = "other", token = "other-token")
        button.performClick()
        assertEquals(0, reversed)

        var applied = 0
        undo.apply(identity, "Muted", perform = { applied++ }, inverse = { reversed++ })
        assertEquals(0, applied, "a stale screen must not start another account's change")
    }

    @Test
    fun `a replacement action invalidates an older success callback`() {
        val screen = launch().get()
        val undo = AnkiquestUndo(screen)
        var oldCompletion: (() -> Unit)? = null
        var reversed = 0
        undo.apply(identity, "Old", perform = { oldCompletion = it }, inverse = { error("old inverse") })
        undo.apply(identity, "New", perform = { it() }, inverse = { reversed++ })
        assertNotNull(oldCompletion).invoke()
        idle()
        assertNotNull(action(screen)).performClick()
        assertEquals(1, reversed)
    }

    @Test
    fun `an expired snackbar cannot invoke its old inverse`() {
        val screen = launch().get()
        val undo = AnkiquestUndo(screen)
        var reversed = 0
        undo.apply(identity, "Muted", perform = { it() }, inverse = { reversed++ })
        idle()
        val oldButton = assertNotNull(action(screen))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        oldButton.performClick()
        assertEquals(0, reversed)
    }

    @Test
    fun `closed is told once the snackbar is gone, or at once when nothing is offered`() {
        val screen = launch().get()
        val undo = AnkiquestUndo(screen)
        var closed = 0
        undo.apply(identity, "Sent", perform = { it() }, inverse = {}, closed = { closed++ })
        idle()
        assertNotNull(action(screen))
        assertEquals(0, closed)
        undo.clear()
        idle()
        assertEquals(1, closed)

        account = null
        undo.apply(identity, "Sent", perform = { error("not owned") }, inverse = {}, closed = { closed++ })
        assertEquals(2, closed)
    }

    @Test
    fun `the snackbar can show in a dialog's window`() {
        val screen = launch().get()
        val dialog = AppCompatDialog(screen).apply { setContentView(FrameLayout(screen)) }
        dialog.show()
        val undo = AnkiquestUndo(screen) { dialog.findViewById<View>(android.R.id.content)!! }
        undo.apply(identity, "Sent", perform = { it() }, inverse = {})
        idle()
        assertNull(action(screen))
        assertNotNull(dialog.findViewById<View>(com.google.android.material.R.id.snackbar_action))
        dialog.dismiss()
    }

    private fun launch() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            EmptyAnkiActivity::class.java,
            Intent(targetContext, EmptyAnkiActivity::class.java),
        ).also { it.get().setContentView(FrameLayout(it.get())) }

    private fun action(activity: EmptyAnkiActivity): View? = activity.findViewById(com.google.android.material.R.id.snackbar_action)

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
}
