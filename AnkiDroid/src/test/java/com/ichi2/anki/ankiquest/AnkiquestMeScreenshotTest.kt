// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.Rect
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
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestAvatars, AnkiquestRecords)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestBoards.player(identity, identity.user) } returns
            AnkiquestBoards.PlayerPage(profile, own = true, wins = emptyList())
        coEvery { AnkiquestAvatars.refresh(any()) } returns Unit
        coEvery { AnkiquestRecords.records(any(), any()) } returns AnkiquestRecords.parseRecords(JSONArray(example("records")))
        coEvery { AnkiquestRecords.scopes(any()) } returns listOf(AnkiquestRecords.Scope.CIRCLE, AnkiquestRecords.Scope.GLOBAL)
        coEvery { AnkiquestRecords.study(any(), any()) } returns AnkiquestRecords.parseStudy(JSONObject(example("study")))
    }

    @After
    fun cleanupMe() {
        unmockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestAvatars, AnkiquestRecords)
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

    @Test
    fun records() = captureMe("me_records", AnkiquestMeSections.Destination.RECORDS)

    @Test
    fun history() = captureMe("me_history", AnkiquestMeSections.Destination.HISTORY)

    @Test
    fun recordsLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureMe("me_records_200percent", AnkiquestMeSections.Destination.RECORDS)
    }

    @Test
    fun historyLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureMe("me_history_200percent", AnkiquestMeSections.Destination.HISTORY)
    }

    @Test
    fun historyCalendar() = captureMe("me_history_calendar", AnkiquestMeSections.Destination.HISTORY, Part.CALENDAR)

    @Test
    fun historyCalendarLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureMe("me_history_calendar_200percent", AnkiquestMeSections.Destination.HISTORY, Part.CALENDAR)
    }

    private enum class Part { HEADER, CALENDAR }

    private fun captureMe(
        name: String,
        destination: AnkiquestMeSections.Destination? = null,
        part: Part = Part.HEADER,
    ) {
        val intent = destination?.let { AnkiquestMeSections.intent(targetContext, it) } ?: AnkiquestProfileActivity.intent(targetContext)
        val me = startRegularActivity<AnkiquestProfileActivity>(intent)
        advanceRobolectricLooperUntil { !me.findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooperUntil {
            !me.findViewById<View>(R.id.aq_me_records_loading).isVisible && !me.findViewById<View>(R.id.aq_me_history_loading).isVisible
        }
        assertEquals(me.getString(R.string.ankiquest_me), me.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertTrue(me.findViewById<View>(R.id.aq_board_back).isVisible)
        assertTrue(
            me.window.decorView.allViews
                .filterIsInstance<TextView>()
                .any { it.text == me.getString(R.string.aq_home_settings) },
        )
        destination?.let {
            val contentId = if (it == AnkiquestMeSections.Destination.RECORDS) R.id.aq_me_records_content else R.id.aq_me_history_content
            val section =
                if (part == Part.CALENDAR) {
                    me.findViewById<View>(contentId).allViews.filterIsInstance<TextView>().first {
                        it.text == me.getString(R.string.aq_history_calendar, 2026)
                    }
                } else {
                    me.findViewById<View>(contentId).parent as View
                }
            val bounds = Rect(0, 0, section.width, section.height)
            val scroll = me.findViewById<NestedScrollView>(R.id.aq_board_scroll)
            scroll.offsetDescendantRectToMyCoords(section, bounds)
            scroll.scrollTo(0, bounds.top)
            advanceRobolectricLooper()
        }
        captureScreen(name)
        if (destination == AnkiquestMeSections.Destination.HISTORY && part == Part.HEADER && me.resources.configuration.fontScale >= 2f) {
            val caption =
                me.window.decorView.allViews.filterIsInstance<TextView>().first {
                    it.text == me.getString(R.string.aq_history_year_reviews, 2026)
                }
            assertTrue(
                caption.layout.getLineEnd(0) >=
                    caption.text
                        .toString()
                        .substringBefore(' ')
                        .length,
                "the first word must fit",
            )
        }
    }

    private fun example(name: String): String = javaClass.classLoader!!.getResource("ankiquest-api/$name.json")!!.readText()
}
