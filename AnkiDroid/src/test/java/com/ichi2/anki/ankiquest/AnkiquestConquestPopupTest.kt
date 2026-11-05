// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.DialogInterface
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class AnkiquestConquestPopupTest : RobolectricTest() {
    private val account = "https://anki.example.test/hill"

    private fun conquest(
        at: Long,
        lapses: Int = 4,
        leech: Boolean = false,
    ) = JSONObject()
        .put("at", at)
        .put("lapses", lapses)
        .put("answers", 11)
        .put("since", at - DAY)
        .put("leech", leech)

    private fun take(
        vararg conquests: JSONObject,
        now: Long = NOW,
    ) = AnkiquestConquestPopup.take(targetContext.sharedPrefs(), account, JSONArray(conquests.toList()), now)

    @Test
    fun `cards already there on the first look are never celebrated`() {
        assertNull(take(conquest(NOW - 1000)))
        assertNull(take(conquest(NOW - 1000)))
    }

    @Test
    fun `a newly conquered card pops up once, the newest of several, and the others stay quiet`() {
        assertNull(take(conquest(NOW - 5 * DAY)))
        assertEquals(AnkiquestConquestPopup.Fresh(NOW - 1000, 9, true), take(conquest(NOW - 1000, 9, true), conquest(NOW - 5 * DAY)))
        assertNull(take(conquest(NOW - 1000, 9, true), conquest(NOW - 5 * DAY)), "shown once")
        assertEquals(
            AnkiquestConquestPopup.Fresh(NOW - 10, 4, false),
            take(conquest(NOW - 10), conquest(NOW - 20), conquest(NOW - 1000, 9, true)),
        )
        assertNull(take(conquest(NOW - 10), conquest(NOW - 20), conquest(NOW - 1000, 9, true)), "the other new card was counted too")
    }

    @Test
    fun `a card conquered days ago, learned of only now, does not pop up`() {
        assertNull(take())
        assertNull(take(conquest(NOW - 3 * DAY)))
    }

    @Test
    fun `each account keeps its own cards`() {
        assertNull(take(conquest(NOW - 1000)))
        assertNull(AnkiquestConquestPopup.take(targetContext.sharedPrefs(), "https://anki.example.test/cerro", JSONArray(), NOW))
        assertNotNull(
            AnkiquestConquestPopup.take(
                targetContext.sharedPrefs(),
                "https://anki.example.test/cerro",
                JSONArray().put(conquest(NOW)),
                NOW,
            ),
        )
    }

    @Test
    fun `the pop-up names the card and its deck and opens the card`() {
        mockkObject(AnkiquestHomeData)
        try {
            every { AnkiquestHomeData.account(any()) } returns null
            val host = startRegularActivity<AnkiquestConquestActivity>(AnkiquestConquestActivity.intent(targetContext, "hill", 1))
            val identity = HomeAccount("https://anki.example.test", "hill", "secret")
            AnkiquestConquestPopup.show(host, identity, AnkiquestConquestPopup.Fresh(AT, 4, false), "el murciélago", "Español")
            advanceRobolectricLooper()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
            assertEquals(
                listOf(
                    host.resources.getQuantityString(R.plurals.aq_conquest_lead_mine, 4, 4),
                    host.getString(R.string.aq_conquest_popup_card, "el murciélago"),
                    host.getString(R.string.aq_conquest_deck, "Español"),
                ).joinToString("\n\n"),
                message,
            )
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            advanceRobolectricLooper()
            val opened = assertNotNull(shadowOf(host).nextStartedActivity)
            assertEquals(AnkiquestConquestActivity::class.java.name, opened.component?.className)
            assertEquals("hill", opened.getStringExtra(AnkiquestConquestActivity.EXTRA_USER))
            assertEquals(AT, opened.getLongExtra(AnkiquestConquestActivity.EXTRA_AT, 0))
        } finally {
            unmockkObject(AnkiquestHomeData)
        }
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_793_097_000_000L
        const val AT = 1_793_097_000_000L
    }
}
