// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.R

/**
 * AnkiQuest preference keys that analytics must not report, for upstream's `PreferencesAnalyticsTest`.
 * Server, player and token are personal; the rest are actions or screens.
 */
object AnkiquestAnalyticsExclusions {
    private val resources =
        setOf(
            R.string.ankiquest_screen_key,
            R.string.ankiquest_account_screen_key,
            R.string.ankiquest_study_screen_key,
            R.string.ankiquest_notifications_screen_key,
            R.string.ankiquest_sharing_screen_key,
            R.string.ankiquest_maintenance_screen_key,
            R.string.ankiquest_url_key,
            R.string.ankiquest_user_key,
            R.string.ankiquest_token_key,
            R.string.ankiquest_test_key,
            R.string.ankiquest_sign_in_key,
            R.string.ankiquest_upload_all_key,
            R.string.ankiquest_check_updates_key,
            R.string.ankiquest_update_channel_key,
            R.string.ankiquest_deck_status_key,
            R.string.ankiquest_goal_key,
            R.string.ankiquest_deck_notifications_key,
            R.string.ankiquest_moments_key,
            R.string.ankiquest_moments_status_key,
            R.string.ankiquest_share_cap_key,
            R.string.ankiquest_moments_decks_key,
            R.string.ankiquest_avatar_key,
            R.string.ankiquest_companion_key,
            R.string.ankiquest_notify_rank_key,
            R.string.ankiquest_streak_hours_key,
            R.string.ankiquest_nudges_key,
            R.string.ankiquest_celebrations_key,
            R.string.ankiquest_message_alerts_key,
            R.string.ankiquest_nudge_alerts_key,
            R.string.ankiquest_health_key,
            R.string.ankiquest_dashboard_key,
            R.string.ankiquest_community_reminders_key,
            R.string.ankiquest_streak_protection_key,
            R.string.ankiquest_subscriptions_key,
            R.string.ankiquest_subscriptions_all_key,
            R.string.ankiquest_subscriptions_status_key,
            R.string.ankiquest_subscriptions_retry_key,
            R.string.ankiquest_subscriptions_people_key,
        )

    fun keys(context: Context): Set<String> =
        resources.mapTo(mutableSetOf()) { context.getString(it) } +
            setOf(AnkiquestNavigation.PREF_OPEN_TODAY, AnkiquestNavigation.PREF_SESSION_SUMMARY)
}
