// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestCompanionTest : RobolectricTest() {
    private fun account(user: String): HomeAccount {
        val preferences = AnkiDroidApp.sharedPrefs()
        preferences.edit(commit = true) {
            putString(Ankiquest.PREF_URL, "https://example.com")
            putString(Ankiquest.PREF_USER, user)
            putString(Ankiquest.PREF_TOKEN, "token-$user")
        }
        return requireNotNull(AnkiquestHomeData.account())
    }

    @Test
    fun `choice follows only the current account and maps all mascot poses`() {
        val alice = account("alice")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertTrue(AnkiquestCompanion.remember(alice, "ankilope"))
        assertEquals("ankilope", AnkiquestCompanion.selected())
        assertEquals(R.drawable.ankilope_study, AnkiquestCompanion.resource(R.drawable.aki_freeze))
        assertEquals(R.drawable.ankilope_celebrate, AnkiquestCompanion.resource(R.drawable.aki_winner))
        assertEquals(R.drawable.ankilope_celebrate, AnkiquestCompanion.resource(R.drawable.aki_place_1))
        assertEquals(R.drawable.ankilope_study, AnkiquestCompanion.resource(R.drawable.aki_streak_milestone))
        assertEquals(R.drawable.ankilope_welcome, AnkiquestCompanion.resource(R.drawable.aki_challenge))

        val bob = account("bob")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertFalse(AnkiquestCompanion.remember(alice, "none"))
        assertTrue(AnkiquestCompanion.remember(bob, "none"))
        assertFalse(AnkiquestCompanion.visible())

        account("alice")
        assertEquals("ankilope", AnkiquestCompanion.selected())
    }
}
