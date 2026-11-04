// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Color
import androidx.core.graphics.createBitmap
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

/** Opt-in visual QA; the normal unit-test task excludes ScreenshotTestCategory. */
class AnkiquestFeedScreenshotTest : ScreenshotTest() {
    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepareFeed() {
        val identity = HomeAccount("https://anki.example.test", "sam", "visual-test-token")
        mockkObject(AnkiquestHomeData, AnkiquestFeed, AnkiquestShare)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedTest.PAGE))
        every { AnkiquestShare.picture(any()) } returns
            createBitmap(120, 120).apply { eraseColor(Color.rgb(36, 95, 171)) }
    }

    @After
    fun clearFeedMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestFeed, AnkiquestShare)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun activity() = captureFeed()

    @Test
    fun activityLargeText() = captureFeed(largeText = true)

    private fun captureFeed(largeText: Boolean = false) {
        RuntimeEnvironment.setFontScale(if (largeText) 2f else 1f)
        startActivityNormallyOpenCollectionWithIntent(AnkiquestFeedActivity::class.java, AnkiquestFeedActivity.intent(targetContext))
        advanceRobolectricLooper()
        val size = if (device == DeviceConfig.PHONE) "320dp" else "default"
        captureScreen("activity_feed_${size}${if (largeText) "_200percent" else ""}")
    }
}
