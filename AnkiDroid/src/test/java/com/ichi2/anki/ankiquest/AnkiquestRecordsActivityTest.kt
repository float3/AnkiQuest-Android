// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestRecordsActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val boards = AnkiquestRecords.parseRecords(JSONArray(example("records")))
    private val club = AnkiquestRecords.Scope("group:2", "Spanish club")

    @Before
    fun prepareRecords() {
        mockkObject(AnkiquestHomeData, AnkiquestRecords)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestRecords.records(identity, any()) } returns boards
        coEvery { AnkiquestRecords.scopes(identity) } returns emptyList()
    }

    @After
    fun clearRecordMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestRecords)
    }

    @Test
    fun `every board shows its record holder and whoever came closest`() {
        val records = launch()
        for (kind in AnkiquestRecords.Kind.entries) {
            assertTrue(records.hasText(records.getString(AnkiquestRecordsActivity.label(kind))), kind.name)
        }
        assertTrue(records.hasText(records.getString(R.string.aq_challenges_you, "Hill")), "the player's own name says so")
        assertTrue(records.hasText("Cerro"))
        assertTrue(records.hasText("101 XP"))
        assertTrue(records.hasText("−70"), "how far behind the record a chaser is")
        assertTrue(records.hasText(records.resources.getQuantityString(R.plurals.aq_records_reviews, 5, "5")))
        assertTrue(records.hasText(records.resources.getQuantityString(R.plurals.aq_records_days, 3, "3")), "streaks count days")
        assertTrue(
            records.views<View>().any {
                it.contentDescription == records.getString(R.string.aq_records_chaser, 2, "Cerro", "31 XP", "70 XP")
            },
            "each row reads as one line",
        )
        assertFalse(records.hasText(records.getString(R.string.aq_records_scope_circle)), "no choice without friends or groups")
    }

    @Test
    fun `a record holder opens their profile`() {
        val records = launch()
        val row =
            records.views<View>().first {
                it.contentDescription == records.getString(R.string.aq_records_chaser, 2, "Cerro", "31 XP", "70 XP")
            }
        assertTrue(row.performClick())
        val opened = assertNotNull(shadowOf(records).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, opened.component?.className)
        assertEquals("cerro", opened.getStringExtra(AnkiquestProfileActivity.EXTRA_USER))
    }

    @Test
    fun `boards nobody has a record on yet say so`() {
        coEvery { AnkiquestRecords.records(identity, any()) } returns boards.map { it.copy(holders = emptyList()) }
        val records = launch()
        assertTrue(records.hasText(records.getString(R.string.aq_records_empty)))
        assertFalse(records.hasText(records.getString(R.string.aq_profile_best_hour)))
    }

    @Test
    fun `the boards can show everyone or one group`() {
        coEvery { AnkiquestRecords.scopes(identity) } returns
            listOf(AnkiquestRecords.Scope.CIRCLE, AnkiquestRecords.Scope.GLOBAL, club)
        val records = launch()
        assertTrue(records.hasText(records.getString(R.string.aq_records_scope_circle)))
        records.click("Spanish club")
        coVerify { AnkiquestRecords.records(identity, club) }
        records.click(records.getString(R.string.aq_records_scope_global))
        coVerify { AnkiquestRecords.records(identity, AnkiquestRecords.Scope.GLOBAL) }
        records.click(records.getString(R.string.aq_records_scope_circle))
        coVerify(exactly = 1) { AnkiquestRecords.scopes(identity) }
    }

    @Test
    fun `a group the player left falls back to the default boards`() {
        coEvery { AnkiquestRecords.scopes(identity) } returns
            listOf(AnkiquestRecords.Scope.CIRCLE, AnkiquestRecords.Scope.GLOBAL, club)
        coEvery { AnkiquestRecords.records(identity, club) } throws AnkiquestHttpException(403)
        val records = launch()
        records.click("Spanish club")
        assertFalse(records.hasText(records.getString(R.string.aq_home_auth)))
        assertTrue(records.hasText(records.getString(R.string.aq_challenges_you, "Hill")))
        coVerify { AnkiquestRecords.records(identity, null) }
    }

    @Test
    fun `boards show even when the scopes cannot be read`() {
        coEvery { AnkiquestRecords.scopes(identity) } throws IOException("offline")
        val records = launch()
        assertTrue(records.hasText("Cerro"))
        assertFalse(records.hasText(records.getString(R.string.aq_challenges_offline)))
    }

    @Test
    fun `offline and refused boards say what to do`() {
        coEvery { AnkiquestRecords.records(identity, any()) } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_challenges_offline)))

        coEvery { AnkiquestRecords.records(identity, any()) } throws AnkiquestHttpException(401)
        val refused = launch()
        assertTrue(refused.hasText(refused.getString(R.string.aq_home_auth)))
        refused.click(refused.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(refused).nextStartedActivity.component?.className)
    }

    @Test
    fun `without an account the records ask to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val records = launch()
        assertTrue(records.hasText(records.getString(R.string.aq_records_connect)))
        coVerify(exactly = 0) { AnkiquestRecords.records(any(), any()) }
    }

    @Test
    fun `a server without the boards opens the records page on the website`() {
        coEvery { AnkiquestRecords.records(identity, any()) } throws AnkiquestHttpException(404)
        val records = startRegularActivity<AnkiquestRecordsActivity>(AnkiquestRecordsActivity.intent(targetContext))
        advanceRobolectricLooperUntil { records.isFinishing }
        val opened = assertNotNull(shadowOf(records).nextStartedActivity)
        assertEquals(AnkiquestActivity::class.java.name, opened.component?.className)
        assertEquals(AnkiquestNavigation.RECORDS_PATH, opened.getStringExtra(AnkiquestActivity.EXTRA_PATH))
    }

    private fun launch(): AnkiquestRecordsActivity =
        startRegularActivity<AnkiquestRecordsActivity>(AnkiquestRecordsActivity.intent(targetContext))
            .also { records -> advanceRobolectricLooperUntil { !records.findViewById<View>(R.id.aq_records_loading).isVisible } }

    private inline fun <reified T : View> AnkiquestRecordsActivity.views(): List<T> =
        findViewById<View>(R.id.aq_records_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestRecordsActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestRecordsActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_records_loading).isVisible }
        advanceRobolectricLooper()
    }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/$name.json"), name).readText(Charsets.UTF_8)
}
