// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val scope = intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE)
        if (title.isEmpty() || (notification == null && place == null)) {
            finish()
            return
        }
        val language = AnkiquestLanguage.context(this)
        val current = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }
        if (scope == null || current?.scope != scope) {
            showThemedToast(this, language.getString(R.string.ankiquest_share_account_changed), shortLength = false)
            finish()
            return
        }
        val choices =
            arrayOf<CharSequence>(
                language.getString(R.string.ankiquest_share_friends),
                language.getString(R.string.ankiquest_share_elsewhere),
            )
        var drawing = false
        AlertDialog
            .Builder(this)
            .setTitle(language.getString(R.string.ankiquest_share_title))
            .setItems(choices) { _, which ->
                if (which == 0) {
                    shareWithFriends(applicationContext, notification, place, scope)
                } else {
                    drawing = true
                    shareElsewhere(title, body, art)
                }
            }.setOnDismissListener { if (!drawing) finish() }
            .show()
    }

    /** Draws the card away from the main thread, then opens the share sheet and closes. */
    private fun shareElsewhere(
        title: String,
        body: String,
        @DrawableRes art: Int,
    ) {
        lifecycleScope.launch {
            try {
                val chooser = withContext(Dispatchers.IO) { AnkiquestShare.chooser(applicationContext, title, body, art) }
                startActivity(chooser)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest share card failed")
                showThemedToast(
                    this@AnkiquestShareActivity,
                    AnkiquestLanguage.context(this@AnkiquestShareActivity).getString(R.string.ankiquest_share_failed),
                    shortLength = false,
                )
            }
            finish()
        }
    }

    private fun shareWithFriends(
        context: Context,
        notification: Long?,
        place: Int?,
        scope: String,
    ) {
        applicationScope.launch {
            val language = AnkiquestLanguage.context(context)
            val message =
                try {
                    val friends = Ankiquest.shareWin(notification, place, scope)
                    if (friends == 0) {
                        language.getString(R.string.ankiquest_share_nobody)
                    } else {
                        language.resources.getQuantityString(R.plurals.ankiquest_share_sent, friends, friends)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HomeAccountChanged) {
                    language.getString(R.string.ankiquest_share_account_changed)
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest share failed")
                    language.getString(R.string.ankiquest_share_failed)
                }
            launch(Dispatchers.Main) { showThemedToast(context, message, shortLength = false) }
        }
    }
}
