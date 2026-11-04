// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.content.IntentCompat
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestConquestActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")

    /** The server's recorded card: Hill forgot "el murciélago" four times; friends not told yet. */
    private val recorded =
        AnkiquestCommunity.parseConquest(
            JSONObject(assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/conquest.json")).readText(Charsets.UTF_8)),
        )

    @Before
    fun prepareCard() {
        mockkObject(AnkiquestHomeData, AnkiquestCommunity, AnkiquestSharing)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(friends = 2)
    }

    @After
    fun clearCardMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestCommunity, AnkiquestSharing)
    }

    @Test
    fun `the owner sees the card, its label and numbers, and can tell friends once`() {
        coEvery { AnkiquestCommunity.tell(identity, AT) } returns 2
        val card = launch()
        assertEquals(card.getString(R.string.aq_profile_conquest), card.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertTrue(card.hasText("el murciélago"))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_lead_mine, 4, 4)))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_lapses, 4, "4")))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_answers, 11, "11")))
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_days, 10, "10")))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_label_shown)))

        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(told = true, friends = 2)
        card.click(card.getString(R.string.aq_conquest_tell))
        coVerify(exactly = 1) { AnkiquestCommunity.tell(identity, AT) }
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_told_count, 2, 2)))
        val told = card.views<TextView>().first { it.text.toString() == card.getString(R.string.aq_conquest_told) }
        assertFalse(told.isEnabled, "friends are told once")
    }

    @Test
    fun `the owner can stop showing the label`() {
        coEvery { AnkiquestSharing.setConquestLabel(identity, AT, null) } just runs
        val card = launch()
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } returns recorded.copy(label = null)
        card.click(card.getString(R.string.aq_conquest_label_remove))
        coVerify { AnkiquestSharing.setConquestLabel(identity, AT, null) }
        assertFalse(card.hasText("el murciélago"))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_numbers_only)))
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_label_add)))
    }

    @Test
    fun `another player's card has no owner's choices and leads to their profile`() {
        coEvery { AnkiquestCommunity.conquest(identity, "cerro", AT) } returns
            recorded.copy(user = "cerro", display = "Cerro", told = null, friends = null, label = null)
        val card = launch("cerro")
        assertTrue(card.hasText(card.resources.getQuantityString(R.plurals.aq_conquest_lead, 4, "Cerro", 4)))
        assertFalse(card.hasText(card.getString(R.string.aq_conquest_tell)))
        assertFalse(card.hasText(card.getString(R.string.aq_conquest_label_add)))
        card.click(card.getString(R.string.aq_conquest_profile, "Cerro"))
        val opened = assertNotNull(shadowOf(card).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, opened.component?.className)
        assertEquals("cerro", opened.getStringExtra(AnkiquestProfileActivity.EXTRA_USER))
    }

    @Test
    fun `sharing sends the card's words with its page on the server`() {
        val card = launch()
        card.click(card.getString(R.string.ankiquest_share))
        val chooser = assertNotNull(shadowOf(card).nextStartedActivity)
        val sent = assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        val text = sent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.startsWith(card.resources.getQuantityString(R.plurals.aq_conquest_share_label_mine, 4, "el murciélago", 4)))
        assertTrue(text.endsWith("https://anki.example.test/conquered/hill/$AT"))
    }

    @Test
    fun `a card that cannot be found says so`() {
        coEvery { AnkiquestCommunity.conquest(identity, "hill", AT) } throws AnkiquestCommunity.Missing()
        val card = launch()
        assertTrue(card.hasText(card.getString(R.string.aq_conquest_missing)))
    }

    @Test
    fun `a conquest on a profile opens its card`() {
        mockkObject(AnkiquestBoards)
        try {
            val profile =
                AnkiquestBoards.parseProfile(
                    JSONObject(
                        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/player_profile.json")).readText(Charsets.UTF_8),
                    ).put("user", "cerro")
                        .put("conquests", org.json.JSONArray().put(JSONObject().put("at", AT).put("lapses", 5).put("leech", false))),
                )
            coEvery { AnkiquestBoards.player(identity, "cerro") } returns
                AnkiquestBoards.PlayerPage(profile, own = false, wins = emptyList())
            val page = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, "cerro"))
            advanceRobolectricLooperUntil { !page.findViewById<View>(R.id.aq_board_loading).isVisible }
            advanceRobolectricLooper()
            val lapses = page.resources.getQuantityString(R.plurals.aq_profile_lapses, 5, 5)
            page
                .findViewById<View>(R.id.aq_board_content)
                .allViews
                .filterIsInstance<TextView>()
                .first { it.text.toString() == lapses }
                .let { (it.parent as View).performClick() }
            val opened = assertNotNull(shadowOf(page).nextStartedActivity)
            assertEquals(AnkiquestConquestActivity::class.java.name, opened.component?.className)
            assertEquals("cerro", opened.getStringExtra(AnkiquestConquestActivity.EXTRA_USER))
            assertEquals(AT, opened.getLongExtra(AnkiquestConquestActivity.EXTRA_AT, 0))
        } finally {
            unmockkObject(AnkiquestBoards)
        }
    }

    private fun launch(user: String = "hill"): AnkiquestConquestActivity =
        startRegularActivity<AnkiquestConquestActivity>(AnkiquestConquestActivity.intent(targetContext, user, AT)).also { it.settle() }

    private fun AnkiquestConquestActivity.settle() {
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
    }

    private inline fun <reified T : View> AnkiquestConquestActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestConquestActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestConquestActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        settle()
    }

    private companion object {
        const val AT = 1_793_097_000_000L
    }
}
