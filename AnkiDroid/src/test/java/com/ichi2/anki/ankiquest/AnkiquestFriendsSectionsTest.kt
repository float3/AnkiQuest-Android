// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.chip.Chip
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestFriendsSectionsTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private var account: HomeAccount? = identity
    private val page = AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedTest.PAGE)).copy(attention = emptyList())
    private val board =
        AnkiquestBoards.parseLeaderboard(
            JSONArray().put(AnkiquestBoardsTest.row("cerro", 40, 5)),
            AnkiquestBoards.Period.WEEK,
        )

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestFeed, AnkiquestBoards, AnkiquestAvatars, AnkiquestShare)
        every { AnkiquestHomeData.account(any()) } answers { account }
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("friends unavailable")
        coEvery { AnkiquestFeed.page(any(), any(), any(), any()) } returns page
        coEvery { AnkiquestBoards.leaderboard(any(), any()) } returns board
        coEvery { AnkiquestBoards.weekEnds(any()) } returns null
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        every { AnkiquestShare.picture(any()) } returns null
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestFeed, AnkiquestBoards, AnkiquestAvatars, AnkiquestShare)
    }

    @Test
    fun `feed and leaderboard links target Friends and preserve their notification context`() {
        val feed = AnkiquestFeedActivity.intent(targetContext, "old-account")
        assertEquals(AnkiquestFriendsActivity::class.java.name, feed.component?.className)
        assertEquals("old-account", feed.getStringExtra(AnkiquestFeedActivity.EXTRA_ACCOUNT))
        val board = AnkiquestLeaderboardActivity.intent(targetContext, "month", "old-account")
        assertEquals(AnkiquestFriendsActivity::class.java.name, board.component?.className)
        assertEquals("month", board.getStringExtra(AnkiquestLeaderboardActivity.EXTRA_PERIOD))
        assertEquals("old-account", board.getStringExtra(AnkiquestLeaderboardActivity.EXTRA_ACCOUNT))
    }

    @Test
    fun `collapsed sections are lazy and still open when the friends request fails`() {
        val screen = launch().get()
        assertFalse(assertNotNull(screen.findViewById<View>(R.id.aq_friends_feed_content)).isVisible)
        assertFalse(assertNotNull(screen.findViewById<View>(R.id.aq_friends_board_content)).isVisible)
        coVerify(exactly = 0) { AnkiquestFeed.page(any(), any(), any(), any()) }
        coVerify(exactly = 0) { AnkiquestBoards.leaderboard(any(), any()) }
        screen.click(R.string.aq_home_activity)
        screen.click(R.string.ankiquest_dashboard_title)
        assertTrue(screen.findViewById<View>(R.id.aq_friends_feed_content).isVisible)
        assertTrue(screen.findViewById<View>(R.id.aq_friends_board_content).isVisible)
        assertNull(shadowOf(screen).nextStartedActivity)
        coVerify { AnkiquestFeed.page(identity, any(), any(), any()) }
        coVerify { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK) }
    }

    @Test
    fun `the selected leaderboard period survives refresh collapse and rotation`() {
        val controller = launch(AnkiquestLeaderboardActivity.intent(targetContext, "month"))
        val screen = controller.get()
        awaitSections(screen)
        screen.click(R.string.ankiquest_dashboard_title)
        assertFalse(screen.findViewById<View>(R.id.aq_friends_board_content).isVisible)
        screen.click(R.string.ankiquest_dashboard_title)
        screen.findViewById<View>(R.id.aq_feed_refresh).performClick()
        awaitSections(screen)
        controller.recreate()
        awaitSections(controller.get())
        assertTrue(controller.get().findViewById<View>(R.id.aq_friends_board_content).isVisible)
        coVerify(atLeast = 3) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.MONTH) }
        coVerify(exactly = 0) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK) }
    }

    @Test
    fun `switching accounts clears an expanded board and ignores its late old response`() {
        val pending = CompletableDeferred<List<AnkiquestBoards.Standing>>()
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } coAnswers { withContext(NonCancellable) { pending.await() } }
        val controller = launch(AnkiquestLeaderboardActivity.intent(targetContext, "month"))
        try {
            val screen = controller.get()
            assertTrue(assertNotNull(screen.findViewById<View>(R.id.aq_friends_board_loading)).isVisible)
            val next = identity.copy(user = "other", token = "other-token")
            account = next
            coEvery { AnkiquestBoards.leaderboard(next, any()) } returns emptyList()
            controller
                .pause()
                .stop()
                .start()
                .resume()
            awaitSections(screen)
            pending.complete(board)
            advanceRobolectricLooper()
            val labels =
                screen
                    .findViewById<View>(
                        R.id.aq_friends_board_content,
                    ).allViews
                    .filterIsInstance<TextView>()
                    .map { it.text.toString() }
            assertFalse(labels.any { it.contains("Cerro") })
            coVerify { AnkiquestBoards.leaderboard(next, AnkiquestBoards.Period.WEEK) }
        } finally {
            pending.complete(board)
        }
    }

    @Test
    fun `feed filters survive a parent refresh collapse and rotation`() {
        val controller = launch(AnkiquestFeedActivity.intent(targetContext))
        val screen = controller.get()
        awaitSections(screen)
        screen.click(R.string.aq_feed_messages)
        screen.click(R.string.aq_feed_unread_only)
        screen.click(R.string.aq_home_activity)
        screen.findViewById<View>(R.id.aq_feed_refresh).performClick()
        awaitSections(screen)
        screen.click(R.string.aq_home_activity)
        controller.recreate()
        val restored = controller.get()
        awaitSections(restored)
        val selected =
            restored
                .findViewById<View>(R.id.aq_friends_feed_content)
                .allViews
                .filterIsInstance<Chip>()
                .filter { it.isChecked }
                .map { it.text.toString() }
                .toList()
        assertTrue(restored.getString(R.string.aq_feed_messages) in selected)
        assertTrue(restored.getString(R.string.aq_feed_unread_only) in selected)
        coVerify(atLeast = 3) { AnkiquestFeed.page(identity, AnkiquestFeed.Category.MESSAGES, true, null) }
    }

    @Test
    fun `sent messages remain selected after recreation`() {
        coEvery { AnkiquestFeed.sent(any(), any()) } returns AnkiquestFeed.SentPage(emptyList(), null)
        val controller = launch(AnkiquestFeedActivity.intent(targetContext))
        awaitSections(controller.get())
        controller.get().click(R.string.aq_feed_sent)
        controller.recreate()
        awaitSections(controller.get())
        coVerify(exactly = 2) { AnkiquestFeed.sent(identity, null) }
        coVerify(exactly = 1) { AnkiquestFeed.page(identity, any(), any(), any()) }
    }

    @Test
    fun `recreation after an account switch does not restore the old launch period`() {
        val controller = launch(AnkiquestLeaderboardActivity.intent(targetContext, "month"))
        awaitSections(controller.get())
        val next = identity.copy(user = "other", token = "other-token")
        account = next
        controller.recreate()
        awaitSections(controller.get())
        coVerify { AnkiquestBoards.leaderboard(next, AnkiquestBoards.Period.WEEK) }
        coVerify(exactly = 0) { AnkiquestBoards.leaderboard(next, AnkiquestBoards.Period.MONTH) }
    }

    @Test
    fun `a late old feed cannot replace the new account and its default filters`() {
        val controller = launch(AnkiquestFeedActivity.intent(targetContext))
        val screen = controller.get()
        awaitSections(screen)
        screen.click(R.string.aq_feed_messages)
        screen.click(R.string.aq_feed_unread_only)
        val pending = CompletableDeferred<AnkiquestFeed.Page>()
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } coAnswers { withContext(NonCancellable) { pending.await() } }
        screen.findViewById<View>(R.id.aq_feed_refresh).performClick()
        advanceRobolectricLooper()
        try {
            val next = identity.copy(user = "other", token = "other-token")
            account = next
            coEvery { AnkiquestFeed.page(next, any(), any(), any()) } returns page.copy(items = emptyList(), attention = emptyList())
            controller
                .pause()
                .stop()
                .start()
                .resume()
            awaitSections(screen)
            pending.complete(page)
            advanceRobolectricLooper()
            val labels =
                screen
                    .findViewById<View>(R.id.aq_friends_feed_content)
                    .allViews
                    .filterIsInstance<TextView>()
                    .map { it.text.toString() }
                    .toList()
            assertFalse(labels.any { it.contains("Cerro shared a win") })
            coVerify { AnkiquestFeed.page(next, AnkiquestFeed.Category.ALL, false, null) }
        } finally {
            pending.complete(page)
        }
    }

    @Test
    fun `legacy explicit screens redirect without losing the addressed account or period`() {
        val feedIntent =
            Intent(
                targetContext,
                AnkiquestFeedActivity::class.java,
            ).putExtra(AnkiquestFeedActivity.EXTRA_ACCOUNT, "old-account")
                .putExtra("unrelated_payload", ByteArray(300_000))
        val feed = startRegularActivity<AnkiquestFeedActivity>(feedIntent)
        assertTrue(feed.isFinishing)
        assertEquals(AnkiquestFeedActivity.intent(targetContext, "old-account").toUri(0), shadowOf(feed).nextStartedActivity.toUri(0))
        val boardIntent =
            Intent(targetContext, AnkiquestLeaderboardActivity::class.java)
                .putExtra(AnkiquestLeaderboardActivity.EXTRA_PERIOD, "month")
                .putExtra(AnkiquestLeaderboardActivity.EXTRA_ACCOUNT, "old-account")
                .putExtra("unrelated_payload", ByteArray(300_000))
        val board = startRegularActivity<AnkiquestLeaderboardActivity>(boardIntent)
        assertTrue(board.isFinishing)
        assertEquals(
            AnkiquestLeaderboardActivity.intent(targetContext, "month", "old-account").toUri(0),
            shadowOf(board).nextStartedActivity.toUri(0),
        )
    }

    private fun launch(intent: Intent = AnkiquestFriendsActivity.intent(targetContext)) =
        startActivityControllerNormallyOpenCollectionWithIntent(AnkiquestFriendsActivity::class.java, intent).also {
            advanceRobolectricLooperUntil { !it.get().findViewById<View>(R.id.aq_feed_loading).isVisible }
        }

    private fun awaitSections(screen: AnkiquestFriendsActivity) {
        advanceRobolectricLooperUntil {
            listOf(R.id.aq_feed_loading, R.id.aq_friends_feed_loading, R.id.aq_friends_board_loading).none {
                assertNotNull(screen.findViewById<View>(it)).isVisible
            }
        }
    }

    private fun AnkiquestFriendsActivity.click(label: Int) {
        window.decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text == getString(label) && it.isClickable }
            .performClick()
        awaitSections(this)
    }
}
