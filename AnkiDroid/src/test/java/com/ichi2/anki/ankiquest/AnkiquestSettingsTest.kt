// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals
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
        val previousOverride = AnkiDroidApp.sharedPreferencesTestingOverride
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
        AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
        try {
            val account = assertNotNull(HomeAccount.current())
            val consistent =
                (account.server == "https://quest.example/anki" && account.user == "cerro" && account.token == "saved-test-token") ||
                    (account.server == "https://different.example" && account.user == "hill" && account.token == "different-server-secret")
            assertTrue(consistent, "server, player and token never mix two accounts: $account")
        } finally {
            AnkiDroidApp.sharedPreferencesTestingOverride = previousOverride
        }
    }

    @Test
    fun `the privacy notice is the configured server's page`() {
        assertEquals("https://quest.example/anki/privacy", Ankiquest.privacyUrl())
    }

    @Test
    fun `streak protection is available in native settings`() {
        val keys = mutableListOf<String>()
        targetContext.resources.getXml(R.xml.preferences_ankiquest_study).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                val key = xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "key", 0)
                if (key != 0) keys.add(targetContext.getString(key))
            }
        }
        assertTrue("ankiquestStreakProtection" in keys, "The settings page must include the leaderboard's streak protection setting")
    }
}
