// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestGlobalHidesScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
        }
    }

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestSocial.overview(any()) } returns AnkiquestSocial.parse(JSONObject("""{"groups":[]}"""))
        coEvery { AnkiquestBoards.hasScopes(any()) } returns true
        coEvery { AnkiquestBoards.weekEnds(any()) } returns null
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        coEvery { AnkiquestGlobalHides.load(any()) } returns emptyList()
        coEvery { AnkiquestBoards.leaderboard(any(), any(), any()) } returns
            AnkiquestBoards.parseLeaderboard(
                JSONArray("""[{"user":"ana","display":"Ana","rank":3,"level":4,"xp":900,"streak":7}]"""),
                AnkiquestBoards.Period.WEEK,
            )
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun hideAction() = captureAction("hide")

    @Test
    fun hideLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureAction("hide_200percent")
    }

    @Test
    fun hiddenPlayers() = captureHidden("hidden_players")

    @Test
    fun hiddenLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureHidden("hidden_players_200percent")
    }

    @Test
    fun emptyHiddenPlayers() {
        val screen = launch()
        click(screen, R.string.aq_board_hidden_players)
        awaitText(screen, R.string.aq_board_hidden_empty)
        captureScreen("hidden_empty")
    }

    @Test
    fun hiddenUnavailable() {
        coEvery { AnkiquestGlobalHides.load(any()) } throws IOException("offline")
        val screen = launch()
        awaitText(screen, R.string.aq_board_hide_failed)
        captureScreen("hidden_failed")
    }

    @Test
    fun savingHiddenPlayers() {
        val waiting = CompletableDeferred<List<AnkiquestGlobalHides.Player>>()
        coEvery { AnkiquestGlobalHides.change(any(), any(), any()) } coAnswers { waiting.await() }
        try {
            val screen = launch()
            click(screen, R.string.aq_board_hide_for_me)
            awaitText(screen, R.string.ankiquest_goal_saving)
            assertFalse(control(screen, R.string.aq_board_hide_for_me).isEnabled)
            captureScreen("hidden_saving")
        } finally {
            waiting.complete(emptyList())
            advanceRobolectricLooper()
        }
    }

    private fun captureAction(name: String) {
        val screen = launch()
        awaitText(screen, R.string.aq_board_hide_for_me)
        val button = control(screen, R.string.aq_board_hide_for_me)
        reveal(screen, button)
        val nameView =
            screen
                .findViewById<View>(R.id.aq_friends_board_content)
                .allViews
                .filterIsInstance<TextView>()
                .first { it.text.toString() == "Ana" }
        assertTrue(
            (0 until nameView.layout.lineCount).all { nameView.layout.getEllipsisCount(it) == 0 },
            "the Hide action cannot clip the name",
        )
        assertEquals("Hide Ana for me", button.contentDescription)
        assertFits(button)
        captureScreen(name)
    }

    private fun captureHidden(name: String) {
        coEvery { AnkiquestGlobalHides.load(any()) } returns listOf(AnkiquestGlobalHides.Player("cerro", "Cerro"))
        val screen = launch()
        click(screen, R.string.aq_board_hidden_players)
        awaitText(screen, R.string.aq_board_show_again)
        val button = control(screen, R.string.aq_board_show_again)
        reveal(screen, button)
        assertEquals("Show Cerro again", button.contentDescription)
        assertFits(button)
        captureScreen(name)
    }

    private fun assertFits(button: TextView) {
        val bounds = Rect()
        assertTrue(button.getGlobalVisibleRect(bounds))
        assertEquals(button.width, bounds.width())
        assertEquals(button.height, bounds.height())
        assertTrue((0 until button.layout.lineCount).all { button.layout.getEllipsisCount(it) == 0 })
    }

    private fun reveal(
        screen: AnkiquestFriendsActivity,
        target: View,
    ) {
        val scroll = screen.findViewById<NestedScrollView>(R.id.aq_feed_scroll)
        val bounds = Rect(0, 0, target.width, target.height)
        scroll.offsetDescendantRectToMyCoords(target, bounds)
        scroll.scrollTo(0, maxOf(0, bounds.bottom - scroll.height))
        advanceRobolectricLooper()
    }

    private fun launch(): AnkiquestFriendsActivity =
        startRegularActivity<AnkiquestFriendsActivity>(AnkiquestLeaderboardActivity.intent(targetContext)).also { screen ->
            awaitBoard(screen)
            click(screen, R.string.ankiquest_widget_who_global)
        }

    private fun awaitBoard(screen: AnkiquestFriendsActivity) {
        advanceRobolectricLooperUntil {
            !screen.findViewById<View>(R.id.aq_friends_board_loading).isVisible &&
                !screen.findViewById<View>(R.id.aq_feed_loading).isVisible
        }
        advanceRobolectricLooper()
    }

    private fun awaitText(
        screen: AnkiquestFriendsActivity,
        text: Int,
    ) {
        advanceRobolectricLooperUntil {
            screen
                .findViewById<View>(R.id.aq_friends_board_content)
                .allViews
                .filterIsInstance<TextView>()
                .any { it.text == screen.getString(text) }
        }
        advanceRobolectricLooper()
    }

    private fun click(
        screen: AnkiquestFriendsActivity,
        label: Int,
    ) {
        control(screen, label).performClick()
        awaitBoard(screen)
    }

    private fun control(
        screen: AnkiquestFriendsActivity,
        label: Int,
    ): TextView =
        screen
            .findViewById<View>(R.id.aq_friends_board_content)
            .allViews
            .filterIsInstance<TextView>()
            .first { it.isClickable && it.text == screen.getString(label) }
}
