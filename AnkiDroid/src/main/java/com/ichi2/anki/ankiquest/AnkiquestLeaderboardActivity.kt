// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R

/** Redirects old explicit links to their section within Friends, retaining notification context. */
class AnkiquestLeaderboardActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            intent(this, intent.getStringExtra(EXTRA_PERIOD) ?: AnkiquestBoards.Period.WEEK.query, intent.getStringExtra(EXTRA_ACCOUNT)),
        )
        finish()
    }

    companion object {
        /** A period's name, as [AnkiquestBoards.Period.query]; the week when missing or unknown. */
        const val EXTRA_PERIOD = "ankiquest.board_period"

        /** The account a notification was for, as [HomeAccount.notificationAccount]; another account's board says so. */
        const val EXTRA_ACCOUNT = "ankiquest.board_account"

        fun intent(
            context: Context,
            period: String = AnkiquestBoards.Period.WEEK.query,
            account: String? = null,
        ): Intent =
            AnkiquestFriendsSections
                .intent(context, AnkiquestFriendsSections.Destination.LEADERBOARD)
                .putExtra(EXTRA_PERIOD, period)
                .apply { account?.let { putExtra(EXTRA_ACCOUNT, it) } }

        /** The phrase for [period] after "XP", such as "this week". */
        @StringRes
        internal fun periodPhrase(period: AnkiquestBoards.Period): Int =
            when (period) {
                AnkiquestBoards.Period.HOUR -> R.string.ankiquest_period_hour
                AnkiquestBoards.Period.DAY -> R.string.ankiquest_period_day
                AnkiquestBoards.Period.WEEK -> R.string.ankiquest_period_week
                AnkiquestBoards.Period.MONTH -> R.string.ankiquest_period_month
                AnkiquestBoards.Period.YEAR -> R.string.ankiquest_period_year
                AnkiquestBoards.Period.ALL -> R.string.ankiquest_period_all
            }
    }
}
