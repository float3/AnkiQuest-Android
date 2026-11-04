// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.os.Looper
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
import com.ichi2.anki.ankiquest.AnkiquestCommunity
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminder
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminders
import com.ichi2.anki.ankiquest.AnkiquestHttpException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestRemindersFragmentTest : RobolectricTest() {
    private val saved =
        Reminders(setOf(Reminder.GENTLE_DAILY, Reminder.WEEKLY_RECAP), hour = 19, quietStart = 22, quietEnd = 8, dailyLimit = 2)

    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        mockkObject(AnkiquestCommunity)
        coEvery { AnkiquestCommunity.reminders(any()) } returns saved
    }

    @After
    fun clearMocks() {
        unmockkObject(AnkiquestCommunity)
    }

    @Test
    fun `the screen shows the server's reminders and timing`() {
        val fragment = open()
        assertTrue(fragment.switch(Reminder.GENTLE_DAILY).isChecked)
        assertTrue(fragment.switch(Reminder.WEEKLY_RECAP).isChecked)
        assertFalse(fragment.switch(Reminder.URGENT_STREAK).isChecked)
        assertTrue(Reminder.entries.all { !fragment.switch(it).isPersistent && fragment.switch(it).isEnabled })
        assertEquals("19", fragment.list(AnkiquestRemindersFragment.HOUR_KEY).value)
        assertEquals("8", fragment.list(AnkiquestRemindersFragment.QUIET_END_KEY).value)
        assertEquals(
            targetContext.resources.getQuantityString(R.plurals.aq_reminders_limit_value, 2, 2),
            fragment.list(AnkiquestRemindersFragment.LIMIT_KEY).summary,
        )
        assertEquals(24, fragment.list(AnkiquestRemindersFragment.HOUR_KEY).entries.size)
        assertEquals(targetContext.getString(R.string.aq_reminders_hint), fragment.status().summary)
    }

    @Test
    fun `a switch saves every setting and changes only once the server confirms`() {
        val wanted = saved.copy(enabled = saved.enabled + Reminder.URGENT_STREAK)
        val pending = CompletableDeferred<Reminders>()
        coEvery { AnkiquestCommunity.saveReminders(any(), wanted) } coAnswers { pending.await() }
        val fragment = open()
        fragment.switch(Reminder.URGENT_STREAK).performClick()
        idle()
        assertFalse(fragment.switch(Reminder.URGENT_STREAK).isChecked)
        assertFalse(fragment.switch(Reminder.GENTLE_DAILY).isEnabled, "nothing else changes while saving")
        pending.complete(wanted)
        idle()
        assertTrue(fragment.switch(Reminder.URGENT_STREAK).isChecked)
        assertTrue(fragment.switch(Reminder.GENTLE_DAILY).isEnabled)
        assertEquals(targetContext.getString(R.string.aq_reminders_saved), fragment.status().summary)
        coVerify(exactly = 1) { AnkiquestCommunity.saveReminders(any(), wanted) }
    }

    @Test
    fun `choosing an hour saves it`() {
        val wanted = saved.copy(hour = 7)
        coEvery { AnkiquestCommunity.saveReminders(any(), wanted) } returns wanted
        val fragment = open()
        fragment.list(AnkiquestRemindersFragment.HOUR_KEY).callChangeListener("7")
        idle()
        assertEquals("7", fragment.list(AnkiquestRemindersFragment.HOUR_KEY).value)
        coVerify { AnkiquestCommunity.saveReminders(any(), wanted) }
    }

    @Test
    fun `the same quiet start and end say quiet hours are off`() {
        coEvery { AnkiquestCommunity.reminders(any()) } returns saved.copy(quietStart = 22, quietEnd = 22)
        val fragment = open()
        assertEquals(
            targetContext.getString(R.string.aq_reminders_quiet_off),
            fragment.list(AnkiquestRemindersFragment.QUIET_END_KEY).summary,
        )
    }

    @Test
    fun `a failed save hides the stale choices until a reload confirms them`() {
        coEvery { AnkiquestCommunity.saveReminders(any(), any()) } throws java.io.IOException("offline")
        val fragment = open()
        fragment.switch(Reminder.MILESTONE).performClick()
        idle()
        assertFalse(fragment.category(AnkiquestRemindersFragment.KINDS_KEY).isVisible)
        assertEquals(targetContext.getString(R.string.aq_reminders_error), fragment.status().summary)
        val retry = fragment.findPreference<Preference>(AnkiquestRemindersFragment.RETRY_KEY)!!
        assertTrue(retry.isVisible)
        retry.performClick()
        idle()
        assertTrue(fragment.category(AnkiquestRemindersFragment.KINDS_KEY).isVisible)
        assertTrue(fragment.switch(Reminder.GENTLE_DAILY).isChecked)
        coVerify(exactly = 2) { AnkiquestCommunity.reminders(any()) }
    }

    @Test
    fun `a refused account and a server without reminders say so`() {
        coEvery { AnkiquestCommunity.reminders(any()) } throws AnkiquestHttpException(401)
        assertEquals(targetContext.getString(R.string.aq_reminders_auth), open().status().summary)
        coEvery { AnkiquestCommunity.reminders(any()) } throws AnkiquestHttpException(404)
        assertEquals(targetContext.getString(R.string.aq_reminders_unsupported), open().status().summary)
    }

    @Test
    fun `without a token the screen asks to sign in`() {
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "") }
        val fragment = open()
        assertEquals(targetContext.getString(R.string.aq_reminders_configure), fragment.status().summary)
        assertFalse(fragment.category(AnkiquestRemindersFragment.KINDS_KEY).isVisible)
        coVerify(exactly = 0) { AnkiquestCommunity.reminders(any()) }
    }

    @Test
    fun `community reminders in the notification settings open this screen`() {
        val fragments = PreferenceTestUtils.getAttrFromXml(targetContext, R.xml.preferences_ankiquest_notifications, "fragment")
        assertTrue(AnkiquestRemindersFragment::class.java.name in fragments)
        assertTrue(getFragmentFromXmlRes(R.xml.preferences_ankiquest_reminders) is AnkiquestRemindersFragment)
    }

    private fun open(): AnkiquestRemindersFragment {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestRemindersFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        idle()
        val fragment = (controller.get().fragment as PreferencesFragment).childFragmentManager.findFragmentById(R.id.settings_container)
        return fragment as AnkiquestRemindersFragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AnkiquestRemindersFragment.switch(reminder: Reminder) =
        findPreference<SwitchPreferenceCompat>(AnkiquestRemindersFragment.reminderKey(reminder))!!

    private fun AnkiquestRemindersFragment.list(key: String) = findPreference<ListPreference>(key)!!

    private fun AnkiquestRemindersFragment.category(key: String) = findPreference<PreferenceCategory>(key)!!

    private fun AnkiquestRemindersFragment.status() = findPreference<Preference>(AnkiquestRemindersFragment.STATUS_KEY)!!
}
