// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.chip.Chip
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapGrid
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestMeSectionsTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private var account: HomeAccount? = identity
    private val boards = AnkiquestRecords.parseRecords(JSONArray(example("records")))
    private val study = AnkiquestRecords.parseStudy(JSONObject(example("study")))
    private val club = AnkiquestRecords.Scope("group:2", "Spanish club")
    private val earlier =
        AnkiquestRecords.Study(
            2025,
            listOf(2026, 2025),
            listOf(AnkiquestRecords.Day(LocalDate.of(2025, 12, 30), 7, 60_000, 70, 1, 4, false)),
            null,
            null,
        )

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestRecords)
        every { AnkiquestHomeData.account(any()) } answers { account }
        coEvery { AnkiquestBoards.player(any(), any()) } throws IOException("offline")
        coEvery { AnkiquestRecords.records(any(), any()) } returns boards
        coEvery { AnkiquestRecords.scopes(any()) } returns listOf(AnkiquestRecords.Scope.CIRCLE, club)
        coEvery { AnkiquestRecords.study(any(), any()) } returns study.copy(years = listOf(2026, 2025))
        coEvery { AnkiquestRecords.study(any(), 2025) } returns earlier
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestRecords)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun `sections retain their filters and selected day through refresh collapse and rotation`() {
        val controller = launch(AnkiquestRecordsActivity.intent(targetContext))
        val me = controller.get()
        awaitSections(me)
        me.click("Spanish club")
        me.click(me.getString(R.string.aq_history_title))
        me.click("2025")
        val selected = LocalDate.of(2025, 12, 29)
        me.grid().select(selected)
        me.click(me.getString(R.string.aq_records_title))
        assertFalse(me.findViewById<View>(R.id.aq_me_records_content).isVisible)
        me.click(me.getString(R.string.aq_records_title))
        me.findViewById<View>(R.id.aq_board_refresh).performClick()
        awaitSections(me)
        assertEquals(selected, me.grid().selectedDate)
        assertTrue(me.views<Chip>().single { it.text == "Spanish club" }.isChecked)
        assertNull(shadowOf(me).nextStartedActivity)

        controller.recreate()
        val restored = controller.get()
        awaitSections(restored)
        assertTrue(restored.findViewById<View>(R.id.aq_me_records_content).isVisible)
        assertTrue(restored.findViewById<View>(R.id.aq_me_history_content).isVisible)
        assertTrue(restored.views<Chip>().single { it.text == "Spanish club" }.isChecked)
        assertEquals(selected, restored.grid().selectedDate)
        coVerify { AnkiquestRecords.records(identity, club) }
        coVerify { AnkiquestRecords.study(identity, 2025) }
    }

    @Test
    fun `switching accounts resets history and ignores a late old record response`() {
        val oldRecords = CompletableDeferred<List<AnkiquestRecords.Board>>()
        coEvery { AnkiquestRecords.records(identity, any()) } coAnswers { withContext(NonCancellable) { oldRecords.await() } }
        val controller = launch(AnkiquestRecordsActivity.intent(targetContext))
        try {
            val me = controller.get()
            assertTrue(me.findViewById<View>(R.id.aq_me_records_loading).isVisible)
            me.click(me.getString(R.string.aq_history_title), R.id.aq_me_history_loading)
            me.click("2025", R.id.aq_me_history_loading)
            val next = identity.copy(user = "other", token = "other-token")
            coEvery { AnkiquestRecords.records(next, any()) } returns emptyList()
            account = next
            controller
                .pause()
                .stop()
                .start()
                .resume()
            awaitSections(me)
            oldRecords.complete(boards)
            advanceRobolectricLooper()
            assertFalse(me.views<TextView>().any { it.text == "Cerro" })
            assertTrue(me.views<TextView>().any { it.text == me.getString(R.string.aq_records_empty) })
            assertEquals(study.latest, me.grid().selectedDate)
            coVerify { AnkiquestRecords.study(next, null) }
            coVerify { AnkiquestRecords.records(next, null) }
        } finally {
            oldRecords.complete(boards)
        }
    }

    @Test
    fun `signing out clears expanded data and asks to connect`() {
        val controller = launch(AnkiquestRecordsActivity.intent(targetContext))
        val me = controller.get()
        awaitSections(me)
        account = null
        controller
            .pause()
            .stop()
            .start()
            .resume()
        awaitSections(me)
        assertFalse(me.views<TextView>().any { it.text == "Cerro" })
        assertTrue(me.views<TextView>().any { it.text == me.getString(R.string.aq_records_connect) })
    }

    @Test
    fun `rotation after an account switch does not restore the old account's collapsed filters`() {
        val controller = launch(AnkiquestRecordsActivity.intent(targetContext))
        val me = controller.get()
        awaitSections(me)
        me.click("Spanish club")
        me.click(me.getString(R.string.aq_history_title))
        me.click("2025")
        me.click(me.getString(R.string.aq_records_title))
        me.click(me.getString(R.string.aq_history_title))
        val next = identity.copy(user = "other", token = "other-token")
        account = next
        controller.recreate()
        val restored = controller.get()
        awaitSections(restored)
        restored.click(restored.getString(R.string.aq_records_title))
        restored.click(restored.getString(R.string.aq_history_title))
        assertTrue(restored.views<Chip>().single { it.text == restored.getString(R.string.aq_records_scope_circle) }.isChecked)
        assertEquals(study.latest, restored.grid().selectedDate)
        coVerify { AnkiquestRecords.records(next, null) }
        coVerify { AnkiquestRecords.study(next, null) }
    }

    @Test
    fun `the final partial row of daily history statistics remains visible`() {
        val me = launch(AnkiquestHistoryActivity.intent(targetContext)).get()
        awaitSections(me)
        me.layoutPhone()
        for (label in listOf(R.string.aq_history_new_cards, R.string.aq_profile_streak)) {
            val tile = me.views<View>().single { it.contentDescription?.toString()?.endsWith(" ${me.getString(label)}") == true }
            assertTrue(tile.height > 0, "${me.getString(label)} needs a visible row")
        }
    }

    @Test
    fun `large text gives history captions room for whole words`() {
        RuntimeEnvironment.setFontScale(2f)
        val me = launch(AnkiquestHistoryActivity.intent(targetContext)).get()
        awaitSections(me)
        me.layoutPhone()
        val caption = me.views<TextView>().single { it.text == me.getString(R.string.aq_history_year_reviews, 2026) }
        assertTrue(
            caption.layout.getLineEnd(0) >=
                caption.text
                    .toString()
                    .substringBefore(' ')
                    .length,
            "the first word must fit",
        )
    }

    @Test
    fun `legacy explicit activities redirect to the matching Me section`() {
        val records =
            startRegularActivity<AnkiquestRecordsActivity>(
                Intent(targetContext, AnkiquestRecordsActivity::class.java).putExtra("unrelated_launch_payload", ByteArray(300_000)),
            )
        assertTrue(records.isFinishing)
        assertEquals(AnkiquestRecordsActivity.intent(targetContext).toUri(0), shadowOf(records).nextStartedActivity.toUri(0))
        val history =
            startRegularActivity<AnkiquestHistoryActivity>(
                Intent(targetContext, AnkiquestHistoryActivity::class.java).putExtra("unrelated_launch_payload", ByteArray(300_000)),
            )
        assertTrue(history.isFinishing)
        assertEquals(AnkiquestHistoryActivity.intent(targetContext).toUri(0), shadowOf(history).nextStartedActivity.toUri(0))
    }

    private fun launch(intent: Intent) =
        startActivityControllerNormallyOpenCollectionWithIntent(AnkiquestProfileActivity::class.java, intent).also {
            advanceRobolectricLooperUntil { !it.get().findViewById<View>(R.id.aq_board_loading).isVisible }
        }

    private fun awaitSections(me: AnkiquestProfileActivity) {
        advanceRobolectricLooperUntil {
            listOf(R.id.aq_board_loading, R.id.aq_me_records_loading, R.id.aq_me_history_loading).none {
                me.findViewById<View>(it).isVisible
            }
        }
        advanceRobolectricLooper()
    }

    private inline fun <reified T : View> AnkiquestProfileActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestProfileActivity.click(
        label: String,
        loadingId: Int? = null,
    ) {
        views<TextView>().first { it.text == label && it.isClickable }.performClick()
        if (loadingId == null) {
            awaitSections(this)
        } else {
            advanceRobolectricLooperUntil { !findViewById<View>(loadingId).isVisible }
        }
    }

    private fun AnkiquestProfileActivity.grid(): ReviewHeatmapGrid = views<ReviewHeatmapGrid>().single()

    private fun AnkiquestProfileActivity.layoutPhone() {
        window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        window.decorView.layout(0, 0, 320, 640)
    }

    private fun ReviewHeatmapGrid.select(day: LocalDate) {
        val cell = cellBounds(day.dayOfYear - 1)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 10, action, cell.centerX(), cell.centerY(), 0)
            onTouchEvent(event)
            event.recycle()
        }
    }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/$name.json"), name).readText(Charsets.UTF_8)
}
