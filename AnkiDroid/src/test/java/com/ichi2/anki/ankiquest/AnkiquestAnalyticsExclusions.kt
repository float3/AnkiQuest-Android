// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import com.ichi2.anki.R

/**
 * AnkiQuest preference keys that analytics must not report, for upstream's `PreferencesAnalyticsTest`.
 * Server, player and token are personal; the rest are actions, groups or screens.
 */
object AnkiquestAnalyticsExclusions {
    private val resources =
        setOf(
            R.string.ankiquest_screen_key,
            R.string.ankiquest_health_key,
            R.string.ankiquest_account_group_key,
            R.string.ankiquest_notify_group_key,
            R.string.ankiquest_sharing_group_key,
            R.string.ankiquest_app_group_key,
            R.string.ankiquest_url_key,
            R.string.ankiquest_user_key,
            R.string.ankiquest_token_key,
            R.string.ankiquest_test_key,
            R.string.ankiquest_debug_info_key,
            R.string.ankiquest_connection_key,
            R.string.ankiquest_sign_in_key,
            R.string.ankiquest_sign_out_key,
            R.string.ankiquest_sign_in_screen_key,
            R.string.ankiquest_account_status_key,
            R.string.ankiquest_password_key,
            R.string.ankiquest_providers_key,
            R.string.ankiquest_devices_key,
            R.string.ankiquest_sign_out_others_key,
            R.string.ankiquest_upload_all_key,
            R.string.ankiquest_export_key,
            R.string.ankiquest_delete_account_key,
            R.string.ankiquest_avatar_key,
            R.string.ankiquest_companion_key,
            R.string.ankiquest_notify_friends_key,
            R.string.ankiquest_notify_reminders_key,
            R.string.ankiquest_reminder_time_key,
            R.string.ankiquest_quiet_hours_key,
            R.string.ankiquest_notify_moments_key,
            R.string.ankiquest_share_moments_key,
            R.string.ankiquest_show_global_key,
            R.string.ankiquest_moments_decks_key,
            R.string.ankiquest_deck_share_ask_key,
            R.string.ankiquest_mood_icon_key,
            R.string.ankiquest_update_channel_key,
            R.string.ankiquest_check_updates_key,
            R.string.aq_privacy_key,
        )

    fun keys(context: Context): Set<String> =
        resources.mapTo(mutableSetOf()) { context.getString(it) } +
            setOf(AnkiquestNavigation.PREF_OPEN_TODAY)
}
