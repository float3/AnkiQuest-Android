// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapGrid
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestHistoryActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val study = AnkiquestRecords.parseStudy(JSONObject(example("study")))

    @Before
    fun prepareHistory() {
        mockkObject(AnkiquestHomeData, AnkiquestRecords)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestRecords.study(identity, any()) } returns study
    }

    @After
    fun clearHistoryMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestRecords)
    }

    @Test
    fun `the year's totals, the latest study day and the calendar are shown`() {
        val history = launch()
        assertTrue(history.hasTile("15", history.getString(R.string.aq_history_year_reviews, 2026)))
        assertTrue(history.hasTile("3", history.getString(R.string.aq_history_year_days, 2026)))
        assertTrue(history.hasTile("204", history.getString(R.string.aq_history_year_xp, 2026)))
        assertTrue(history.hasText(date(LocalDate.of(2026, 10, 28))), "it opens on the latest day with reviews")
        assertTrue(history.hasTile("101", history.getString(R.string.aq_history_xp)))
        assertTrue(history.hasText(history.getString(R.string.aq_history_day_hint)))
        assertTrue(history.views<TextView>().any { it.text.startsWith(history.getString(R.string.aq_history_updated, "").trimEnd()) })
        val grid = history.views<ReviewHeatmapGrid>().single()
        assertEquals(LocalDate.of(2026, 10, 28), grid.selectedDate)
        assertFalse(history.hasText(history.getString(R.string.aq_history_year)), "a single year needs no choice")
    }

    @Test
    fun `tapping a day on the calendar shows that day`() {
        val history = launch()
        val grid = history.views<ReviewHeatmapGrid>().single()
        val day = LocalDate.of(2026, 10, 26)
        val cell = grid.cellBounds(day.dayOfYear - 1)
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, cell.centerX(), cell.centerY(), 0)
        val up = android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, cell.centerX(), cell.centerY(), 0)
        grid.onTouchEvent(down)
        grid.onTouchEvent(up)
        assertTrue(history.hasText(date(day)))
        assertTrue(history.hasTile("62", history.getString(R.string.aq_history_xp)))
        assertTrue(history.hasTile("2", history.getString(R.string.aq_history_streak)))

        val rest = LocalDate.of(2026, 10, 27)
        val restCell = grid.cellBounds(rest.dayOfYear - 1)
        grid.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, restCell.centerX(), restCell.centerY(), 0),
        )
        grid.onTouchEvent(
            android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, restCell.centerX(), restCell.centerY(), 0),
        )
        assertTrue(history.hasText(history.getString(R.string.aq_history_day_empty)))
    }

    @Test
    fun `earlier years can be picked`() {
        val earlier =
            AnkiquestRecords.Study(
                2025,
                listOf(2026, 2025),
                listOf(AnkiquestRecords.Day(LocalDate.of(2025, 12, 30), 7, 60_000, 70, 1, 4, false)),
                null,
                null,
            )
        coEvery { AnkiquestRecords.study(identity, null) } returns study.copy(years = listOf(2026, 2025))
        coEvery { AnkiquestRecords.study(identity, 2025) } returns earlier
        val history = launch()
        assertTrue(history.hasText(history.getString(R.string.aq_history_year)))
        history.click("2025")
        coVerify { AnkiquestRecords.study(identity, 2025) }
        assertTrue(history.hasTile("7", history.getString(R.string.aq_history_year_reviews, 2025)))
        assertTrue(history.hasText(date(LocalDate.of(2025, 12, 30))))
        assertTrue(history.hasText(history.getString(R.string.aq_history_no_reviews)))
    }

    @Test
    fun `a frozen day says the streak was protected`() {
        val frozen = LocalDate.of(2026, 10, 27)
        coEvery { AnkiquestRecords.study(identity, any()) } returns
            study.copy(days = study.days.map { if (it.date == frozen) it.copy(frozen = true) else it })
        val history = launch()
        val grid = history.views<ReviewHeatmapGrid>().single()
        val cell = grid.cellBounds(frozen.dayOfYear - 1)
        grid.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, cell.centerX(), cell.centerY(), 0))
        grid.onTouchEvent(android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, cell.centerX(), cell.centerY(), 0))
        assertTrue(history.hasText(history.getString(R.string.aq_history_frozen)))
    }

    @Test
    fun `a year without study days says so`() {
        coEvery { AnkiquestRecords.study(identity, any()) } returns
            study.copy(days = emptyList(), lastReviewAt = null, lastReceivedAt = null)
        val history = launch()
        assertTrue(history.hasText(history.getString(R.string.aq_history_empty)))
        assertTrue(history.views<ReviewHeatmapGrid>().isEmpty())
    }

    @Test
    fun `offline, refused and missing accounts say what to do`() {
        coEvery { AnkiquestRecords.study(identity, any()) } throws IOException("offline")
        assertTrue(launch().let { it.hasText(it.getString(R.string.aq_records_offline)) })
        coEvery { AnkiquestRecords.study(identity, any()) } throws AnkiquestHttpException(403)
        assertTrue(launch().let { it.hasText(it.getString(R.string.aq_home_auth)) })
        every { AnkiquestHomeData.account(any()) } returns null
        assertTrue(launch().let { it.hasText(it.getString(R.string.aq_history_connect)) })
    }

    @Test
    fun `a server without the calendar opens the history page on the website`() {
        coEvery { AnkiquestRecords.study(identity, any()) } throws AnkiquestHttpException(404)
        val history = startRegularActivity<AnkiquestHistoryActivity>(AnkiquestHistoryActivity.intent(targetContext))
        advanceRobolectricLooperUntil { history.isFinishing }
        val opened = assertNotNull(shadowOf(history).nextStartedActivity)
        assertEquals(AnkiquestActivity::class.java.name, opened.component?.className)
        assertEquals(AnkiquestNavigation.HISTORY_PATH, opened.getStringExtra(AnkiquestActivity.EXTRA_PATH))
    }

    private fun date(day: LocalDate): String =
        day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(AnkiquestLanguage.locale()))

    private fun launch(): AnkiquestHistoryActivity =
        startRegularActivity<AnkiquestHistoryActivity>(AnkiquestHistoryActivity.intent(targetContext))
            .also { history -> advanceRobolectricLooperUntil { !history.findViewById<View>(R.id.aq_records_loading).isVisible } }

    private inline fun <reified T : View> AnkiquestHistoryActivity.views(): List<T> =
        findViewById<View>(R.id.aq_records_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestHistoryActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestHistoryActivity.hasTile(
        value: String,
        label: String,
    ): Boolean = views<View>().any { it.contentDescription == "$value $label" }

    private fun AnkiquestHistoryActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_records_loading).isVisible }
        advanceRobolectricLooper()
    }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/$name.json"), name).readText(Charsets.UTF_8)
}
