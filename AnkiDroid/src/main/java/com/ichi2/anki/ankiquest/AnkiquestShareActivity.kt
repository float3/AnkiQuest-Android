// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Passes on a win from a notification: to friends in AnkiQuest, or anywhere else through Android's
 * share sheet, from [AnkiquestShareSheet] where the server allows it. A conquered card first asks
 * whether to show what the card was.
 */
class AnkiquestShareActivity : AnkiActivity() {
    private var cardOwner: AnkiquestDialogOwner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(AnkiquestShare.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(AnkiquestShare.EXTRA_BODY).orEmpty()
        val win = intent.getLongExtra(AnkiquestShare.EXTRA_WIN, 0).takeIf { it > 0 }
        val notification = intent.getLongExtra(AnkiquestShare.EXTRA_NOTIFICATION, 0).takeIf { it > 0 }
        val place = intent.getIntExtra(AnkiquestShare.EXTRA_PLACE, 0).takeIf { it in 1..3 }
        val scope = intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE)
        if (title.isEmpty() || (win == null && notification == null && place == null)) {
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
        val share = Share(win, notification, place, title, body, scope)
        when {
            intent.getStringExtra(AnkiquestShare.EXTRA_DESTINATION) == AnkiquestShare.DESTINATION_FRIENDS -> {
                shareWithFriends(applicationContext, share)
                finish()
            }
            win != null && intent.getStringExtra(AnkiquestShare.EXTRA_TOPIC) == "conquered" -> askAboutCard(current, win, share)
            else -> chooseDestination(share)
        }
    }

    override fun onStop() {
        super.onStop()
        cardOwner?.let {
            closeCard(it)
            if (!isChangingConfigurations) {
                finish()
            }
        }
    }

    /** What is being shared, as the notification's Share button handed it over. */
    private data class Share(
        val win: Long?,
        val notification: Long?,
        val place: Int?,
        val title: String,
        val body: String,
        val scope: String,
    )

    /**
     * Where the win goes. Servers that take chosen friends get AnkiQuest's own share sheet, with
     * other apps one tap away. Otherwise, from Android 14 the share sheet itself offers AnkiQuest
     * friends next to the other apps; older versions ask first, then open the share sheet for other apps.
     */
    private fun chooseDestination(share: Share) {
        val account = AnkiquestHomeData.account()?.takeIf { it.scope == share.scope }
        if (share.win != null && account != null && AnkiquestMomentShare.available(account)) {
            AnkiquestShareSheet(
                this,
                account,
                AnkiquestSharing.MomentRef.Win(share.win),
                AnkiquestShare.Card(share.title, share.body, AnkiquestShare.art(intent, share.place)),
                elsewhere = { shareElsewhere(share, null) },
                closed = { if (it != AnkiquestShareSheet.Exit.ELSEWHERE) finish() },
            ).show()
            return
        }
        if (AnkiquestShare.sheetOffersFriends()) {
            shareElsewhere(share, AnkiquestShare.friendsTarget(this, intent))
            return
        }
        val language = AnkiquestLanguage.context(this)
        val choices =
            arrayOf<CharSequence>(
                language.getString(R.string.ankiquest_share_friends),
                language.getString(R.string.ankiquest_share_elsewhere),
            )
        var leaving = false
        AlertDialog
            .Builder(this)
            .setTitle(language.getString(R.string.ankiquest_share_title))
            .setItems(choices) { _, which ->
                if (which == 0) {
                    shareWithFriends(applicationContext, share)
                } else {
                    leaving = true
                    shareElsewhere(share, null)
                }
            }.setOnDismissListener { if (!leaving) finish() }
            .show()
    }

    /**
     * A conquered card can say what the card was. Nothing about the card leaves the phone unless the
     * player ticks the box: the text starts as the front's field values, or the label they gave it
     * before, and they see and can change exactly what is shared. Sharing without the box ticked
     * takes an earlier label off the card and its page.
     */
    private fun askAboutCard(
        account: HomeAccount,
        win: Long,
        share: Share,
    ) {
        lifecycleScope.launch {
            val owner =
                lifecycle.withResumed {
                    AnkiquestDialogOwner(this@AnkiquestShareActivity, account).also { cardOwner = it }
                }
            if (!ownsCard(owner)) {
                return@launch
            }
            val language = AnkiquestLanguage.context(this@AnkiquestShareActivity)
            val known =
                try {
                    AnkiquestShare.conqueredAt(AnkiquestSharing.winKey(account, win))?.let { at ->
                        at to AnkiquestSharing.conquestLabel(account, at)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest conquered card unavailable; sharing it without the card")
                    null
                }
            if (!ownsCard(owner)) {
                return@launch
            }
            if (known == null) {
                nextDestination(owner, share)
                return@launch
            }
            val (at, saved) = known
            val suggestion = saved ?: AnkiquestShare.conqueredCardText(at).orEmpty()
            if (!ownsCard(owner)) {
                return@launch
            }
            val include =
                AppCompatCheckBox(this@AnkiquestShareActivity).apply {
                    text = language.getString(R.string.ankiquest_share_card_include)
                    isChecked = saved != null
                }
            val field =
                AppCompatEditText(this@AnkiquestShareActivity).apply {
                    setText(suggestion)
                    hint = language.getString(R.string.ankiquest_share_card_hint)
                    isSingleLine = true
                    filters = arrayOf(InputFilter.LengthFilter(AnkiquestShare.MAX_LABEL))
                    isEnabled = include.isChecked
                }
            include.setOnCheckedChangeListener { _, checked -> field.isEnabled = checked }
            val padding = (20 * resources.displayMetrics.density).toInt()
            val content =
                LinearLayout(this@AnkiquestShareActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding / 2, padding, 0)
                    addView(TextView(context).apply { text = language.getString(R.string.ankiquest_share_card_explanation) })
                    addView(include)
                    addView(field)
                }
            owner.show(
                AlertDialog
                    .Builder(this@AnkiquestShareActivity)
                    .setTitle(language.getString(R.string.ankiquest_share_card_title))
                    .setView(content)
                    .setPositiveButton(language.getString(R.string.ankiquest_share_next)) { dialog, _ ->
                        if (!owner.take(dialog)) {
                            if (cardOwner === owner && !owner.isCurrent()) {
                                closeCard(owner)
                                finish()
                            }
                            return@setPositiveButton
                        }
                        val label = AnkiquestShare.chosenLabel(include.isChecked, field.text?.toString())
                        lifecycleScope.launch {
                            try {
                                if (!ownsCard(owner)) {
                                    return@launch
                                }
                                if (label != saved) {
                                    AnkiquestSharing.setConquestLabel(account, at, label)
                                }
                                nextDestination(owner, share)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "ankiquest conquered card label failed")
                                if (owner.isCurrent()) {
                                    showThemedToast(
                                        this@AnkiquestShareActivity,
                                        language.getString(R.string.ankiquest_share_card_failed),
                                        shortLength = false,
                                    )
                                }
                                finish()
                            } finally {
                                closeCard(owner)
                            }
                        }
                    }.setNegativeButton(language.getString(android.R.string.cancel)) { _, _ ->
                        closeCard(owner)
                        finish()
                    }.setOnCancelListener {
                        closeCard(owner)
                        finish()
                    }.create(),
            )
        }
    }

    private fun ownsCard(owner: AnkiquestDialogOwner): Boolean {
        if (owner.isCurrent()) {
            return true
        }
        closeCard(owner)
        finish()
        return false
    }

    private fun closeCard(owner: AnkiquestDialogOwner) {
        owner.close()
        if (cardOwner === owner) {
            cardOwner = null
        }
    }

    private fun nextDestination(
        owner: AnkiquestDialogOwner,
        share: Share,
    ) {
        if (!ownsCard(owner)) {
            return
        }
        closeCard(owner)
        chooseDestination(share)
    }

    /**
     * Prefer the canonical picture within the short deadline; otherwise draw the notification's
     * public content locally. [friends] adds the sheet's AnkiQuest destination.
     */
    private fun shareElsewhere(
        share: Share,
        friends: PendingIntent?,
    ) {
        lifecycleScope.launch {
            val language = AnkiquestLanguage.context(this@AnkiquestShareActivity)
            val failure =
                try {
                    val art = AnkiquestShare.art(intent, share.place)
                    val card = AnkiquestShare.Card(share.title, share.body, art)
                    val link =
                        try {
                            share.win?.let { Ankiquest.shareWinLink(it, share.scope) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: HomeAccountChanged) {
                            throw e
                        } catch (e: Exception) {
                            Timber.d(e, "AnkiQuest notification: drawing its share picture locally")
                            null
                        }
                    val chooser =
                        withContext(Dispatchers.IO) {
                            if (link == null) {
                                AnkiquestShare.textChooser(applicationContext, share.title, share.body, friends, art)
                            } else {
                                AnkiquestShare.linkChooser(applicationContext, link, friends, card)
                            }
                        }
                    if (AnkiquestHomeData.account()?.scope != share.scope) {
                        throw HomeAccountChanged()
                    }
                    lifecycle.withResumed {
                        if (AnkiquestHomeData.account()?.scope != share.scope) {
                            throw HomeAccountChanged()
                        }
                        if (!isFinishing && !isDestroyed) {
                            startActivity(chooser)
                        }
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HomeAccountChanged) {
                    R.string.ankiquest_share_account_changed
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest share link failed")
                    R.string.ankiquest_share_failed
                }
            failure?.let { showThemedToast(this@AnkiquestShareActivity, language.getString(it), shortLength = false) }
            finish()
        }
    }

    private fun shareWithFriends(
        context: Context,
        share: Share,
    ) {
        applicationScope.launch {
            val language = AnkiquestLanguage.context(context)
            val message =
                try {
                    val friends =
                        if (share.win != null) {
                            Ankiquest.shareWinWithFriends(share.win, share.scope)
                        } else {
                            Ankiquest.shareWin(share.notification, share.place, share.scope)
                        }
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
