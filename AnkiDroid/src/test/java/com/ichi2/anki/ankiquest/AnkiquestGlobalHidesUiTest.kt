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
import java.net.HttpURLConnection.HTTP_CONFLICT
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h740dp")
class AnkiquestGlobalHidesUiTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "test-token")
    private var account: HomeAccount? = identity
    private var hidden = emptyList<AnkiquestGlobalHides.Player>()

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
        every { AnkiquestHomeData.account(any()) } answers { account }
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("overview unavailable")
        coEvery { AnkiquestBoards.hasScopes(any()) } returns true
        coEvery { AnkiquestBoards.weekEnds(any()) } returns null
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        coEvery { AnkiquestGlobalHides.load(any()) } answers { hidden }
        coEvery { AnkiquestGlobalHides.change(identity, "ana", any()) } answers {
            hidden =
                if (thirdArg<AnkiquestGlobalHides.Action>() == AnkiquestGlobalHides.Action.HIDE) {
                    listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
                } else {
                    emptyList()
                }
            hidden
        }
        coEvery { AnkiquestBoards.leaderboard(any(), any(), any()) } answers {
            val rows = JSONArray()
            if (hidden.isEmpty() || thirdArg<AnkiquestBoards.Source>() != AnkiquestBoards.Source.Global) {
                rows.put(JSONObject("""{"user":"ana","display":"Ana","rank":3,"xp":90,"level":2,"streak":7}"""))
            }
            rows.put(AnkiquestBoardsTest.row("cerro", 40, 4).put("rank", 20))
            rows.put(AnkiquestBoardsTest.row("hill", 2, 0).put("rank", 103))
            AnkiquestBoards.parseLeaderboard(rows, AnkiquestBoards.Period.WEEK)
        }
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
    }

    @Test
    fun `Global offers private hiding for a stranger and keeps the original ranks`() {
        val screen = launch()
        assertFalse(labels(screen).contains("Hide for me"))
        click(screen, "Global")
        assertTrue(labels(screen).contains("Hide for me"), "a supported Global board needs the private hide action")
        assertEquals(1, labels(screen).count { it == "Hide for me" }, "own and circle rows cannot be hidden here")
        click(screen, "Hide for me")
        coVerify(exactly = 1) { AnkiquestGlobalHides.change(identity, "ana", AnkiquestGlobalHides.Action.HIDE) }
        assertFalse(labels(screen).contains("Ana"))
        assertTrue(labels(screen).contains("103"), "hiding cannot renumber the viewer's place")
    }

    @Test
    fun `Hidden players can be shown again without changing the selected period`() {
        hidden = listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
        val screen = launch()
        click(screen, "Global")
        click(screen, screen.getString(R.string.aq_board_month))
        assertTrue(labels(screen).contains("Hidden players"), "private hides need a reversible control")
        click(screen, "Hidden players")
        click(screen, "Show again")
        coVerify(exactly = 1) { AnkiquestGlobalHides.change(identity, "ana", AnkiquestGlobalHides.Action.SHOW) }
        coVerify(atLeast = 2) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.MONTH, AnkiquestBoards.Source.Global) }
        assertTrue(labels(screen).contains("Ana"))
    }

    @Test
    fun `an older server keeps Global usable without private hide controls`() {
        coEvery { AnkiquestGlobalHides.load(identity) } returns null
        val screen = launch()
        click(screen, "Global")
        assertTrue("Ana" in labels(screen))
        assertFalse("Hide for me" in labels(screen))
        assertFalse("Hidden players" in labels(screen))
        coVerify(exactly = 0) { AnkiquestGlobalHides.change(any(), any(), any()) }
    }

    @Test
    fun `loading hidden players can fail and retry without losing the board`() {
        coEvery { AnkiquestGlobalHides.load(identity) } throws IOException("offline")
        val screen = launch()
        click(screen, "Global")
        assertTrue("Ana" in labels(screen))
        assertTrue(screen.getString(R.string.aq_board_hide_failed) in labels(screen))
        coEvery { AnkiquestGlobalHides.load(identity) } returns emptyList()
        click(screen, "Retry")
        assertTrue("Hide for me" in labels(screen))
        coVerify(exactly = 2) { AnkiquestGlobalHides.load(identity) }
    }

    @Test
    fun `a save failure keeps the row and permits retry`() {
        coEvery { AnkiquestGlobalHides.change(identity, "ana", any()) } throws AnkiquestHttpException(HTTP_CONFLICT)
        val screen = launch()
        click(screen, "Global")
        click(screen, "Hide for me")
        assertTrue("Ana" in labels(screen))
        assertTrue(screen.getString(R.string.aq_board_hide_limit) in labels(screen))
        assertFalse(screen.getString(R.string.aq_board_hidden_saved) in labels(screen))
        coEvery { AnkiquestGlobalHides.change(identity, "ana", any()) } answers {
            hidden = listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
            hidden
        }
        click(screen, "Hide for me")
        assertFalse("Ana" in labels(screen))
        coVerify(exactly = 2) { AnkiquestGlobalHides.change(identity, "ana", AnkiquestGlobalHides.Action.HIDE) }
    }

    @Test
    fun `failed Show again keeps the private entry without claiming success`() {
        hidden = listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
        coEvery { AnkiquestGlobalHides.change(identity, "ana", any()) } throws IOException("offline")
        val screen = launch()
        click(screen, "Global")
        click(screen, "Hidden players")
        click(screen, "Show again")
        assertTrue("Show again" in labels(screen))
        assertTrue(screen.getString(R.string.aq_board_hide_failed) in labels(screen))
        assertFalse(screen.getString(R.string.aq_board_shown_saved) in labels(screen))
    }

    @Test
    fun `an in flight save sends once and refreshes the period chosen while waiting`() {
        val waiting = CompletableDeferred<List<AnkiquestGlobalHides.Player>>()
        coEvery { AnkiquestGlobalHides.change(identity, "ana", any()) } coAnswers { waiting.await() }
        val screen = launch()
        try {
            click(screen, "Global")
            val stale = control(screen, "Hide for me")
            stale.performClick()
            advanceRobolectricLooper()
            stale.performClick()
            assertFalse(control(screen, "Hide for me").isEnabled)
            click(screen, screen.getString(R.string.aq_board_month))
            coVerify(exactly = 1) { AnkiquestGlobalHides.change(identity, "ana", AnkiquestGlobalHides.Action.HIDE) }
            hidden = listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
            waiting.complete(hidden)
            awaitBoard(screen)
            assertFalse("Ana" in labels(screen))
            coVerify(atLeast = 2) { AnkiquestBoards.leaderboard(identity, AnkiquestBoards.Period.MONTH, AnkiquestBoards.Source.Global) }
        } finally {
            waiting.complete(emptyList())
        }
    }

    @Test
    fun `retained hide controls cannot act in Friends Group or a collapsed leaderboard`() {
        val screen = launch()
        click(screen, "Global")
        val stale = control(screen, "Hide for me")
        click(screen, "Friends")
        stale.performClick()
        click(screen, "Group")
        stale.performClick()
        click(screen, "Global")
        val fresh = control(screen, "Hide for me")
        screen.window.decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text == screen.getString(R.string.ankiquest_dashboard_title) }
            .performClick()
        fresh.performClick()
        coVerify(exactly = 0) { AnkiquestGlobalHides.change(any(), any(), any()) }
    }

    @Test
    fun `retained Show again cannot act after its panel is collapsed`() {
        hidden = listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
        val screen = launch()
        click(screen, "Global")
        click(screen, "Hidden players")
        val stale = control(screen, "Show again")
        click(screen, "Hidden players")
        stale.performClick()
        coVerify(exactly = 0) { AnkiquestGlobalHides.change(any(), any(), any()) }
    }

    @Test
    fun `retained hide controls cannot act for a changed account or stopped activity`() {
        val controller = launchController()
        val screen = controller.get()
        click(screen, "Global")
        val stale = control(screen, "Hide for me")
        account = identity.copy(token = "new-token")
        stale.performClick()
        account = identity
        controller.pause().stop()
        stale.performClick()
        coVerify(exactly = 0) { AnkiquestGlobalHides.change(any(), any(), any()) }
    }

    @Test
    fun `late hidden players from a previous account are discarded`() {
        val waiting = CompletableDeferred<List<AnkiquestGlobalHides.Player>>()
        coEvery { AnkiquestGlobalHides.load(identity) } coAnswers { withContext(NonCancellable) { waiting.await() } }
        val controller = launchController()
        try {
            val screen = controller.get()
            click(screen, "Global")
            account = identity.copy(user = "other", token = "other-token")
            controller
                .pause()
                .stop()
                .start()
                .resume()
            awaitBoard(screen)
            waiting.complete(listOf(AnkiquestGlobalHides.Player("old", "Previous account")))
            advanceRobolectricLooper()
            click(screen, "Global")
            click(screen, "Hidden players")
            assertFalse("Previous account" in labels(screen))
            assertTrue(screen.getString(R.string.aq_board_hidden_empty) in labels(screen))
        } finally {
            waiting.complete(emptyList())
        }
    }

    @Test
    fun `a late save does not update or reload the next account`() {
        val waiting = CompletableDeferred<List<AnkiquestGlobalHides.Player>>()
        coEvery { AnkiquestGlobalHides.change(identity, "ana", any()) } coAnswers {
            withContext(NonCancellable) { waiting.await() }
        }
        val controller = launchController()
        try {
            val screen = controller.get()
            click(screen, "Global")
            click(screen, "Hide for me")
            val next = identity.copy(user = "other", token = "other-token")
            account = next
            controller
                .pause()
                .stop()
                .start()
                .resume()
            awaitBoard(screen)
            waiting.complete(listOf(AnkiquestGlobalHides.Player("ana", "Ana")))
            advanceRobolectricLooper()
            assertFalse(screen.getString(R.string.aq_board_hidden_saved) in labels(screen))
            coVerify(exactly = 1) { AnkiquestBoards.leaderboard(next, any(), any()) }
            coVerify(exactly = 0) { AnkiquestGlobalHides.change(next, any(), any()) }
        } finally {
            waiting.complete(emptyList())
        }
    }

    private fun launch(): AnkiquestFriendsActivity = launchController().get()

    private fun launchController() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            AnkiquestFriendsActivity::class.java,
            AnkiquestLeaderboardActivity.intent(targetContext),
        ).also { awaitBoard(it.get()) }

    private fun awaitBoard(screen: AnkiquestFriendsActivity) {
        advanceRobolectricLooperUntil {
            !screen.findViewById<View>(R.id.aq_friends_board_loading).isVisible &&
                !screen.findViewById<View>(R.id.aq_feed_loading).isVisible
        }
        advanceRobolectricLooper()
    }

    private fun labels(screen: AnkiquestFriendsActivity): List<String> =
        screen
            .findViewById<View>(R.id.aq_friends_board_content)
            .allViews
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
            .toList()

    private fun click(
        screen: AnkiquestFriendsActivity,
        label: String,
    ) {
        control(screen, label).performClick()
        awaitBoard(screen)
    }

    private fun control(
        screen: AnkiquestFriendsActivity,
        label: String,
    ): TextView =
        assertNotNull(
            screen
                .findViewById<View>(R.id.aq_friends_board_content)
                .allViews
                .filterIsInstance<TextView>()
                .firstOrNull { it.isClickable && it.text.toString() == label },
            label,
        )
}
