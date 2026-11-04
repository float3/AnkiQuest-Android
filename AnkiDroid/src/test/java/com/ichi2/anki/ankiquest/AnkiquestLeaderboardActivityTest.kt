// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestLeaderboardActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "bo", "secret")
    private val week =
        AnkiquestBoards.parseLeaderboard(
            JSONArray()
                .put(AnkiquestBoardsTest.row("ana", week = 90, hour = 5))
                .put(AnkiquestBoardsTest.row("bo", week = 40, hour = 30))
                .put(AnkiquestBoardsTest.row("cy", week = 10, hour = 0))
                .put(AnkiquestBoardsTest.row("di", week = 2, hour = 0).put("streak", 0)),
            AnkiquestBoards.Period.WEEK,
        )

    @Before
    fun prepareBoard() {
        TimeManager.resetWith(MockTime(NOW))
        mockkObject(AnkiquestHomeData, AnkiquestBoards)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } returns week
        coEvery { AnkiquestBoards.weekEnds(identity) } returns NOW + 2 * 24 * 60 * 60 * 1000L
    }

    @After
    fun clearBoardMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestBoards)
    }

    @Test
    fun `the week shows each player's rank, XP, streak and today, with the player marked`() {
        val board = launch()
        assertTrue(board.hasText("Ana"))
        assertTrue(board.hasText(board.getString(R.string.aq_challenges_you, "Bo")), "the player's own row says so")
        assertTrue(board.hasText(board.getString(R.string.aq_board_xp, "90")))
        assertTrue(board.hasText(board.getString(R.string.aq_board_today_reviews, "4")))
        assertTrue(board.hasText("4"), "places after the podium are numbered")
        assertTrue(
            board.hasText(
                board.getString(R.string.ankiquest_widget_level, 1) + " · 3 · " + board.getString(R.string.ankiquest_streak_studied),
            ),
        )
        assertTrue(board.hasText(board.getString(R.string.ankiquest_widget_level, 1)), "a player without a streak shows no streak")
        assertTrue(board.hasText(board.getString(R.string.aq_board_your_place, 2, 4)))
        assertTrue(board.hasTextContaining(board.getString(R.string.aq_board_resets, "").trim()), "the week says when it resets")
        val own =
            board.views<View>().first {
                it.contentDescription?.startsWith(
                    board.getString(R.string.aq_board_row_description, 2, board.getString(R.string.aq_challenges_you, "Bo"), 1, "40"),
                ) ==
                    true
            }
        assertTrue(own.contentDescription.contains(board.getString(R.string.ankiquest_streak_studied)))
        coVerify { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK) }
    }

    @Test
    fun `a player whose day began before the reset shows the XP still counting for last week`() {
        val closing =
            AnkiquestBoards.parseLeaderboard(
                JSONArray()
                    .put(AnkiquestBoardsTest.row("ana", week = 0, hour = 0).put("closing_xp", 1234).put("day_ends_at", NOW + 60_000))
                    .put(AnkiquestBoardsTest.row("bo", week = 5, hour = 0).put("closing_xp", 9).put("day_ends_at", NOW - 1)),
                AnkiquestBoards.Period.WEEK,
            )
        coEvery { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK) } returns closing
        val board = launch()
        assertTrue(board.hasText(board.getString(R.string.aq_board_closing_xp, "1,234")))
        assertFalse(board.hasText(board.getString(R.string.aq_board_closing_xp, "9")), "a day that has ended no longer counts")
        val described = board.getString(R.string.aq_board_closing_xp, "1,234")
        assertTrue(board.views<View>().any { it.contentDescription?.contains(described) == true })
    }

    @Test
    fun `choosing a period loads its board`() {
        val day =
            AnkiquestBoards.parseLeaderboard(
                JSONArray().put(AnkiquestBoardsTest.row("cy", week = 7, hour = 0)),
                AnkiquestBoards.Period.DAY,
            )
        coEvery { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.DAY) } returns day
        val board = launch()
        board.click(board.getString(R.string.ankiquest_nav_today))
        coVerify { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.DAY) }
        assertTrue(board.hasText("Cy"))
        assertFalse(board.hasText("Ana"))
        assertTrue(board.hasText(board.getString(R.string.aq_board_xp_in, board.getString(R.string.ankiquest_period_day))))
        assertFalse(board.hasTextContaining(board.getString(R.string.aq_board_resets, "").trim()), "only the week resets")
    }

    @Test
    fun `a notification opens the period it names`() {
        launch(AnkiquestBoards.Period.MONTH.query)
        coVerify { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.MONTH) }
    }

    @Test
    fun `tapping a player opens their profile`() {
        val board = launch()
        board.views<View>().first { it.contentDescription?.contains("Ana") == true && it.isClickable }.performClick()
        val opened = assertNotNull(shadowOf(board).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, opened.component?.className)
        assertEquals("ana", opened.getStringExtra(AnkiquestProfileActivity.EXTRA_USER))
    }

    @Test
    fun `an empty board and a board without XP say so`() {
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } returns emptyList()
        val empty = launch()
        assertTrue(empty.hasText(empty.getString(R.string.aq_board_empty)))

        coEvery { AnkiquestBoards.leaderboard(identity, any()) } returns week.map { it.copy(xp = 0) }
        val quiet = launch()
        assertTrue(quiet.hasText(quiet.getString(R.string.aq_board_no_xp)))
        assertFalse(quiet.hasText("Ana"))
    }

    @Test
    fun `failures say why, and a refused token leads to settings`() {
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_board_offline)))

        coEvery { AnkiquestBoards.leaderboard(identity, any()) } throws AnkiquestHttpException(401)
        val refused = launch()
        assertTrue(refused.hasText(refused.getString(R.string.aq_home_auth)))
        refused.click(refused.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(refused).nextStartedActivity.component?.className)
    }

    @Test
    fun `the board shows without the week's end when the server cannot say`() {
        coEvery { AnkiquestBoards.weekEnds(identity) } throws IOException("offline")
        val board = launch()
        assertTrue(board.hasText("Ana"))
        assertFalse(board.hasText(board.getString(R.string.aq_board_offline)))
    }

    @Test
    fun `a server without the board's JSON opens its web page instead`() {
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } throws AnkiquestHttpException(404)
        val board = launch(AnkiquestBoards.Period.YEAR.query)
        val opened = assertNotNull(shadowOf(board).nextStartedActivity)
        assertEquals(AnkiquestActivity::class.java.name, opened.component?.className)
        assertEquals("/year", opened.getStringExtra(AnkiquestActivity.EXTRA_PATH))
        assertTrue(board.isFinishing)
    }

    @Test
    fun `without an account the board asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val board = launch()
        assertTrue(board.hasText(board.getString(R.string.aq_board_connect)))
        coVerify(exactly = 0) { AnkiquestBoards.leaderboard(any(), any()) }
    }

    @Test
    fun `a notification for another account shows this account's board and says so`() {
        val board = launch(account = "https://other.example.test/bo")
        assertTrue(board.hasText(board.getString(R.string.aq_board_account_changed)))
        assertTrue(board.hasText("Ana"))
        assertFalse(launch(account = identity.notificationAccount).hasText(board.getString(R.string.aq_board_account_changed)))
    }

    private fun launch(
        period: String = AnkiquestBoards.Period.WEEK.query,
        account: String? = null,
    ): AnkiquestLeaderboardActivity =
        startRegularActivity<AnkiquestLeaderboardActivity>(AnkiquestLeaderboardActivity.intent(targetContext, period, account))
            .also { board ->
                advanceRobolectricLooperUntil { board.isFinishing || !board.findViewById<View>(R.id.aq_board_loading).isVisible }
            }

    private inline fun <reified T : View> AnkiquestLeaderboardActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestLeaderboardActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestLeaderboardActivity.hasTextContaining(value: String): Boolean =
        views<TextView>().any { it.text.toString().contains(value) }

    private fun AnkiquestLeaderboardActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
    }

    private companion object {
        const val NOW = 1_793_183_460_000L
    }
}
