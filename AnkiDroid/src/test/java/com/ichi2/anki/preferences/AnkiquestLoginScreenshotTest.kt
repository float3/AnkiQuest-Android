// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.widget.EditText
import androidx.core.content.edit
import androidx.preference.Preference
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings
import com.ichi2.anki.ankiquest.AnkiquestPoll
import com.ichi2.anki.ankiquest.HomeAccount
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnkiquestLoginScreenshotTest : ScreenshotTest() {
    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) RuntimeEnvironment.setQualifiers("w320dp-h640dp-port-mdpi")
    }

    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://ankiquest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "screenshot-token")
        }
        AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))
        mockkObject(AnkiquestPoll)
        justRun { AnkiquestPoll.refreshNow(any()) }
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestPoll)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun username() = captureLogin("username")

    @Test
    fun largeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureLogin("username_200percent")
    }

    private fun captureLogin(name: String) {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestSettingsFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        advanceRobolectricLooper()
        val preferences = controller.get().fragment as PreferencesFragment
        val fragment = preferences.childFragmentManager.findFragmentById(R.id.settings_container) as AnkiquestSettingsFragment
        assertNotNull(fragment.findPreference<Preference>(targetContext.getString(R.string.ankiquest_sign_in_key))).performClick()
        advanceRobolectricLooper()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog())
        val user = dialog.findViewById<EditText>(R.id.ankiquest_sign_in_user)
        user.isCursorVisible = false
        assertEquals("member", user.text.toString())
        assertEquals("", dialog.findViewById<EditText>(R.id.ankiquest_sign_in_password).text.toString())
        captureScreen(name)
    }
}
