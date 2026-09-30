// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AnkiquestSettingsMenuTest : RobolectricTest() {
    @Test
    fun `each existing setting remains available exactly once`() {
        val pages =
            listOf(
                R.xml.preferences_ankiquest,
                R.xml.preferences_ankiquest_account,
                R.xml.preferences_ankiquest_study,
                R.xml.preferences_ankiquest_notifications,
                R.xml.preferences_ankiquest_sharing,
                R.xml.preferences_ankiquest_maintenance,
            )
        val navigationKeys =
            setOf(
                R.string.ankiquest_screen_key,
                R.string.ankiquest_account_screen_key,
                R.string.ankiquest_study_screen_key,
                R.string.ankiquest_notifications_screen_key,
                R.string.ankiquest_sharing_screen_key,
                R.string.ankiquest_maintenance_screen_key,
            ).map(targetContext::getString).toSet()
        val settings =
            pages
                .flatMap { PreferenceTestUtils.getAttrFromXml(targetContext, it, "key") }
                .map { targetContext.getString(it.removePrefix("@").toInt()) }
                .filterNot { it in navigationKeys }
        val expected =
            listOf(
                R.string.ankiquest_dashboard_key,
                R.string.ankiquest_url_key,
                R.string.ankiquest_user_key,
                R.string.ankiquest_token_key,
                R.string.ankiquest_avatar_key,
                R.string.ankiquest_companion_key,
                R.string.ankiquest_test_key,
                R.string.ankiquest_open_today_key,
                R.string.ankiquest_summary_preference_key,
                R.string.ankiquest_deck_status_key,
                R.string.ankiquest_streak_protection_key,
                R.string.ankiquest_notify_rank_key,
                R.string.ankiquest_streak_hours_key,
                R.string.ankiquest_nudges_key,
                R.string.ankiquest_celebrations_key,
                R.string.ankiquest_community_reminders_key,
                R.string.ankiquest_message_alerts_key,
                R.string.ankiquest_nudge_alerts_key,
                R.string.ankiquest_health_key,
                R.string.ankiquest_deck_notifications_key,
                R.string.ankiquest_subscriptions_key,
                R.string.ankiquest_upload_all_key,
                R.string.ankiquest_update_channel_key,
                R.string.ankiquest_check_updates_key,
            ).map(targetContext::getString)

        assertEquals(expected.size, settings.size, "A setting was omitted or duplicated")
        assertEquals(expected.toSet(), settings.toSet())
        assertEquals(5, PreferenceTestUtils.getAttrFromXml(targetContext, R.xml.preferences_ankiquest, "fragment").size)
    }
}
