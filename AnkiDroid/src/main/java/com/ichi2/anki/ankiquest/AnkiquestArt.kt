// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.annotation.DrawableRes
import com.ichi2.anki.R

/** The mascot art for each moment the server tells the app about. */
internal object AnkiquestArt {
    /**
     * Art for an inbox notification of [kind]. [topic] is what a celebration is about
     * ("streak", "achievement", …) or "place:N" for a weekly recap; older servers send none.
     */
    @DrawableRes
    fun forNotice(
        kind: String,
        topic: String?,
    ): Int =
        when (kind) {
            "celebration" -> if (topic == "streak") R.drawable.aki_streak_milestone else R.drawable.aki_achievement
            "challenge_invite", "challenge_accepted" -> R.drawable.aki_challenge
            "challenge_complete" -> R.drawable.aki_challenge_won
            "reminder_weekly_recap" -> place(topic?.removePrefix("place:")?.toIntOrNull()) ?: R.drawable.aki_face
            else -> R.drawable.aki_face
        }

    /** Art for finishing or climbing to [place] (1-based) on the weekly leaderboard, for the top three. */
    @DrawableRes
    fun place(place: Int?): Int? =
        when (place) {
            1 -> R.drawable.aki_place_1
            2 -> R.drawable.aki_place_2
            3 -> R.drawable.aki_place_3
            else -> null
        }

    /** The medal shown beside the top three leaderboard rows. */
    @DrawableRes
    fun medal(place: Int): Int? =
        when (place) {
            1 -> R.drawable.icon_place_1
            2 -> R.drawable.icon_place_2
            3 -> R.drawable.icon_place_3
            else -> null
        }
}
