// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        mockkObject(AnkiquestSocial, AnkiquestHomeData, AnkiquestFeed, AnkiquestShare)
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("overview unavailable")
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedTest.PAGE))
        every { AnkiquestShare.picture(any()) } returns
            createBitmap(120, 120).apply { eraseColor(Color.rgb(36, 95, 171)) }
    }

    @After
    fun clearFeedMocks() {
        unmockkObject(AnkiquestSocial, AnkiquestHomeData, AnkiquestFeed, AnkiquestShare)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun activity() = captureFeed()

    @Test
    fun activityLargeText() = captureFeed(largeText = true)

    @Test
    fun delayedFeed() {
        coEvery { AnkiquestSocial.overview(any()) } coAnswers {
            withContext(Dispatchers.IO) {
                delay(1_000)
                throw IOException("overview unavailable")
            }
        }
        coEvery { AnkiquestFeed.page(any(), any(), any(), any()) } coAnswers {
            withContext(Dispatchers.IO) {
                delay(500)
                AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedTest.PAGE))
            }
        }
        captureFeed(prefix = "activity_feed_delayed")
    }

    private fun captureFeed(
        largeText: Boolean = false,
        prefix: String = "activity_feed",
    ) {
        RuntimeEnvironment.setFontScale(if (largeText) 2f else 1f)
        val screen =
            startActivityNormallyOpenCollectionWithIntent(AnkiquestFriendsActivity::class.java, AnkiquestFeedActivity.intent(targetContext))
        // Both requests can rerender Friends; capture their finished fixture, not a loading frame.
        advanceRobolectricLooperUntil {
            !screen.findViewById<View>(R.id.aq_feed_loading).isVisible &&
                !screen.findViewById<View>(R.id.aq_friends_feed_loading).isVisible &&
                screen.window.decorView.allViews
                    .filterIsInstance<TextView>()
                    .any { it.text == "Challenge invitation" }
        }
        assertFalse(screen.findViewById<View>(R.id.aq_feed_loading).isVisible, "capture must await the parent overview")
        assertFalse(screen.findViewById<View>(R.id.aq_friends_feed_loading).isVisible, "capture must await the feed")
        assertTrue(
            screen.window.decorView.allViews
                .filterIsInstance<TextView>()
                .any { it.text == "Challenge invitation" },
        )
        val heading =
            screen.window.decorView.allViews.filterIsInstance<TextView>().first {
                it.text ==
                    screen.getString(R.string.aq_home_activity)
            }
        val scroll = screen.findViewById<NestedScrollView>(R.id.aq_feed_scroll)
        val bounds = Rect(0, 0, heading.width, heading.height)
        scroll.offsetDescendantRectToMyCoords(heading, bounds)
        // The deep-link scroll can run before the parent finishes its layout.
        scroll.scrollTo(0, bounds.top)
        advanceRobolectricLooper()
        assertEquals(bounds.top, scroll.scrollY, "capture must use the settled section position")
        val size = if (device == DeviceConfig.PHONE) "320dp" else "default"
        captureScreen("${prefix}_${size}${if (largeText) "_200percent" else ""}")
    }
}
