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

    /** Where an inbox notification leads: always a native screen. */
    sealed interface Destination {
        data class Today(
            val focus: String? = null,
        ) : Destination

        /** The native activity feed, [AnkiquestFeedActivity]. */
        data object Activity : Destination

        /** The native friends and groups, [AnkiquestFriendsActivity]. */
        data object Friends : Destination

        /** The native friend goals, [AnkiquestChallengesActivity], opened on goal [id] when there is one. */
        data class Challenges(
            val id: Long? = null,
        ) : Destination

        /** The native leaderboard, [AnkiquestLeaderboardActivity], showing [period]. */
        data class Leaderboard(
            val period: String,
        ) : Destination
    }

    private val weekly = setOf("reminder_weekly_recap", "reminder_weekly_closing")
    private val feed = setOf("completion", "conquest", "share", "reaction", "message", "reply")

    /**
     * What a notification of [kind] is about. Study reminders, celebrations and nudges lead to Today,
     * weekly results to the leaderboard, challenges to their goal and friends' news to the activity
     * feed. Only ids and known kinds choose the route; unknown kinds open Today.
     */
    fun notificationDestination(
        kind: String,
        topic: String?,
        challenge: Long,
    ): Destination =
        when {
            challenge > 0 -> Destination.Challenges(challenge)
            kind.startsWith("challenge_") -> Destination.Challenges()
            kind in weekly -> Destination.Leaderboard(AnkiquestBoards.Period.WEEK.query)
            kind in feed -> Destination.Activity
            kind.startsWith("friend_") || kind.startsWith("group_") -> Destination.Friends
            kind == "reminder_freeze_refill" || topic?.substringBefore(':') == "quest" ->
                Destination.Today(AnkiquestHomeActivity.FOCUS_QUESTS)
            else -> Destination.Today()
        }

    fun enabled(): Boolean = Ankiquest.dashboardUrl() != null

    fun opensToday(intent: Intent): Boolean =
        enabled() &&
            AnkiDroidApp.sharedPrefs().getBoolean(PREF_OPEN_TODAY, false) &&
            !intent.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false) &&
            !intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK) &&
            intent.action == Intent.ACTION_MAIN &&
            intent.data == null

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
            R.id.ankiquest_nav_friends -> AnkiquestFriendsActivity.intent(context)
            R.id.ankiquest_nav_progress -> AnkiquestProfileActivity.intent(context)
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
