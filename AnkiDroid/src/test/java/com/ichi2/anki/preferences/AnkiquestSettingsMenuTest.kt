// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AnkiquestSettingsMenuTest : RobolectricTest() {
    /** Each group's key and its settings, in order; a setting outside any group is a group of its own. */
    private fun layout(xml: Int): List<Pair<String, List<String>>> {
        val groups = mutableListOf<Pair<String, MutableList<String>>>()
        targetContext.resources.getXml(xml).use { parser ->
            var depth = 0
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val id = parser.getAttributeResourceValue(ANDROID, "key", 0)
                        val key = if (id == 0) "" else targetContext.getString(id)
                        when (depth) {
                            1 -> {}
                            2 -> groups.add(key to mutableListOf())
                            else -> groups.last().second.add(key)
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                }
            }
        }
        return groups
    }

    private fun keys(vararg ids: Int) = ids.map(targetContext::getString)

    @Test
    fun `one screen with four short groups`() {
        assertEquals(
            listOf(
                targetContext.getString(R.string.ankiquest_health_key) to emptyList(),
                targetContext.getString(R.string.ankiquest_account_group_key) to
                    keys(
                        R.string.ankiquest_sign_in_key,
                        R.string.ankiquest_avatar_key,
                        R.string.ankiquest_companion_key,
                        R.string.ankiquest_sign_out_key,
                        R.string.ankiquest_sign_in_screen_key,
                    ),
                targetContext.getString(R.string.ankiquest_notify_group_key) to
                    keys(
                        R.string.ankiquest_notify_friends_key,
                        R.string.ankiquest_notify_reminders_key,
                        R.string.ankiquest_reminder_time_key,
                        R.string.ankiquest_quiet_hours_key,
                        R.string.ankiquest_notify_moments_key,
                    ),
                targetContext.getString(R.string.ankiquest_sharing_group_key) to
                    keys(
                        R.string.ankiquest_share_moments_key,
                        R.string.ankiquest_show_global_key,
                        R.string.ankiquest_moments_decks_key,
                        R.string.ankiquest_deck_share_ask_key,
                    ),
                targetContext.getString(R.string.ankiquest_app_group_key) to
                    keys(
                        R.string.ankiquest_mood_icon_key,
                        R.string.ankiquest_open_today_key,
                        R.string.ankiquest_update_channel_key,
                        R.string.ankiquest_check_updates_key,
                        R.string.aq_privacy_key,
                    ),
            ),
            layout(R.xml.preferences_ankiquest),
        )
    }

    @Test
    fun `only manage account leads to another screen`() {
        assertEquals(
            listOf(AnkiquestSignInFragment::class.java.name),
            PreferenceTestUtils.getAttrFromXml(targetContext, R.xml.preferences_ankiquest, "fragment"),
        )
        assertEquals(AnkiquestSignInFragment::class, getFragmentFromXmlRes(R.xml.preferences_ankiquest_sign_in)!!::class)
    }

    @Test
    fun `manage account holds the account's devices, data and connection`() {
        val groups = layout(R.xml.preferences_ankiquest_sign_in).toMap()
        assertEquals(
            keys(R.string.ankiquest_url_key, R.string.ankiquest_user_key, R.string.ankiquest_token_key, R.string.ankiquest_test_key),
            groups[targetContext.getString(R.string.ankiquest_connection_key)],
        )
        assertEquals(keys(R.string.ankiquest_sign_out_others_key), groups[targetContext.getString(R.string.ankiquest_devices_key)])
        assertEquals(
            keys(R.string.ankiquest_upload_all_key, R.string.ankiquest_export_key, R.string.ankiquest_delete_account_key),
            groups[""],
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
