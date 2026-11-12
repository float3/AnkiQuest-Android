// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.Preference
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.preferences.PreferencesFragment
import com.ichi2.anki.withDeckPicker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Both sharing entry points return to their screen with a readable Undo action. */
class AnkiquestDeckUndoScreenshotTest : ScreenshotTest() {
    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepareSharing() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "screenshot-token")
        }
        mockkObject(Ankiquest, AnkiquestPoll, AnkiquestHomeData, AnkiquestCommunity, AnkiquestSharing, AnkiquestSubscriptions)
        justRun { Ankiquest.onActivityResumed(any()) }
        justRun { AnkiquestPoll.refreshNow(any()) }
        coEvery { Ankiquest.deckNotificationSettings(any()) } returns
            JSONObject(
                """{"decks":[{"id":"1","name":"Default","enabled":true,"recipients":["ana"]}],
                   "recipients":[{"user":"ana","display":"Ana"}]}""",
            )
        coEvery { Ankiquest.saveDeckNotificationSettings(any(), any(), any(), any()) } returns JSONObject()
        coEvery { Ankiquest.notificationSwitches() } returns JSONObject().put(Ankiquest.NUDGES, false).put(Ankiquest.CELEBRATIONS, true)
        coEvery { AnkiquestHomeData.companion(any()) } returns "aki"
        coEvery { AnkiquestCommunity.reminders(any()) } returns AnkiquestCommunity.Reminders(emptySet(), 19, 22, 8, 2)
        coEvery { AnkiquestSharing.settings(any()) } returns AnkiquestSharing.Settings(mapOf("daily_goal" to "ask"), 3)
        val repository = mockk<SubscriptionRepository>()
        every { AnkiquestSubscriptions.repository } returns repository
        coEvery { repository.load(any()) } returns IncomingSubscriptions(true, emptySet(), emptyList())
    }

    @After
    fun cleanupSharing() {
        unmockkObject(Ankiquest, AnkiquestPoll, AnkiquestHomeData, AnkiquestCommunity, AnkiquestSharing, AnkiquestSubscriptions)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun deck() = captureDeck("deck_undo")

    @Test
    fun deckLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureDeck("deck_undo_200percent")
    }

    @Test
    fun bulk() = captureBulk("bulk_undo")

    @Test
    fun bulkLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        captureBulk("bulk_undo_200percent")
    }

    private fun captureDeck(name: String) =
        withDeckPicker(deckCount = 1) { screen ->
            AnkiquestDeckShare.choose(screen, 1)
            advanceRobolectricLooperUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            captureUndo(screen, name)
        }

    private fun captureBulk(name: String) {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestSettingsFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        shadowOf(Looper.getMainLooper()).idle()
        val preferences = controller.get().fragment as PreferencesFragment
        val fragment = preferences.childFragmentManager.findFragmentById(R.id.settings_container) as AnkiquestSettingsFragment
        assertNotNull(fragment.findPreference<Preference>(targetContext.getString(R.string.ankiquest_moments_decks_key))).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val decks = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        decks.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val people = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        people.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        captureUndo(controller.get(), name)
    }

    private fun captureUndo(
        screen: AnkiActivity,
        name: String,
    ) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val action = assertNotNull(screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_action))
        assertEquals(screen.getString(CommonString.undo), action.text.toString())
        assertTrue(action.isShown)
        captureScreen(name)
    }
}
