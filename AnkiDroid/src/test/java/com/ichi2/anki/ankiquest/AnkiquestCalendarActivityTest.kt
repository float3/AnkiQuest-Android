// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestCalendarActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")

    /** The server's recorded October 2026: Hill won the 25th and 26th, nobody the 27th, and leads the 28th and the week. */
    private val october =
        AnkiquestCommunity.parseCalendar(
            JSONObject(
                assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/community.json")).readText(Charsets.UTF_8),
            ),
        )

    @Before
    fun prepareCalendar() {
        TimeManager.resetWith(MockTime(NOW))
        mockkObject(AnkiquestHomeData, AnkiquestCommunity)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestCommunity.calendar(identity, any(), any()) } returns october
    }

    @After
    fun clearCalendarMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestCommunity)
    }

    @Test
    fun `the month shows who won each day, and a day opens its full standings`() {
        val calendar = launch()
        coVerify { AnkiquestCommunity.calendar(identity, 2026, 10) }
        val days = calendar.views<LinearLayout>().filter { it.contentDescription != null && it.isClickable }
        assertEquals(4, days.size, "only days with a result open")
        assertTrue(days.count { it.contentDescription.contains("Hill") } == 3)
        assertTrue(days.any { it.contentDescription.contains(calendar.getString(R.string.aq_calendar_pending)) })
        assertTrue(
            calendar.views<LinearLayout>().any {
                it.contentDescription?.contains(calendar.getString(R.string.aq_calendar_upcoming)) == true &&
                    !it.isClickable
            },
            "days after today are still to come",
        )
        assertFalse(calendar.hasText(calendar.getString(R.string.aq_calendar_place, 2, "Cerro")))

        val live = days.single { it.contentDescription.contains(calendar.getString(R.string.aq_calendar_live)) }
        live.performClick()
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_calendar_leader, "Hill", "70")))
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_calendar_place, 1, "Hill")))
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_calendar_place, 2, "Cerro")))
        assertTrue(calendar.hasTextContaining("UTC"), "the competition's time zone and cutoff are explained")
    }

    @Test
    fun `weeks list their champion or leader and open their standings`() {
        val calendar = launch()
        calendar.click(calendar.getString(R.string.aq_calendar_weeks))
        val leader = calendar.getString(R.string.aq_calendar_leader, "Hill", "132")
        assertTrue(calendar.hasText(leader))
        calendar.views<TextView>().first { it.text.toString() == leader }.let { (it.parent as View).performClick() }
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_calendar_place, 3, "Friend")))

        calendar.click(calendar.getString(R.string.aq_calendar_months))
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_calendar_months)))
        assertFalse(calendar.hasText(calendar.getString(R.string.aq_calendar_empty)))
    }

    @Test
    fun `the arrows load the previous month and the previous year`() {
        val calendar = launch()
        calendar.views<View>().first { it.contentDescription == calendar.getString(R.string.aq_calendar_previous_month) }.performClick()
        calendar.settle()
        coVerify { AnkiquestCommunity.calendar(identity, 2026, 9) }

        calendar.click(calendar.getString(R.string.aq_calendar_weeks))
        calendar.views<View>().first { it.contentDescription == calendar.getString(R.string.review_heatmap_previous_year) }.performClick()
        calendar.settle()
        coVerify { AnkiquestCommunity.calendar(identity, 2025, 9) }
    }

    @Test
    fun `a server without the calendar's JSON says it needs updating`() {
        coEvery { AnkiquestCommunity.calendar(identity, any(), any()) } throws AnkiquestHttpException(404)
        val calendar = launch()
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_server_outdated)))
        assertNull(shadowOf(calendar).nextStartedActivity)
        assertFalse(calendar.isFinishing)
    }

    @Test
    fun `without an account the calendar asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val calendar = launch()
        assertTrue(calendar.hasText(calendar.getString(R.string.aq_board_connect)))
        coVerify(exactly = 0) { AnkiquestCommunity.calendar(any(), any(), any()) }
    }

    @Test
    fun `the leaderboard opens the calendar`() {
        mockkObject(AnkiquestBoards, AnkiquestSocial)
        try {
            coEvery { AnkiquestSocial.overview(any()) } throws IOException("overview unavailable")
            coEvery { AnkiquestBoards.hasScopes(any()) } returns false
            coEvery { AnkiquestBoards.leaderboard(identity, any()) } returns emptyList()
            coEvery { AnkiquestBoards.weekEnds(identity) } returns null
            val board = startRegularActivity<AnkiquestFriendsActivity>(AnkiquestLeaderboardActivity.intent(targetContext))
            advanceRobolectricLooperUntil { !board.findViewById<View>(R.id.aq_friends_board_loading).isVisible }
            board
                .findViewById<View>(R.id.aq_friends_board_content)
                .allViews
                .filterIsInstance<TextView>()
                .first { it.text.toString() == board.getString(R.string.aq_calendar_title) }
                .performClick()
            assertEquals(AnkiquestCalendarActivity::class.java.name, shadowOf(board).nextStartedActivity.component?.className)
        } finally {
            unmockkObject(AnkiquestBoards, AnkiquestSocial)
        }
    }

    private fun launch(): AnkiquestCalendarActivity =
        startRegularActivity<AnkiquestCalendarActivity>(AnkiquestCalendarActivity.intent(targetContext)).also { it.settle() }

    private fun AnkiquestCalendarActivity.settle() {
        advanceRobolectricLooperUntil { isFinishing || !findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
    }

    private inline fun <reified T : View> AnkiquestCalendarActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestCalendarActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestCalendarActivity.hasTextContaining(value: String): Boolean =
        views<TextView>().any {
            it.text.toString().contains(value)
        }

    private fun AnkiquestCalendarActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        control.performClick()
        settle()
    }

    private companion object {
        /** Wednesday 28 October 2026, 10:31 UTC, as the server's examples. */
        const val NOW = 1_793_183_460_000L
    }
}
