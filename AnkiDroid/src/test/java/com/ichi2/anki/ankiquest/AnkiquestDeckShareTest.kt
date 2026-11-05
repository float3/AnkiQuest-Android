// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestDeckShareTest : RobolectricTest() {
    private fun settings(vararg decks: JSONObject) =
        JSONObject()
            .put("decks", JSONArray(decks.toList()))
            .put(
                "recipients",
                JSONArray()
                    .put(JSONObject().put("user", "cerro").put("display", "Cerro"))
                    .put(JSONObject().put("user", "ana")),
            )

    private fun deck(
        id: Long,
        enabled: Boolean,
        vararg recipients: String,
    ) = JSONObject()
        .put("id", id.toString())
        .put("enabled", enabled)
        .put("recipients", JSONArray(recipients.toList()))

    @Test
    fun `a finished deck asks about everyone in the player's groups`() {
        val prompt = AnkiquestDeckShare.prompt(7, "Japanese::Kanji", settings(deck(7, false), deck(8, true, "cerro")))
        assertEquals(
            AnkiquestDeckShare.Prompt(
                7,
                "Japanese::Kanji",
                listOf(AnkiquestDeckShare.Recipient("cerro", "Cerro"), AnkiquestDeckShare.Recipient("ana", "ana")),
            ),
            prompt,
        )
    }

    @Test
    fun `no question when the deck already tells its people or there is nobody to tell`() {
        assertNull(AnkiquestDeckShare.prompt(7, "Japanese", settings(deck(7, true, "cerro"))))
        assertNull(AnkiquestDeckShare.prompt(7, "Japanese", JSONObject().put("decks", JSONArray()).put("recipients", JSONArray())))
        assertEquals(
            2,
            AnkiquestDeckShare.prompt(7, "Japanese", settings(deck(7, true)))?.recipients?.size,
            "sharing switched on without anyone chosen announces nothing, so it still asks",
        )
    }

    @Test
    fun `each deck is asked about once a day, and only today's answers are kept`() {
        val prefs = targetContext.sharedPrefs()
        assertTrue(AnkiquestDeckShare.firstAsk(prefs, "account", 7, 100))
        assertFalse(AnkiquestDeckShare.firstAsk(prefs, "account", 7, 100))
        assertTrue(AnkiquestDeckShare.firstAsk(prefs, "account", 8, 100))
        assertTrue(AnkiquestDeckShare.firstAsk(prefs, "other account", 7, 100))
        assertTrue(AnkiquestDeckShare.firstAsk(prefs, "account", 7, 101))
        assertEquals(setOf("account:101:7"), prefs.getStringSet("ankiquestDeckShareAsked", null))
    }

    @Test
    fun `the question can be switched off`() {
        val prefs = targetContext.sharedPrefs()
        assertTrue(AnkiquestDeckShare.enabled(prefs))
        prefs.edit { putBoolean(AnkiquestDeckShare.PREF_ASK, false) }
        assertFalse(AnkiquestDeckShare.enabled(prefs))
    }

    @Test
    fun `a deck's menu ticks the people already told about it`() {
        val settings = settings(deck(7, true, "cerro"), deck(8, false, "ana"))
        assertEquals(setOf("cerro"), AnkiquestDeckShare.told(7, settings))
        assertEquals(emptySet(), AnkiquestDeckShare.told(8, settings), "a deck not shared tells nobody")
        assertEquals(emptySet(), AnkiquestDeckShare.told(9, settings))
    }
}
