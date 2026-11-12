// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings
import com.ichi2.anki.ankiquest.AnkiquestCommunity
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminder
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminders
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestNotifier
import com.ichi2.anki.ankiquest.AnkiquestPoll
import com.ichi2.anki.ankiquest.AnkiquestSharing
import com.ichi2.anki.ankiquest.AnkiquestSubscriptions
import com.ichi2.anki.ankiquest.AnkiquestWidgetSettings
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.IncomingSubscriptions
import com.ichi2.anki.ankiquest.SubscriptionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

@RunWith(AndroidJUnit4::class)
class AnkiquestSettingsFragmentTest : RobolectricTest() {
    private val reminders = Reminders(emptySet(), hour = 19, quietStart = 22, quietEnd = 8, dailyLimit = 2)
    private val sharing = AnkiquestSharing.Settings(mapOf("daily_goal" to "ask", "level" to "auto"), dailyCap = 3)
    private lateinit var repository: SubscriptionRepository

    @Before
    fun prepare() {
        signIn()
        repository = mockk()
        mockkObject(AnkiquestCommunity, AnkiquestSharing, AnkiquestSubscriptions, AnkiquestPoll, Ankiquest, AnkiquestHomeData)
        every { AnkiquestSubscriptions.repository } returns repository
        justRun { AnkiquestPoll.refreshNow(any()) }
        coEvery { repository.load(any()) } returns IncomingSubscriptions(true, emptySet(), emptyList())
        coEvery { AnkiquestCommunity.reminders(any()) } returns reminders
        coEvery { AnkiquestSharing.settings(any()) } returns sharing
        coEvery { Ankiquest.notificationSwitches() } returns JSONObject().put(Ankiquest.NUDGES, false).put(Ankiquest.CELEBRATIONS, true)
        coEvery { AnkiquestHomeData.companion(any()) } returns "aki"
    }

    @After
    fun clearMocks() {
        unmockkObject(AnkiquestCommunity, AnkiquestSharing, AnkiquestSubscriptions, AnkiquestPoll, Ankiquest, AnkiquestHomeData)
    }

    private fun signIn() =
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }

    @Test
    fun `a phone that is not signed in only offers signing in and the app's own settings`() {
        AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }
        val fragment = open()
        val signIn = fragment.preference<Preference>(R.string.ankiquest_sign_in_key)
        assertEquals(targetContext.getString(R.string.ankiquest_sign_in_title), signIn.title)
        assertTrue(signIn.isSelectable)
        for (hidden in listOf(R.string.ankiquest_avatar_key, R.string.ankiquest_companion_key, R.string.ankiquest_sign_out_key)) {
            assertFalse(fragment.preference<Preference>(hidden).isVisible)
        }
        assertFalse(fragment.preference<PreferenceCategory>(R.string.ankiquest_notify_group_key).isVisible)
        assertFalse(fragment.preference<PreferenceCategory>(R.string.ankiquest_sharing_group_key).isVisible)
        assertTrue(fragment.preference<PreferenceCategory>(R.string.ankiquest_app_group_key).isVisible)
        assertTrue(fragment.preference<Preference>(R.string.ankiquest_sign_in_screen_key).isVisible)
        coVerify(exactly = 0) { AnkiquestCommunity.reminders(any()) }
    }

    @Test
    fun `signing back in prefills the previous username but no password`() {
        AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))
        val fragment = open()
        fragment.preference<Preference>(R.string.ankiquest_sign_in_key).performClick()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog())

        assertEquals("https://quest.example", dialog.findViewById<EditText>(R.id.ankiquest_sign_in_server).text.toString())
        val user = dialog.findViewById<EditText>(R.id.ankiquest_sign_in_user)
        assertEquals("member", user.text.toString())
        assertEquals("", dialog.findViewById<EditText>(R.id.ankiquest_sign_in_password).text.toString())
        user.setText("another")
        assertEquals("another", user.text.toString())
        assertNull(AnkiDroidApp.sharedPrefs().getString(Ankiquest.PREF_TOKEN, null))
    }

    @Test
    fun `changing the server refreshes the suggested username without replacing a typed name`() {
        AnkiquestAccountSettings.signOut(assertNotNull(HomeAccount.current()))
        val fragment = open()
        fragment.preference<Preference>(R.string.ankiquest_sign_in_key).performClick()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog())
        val server = dialog.findViewById<EditText>(R.id.ankiquest_sign_in_server)
        val user = dialog.findViewById<EditText>(R.id.ankiquest_sign_in_user)
        assertEquals("member", user.text.toString())

        server.setText("https://different.example")
        assertEquals("", user.text.toString())
        server.setText("https://quest.example/")
        assertEquals("member", user.text.toString())
        user.setText("typed-name")
        server.setText("https://different.example")
        assertEquals("typed-name", user.text.toString())
    }

    @Test
    fun `a signed-in player sees their name and every group with the server's choices`() {
        AnkiquestNotifier.setStreakReminder(false)
        val fragment = open()
        assertEquals(
            targetContext.getString(R.string.ankiquest_signed_in_as, "member"),
            fragment.preference<Preference>(R.string.ankiquest_sign_in_key).title,
        )
        assertTrue(fragment.preference<PreferenceCategory>(R.string.ankiquest_notify_group_key).isVisible)
        assertTrue(fragment.switch(R.string.ankiquest_notify_friends_key).isChecked)
        assertFalse(fragment.switch(R.string.ankiquest_notify_reminders_key).isChecked, "no reminder is on")
        assertTrue(fragment.switch(R.string.ankiquest_notify_moments_key).isChecked, "celebrations are on")
        val cue = fragment.switch(R.string.ankiquest_streak_cue_key)
        assertTrue(cue.isChecked)
        cue.isChecked = false
        assertFalse(AnkiDroidApp.sharedPrefs().getBoolean(targetContext.getString(R.string.ankiquest_streak_cue_key), true))
        assertTrue(fragment.switch(R.string.ankiquest_share_moments_key).isChecked)
        val time = fragment.preference<ListPreference>(R.string.ankiquest_reminder_time_key)
        assertEquals("19", time.value)
        assertTrue(time.isEnabled)
        assertTrue(
            fragment
                .preference<Preference>(R.string.ankiquest_quiet_hours_key)
                .summary
                .toString()
                .isNotEmpty(),
        )
    }

    @Test
    fun `turning reminders on turns on every server reminder and the streak warning`() {
        AnkiquestNotifier.setStreakReminder(false)
        val all = reminders.copy(enabled = Reminder.entries.toSet())
        coEvery { AnkiquestCommunity.saveReminders(any(), all) } returns all
        val fragment = open()
        val switch = fragment.switch(R.string.ankiquest_notify_reminders_key)
        switch.performClick()
        idle()
        assertTrue(switch.isChecked)
        assertTrue(AnkiquestNotifier.streakReminder())
        coVerify { AnkiquestCommunity.saveReminders(any(), all) }
    }

    @Test
    @Config(sdk = [36])
    fun `notification permission waits for an alert to be enabled`() {
        shadowOf(targetContext as Application).denyPermissions(POST_NOTIFICATIONS_PERMISSION)
        AnkiquestNotifier.setStreakReminder(false)
        val enabled = reminders.copy(enabled = Reminder.entries.toSet())
        coEvery { AnkiquestCommunity.saveReminders(any(), enabled) } returns enabled

        val fragment = open()
        val host = shadowOf(fragment.requireActivity())
        assertNull(host.lastRequestedPermission)

        fragment.switch(R.string.ankiquest_notify_reminders_key).performClick()
        idle()
        val request = assertNotNull(host.lastRequestedPermission)
        assertEquals(listOf(POST_NOTIFICATIONS_PERMISSION), request.requestedPermissions.toList())
    }

    @Test
    fun `choosing a reminder time saves it on top of the server's latest reminders`() {
        val later = reminders.copy(hour = 7)
        coEvery { AnkiquestCommunity.saveReminders(any(), later) } returns later
        val fragment = open()
        fragment.preference<ListPreference>(R.string.ankiquest_reminder_time_key).callChangeListener("7")
        idle()
        assertEquals("7", fragment.preference<ListPreference>(R.string.ankiquest_reminder_time_key).value)
        coVerify { AnkiquestCommunity.saveReminders(any(), later) }
    }

    @Test
    fun `turning sharing off keeps every kind of win private`() {
        val private = mapOf("daily_goal" to "private", "level" to "private")
        coEvery { AnkiquestSharing.saveSettings(any(), private, null) } returns sharing.copy(policies = private)
        val fragment = open()
        val switch = fragment.switch(R.string.ankiquest_share_moments_key)
        switch.performClick()
        idle()
        assertFalse(switch.isChecked)
        coVerify { AnkiquestSharing.saveSettings(any(), private, null) }
    }

    @Test
    fun `global listing reflects the server and saves only that choice`() {
        val field = AnkiquestWidgetSettings.GLOBAL_SETTING
        coEvery { Ankiquest.notificationSwitches() } returns JSONObject().put(field, true)
        coEvery { Ankiquest.setNotificationSwitch(field, false) } returns JSONObject().put(field, false)

        val switch = assertNotNull(open().findPreference<SwitchPreferenceCompat>("ankiquestShowGlobal"))
        assertTrue(switch.isVisible)
        assertTrue(switch.isChecked)
        switch.performClick()
        idle()

        assertFalse(switch.isChecked)
        coVerify(exactly = 1) { Ankiquest.setNotificationSwitch(field, false) }
    }

    @Test
    fun `global listing is hidden on an older server`() {
        val switch = assertNotNull(open().findPreference<SwitchPreferenceCompat>("ankiquestShowGlobal"))
        assertFalse(switch.isVisible)
    }

    @Test
    fun `switches read the new account while the old read is suspended`() {
        AnkiquestNotifier.setRankAlerts(false)
        val prefs = AnkiDroidApp.sharedPrefs()
        val old = CompletableDeferred<JSONObject>()
        val field = AnkiquestWidgetSettings.GLOBAL_SETTING
        val current = JSONObject().put(Ankiquest.NUDGES, false).put(Ankiquest.CELEBRATIONS, false).put(field, true)
        coEvery { Ankiquest.notificationSwitches() } coAnswers {
            if (prefs.getString(Ankiquest.PREF_USER, null) == "member") old.await() else current
        }
        try {
            val fragment = open()
            val moments = fragment.switch(R.string.ankiquest_notify_moments_key)
            val listing = fragment.switch(R.string.ankiquest_show_global_key)
            assertFalse(moments.isEnabled)

            prefs.edit {
                putString(Ankiquest.PREF_USER, "other")
                putString(Ankiquest.PREF_TOKEN, "other-token")
            }
            fragment.onResume()
            idle()
            assertTrue(moments.isEnabled)
            assertFalse(moments.isChecked)
            assertTrue(listing.isChecked)

            old.complete(JSONObject().put(Ankiquest.NUDGES, true).put(Ankiquest.CELEBRATIONS, true).put(field, false))
            idle()
            assertFalse(moments.isChecked)
            assertTrue(listing.isChecked)
        } finally {
            old.cancel()
        }
    }

    @Test
    fun `turning friends off pauses incoming deck alerts`() {
        coEvery { repository.setEnabled(any(), false) } returns IncomingSubscriptions(false, emptySet(), emptyList())
        val fragment = open()
        fragment.switch(R.string.ankiquest_notify_friends_key).performClick()
        idle()
        assertFalse(fragment.switch(R.string.ankiquest_notify_friends_key).isChecked)
        coVerify { repository.setEnabled(any(), false) }
    }

    @Test
    fun `the notification check shows only when alerts cannot get through, and leads to the fix`() {
        val notifications = shadowOf(targetContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        notifications.setNotificationsEnabled(true)
        shadowOf(targetContext as Application).grantPermissions(POST_NOTIFICATIONS_PERMISSION)
        val fragment = open()
        val health = fragment.preference<Preference>(R.string.ankiquest_health_key)
        assertFalse(health.isVisible, "nothing to fix")

        notifications.setNotificationsEnabled(false)
        fragment.onResume()
        assertTrue(health.isVisible)
        health.performClick()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertTrue(
            assertNotNull(dialog.findViewById<TextView>(android.R.id.message))
                .text
                .contains(targetContext.getString(R.string.ankiquest_health_last_sync, "")),
        )
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown, "no test alert any more")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val settings = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertEquals(3, assertNotNull(settings.listView).adapter.count)
    }

    private fun open(): AnkiquestSettingsFragment {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestSettingsFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        idle()
        val fragment = (controller.get().fragment as PreferencesFragment).childFragmentManager.findFragmentById(R.id.settings_container)
        return fragment as AnkiquestSettingsFragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : Preference> AnkiquestSettingsFragment.preference(key: Int): T = findPreference(targetContext.getString(key))!!

    private fun AnkiquestSettingsFragment.switch(key: Int): SwitchPreferenceCompat = preference(key)
}
