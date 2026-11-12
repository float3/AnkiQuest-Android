// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.chip.Chip
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnkiquestLeaderboardScopesScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")
    private val overview =
        AnkiquestSocial.parse(JSONObject("""{"groups":[{"id":7,"name":"Study group","members":[]}]}"""))

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
        coEvery { AnkiquestSocial.overview(any()) } returns overview
        coEvery { AnkiquestBoards.hasScopes(any()) } returns true
        coEvery { AnkiquestGlobalHides.load(any()) } returns null
        val board =
            AnkiquestBoards.parseLeaderboard(
                JSONArray(
                    """[
                    {"user":"ana","display":"Ana","rank":3,"level":4,"xp":900,"streak":7},
                    {"user":"hill","display":"Hill","rank":103,"level":2,"xp":40,"streak":3,"me":true,"shown":false,
                     "today_reviews":12,"today_minutes":10,"streak_minutes":10,"streak_state":"studied","day_ends_at":4102444800000}
                    ]""",
                ),
                AnkiquestBoards.Period.WEEK,
            )
        coEvery { AnkiquestBoards.leaderboard(any(), any(), any()) } returns board
        coEvery { AnkiquestBoards.leaderboard(any(), any(), AnkiquestBoards.Source.Group(7)) } returns
            board.map { it.copy(rank = null, shown = null) }
        coEvery { AnkiquestBoards.weekEnds(any()) } returns null
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestGlobalHides)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun global() = captureGlobal("global")

    @Test
    fun globalLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureGlobal("global_200percent")
    }

    @Test
    fun group() {
        val screen = launch()
        click(screen, R.string.aq_board_scope_group)
        captureScreen("group")
    }

    @Test
    fun noGroups() {
        coEvery { AnkiquestSocial.overview(any()) } returns overview.copy(groups = emptyList())
        val screen = launch()
        click(screen, R.string.aq_board_scope_group)
        captureScreen("no_groups")
    }

    @Test
    fun unavailableGroup() {
        coEvery { AnkiquestBoards.leaderboard(any(), any(), AnkiquestBoards.Source.Group(7)) } throws AnkiquestHttpException(HTTP_FORBIDDEN)
        val screen = launch()
        click(screen, R.string.aq_board_scope_group)
        captureScreen("unavailable_group")
    }

    private fun captureGlobal(name: String) {
        val screen = launch()
        click(screen, R.string.ankiquest_widget_who_global)
        val selected =
            screen
                .findViewById<View>(R.id.aq_friends_board_content)
                .allViews
                .filterIsInstance<Chip>()
                .first { it.isChecked }
        val bounds = Rect()
        assertTrue(selected.getGlobalVisibleRect(bounds))
        assertEquals(selected.width, bounds.width(), "the selected scope must fit at large text sizes")
        captureScreen(name)
        val own =
            screen.findViewById<View>(R.id.aq_friends_board_content).allViews.first {
                it.isClickable && it.contentDescription?.contains("Place 103:") == true
            }
        val scroll = screen.findViewById<NestedScrollView>(R.id.aq_feed_scroll)
        val rowBounds = Rect(0, 0, own.width, own.height)
        scroll.offsetDescendantRectToMyCoords(own, rowBounds)
        scroll.scrollTo(0, rowBounds.top)
        advanceRobolectricLooper()
        val rank = own.allViews.filterIsInstance<TextView>().first { it.text.toString() == "103" }
        assertEquals(1, rank.layout.lineCount, "a Global rank below the top 100 must stay on one line at large text sizes")
        captureScreen("${name}_own_row")
    }

    private fun launch(): AnkiquestFriendsActivity =
        startRegularActivity<AnkiquestFriendsActivity>(AnkiquestLeaderboardActivity.intent(targetContext)).also(::awaitBoard)

    private fun awaitBoard(screen: AnkiquestFriendsActivity) {
        advanceRobolectricLooperUntil {
            !screen.findViewById<View>(R.id.aq_friends_board_loading).isVisible &&
                !screen.findViewById<View>(R.id.aq_feed_loading).isVisible
        }
        advanceRobolectricLooper()
    }

    private fun click(
        screen: AnkiquestFriendsActivity,
        label: Int,
    ) {
        screen
            .findViewById<View>(R.id.aq_friends_board_content)
            .allViews
            .filterIsInstance<TextView>()
            .first { it.isClickable && it.text == screen.getString(label) }
            .performClick()
        awaitBoard(screen)
    }
}
