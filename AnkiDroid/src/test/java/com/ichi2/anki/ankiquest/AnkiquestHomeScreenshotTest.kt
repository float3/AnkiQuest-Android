// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
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

/** Opt-in visual QA; the normal unit-test task excludes ScreenshotTestCategory. */
class AnkiquestHomeScreenshotTest : ScreenshotTest() {
    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepareHome() {
        val identity = HomeAccount("https://anki.example.test", "sam", "visual-test-token")
        val profile =
            JSONObject()
                .put("level", 4)
                .put("streak", 3)
                .put("xp_into_level", 120)
                .put("xp_for_next", 250)
                .put(
                    "quests",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "A little practice")
                            .put("progress", 6)
                            .put("target", 10)
                            .put("done", false),
                    ),
                )
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        coEvery { AnkiquestHomeData.profile(any()) } returns profile
    }

    @After
    fun clearHomeMocks() {
        unmockkObject(AnkiquestHomeData)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun today() = captureToday()

    @Test
    fun todayLargeText() = captureToday(largeText = true)

    @Test
    fun suggestedGoal() = captureSuggestedGoal(1f)

    @Test
    fun suggestedGoalLargeText() = captureSuggestedGoal(2f)

    private fun captureSuggestedGoal(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        coEvery { AnkiquestHomeData.profile(any()) } returns
            JSONObject().put(
                "goal",
                AnkiquestDailyGoal.Choice.DEFAULT
                    .json()
                    .put("chosen", false),
            )
        val home =
            startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, AnkiquestHomeActivity.intent(targetContext))
        advanceRobolectricLooperUntil { !home.findViewById<View>(R.id.aq_home_loading).isVisible }
        val heading =
            home.window.decorView.allViews
                .filterIsInstance<TextView>()
                .single { it.text == home.getString(R.string.ankiquest_goal_setup_title) }
        home.findViewById<NestedScrollView>(R.id.aq_home_scroll).scrollTo(0, (heading.parent as View).top)
        advanceRobolectricLooper()
        captureScreen("suggested_goal_${(fontScale * 100).toInt()}percent")
    }

    @Test
    fun todayGoal() {
        Settings.Global.putFloat(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val goal =
            AnkiquestDailyGoal.Status(
                day = 1,
                minutes = 7,
                streakMinutes = 10,
                studiedCards = 48,
                mode = AnkiquestDailyGoal.Mode.CARDS,
                goal = AnkiquestDailyGoal.Goal(140),
            )
        coEvery { AnkiquestHomeData.local() } returns
            HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12, goal = goal, secondsPerCard = 6.0)
        captureToday(prefix = "today_goal")
    }

    private fun captureToday(
        largeText: Boolean = false,
        prefix: String = "today",
    ) {
        RuntimeEnvironment.setFontScale(if (largeText) 2f else 1f)
        startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, AnkiquestHomeActivity.intent(targetContext))
        advanceRobolectricLooper()
        val size = if (device == DeviceConfig.PHONE) "320dp" else "default"
        captureScreen("${prefix}_${size}${if (largeText) "_200percent" else ""}")
    }
}
