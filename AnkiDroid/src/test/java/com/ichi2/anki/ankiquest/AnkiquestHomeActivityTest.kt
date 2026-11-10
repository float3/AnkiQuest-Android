// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestHomeActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "member", "secret")
    private val profile =
        JSONObject()
            .put("level", 2)
            .put("streak", 3)
            .put(
                "quests",
                JSONArray().put(JSONObject().put("title", "A little practice").put("progress", 6).put("target", 10)),
            )

    @Before
    fun prepareHome() {
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        coEvery { AnkiquestHomeData.profile(identity) } returns profile
        // Otherwise the activity really asks anki.example.test, and the render that follows waits on that socket.
        coEvery { AnkiquestHomeData.companion(identity) } throws IOException("offline")
    }

    @After
    fun clearHomeMocks() {
        unmockkObject(AnkiquestHomeData)
    }

    @Test
    fun `an empty unstudied deck is clearly labelled nothing scheduled today`() {
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 0, 0, 0)), 12)
        val home = launch()
        assertTrue(home.hasText("Nothing scheduled today"))
    }

    @Test
    fun `learning later shows its next time without inviting an empty study session`() {
        val next =
            com.ichi2.anki.common.time.TimeManager.time
                .intTimeMS() + 3_600_000L
        val daily = DailyDeckState(0, 1, 1, next)
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 0, 0, 0, daily)), 12)
        val home = launch()
        assertTrue(home.hasTextContaining("Learning later today"))
        assertFalse(home.hasText(home.getString(R.string.aq_home_study_due, 0)))
    }

    @Test
    fun `completed deck has a done state without an empty study action`() {
        val daily = DailyDeckState(0, 0, 1)
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 0, 0, 0, daily)), 12)
        val home = launch()
        assertTrue(home.hasText("Done today"))
        assertFalse(home.hasText(home.getString(R.string.aq_home_study_due, 0)))
    }

    @Test
    fun `Today studies the local focus deck and shows server quests`() {
        val home = launch()
        assertTrue(home.hasText("Spanish"))
        assertTrue(home.hasText(home.getString(R.string.aq_home_streak_level, 3, 2)))
        assertTrue(home.hasText("A little practice"))
        home.click(home.getString(R.string.aq_home_study_due, 24))
        val study = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(DeckPicker::class.java.name, study.component?.className)
        assertEquals(12L, study.getLongExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, 0))
        assertTrue(study.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, study.flags)
    }

    @Test
    fun `Today shows the closing week while the player's day still counts for it`() {
        val endsAt =
            com.ichi2.anki.common.time.TimeManager.time
                .intTimeMS() + 60 * 60 * 1000L
        val closing = JSONObject().put("xp", 1234).put("ends_at", endsAt)
        coEvery { AnkiquestHomeData.profile(identity) } returns JSONObject(profile.toString()).put("closing_week", closing)
        val home = launch()
        val line =
            assertNotNull(AnkiquestBoards.closingLine(home, AnkiquestBoards.ClosingWeek(1234, endsAt), endsAt - 1))
        assertTrue(home.hasText(line))
    }

    @Test
    fun `offline Today keeps local study`() {
        coEvery { AnkiquestHomeData.profile(identity) } throws IOException("offline")
        val home = launch()
        assertTrue(home.hasText("Spanish"))
        assertTrue(home.hasText(home.getString(R.string.aq_home_offline)))
        assertFalse(home.hasText("A little practice"))
    }

    @Test
    fun `a failed local refresh replaces the stale deck with a failure`() {
        val controller = launchController()
        val home = controller.get()
        assertTrue(home.hasText("Spanish"))
        coEvery { AnkiquestHomeData.local() } throws IOException("collection unavailable")

        controller.pause().resume()
        advanceRobolectricLooper()

        assertFalse(home.hasText("Spanish"))
        assertTrue(home.hasText(home.getString(R.string.aq_home_local_unavailable)))
    }

    @Test
    fun `a deck choice survives a refresh while the picker is open`() {
        val spanish = col.decks.id("Spanish")
        val geography = col.decks.id("Geography")
        val decks = listOf(HomeDeck(spanish, "Spanish", 1, 0, 0), HomeDeck(geography, "Geography", 2, 0, 0))
        col.decks.select(spanish)
        coEvery { AnkiquestHomeData.local() } coAnswers { HomeLocal(decks, col.decks.selected()) }
        val controller = launchController()
        val home = controller.get()
        home.click(home.getString(R.string.aq_home_change_deck))
        val picker = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        val choices = assertNotNull(picker.listView)
        controller.pause().resume()
        advanceRobolectricLooper()

        assertTrue(choices.performItemClick(choices.getChildAt(1), 1, choices.adapter.getItemId(1)))
        advanceRobolectricLooperUntil { home.hasText("Geography") }
        assertEquals(geography, col.decks.selected())
    }

    @Test
    fun `a pending local result for an old account is ignored`() {
        val other = HomeAccount("https://anki.example.test", "other", "another-token")
        val waiting = CompletableDeferred<HomeLocal>()
        var switched = false
        coEvery { AnkiquestHomeData.local() } coAnswers {
            if (switched) HomeLocal(listOf(HomeDeck(19, "Geography", 1, 0, 0)), 19) else waiting.await()
        }
        coEvery { AnkiquestHomeData.profile(other) } returns profile
        coEvery { AnkiquestHomeData.companion(other) } throws IOException("offline")
        val controller = launchController()
        val home = controller.get()
        switched = true
        every { AnkiquestHomeData.account(any()) } returns other
        controller.pause().resume()
        advanceRobolectricLooperUntil { home.hasText("Geography") }

        waiting.complete(HomeLocal(listOf(HomeDeck(12, "Spanish", 1, 0, 0)), 12))
        advanceRobolectricLooper()
        assertTrue(home.hasText("Geography"))
        assertFalse(home.hasText("Spanish"))
    }

    @Test
    fun `a new account loads copies without the previous failure`() {
        val other = HomeAccount("https://anki.example.test", "other", "another-token")
        val waiting = CompletableDeferred<JSONObject>()
        mockkObject(AnkiquestDeckCopies)
        try {
            coEvery { AnkiquestDeckCopies.inbox(identity) } throws IOException("offline")
            coEvery { AnkiquestDeckCopies.inbox(other) } coAnswers { waiting.await() }
            coEvery { AnkiquestHomeData.profile(other) } returns profile
            coEvery { AnkiquestHomeData.companion(other) } throws IOException("offline")
            val controller = launchController()
            val home = controller.get()
            val failure = home.getString(R.string.aq_copies_unavailable)
            advanceRobolectricLooperUntil { home.hasText(failure) }

            every { AnkiquestHomeData.account(any()) } returns other
            controller.pause().resume()
            advanceRobolectricLooper()

            assertFalse(home.hasText(failure))
            assertTrue(home.hasText(home.getString(R.string.aq_home_loading_local)))
        } finally {
            waiting.cancel()
            unmockkObject(AnkiquestDeckCopies)
        }
    }

    @Test
    fun `rejected credentials point to settings`() {
        coEvery { AnkiquestHomeData.profile(identity) } throws AnkiquestHttpException(401)
        val home = launch()
        assertTrue(home.hasText(home.getString(R.string.aq_home_auth)))
        assertTrue(home.hasText(home.getString(R.string.aq_home_settings)))
    }

    @Test
    fun `navigation keeps Today, Activity, friend goals and Progress native and opens other community pages on the website`() {
        val home = launch()
        val nav = home.findViewById<BottomNavigationView>(R.id.ankiquest_tab_bar)
        assertEquals(4, nav.menu.size)

        nav.selectedItemId = R.id.ankiquest_nav_friends
        val friends = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(AnkiquestFriendsActivity::class.java.name, friends.component?.className)
        assertEquals(R.id.ankiquest_nav_today, nav.selectedItemId)

        nav.selectedItemId = R.id.ankiquest_nav_progress
        val progress = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, progress.component?.className, "progress is the native profile")
        assertEquals(R.id.ankiquest_nav_today, nav.selectedItemId)

        home.findViewById<View>(R.id.aq_home_activity).performClick()
        val feed = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(AnkiquestFeedActivity::class.java.name, feed.component?.className)
        assertNull(feed.getStringExtra(AnkiquestFeedActivity.EXTRA_ACCOUNT))

        nav.selectedItemId = R.id.ankiquest_nav_decks
        val decks = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(DeckPicker::class.java.name, decks.component?.className)
        assertFalse(decks.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK))
        assertTrue(decks.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertEquals(R.id.ankiquest_nav_today, nav.selectedItemId)
    }

    @Test
    fun `the daily goal is changed on Today, from the server's goal`() {
        val goal = AnkiquestDailyGoal.Status(100, 4, 10, 2, AnkiquestDailyGoal.Mode.CARDS, AnkiquestDailyGoal.Goal(20))
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12, goal = goal)
        mockkObject(AnkiquestDailyGoal, AnkiquestGoalDialog)
        try {
            coEvery { AnkiquestDailyGoal.fetch(identity, any()) } throws IOException("offline")
            val home = launch()
            assertTrue(home.hasText(home.getString(R.string.aq_goal_today)))
            home.click(home.getString(R.string.aq_goal_change))
            coVerify { AnkiquestDailyGoal.fetch(identity, any()) }
        } finally {
            unmockkObject(AnkiquestDailyGoal, AnkiquestGoalDialog)
        }
    }

    @Test
    fun `Today links to the study history and the records`() {
        val home = launch()
        for ((label, screen) in listOf(
            R.string.aq_history_title to AnkiquestHistoryActivity::class.java,
            R.string.aq_records_title to AnkiquestRecordsActivity::class.java,
        )) {
            home.click(home.getString(label))
            assertEquals(screen.name, assertNotNull(shadowOf(home).nextStartedActivity).component?.className)
        }
    }

    private fun launch(): AnkiquestHomeActivity = launchController().get()

    private fun launchController() =
        startActivityControllerNormallyOpenCollectionWithIntent(
            AnkiquestHomeActivity::class.java,
            AnkiquestHomeActivity.intent(targetContext),
        ).also { controller ->
            advanceRobolectricLooperUntil { !controller.get().findViewById<View>(R.id.aq_home_loading).isVisible }
        }

    private fun AnkiquestHomeActivity.hasText(value: String): Boolean =
        findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().any { it.text.toString() == value }

    private fun AnkiquestHomeActivity.hasTextContaining(value: String): Boolean =
        findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().any { it.text.toString().contains(value) }

    private fun AnkiquestHomeActivity.click(label: String) {
        val control =
            findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().first {
                it.text.toString() == label && it.isClickable
            }
        assertTrue(control.isEnabled)
        control.performClick()
        advanceRobolectricLooper()
    }
}
