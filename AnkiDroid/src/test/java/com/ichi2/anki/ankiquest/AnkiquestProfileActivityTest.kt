// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestProfileActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val profile = AnkiquestBoards.parseProfile(JSONObject(AnkiquestBoardsTest.PROFILE))
    private val own =
        AnkiquestBoards.PlayerPage(
            profile,
            own = true,
            wins = listOf(AnkiquestBoards.Win("Daily goal reached!", "5 cards today in 0 minutes.", 1793183400)),
        )

    @Before
    fun prepareProfile() {
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestSocial, AnkiquestSubscriptions)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestBoards.player(identity, "hill") } returns own
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("offline")
        every { AnkiquestSubscriptions.repository } returns repository
        coEvery { repository.load(any()) } throws IOException("offline")
    }

    private val repository = mockk<SubscriptionRepository>()

    @After
    fun clearProfileMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestSocial, AnkiquestSubscriptions)
    }

    private fun overview(vararg muted: String) =
        AnkiquestSocial.Overview(
            friends = emptyList(),
            incoming = emptyList(),
            outgoing = emptyList(),
            muted = muted.map { AnkiquestSocial.Person(it, it.replaceFirstChar(Char::uppercase)) },
            groups = emptyList(),
            public = true,
        )

    @Test
    fun `another player can be muted and their deck alerts stopped from their profile`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        coEvery { repository.load(identity) } returns IncomingSubscriptions(true, emptySet(), listOf(IncomingSender("cerro", "Cerro")))
        coEvery { repository.setSubscribed(identity, "cerro", false) } returns
            IncomingSubscriptions(true, setOf("cerro"), listOf(IncomingSender("cerro", "Cerro")))
        val screen = launch("cerro")
        advanceRobolectricLooper()
        assertTrue(screen.hasText(screen.getString(R.string.aq_friends_mute)))
        val stop = screen.getString(R.string.aq_profile_deck_alerts_stop, "Cerro")
        assertTrue(screen.hasText(stop))

        coEvery { repository.load(identity) } returns IncomingSubscriptions(true, setOf("cerro"), listOf(IncomingSender("cerro", "Cerro")))
        screen.click(stop)
        advanceRobolectricLooper()
        coVerify { repository.setSubscribed(identity, "cerro", false) }
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_deck_alerts_start, "Cerro")))
    }

    @Test
    fun `controls the server cannot tell about stay hidden, and the player's own profile pauses streak protection`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        val theirs = launch("cerro")
        advanceRobolectricLooper()
        assertFalse(theirs.hasText(theirs.getString(R.string.aq_friends_mute)))
        assertFalse(theirs.hasText(theirs.getString(R.string.aq_profile_freezes_pause)))

        val mine = launch()
        assertTrue(mine.hasText(mine.getString(R.string.aq_profile_freezes_pause)))
        assertFalse(mine.hasText(mine.getString(R.string.aq_friends_mute)))
    }

    @Test
    fun `the player's own profile shows level, streak, freezes, quests, wins and achievements`() {
        val screen = launch()
        coVerify { AnkiquestBoards.player(identity, "hill") }
        assertEquals(screen.getString(R.string.ankiquest_me), screen.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_level, 2)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_level_progress, "1", "280", 3, "101")))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_freezes_count, 2)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_freezes_ready)))
        assertTrue(screen.hasText(screen.getString(R.string.ankiquest_streak_studied)))
        assertTrue(screen.hasText("Review 15 cards"))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_wins)))
        assertTrue(screen.hasText("Daily goal reached!"))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_leech)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_best_hour)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_unlocked, 1, 3)))
        assertTrue(screen.hasText("Done"))
        screen.click(screen.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(screen).nextStartedActivity.component?.className)
    }

    @Test
    fun `a closing week still counting shows its XP until it closes`() {
        val endsAt = TimeManager.time.intTimeMS() + 60 * 60 * 1000L
        coEvery { AnkiquestBoards.player(identity, "hill") } returns
            own.copy(profile = profile.copy(closingWeek = AnkiquestBoards.ClosingWeek(1234, endsAt)))
        val screen = launch()
        val line = assertNotNull(AnkiquestBoards.closingLine(screen, AnkiquestBoards.ClosingWeek(1234, endsAt), endsAt - 1))
        assertTrue(screen.hasText(line))
        coEvery { AnkiquestBoards.player(identity, "hill") } returns own
        val without = launch()
        assertFalse(without.hasText(line))
    }

    @Test
    fun `another player's profile has no wins or settings`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        val screen = launch("cerro")
        assertEquals("Cerro", screen.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertFalse(screen.hasText(screen.getString(R.string.aq_home_settings)))
        assertFalse(screen.hasText(screen.getString(R.string.aq_profile_wins)))
        screen.click(screen.getString(R.string.aq_profile_leaderboard))
        assertEquals(AnkiquestLeaderboardActivity::class.java.name, shadowOf(screen).nextStartedActivity.component?.className)
    }

    @Test
    fun `personal bests lead to the records, and on the player's own profile to the study history`() {
        val mine = launch()
        mine.click(mine.getString(R.string.aq_records_title))
        assertEquals(AnkiquestRecordsActivity::class.java.name, shadowOf(mine).nextStartedActivity.component?.className)
        mine.click(mine.getString(R.string.aq_history_title))
        assertEquals(AnkiquestHistoryActivity::class.java.name, shadowOf(mine).nextStartedActivity.component?.className)

        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        val theirs = launch("cerro")
        assertTrue(theirs.hasText(theirs.getString(R.string.aq_records_title)))
        assertFalse(theirs.hasText(theirs.getString(R.string.aq_history_title)), "another player's history is private")
    }

    @Test
    fun `many achievements show the closest first until all are asked for`() {
        val many =
            (1..10).map {
                AnkiquestBoards.Achievement("Goal $it", "", 10, it.toLong(), 100, null)
            }
        coEvery { AnkiquestBoards.player(identity, "hill") } returns own.copy(profile = profile.copy(achievements = many))
        val screen = launch()
        assertTrue(screen.hasText("Goal 10"))
        assertFalse(screen.hasText("Goal 1"))
        screen.click(screen.getString(R.string.aq_profile_show_all))
        assertTrue(screen.hasText("Goal 1"))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_show_fewer)))
    }

    @Test
    fun `a hidden player and a failure say so`() {
        coEvery { AnkiquestBoards.player(identity, "ghost") } throws AnkiquestBoards.NotVisible()
        val hidden = launch("ghost")
        assertTrue(hidden.hasText(hidden.getString(R.string.aq_profile_hidden)))

        coEvery { AnkiquestBoards.player(identity, "hill") } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_board_offline)))
        offline.click(offline.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(offline).nextStartedActivity.component?.className)
    }

    @Test
    fun `without an account the profile asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val screen = launch()
        assertTrue(screen.hasText(screen.getString(R.string.aq_board_connect)))
        coVerify(exactly = 0) { AnkiquestBoards.player(any(), any()) }
    }

    private fun launch(user: String? = null): AnkiquestProfileActivity =
        startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, user))
            .also { screen -> advanceRobolectricLooperUntil { !screen.findViewById<View>(R.id.aq_board_loading).isVisible } }

    private inline fun <reified T : View> AnkiquestProfileActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestProfileActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestProfileActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
    }
}
