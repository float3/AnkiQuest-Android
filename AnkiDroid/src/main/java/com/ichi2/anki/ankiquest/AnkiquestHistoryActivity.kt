// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ichi2.anki.AnkiActivity

/** Keeps old explicit activity links working after the section moved into Me. */
class AnkiquestHistoryActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(intent(this))
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = AnkiquestMeSections.intent(context, AnkiquestMeSections.Destination.HISTORY)
    }
}
