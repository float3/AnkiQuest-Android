// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/** Asks where a win from a notification should go: to friends in AnkiQuest, or anywhere else. */
class AnkiquestShareActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(AnkiquestShare.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(AnkiquestShare.EXTRA_BODY).orEmpty()
        val art = intent.getIntExtra(AnkiquestShare.EXTRA_ART, R.drawable.aki_celebrate)
        val notification = intent.getLongExtra(AnkiquestShare.EXTRA_NOTIFICATION, 0).takeIf { it > 0 }
        val place = intent.getIntExtra(AnkiquestShare.EXTRA_PLACE, 0).takeIf { it in 1..3 }
        if (title.isEmpty() || (notification == null && place == null)) {
            finish()
            return
        }
        val language = AnkiquestLanguage.context(this)
        val choices =
            arrayOf<CharSequence>(
                language.getString(R.string.ankiquest_share_friends),
                language.getString(R.string.ankiquest_share_elsewhere),
            )
        AlertDialog
            .Builder(this)
            .setTitle(language.getString(R.string.ankiquest_share_title))
            .setItems(choices) { _, which ->
                if (which == 0) {
                    shareWithFriends(applicationContext, notification, place)
                } else {
                    AnkiquestShare.shareElsewhere(this, title, body, art)
                }
            }.setOnDismissListener { finish() }
            .show()
    }

    private fun shareWithFriends(
        context: Context,
        notification: Long?,
        place: Int?,
    ) {
        applicationScope.launch {
            val language = AnkiquestLanguage.context(context)
            val message =
                try {
                    val friends = Ankiquest.shareWin(notification, place)
                    if (friends == 0) {
                        language.getString(R.string.ankiquest_share_nobody)
                    } else {
                        language.resources.getQuantityString(R.plurals.ankiquest_share_sent, friends, friends)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest share failed")
                    language.getString(R.string.ankiquest_share_failed)
                }
            launch(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }
}
