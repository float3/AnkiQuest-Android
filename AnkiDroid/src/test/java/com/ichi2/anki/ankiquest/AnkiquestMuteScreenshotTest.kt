// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The same immediate mute and Undo action on both social entry points. */
class AnkiquestMuteScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")
    private val person = AnkiquestSocial.Person("cerro", "Cerro")
    private var overview = AnkiquestSocial.Overview(listOf(person), emptyList(), emptyList(), emptyList(), emptyList(), true)

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
        }
    }

    @Before
    fun prepareSocialScreens() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestSubscriptions)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestSocial.overview(identity) } answers { overview }
        coEvery { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Mute(person.user)) } answers {
            overview = overview.copy(muted = listOf(person))
            overview
        }
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        val profile =
            AnkiquestBoards
                .parseProfile(
                    JSONObject(AnkiquestBoardsTest.PROFILE),
                ).copy(user = person.user, display = person.display)
        coEvery { AnkiquestBoards.player(identity, person.user) } returns
            AnkiquestBoards.PlayerPage(profile, own = false, wins = emptyList())
        val repository = mockk<SubscriptionRepository>()
        every { AnkiquestSubscriptions.repository } returns repository
        coEvery { repository.load(any()) } throws IOException("offline")
    }

    @After
    fun cleanupSocialScreens() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestAvatars, AnkiquestSubscriptions)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun friends() = captureFriends("friends_mute")

    @Test
    fun friendsLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureFriends("friends_mute_200percent")
    }

    @Test
    fun profile() = captureProfile("profile_mute")

    @Test
    fun profileLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureProfile("profile_mute_200percent")
    }

    private fun captureFriends(name: String) {
        val screen = startRegularActivity<AnkiquestFriendsActivity>(Intent(targetContext, AnkiquestFriendsActivity::class.java))
        advanceRobolectricLooperUntil { !screen.findViewById<View>(R.id.aq_feed_loading).isVisible }
        muteAndCapture(screen, name)
    }

    private fun captureProfile(name: String) {
        val screen = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, person.user))
        advanceRobolectricLooperUntil { !screen.findViewById<View>(R.id.aq_board_loading).isVisible }
        muteAndCapture(screen, name)
    }

    private fun muteAndCapture(
        screen: AnkiActivity,
        name: String,
    ) {
        screen.window.decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text == screen.getString(R.string.aq_friends_mute) }
            .performClick()
        // Finish the show animation without advancing through the snackbar timeout.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val action = assertNotNull(screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_action))
        assertEquals(screen.getString(CommonString.undo), action.text.toString())
        assertTrue(action.isShown)
        val background = MaterialColors.getColor(screen, com.google.android.material.R.attr.colorSurfaceInverse, 0)
        assertTrue(ColorUtils.calculateContrast(action.currentTextColor, background) >= 4.5, "Undo must remain readable")
        screen.window.decorView.allViews
            .filterIsInstance<com.google.android.material.bottomnavigation.BottomNavigationView>()
            .firstOrNull { it.isVisible }
            ?.let { navigation ->
                val actionBounds = Rect()
                val navBounds = Rect()
                assertTrue(action.getGlobalVisibleRect(actionBounds))
                assertTrue(navigation.getGlobalVisibleRect(navBounds))
                assertTrue(actionBounds.bottom <= navBounds.top, "Undo must not cover the tabs")
            }
        captureScreen(name)
    }
}
