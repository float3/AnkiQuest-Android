// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.contextmenu.DeckPickerMenuContentProvider
import com.ichi2.anki.dialogs.DeckPickerContextMenu.DeckPickerContextMenuOption
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestSettingsTest : RobolectricTest() {
    @Before
    fun configureAccount() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example/anki")
            putString(Ankiquest.PREF_USER, "cerro")
            putString(Ankiquest.PREF_TOKEN, "saved-test-token")
        }
    }

    @Test
    fun `the account comes from one snapshot when settings change while it is read`() {
        val preferences = AnkiDroidApp.sharedPrefs()
        val previousOverride = SharedPreferencesTestHook.override
        val changingPreferences = mockk<SharedPreferences>()
        every { changingPreferences.getString(any(), any()) } answers { preferences.getString(firstArg(), secondArg()) }
        every { changingPreferences.all } answers {
            val captured = preferences.all
            preferences.edit {
                putString(Ankiquest.PREF_URL, "https://different.example")
                putString(Ankiquest.PREF_USER, "hill")
                putString(Ankiquest.PREF_TOKEN, "different-server-secret")
            }
            captured
        }
        SharedPreferencesTestHook.override = changingPreferences
        try {
            val account = assertNotNull(HomeAccount.current())
            val consistent =
                (account.server == "https://quest.example/anki" && account.user == "cerro" && account.token == "saved-test-token") ||
                    (account.server == "https://different.example" && account.user == "hill" && account.token == "different-server-secret")
            assertTrue(consistent, "server, player and token never mix two accounts: $account")
        } finally {
            SharedPreferencesTestHook.override = previousOverride
        }
    }

    @Test
    fun `the privacy notice is the configured server's page`() {
        assertEquals("https://quest.example/anki/privacy", Ankiquest.privacyUrl())
    }

    @Test
    fun `sharing switches every kind of win off, and back on to the server's defaults`() {
        val settings =
            AnkiquestSharing.Settings(
                mapOf("daily_goal" to "auto", "quest" to "ask", "streak" to "private", "future_kind" to "auto"),
                dailyCap = 3,
            )
        assertTrue(settings.shared)
        val off = settings.switched(false)
        assertEquals(setOf("private"), off.values.toSet())
        assertFalse(settings.copy(policies = off).shared)
        assertEquals(
            mapOf("daily_goal" to "ask", "quest" to "private", "streak" to "ask", "future_kind" to "ask"),
            settings.switched(true),
        )
    }

    @Test
    fun `the reminders switch turns the phone's streak warning off and back on without losing chosen hours`() {
        val prefs = AnkiDroidApp.sharedPrefs()
        prefs.edit { remove(AnkiquestNotifier.PREF_STREAK_HOURS) }
        assertTrue(AnkiquestNotifier.streakReminder(prefs), "on for a new player")
        prefs.edit { putString(AnkiquestNotifier.PREF_STREAK_HOURS, "4") }
        AnkiquestNotifier.setStreakReminder(true, prefs)
        assertEquals("4", prefs.getString(AnkiquestNotifier.PREF_STREAK_HOURS, null), "already on: the chosen hours stay")
        AnkiquestNotifier.setStreakReminder(false, prefs)
        assertFalse(AnkiquestNotifier.streakReminder(prefs))
        AnkiquestNotifier.setStreakReminder(true, prefs)
        assertEquals(AnkiquestNotifier.DEFAULT_STREAK_HOURS, prefs.getString(AnkiquestNotifier.PREF_STREAK_HOURS, null))
    }

    @Test
    fun `leaderboard alerts are on until the moments switch turns them off`() {
        val prefs = AnkiDroidApp.sharedPrefs()
        prefs.edit { remove(AnkiquestNotifier.PREF_RANK) }
        assertTrue(AnkiquestNotifier.rankAlerts(prefs))
        AnkiquestNotifier.setRankAlerts(false, prefs)
        assertFalse(AnkiquestNotifier.rankAlerts(prefs))
    }

    @Test
    fun `a deck's menu offers telling friends only to a signed-in player`() {
        AnkiDroidApp.sharedPrefs().edit {
            remove(Ankiquest.PREF_URL)
            remove(Ankiquest.PREF_USER)
            remove(Ankiquest.PREF_TOKEN)
        }
        assertFalse(AnkiquestDeckShare.available())
        assertFalse(
            DeckPickerContextMenuOption.SHARE_WITH_FRIENDS in
                DeckPickerMenuContentProvider.createOptionsList(isDynamic = false, hasBuriedInDeck = false),
        )
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        assertTrue(AnkiquestDeckShare.available())
        assertTrue(
            DeckPickerContextMenuOption.SHARE_WITH_FRIENDS in
                DeckPickerMenuContentProvider.createOptionsList(isDynamic = false, hasBuriedInDeck = false),
        )
        assertFalse(
            DeckPickerContextMenuOption.SHARE_WITH_FRIENDS in
                DeckPickerMenuContentProvider.createOptionsList(isDynamic = true, hasBuriedInDeck = false),
            "filtered decks are not shared",
        )
    }
}
