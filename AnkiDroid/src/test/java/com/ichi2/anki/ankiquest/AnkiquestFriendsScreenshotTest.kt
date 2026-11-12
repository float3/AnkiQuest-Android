// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
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
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The sections share Friends' scroll and navigation, including offline and large-text states. */
class AnkiquestFriendsScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
        }
    }

    @Before
    fun prepareFriends() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestFeed, AnkiquestBoards, AnkiquestAvatars, AnkiquestShare)
        every { AnkiquestHomeData.account(any()) } returns identity
        val sample = assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/social.json")).readText(Charsets.UTF_8)
        val overview = AnkiquestSocial.parse(JSONObject(sample))
        coEvery { AnkiquestSocial.overview(any()) } returns overview
        coEvery { AnkiquestFeed.page(any(), any(), any(), any()) } returns AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedTest.PAGE))
        coEvery { AnkiquestBoards.hasScopes(any()) } returns false
        coEvery { AnkiquestBoards.leaderboard(any(), any()) } returns
            AnkiquestBoards.parseLeaderboard(
                JSONArray().put(AnkiquestBoardsTest.row("cerro", 90, 5)).put(AnkiquestBoardsTest.row("hill", 40, 2)),
                AnkiquestBoards.Period.WEEK,
            )
        coEvery { AnkiquestBoards.weekEnds(any()) } returns null
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        every { AnkiquestShare.picture(any()) } returns null
    }

    @After
    fun cleanupFriends() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestFeed, AnkiquestBoards, AnkiquestAvatars, AnkiquestShare)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun collapsed() {
        captureFriends("friends_collapsed")
    }

    @Test
    fun leaderboard() {
        captureFriends("friends_leaderboard", AnkiquestLeaderboardActivity.intent(targetContext))
    }

    @Test
    fun leaderboardLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        val screen = captureFriends("friends_leaderboard_200percent", AnkiquestLeaderboardActivity.intent(targetContext))
        val status = screen.getString(R.string.ankiquest_streak_studied)
        val labels =
            screen.window.decorView.allViews
                .filterIsInstance<TextView>()
                .filter { it.text.contains(status) }
                .toList()
        assertTrue(labels.isNotEmpty())
        for (label in labels) {
            val layout = assertNotNull(label.layout)
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertFalse(
                    end in 1 until label.text.length && label.text[end - 1].isLetter() && label.text[end].isLetter(),
                    "the status must wrap between words at 200%: ${label.text}",
                )
            }
        }
    }

    @Test
    fun offline() {
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("offline")
        coEvery { AnkiquestFeed.page(any(), any(), any(), any()) } throws IOException("offline")
        captureFriends("friends_offline", AnkiquestFeedActivity.intent(targetContext))
    }

    private fun captureFriends(
        name: String,
        intent: Intent = AnkiquestFriendsActivity.intent(targetContext),
    ): AnkiquestFriendsActivity {
        val screen = startRegularActivity<AnkiquestFriendsActivity>(intent)
        advanceRobolectricLooperUntil {
            listOf(R.id.aq_feed_loading, R.id.aq_friends_feed_loading, R.id.aq_friends_board_loading)
                .none { screen.findViewById<View>(it).isVisible }
        }
        advanceRobolectricLooper()
        assertEquals(screen.getString(R.string.ankiquest_nav_friends), screen.findViewById<TextView>(R.id.aq_feed_title).text)
        assertTrue(screen.findViewById<View>(R.id.ankiquest_tab_bar).isShown)
        assertTrue(
            screen.window.decorView.allViews.filterIsInstance<TextView>().any {
                it.text ==
                    screen.getString(R.string.aq_home_activity)
            },
        )
        captureScreen(name)
        return screen
    }
}
