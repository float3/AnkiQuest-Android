// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R

/** Keeps old explicit activity links working after the section moved into Me. */
class AnkiquestRecordsActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(intent(this))
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = AnkiquestMeSections.intent(context, AnkiquestMeSections.Destination.RECORDS)

        @StringRes
        internal fun label(kind: AnkiquestRecords.Kind): Int =
            when (kind) {
                AnkiquestRecords.Kind.HOUR -> R.string.aq_profile_best_hour
                AnkiquestRecords.Kind.DAY -> R.string.aq_profile_best_day
                AnkiquestRecords.Kind.WEEK -> R.string.aq_profile_best_week
                AnkiquestRecords.Kind.MONTH -> R.string.aq_profile_best_month
                AnkiquestRecords.Kind.YEAR -> R.string.aq_profile_best_year
                AnkiquestRecords.Kind.STREAK -> R.string.review_heatmap_longest_streak
                AnkiquestRecords.Kind.DAYS -> R.string.review_heatmap_days_studied
            }
    }
}
