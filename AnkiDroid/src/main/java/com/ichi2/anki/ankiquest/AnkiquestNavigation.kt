// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R

/** Entry policy is independent of connectivity: the local study library always works. */
object AnkiquestNavigation {
    const val PREF_OPEN_TODAY = "ankiquestOpenToday"
    const val PREF_SESSION_SUMMARY = "ankiquestSessionSummary"
    const val FRIENDS_PATH = "/community#challenges"
    const val ACTIVITY_PATH = "/community#activity"

    fun enabled(): Boolean = Ankiquest.dashboardUrl() != null

    fun opensToday(intent: Intent): Boolean =
        enabled() &&
            AnkiDroidApp.sharedPrefs().getBoolean(PREF_OPEN_TODAY, false) &&
            !intent.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false) &&
            !intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK) &&
            intent.action == Intent.ACTION_MAIN &&
            intent.data == null

    /** The website page for one friend goal. */
    fun challengePath(id: Long): String = "/community#challenge-$id"

    /** Where a bottom navigation item leads; null for Decks, the deck list itself. */
    fun destination(
        context: Context,
        item: Int,
    ): Intent? =
        when (item) {
            R.id.ankiquest_nav_today ->
                AnkiquestHomeActivity
                    .intent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            R.id.ankiquest_nav_friends -> AnkiquestActivity.intent(context, FRIENDS_PATH)
            R.id.ankiquest_nav_progress -> AnkiquestActivity.intent(context, null)
            else -> null
        }

    /** A credential change invalidates private presentation state, including a same-user token change. */
    fun accountFingerprint(): String {
        val settings = AnkiDroidApp.sharedPrefs().all
        val player =
            (settings[Ankiquest.PREF_USER] as? String).orEmpty().trim().ifEmpty {
                (settings["username"] as? String).orEmpty().trim()
            }
        return sha256(
            settings[Ankiquest.PREF_URL].toString(),
            settings[Ankiquest.PREF_USER].toString(),
            player,
            settings[Ankiquest.PREF_TOKEN].toString(),
        )
    }
}
