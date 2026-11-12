// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestLeaderboardScopesTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private var account: HomeAccount? = identity
    private var answer = JSONArray()

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
        every { AnkiquestHomeData.account(any()) } answers { account }
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("overview unavailable")
        coEvery { AnkiquestBoards.leaderboard(any(), any(), any()) } answers {
            AnkiquestBoards.parseLeaderboard(answer, AnkiquestBoards.Period.WEEK)
        }
        coEvery { AnkiquestBoards.weekEnds(any()) } returns null
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        coEvery { AnkiquestBoards.hasScopes(any()) } returns true
        coEvery { AnkiquestGlobalHides.load(any()) } returns null
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
    }

    @Test
    fun `a stranger summary never invents today's reviews or asks to refresh private details`() {
        answer.put(stranger("ana", rank = 3, xp = 90))
        val screen = launch().get()
        click(screen, R.string.ankiquest_widget_who_global)
        val words = labels(screen)
        assertTrue("Ana" in words)
        assertFalse(screen.getString(R.string.aq_board_today_reviews, "0") in words)
        assertFalse(words.any { it.contains("Refresh to see") })
    }

    @Test
    fun `global gaps and an own place below the top hundred keep the server's ranks`() {
        answer.put(stranger("ana", rank = 3, xp = 90))
        answer.put(
            AnkiquestBoardsTest
                .row("hill", 2, 0)
                .put("rank", 103)
                .put("me", true)
                .put("shown", false),
        )
        val screen = launch().get()
        click(screen, R.string.ankiquest_widget_who_global)
        val words = labels(screen)
        assertTrue("103" in words, "the own row must not be renumbered as second")
        assertTrue("You are in place 103." in words)
        assertTrue("Your row is not shown to others." in words)
        assertFalse(screen.getString(R.string.aq_board_your_place, 2, 2) in words)
    }

    @Test
    fun `equal period XP keeps the server rank when strangers have no lifetime XP`() {
        val rows =
            JSONArray()
                .put(stranger("ana", rank = 1, xp = 20))
                .put(AnkiquestBoardsTest.row("hill", 20, 0).put("xp_total", 999).put("rank", 2))
        val parsed = AnkiquestBoards.parseLeaderboard(rows, AnkiquestBoards.Period.WEEK)
        assertEquals(listOf("ana", "hill"), parsed.map { it.user })
    }

    @Test
    fun `Global keeps the chosen period across refresh collapse and recreation`() {
        val controller = launch()
        val screen = controller.get()
        click(screen, R.string.ankiquest_widget_who_global)
        click(screen, R.string.aq_board_month)
        screen.findViewById<View>(R.id.aq_feed_refresh).performClick()
        awaitBoard(screen)
        val heading =
            screen.window.decorView.allViews.filterIsInstance<TextView>().first {
                it.text ==
                    screen.getString(R.string.ankiquest_dashboard_title)
            }
        heading.performClick()
        heading.performClick()
        awaitBoard(screen)
        controller.recreate()
        awaitBoard(controller.get())
        coVerify(atLeast = 4) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.MONTH, AnkiquestBoards.Source.Global) }
        assertTrue(controller.get().findViewById<View>(R.id.aq_friends_board_content).isVisible)
    }

    @Test
    fun `Group uses the existing overview and preserves the selected group on recreation`() {
        val overview = overview()
        coEvery { AnkiquestSocial.overview(identity) } returns overview
        val controller = launch()
        val screen = controller.get()
        click(screen, R.string.aq_board_scope_group)
        click(screen, "Evening study")
        click(screen, R.string.aq_board_year)
        coVerify { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK, AnkiquestBoards.Source.Group(7)) }
        coVerify { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.YEAR, AnkiquestBoards.Source.Group(9)) }
        coVerify(exactly = 1) { AnkiquestSocial.overview(identity) }
        controller.recreate()
        awaitBoard(controller.get())
        coVerify(atLeast = 2) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.YEAR, AnkiquestBoards.Source.Group(9)) }
    }

    @Test
    fun `an unavailable group does not silently show the Friends board`() {
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        coEvery { AnkiquestBoards.leaderboard(identity, any(), AnkiquestBoards.Source.Group(7)) } throws
            AnkiquestHttpException(HTTP_FORBIDDEN)
        answer.put(stranger("ana", 4, 90))
        val screen = launch().get()
        assertTrue("Ana" in labels(screen))
        click(screen, R.string.aq_board_scope_group)
        assertFalse("Ana" in labels(screen))
        assertTrue(screen.getString(R.string.aq_board_group_unavailable) in labels(screen))
        assertFalse(screen.getString(R.string.aq_home_auth) in labels(screen))
    }

    @Test
    fun `a restored collapsed group does not fetch its board until expanded`() {
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        val controller = launch()
        val screen = controller.get()
        click(screen, R.string.aq_board_scope_group)
        screen.window.decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text == screen.getString(R.string.ankiquest_dashboard_title) }
            .performClick()
        controller.recreate()
        awaitBoard(controller.get())
        assertFalse(controller.get().findViewById<View>(R.id.aq_friends_board_content).isVisible)
        coVerify(exactly = 2) { AnkiquestBoards.leaderboard(identity, any(), any()) }
    }

    @Test
    fun `leaving the selected group clears its board and returns to Friends`() {
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        val screen = launch().get()
        click(screen, R.string.aq_board_scope_group)
        coEvery { AnkiquestSocial.overview(identity) } returns overview().copy(groups = emptyList())
        screen.findViewById<View>(R.id.aq_feed_refresh).performClick()
        awaitBoard(screen)
        assertTrue(screen.getString(R.string.aq_board_group_missing) in labels(screen))
        coVerify(atLeast = 2) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK, AnkiquestBoards.Source.Friends) }
    }

    @Test
    fun `an old server hides scope choices and keeps the original request`() {
        coEvery { AnkiquestBoards.hasScopes(identity) } returns false
        val screen = launch().get()
        assertFalse(screen.getString(R.string.ankiquest_widget_who_global) in labels(screen))
        assertFalse(screen.getString(R.string.aq_board_scope_group) in labels(screen))
        coVerify(exactly = 1) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK) }
    }

    @Test
    fun `an optional scope failure leaves Friends usable and can recover on refresh`() {
        coEvery { AnkiquestBoards.hasScopes(identity) } throws IOException("offline")
        answer.put(AnkiquestBoardsTest.row("ana", 90, 5))
        val screen = launch().get()
        assertTrue("Ana" in labels(screen))
        assertFalse(screen.getString(R.string.ankiquest_widget_who_global) in labels(screen))
        coEvery { AnkiquestBoards.hasScopes(identity) } returns true
        screen.findViewById<View>(R.id.aq_feed_refresh).performClick()
        awaitBoard(screen)
        assertTrue(screen.getString(R.string.ankiquest_widget_who_global) in labels(screen))
    }

    @Test
    fun `a late Global answer cannot replace a newer period`() {
        val waiting = CompletableDeferred<List<AnkiquestBoards.Standing>>()
        coEvery { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.WEEK, AnkiquestBoards.Source.Global) } coAnswers {
            withContext(NonCancellable) { waiting.await() }
        }
        val controller = launch()
        try {
            val screen = controller.get()
            control(screen, screen.getString(R.string.ankiquest_widget_who_global)).performClick()
            advanceRobolectricLooper()
            answer = JSONArray().put(stranger("current", 4, 70))
            click(screen, R.string.aq_board_month)
            waiting.complete(AnkiquestBoards.parseLeaderboard(JSONArray().put(stranger("old", 3, 80)), AnkiquestBoards.Period.WEEK))
            advanceRobolectricLooper()
            assertTrue("Current" in labels(screen))
            assertFalse("Old" in labels(screen))
        } finally {
            waiting.complete(emptyList())
        }
    }

    @Test
    fun `a late group refusal cannot erase a newer Global board`() {
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        val waiting = CompletableDeferred<Unit>()
        coEvery { AnkiquestBoards.leaderboard(identity, any(), AnkiquestBoards.Source.Group(7)) } coAnswers {
            withContext(NonCancellable) {
                waiting.await()
                throw AnkiquestHttpException(HTTP_FORBIDDEN)
            }
        }
        val screen = launch().get()
        try {
            control(screen, screen.getString(R.string.aq_board_scope_group)).performClick()
            advanceRobolectricLooper()
            answer = JSONArray().put(stranger("current", 4, 70))
            click(screen, R.string.ankiquest_widget_who_global)
            waiting.complete(Unit)
            advanceRobolectricLooper()
            // Tapping the selected scope redraws its cached rows without another request.
            click(screen, R.string.ankiquest_widget_who_global)
            assertTrue("Current" in labels(screen))
            assertFalse(screen.getString(R.string.aq_board_group_unavailable) in labels(screen))
        } finally {
            waiting.complete(Unit)
        }
    }

    @Test
    fun `a late old account capability result never enables its Global choice`() {
        val waiting = CompletableDeferred<Boolean>()
        coEvery { AnkiquestBoards.hasScopes(identity) } coAnswers { withContext(NonCancellable) { waiting.await() } }
        val controller =
            startActivityControllerNormallyOpenCollectionWithIntent(
                AnkiquestFriendsActivity::class.java,
                AnkiquestLeaderboardActivity.intent(targetContext, "month"),
            )
        try {
            val next = identity.copy(user = "other", token = "other-token")
            account = next
            coEvery { AnkiquestBoards.hasScopes(next) } returns false
            controller
                .pause()
                .stop()
                .start()
                .resume()
            awaitBoard(controller.get())
            waiting.complete(true)
            advanceRobolectricLooper()
            assertFalse(controller.get().getString(R.string.ankiquest_widget_who_global) in labels(controller.get()))
            coVerify(exactly = 0) { AnkiquestBoards.leaderboard(identity, any(), any()) }
            coVerify { AnkiquestBoards.leaderboard(next, AnkiquestBoards.Period.WEEK) }
        } finally {
            waiting.complete(true)
        }
    }

    private fun overview() =
        AnkiquestSocial.parse(
            JSONObject(
                """{"groups":[{"id":7,"name":"Study group","members":[]},{"id":9,"name":"Evening study","members":[]}]}""",
            ),
        )

    private fun launch() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            AnkiquestFriendsActivity::class.java,
            AnkiquestLeaderboardActivity.intent(targetContext),
        ).also { controller ->
            awaitBoard(controller.get())
        }

    private fun awaitBoard(screen: AnkiquestFriendsActivity) {
        advanceRobolectricLooperUntil {
            !screen.findViewById<View>(R.id.aq_friends_board_loading).isVisible &&
                !screen.findViewById<View>(R.id.aq_feed_loading).isVisible
        }
        advanceRobolectricLooper()
    }

    private fun control(
        screen: AnkiquestFriendsActivity,
        label: String,
    ): TextView =
        screen.findViewById<View>(R.id.aq_friends_board_content).allViews.filterIsInstance<TextView>().first {
            it.isClickable &&
                it.text.toString() == label
        }

    private fun click(
        screen: AnkiquestFriendsActivity,
        label: Int,
    ) = click(screen, screen.getString(label))

    private fun click(
        screen: AnkiquestFriendsActivity,
        label: String,
    ) {
        control(screen, label).performClick()
        awaitBoard(screen)
    }

    private fun labels(screen: AnkiquestFriendsActivity): List<String> =
        screen
            .findViewById<View>(R.id.aq_friends_board_content)
            .allViews
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
            .toList()

    private fun stranger(
        user: String,
        rank: Int,
        xp: Long,
    ): JSONObject =
        JSONObject()
            .put("rank", rank)
            .put("user", user)
            .put("display", user.replaceFirstChar { it.uppercase() })
            .put("level", 2)
            .put("xp", xp)
            .put("period", "week")
            .put("streak", 7)
}
