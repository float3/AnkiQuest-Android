// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R

/** Redirects old explicit links to their section within Friends, retaining notification context. */
class AnkiquestFeedActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(intent(this, intent.getStringExtra(EXTRA_ACCOUNT)))
        finish()
    }

    companion object {
        /** The account a notification was for, as [HomeAccount.notificationAccount]; another account's feed says so. */
        const val EXTRA_ACCOUNT = "ankiquest.feed_account"

        fun intent(
            context: Context,
            account: String? = null,
        ): Intent =
            AnkiquestFriendsSections
                .intent(context, AnkiquestFriendsSections.Destination.FEED)
                .apply { account?.let { putExtra(EXTRA_ACCOUNT, it) } }

        /** The suggested one-tap answer to a row of [kind], as the website and notifications suggest it. */
        internal fun quickReply(kind: String): Int? =
            when (kind) {
                "completion", "conquest", "share" -> R.string.ankiquest_reply_cheer
                "reply", "reaction" -> R.string.ankiquest_reply_thanks
                "nudge" -> R.string.ankiquest_reply_on_it
                else -> null
            }
    }
}
