// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Protection changes retain their explanation and readable Undo at both text sizes. */
class AnkiquestProtectionScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")
    private var enabled = true

    private enum class Protection { READY, PAUSED }

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
        }
    }

    @Before
    fun prepareProtection() {
        mockkObject(Ankiquest, AnkiquestHomeData, AnkiquestBoards, AnkiquestAvatars)
        every { AnkiquestHomeData.account(any()) } returns identity
        val profile = AnkiquestBoards.parseProfile(JSONObject(AnkiquestBoardsTest.PROFILE))
        coEvery { AnkiquestBoards.player(identity, identity.user) } answers {
            AnkiquestBoards.PlayerPage(profile.copy(freezesEnabled = enabled), own = true, wins = emptyList())
        }
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        coEvery { Ankiquest.setStreakProtection(any()) } answers {
            enabled = firstArg()
            enabled
        }
    }

    @After
    fun cleanupProtection() {
        unmockkObject(Ankiquest, AnkiquestHomeData, AnkiquestBoards, AnkiquestAvatars)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun paused() = captureProtection("protection_paused", Protection.PAUSED)

    @Test
    fun resumed() = captureProtection("protection_resumed", Protection.READY)

    @Test
    fun pausedLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureProtection("protection_paused_200percent", Protection.PAUSED)
    }

    @Test
    fun resumedLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureProtection("protection_resumed_200percent", Protection.READY)
    }

    private fun captureProtection(
        name: String,
        result: Protection,
    ) {
        enabled = result == Protection.PAUSED
        val screen = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext))
        advanceRobolectricLooperUntil { !screen.findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
        val label = if (enabled) R.string.aq_profile_freezes_pause else R.string.aq_profile_freezes_resume
        val control =
            screen.window.decorView.allViews
                .filterIsInstance<TextView>()
                .first { it.text == screen.getString(label) }
        val scroll = screen.findViewById<NestedScrollView>(R.id.aq_board_scroll)
        val bounds = Rect(0, 0, control.width, control.height)
        scroll.offsetDescendantRectToMyCoords(control, bounds)
        scroll.scrollTo(0, bounds.top)
        control.performClick()
        // Finish the show animation without expiring the action.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(result == Protection.READY, enabled)
        // The new label can wrap differently; frame the updated control after the profile reloads.
        val updatedLabel = if (enabled) R.string.aq_profile_freezes_pause else R.string.aq_profile_freezes_resume
        val updated =
            screen.window.decorView.allViews
                .filterIsInstance<TextView>()
                .first { it.text == screen.getString(updatedLabel) }
        val position = Rect(0, 0, updated.width, updated.height)
        scroll.offsetDescendantRectToMyCoords(updated, position)
        scroll.scrollTo(0, position.top)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val controlBounds = Rect()
        assertTrue(updated.getGlobalVisibleRect(controlBounds))
        assertEquals(updated.height, controlBounds.height(), "the updated control must fit on screen")
        val action = assertNotNull(screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_action))
        assertEquals(screen.getString(CommonString.undo), action.text.toString())
        assertTrue(action.isShown)
        val visible = Rect()
        assertTrue(action.getGlobalVisibleRect(visible))
        assertEquals(action.height, visible.height(), "Undo must fit on screen")
        assertTrue(
            screen.window.decorView.allViews.filterIsInstance<TextView>().any {
                it.text == screen.getString(R.string.ankiquest_streak_protection_summary)
            },
        )
        captureScreen(name)
    }
}
