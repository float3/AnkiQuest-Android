// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestMobileNavigationTest : RobolectricTest() {
    @Before
    fun configure() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member name")
            putString(Ankiquest.PREF_TOKEN, "member-token")
        }
    }

    @Test
    fun `launcher opens decks by default without an extra Today screen`() {
        AnkiDroidApp.sharedPrefs().edit { remove(AnkiquestNavigation.PREF_OPEN_TODAY) }
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `initializing settings keeps Decks as the default opening screen`() {
        AnkiDroidApp.sharedPrefs().edit { remove(AnkiquestNavigation.PREF_OPEN_TODAY) }
        PreferenceManager.setDefaultValues(targetContext, R.xml.preferences_ankiquest, true)
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `only a normal launcher entry opens Today`() {
        AnkiDroidApp.sharedPrefs().edit { putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, true) }
        assertTrue(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_VIEW)))
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN).putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)))
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN).putExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, 1L)))
        AnkiDroidApp.sharedPrefs().edit { putBoolean(AnkiquestNavigation.PREF_OPEN_TODAY, false) }
        assertFalse(AnkiquestNavigation.opensToday(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `changing credentials invalidates the presentation identity`() {
        val before = AnkiquestNavigation.accountFingerprint()
        assertFalse(before.contains("member-token"))
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "replacement-token") }
        assertNotEquals(before, AnkiquestNavigation.accountFingerprint())
    }

    @Test
    fun `presentation identity captures fallback member in the same settings snapshot`() {
        val preferences = AnkiDroidApp.sharedPrefs()
        preferences.edit {
            remove(Ankiquest.PREF_USER)
            putString("username", "original-member")
        }
        val before = AnkiquestNavigation.accountFingerprint()
        val changingPreferences = mockk<SharedPreferences>()
        every { changingPreferences.all } answers {
            val snapshot = preferences.all
            preferences.edit { putString("username", "different-member") }
            snapshot
        }
        val previousOverride = AnkiDroidApp.sharedPreferencesTestingOverride
        try {
            AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
            assertEquals(before, AnkiquestNavigation.accountFingerprint())
        } finally {
            AnkiDroidApp.sharedPreferencesTestingOverride = previousOverride
        }
        assertNotEquals(before, AnkiquestNavigation.accountFingerprint())
    }

    @Test
    fun `web routes stay within the configured server and preserve encoded members`() {
        val dashboard = "https://anki.example.test/prefix/#member%20name"
        assertEquals(
            "https://anki.example.test/prefix/community?embed=1#challenge-42",
            AnkiquestActivity.destinationUrl(dashboard, "/community#challenge-42"),
        )
        assertEquals(
            "https://anki.example.test/prefix/week?embed=1#member%20name",
            AnkiquestActivity.destinationUrl(dashboard, "/week#member%20name"),
        )
        for (unsafe in listOf(
            "https://other.test/",
            "//other.test/",
            "/community/../../auth/logout",
            "/auth/session",
            "/community?token=secret",
        )) {
            assertEquals("https://anki.example.test/prefix/?embed=1#member%20name", AnkiquestActivity.destinationUrl(dashboard, unsafe))
        }
    }

    @Test
    fun `notifications open the goal or activity page for their owning account`() {
        val owner = "https://anki.example.test/member%20name"
        val goal = JSONObject().put("id", 71).put("challenge_id", 42).put("route", "https://untrusted.test/")
        val intent = AnkiquestNotifier.notificationIntent(targetContext, goal, owner)
        assertEquals(ComponentName(targetContext, AnkiquestActivity::class.java), intent.component)
        assertEquals("/community#challenge-42", intent.getStringExtra(AnkiquestActivity.EXTRA_PATH))
        assertEquals(owner, intent.getStringExtra(AnkiquestActivity.EXTRA_ACCOUNT))
        assertEquals(null, intent.data)
        assertEquals("/community#challenge-42", AnkiquestActivity.route(intent, owner))
        assertEquals(
            "https://anki.example.test/community?embed=1#challenge-42",
            AnkiquestActivity.destinationUrl("https://anki.example.test/#member%20name", AnkiquestActivity.route(intent, owner)),
        )

        val message = AnkiquestNotifier.notificationIntent(targetContext, JSONObject().put("id", 72), owner)
        assertEquals("/community#activity", message.getStringExtra(AnkiquestActivity.EXTRA_PATH))
    }

    @Test
    fun `each notification kind opens what it is about`() {
        val today = AnkiquestNavigation.Destination.Today()
        val quests = AnkiquestNavigation.Destination.Today(AnkiquestHomeActivity.FOCUS_QUESTS)
        val week = AnkiquestNavigation.Destination.Website(AnkiquestNavigation.WEEK_PATH)
        val feed = AnkiquestNavigation.Destination.Website(AnkiquestNavigation.ACTIVITY_PATH)
        val challenges = AnkiquestNavigation.Destination.Website(AnkiquestNavigation.FRIENDS_PATH)
        val goal = AnkiquestNavigation.Destination.Website(AnkiquestNavigation.challengePath(42))
        val cases =
            listOf(
                Triple("reminder_daily", null, 0L) to today,
                Triple("reminder_urgent", null, 0L) to today,
                Triple("reminder_milestone", null, 0L) to today,
                Triple("reminder_freeze_used", null, 0L) to today,
                Triple("reminder_freeze_refill", null, 0L) to quests,
                Triple("reminder_weekly_recap", "place:2", 0L) to week,
                Triple("reminder_weekly_recap", null, 0L) to week,
                Triple("reminder_weekly_closing", null, 0L) to week,
                Triple("celebration", "goal", 0L) to today,
                Triple("celebration", "secured", 0L) to today,
                Triple("celebration", "streak", 0L) to today,
                Triple("celebration", "level", 0L) to today,
                Triple("event", "quest", 0L) to quests,
                Triple("event", null, 0L) to today,
                Triple("risk", null, 0L) to today,
                Triple("nudge", null, 0L) to today,
                Triple("challenge_invite", null, 42L) to goal,
                Triple("challenge_accepted", null, 42L) to goal,
                Triple("challenge_complete", null, 42L) to goal,
                Triple("challenge_invite", null, 0L) to challenges,
                Triple("share", "streak", 0L) to feed,
                Triple("reaction", null, 0L) to feed,
                Triple("completion", null, 0L) to feed,
                Triple("conquest", null, 0L) to feed,
                Triple("message", null, 0L) to feed,
                Triple("reply", null, 0L) to feed,
                Triple("something_new", null, 0L) to today,
                Triple("", null, 0L) to today,
            )
        for ((input, expected) in cases) {
            val (kind, topic, challenge) = input
            assertEquals(expected, AnkiquestNavigation.notificationDestination(kind, topic, challenge), "$kind $topic $challenge")
        }
    }

    @Test
    fun `study notifications open the native Today screen and the freeze quest reminder its quests`() {
        val owner = "https://anki.example.test/member%20name"
        val freezeEntry = JSONObject().put("id", 73).put("kind", "reminder_freeze_refill")
        val freeze = AnkiquestNotifier.notificationIntent(targetContext, freezeEntry, owner)
        assertEquals(ComponentName(targetContext, AnkiquestHomeActivity::class.java), freeze.component)
        assertEquals(AnkiquestHomeActivity.FOCUS_QUESTS, freeze.getStringExtra(AnkiquestHomeActivity.EXTRA_FOCUS))
        assertTrue((freeze.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        val dailyEntry =
            JSONObject()
                .put("id", 74)
                .put("kind", "reminder_daily")
                .put("topic", JSONObject.NULL)
                .put("route", "https://untrusted.test/")
        val daily = AnkiquestNotifier.notificationIntent(targetContext, dailyEntry, owner)
        assertEquals(ComponentName(targetContext, AnkiquestHomeActivity::class.java), daily.component)
        assertFalse(daily.hasExtra(AnkiquestHomeActivity.EXTRA_FOCUS))
        assertEquals(null, daily.data)

        val recapEntry = JSONObject().put("id", 75).put("kind", "reminder_weekly_recap")
        val recap = AnkiquestNotifier.notificationIntent(targetContext, recapEntry, owner)
        assertEquals(ComponentName(targetContext, AnkiquestActivity::class.java), recap.component)
        assertEquals(AnkiquestNavigation.WEEK_PATH, recap.getStringExtra(AnkiquestActivity.EXTRA_PATH))
        assertEquals(owner, recap.getStringExtra(AnkiquestActivity.EXTRA_ACCOUNT))
    }

    @Test
    fun `a notification for another account cannot open a coincident challenge id`() {
        val intent = AnkiquestNotifier.notificationIntent(targetContext, JSONObject().put("challenge_id", 42), "https://other.test/member")
        val current = AnkiquestHomeData.account()!!.notificationAccount
        assertFalse(AnkiquestActivity.owned(intent, current))
        assertEquals(AnkiquestNavigation.ACTIVITY_PATH, AnkiquestActivity.route(intent, current))
        assertEquals(AnkiquestNavigation.ACTIVITY_PATH, AnkiquestActivity.route(intent, null))
        val unscoped = AnkiquestActivity.intent(targetContext, "/week")
        assertTrue(AnkiquestActivity.owned(unscoped, current))
        assertEquals("/week", AnkiquestActivity.route(unscoped, null))
    }

    @Test
    fun `community routes are pages the web session may authenticate`() {
        val session = AnkiquestWebSession("https://anki.example.test/prefix/#member%20name", "member name", "member-token")
        for (path in listOf(
            AnkiquestNavigation.FRIENDS_PATH,
            AnkiquestNavigation.ACTIVITY_PATH,
            AnkiquestNavigation.challengePath(42),
            null,
        )) {
            val url = AnkiquestActivity.destinationUrl(session.dashboard, path)
            assertTrue(session.allows(url), url)
        }
    }

    @Test
    fun `deck list navigation opens Today natively and community pages on the website`() {
        val today = AnkiquestNavigation.destination(targetContext, R.id.ankiquest_nav_today)!!
        assertEquals(ComponentName(targetContext, AnkiquestHomeActivity::class.java), today.component)
        val friends = AnkiquestNavigation.destination(targetContext, R.id.ankiquest_nav_friends)!!
        assertEquals(ComponentName(targetContext, AnkiquestActivity::class.java), friends.component)
        assertEquals("/community#challenges", friends.getStringExtra(AnkiquestActivity.EXTRA_PATH))
        val progress = AnkiquestNavigation.destination(targetContext, R.id.ankiquest_nav_progress)!!
        assertEquals(ComponentName(targetContext, AnkiquestActivity::class.java), progress.component)
        assertEquals(null, progress.getStringExtra(AnkiquestActivity.EXTRA_PATH))
        assertEquals(null, AnkiquestNavigation.destination(targetContext, R.id.ankiquest_nav_decks))
    }
}
