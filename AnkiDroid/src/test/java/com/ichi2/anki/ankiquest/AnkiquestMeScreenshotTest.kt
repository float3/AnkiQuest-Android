// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

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
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Me keeps its own Back action and Settings when the profile is unavailable. */
class AnkiquestMeScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
        }
    }

    @Before
    fun prepareMe() {
        val profile = AnkiquestBoards.parseProfile(JSONObject(AnkiquestBoardsTest.PROFILE))
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestAvatars)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestBoards.player(identity, identity.user) } returns
            AnkiquestBoards.PlayerPage(profile, own = true, wins = emptyList())
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
    }

    @After
    fun cleanupMe() {
        unmockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestAvatars)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun me() = captureMe("me")

    @Test
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureMe("me_200percent")
    }

    @Test
    fun offline() {
        coEvery { AnkiquestBoards.player(identity, identity.user) } throws IOException("offline")
        captureMe("me_offline")
    }

    private fun captureMe(name: String) {
        val me = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext))
        advanceRobolectricLooperUntil { !me.findViewById<View>(R.id.aq_board_loading).isVisible }
        assertEquals(me.getString(R.string.ankiquest_me), me.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertTrue(me.findViewById<View>(R.id.aq_board_back).isVisible)
        assertTrue(
            me.window.decorView.allViews
                .filterIsInstance<TextView>()
                .any { it.text == me.getString(R.string.aq_home_settings) },
        )
        captureScreen(name)
    }
}
