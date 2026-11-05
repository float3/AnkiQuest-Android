// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestMoments.Kind
import com.ichi2.anki.ankiquest.AnkiquestMoments.Moment
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The one card every moment gets: Aki in a pose for the moment (or the server's picture of it, once
 * it has one), a title, one line of detail and the date, with Share and, until friends were told,
 * Tell friends. Several moments fold into one card that swipes through them, "and N more" below.
 */
internal object AnkiquestMomentCard {
    private const val ART_DP = 128

    /** Shows [moments] (at least one) of [account] over [host]; answers the dialog. */
    fun show(
        host: Activity,
        account: HomeAccount,
        moments: List<Moment>,
    ): AlertDialog {
        val content = LayoutInflater.from(host).inflate(R.layout.dialog_ankiquest_moment, null)
        val pager = content.findViewById<ViewPager2>(R.id.aq_moment_pager)
        val more = content.findViewById<TextView>(R.id.aq_moment_more)
        val scope = (host as? LifecycleOwner)?.lifecycleScope
        pager.adapter = Pages(host, account, moments, scope)
        more.isVisible = moments.size > 1
        pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    more.text = foldLine(host, position, moments.size)
                }
            },
        )
        more.text = foldLine(host, 0, moments.size)
        return AlertDialog
            .Builder(host)
            .setView(content)
            .setNegativeButton(CommonString.close, null)
            .show()
    }

    /** "And 2 more" on the first card, "2 of 3" once swiping. */
    @VisibleForTesting
    internal fun foldLine(
        context: Context,
        position: Int,
        count: Int,
    ): String =
        if (position == 0) {
            context.resources.getQuantityString(R.plurals.aq_moment_more, count - 1, count - 1)
        } else {
            context.getString(R.string.aq_moment_position, position + 1, count)
        }

    /** Aki's pose for [moment]. */
    @DrawableRes
    fun art(moment: Moment): Int =
        when (moment.kind) {
            Kind.STREAK -> R.drawable.aki_streak_milestone
            Kind.LEVEL -> R.drawable.aki_level_up
            Kind.RANK -> AnkiquestArt.place(moment.place) ?: R.drawable.aki_winner
            Kind.ACHIEVEMENT -> R.drawable.aki_achievement
            Kind.RECORD -> R.drawable.aki_winner
            Kind.LEECH -> R.drawable.aki_leech
            Kind.CONQUERED -> R.drawable.aki_deck_conquered
            Kind.GOAL -> R.drawable.aki_goal
            Kind.UNLOCKS -> R.drawable.aki_celebrate
            Kind.SECURED -> R.drawable.aki_minimum
        }

    /** Whether friends can be told about [moment] at all: it needs something the server can pass on. */
    fun tellable(moment: Moment): Boolean =
        moment.win != null || moment.notification != null || moment.place != null || moment.conquest != null

    private class Holder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.aq_moment_art)
        val title: TextView = view.findViewById(R.id.aq_moment_title)
        val detail: TextView = view.findViewById(R.id.aq_moment_detail)
        val date: TextView = view.findViewById(R.id.aq_moment_date)
        val open: MaterialButton = view.findViewById(R.id.aq_moment_open)
        val tell: MaterialButton = view.findViewById(R.id.aq_moment_tell)
        val share: MaterialButton = view.findViewById(R.id.aq_moment_share)
    }

    /** What the server knows about a moment beyond the inbox row: whether friends heard it, and its picture. */
    private data class Extras(
        val told: Boolean,
        val image: String?,
        val win: Long?,
    )

    private class Pages(
        private val host: Activity,
        private val account: HomeAccount,
        private val moments: List<Moment>,
        private val scope: CoroutineScope?,
    ) : RecyclerView.Adapter<Holder>() {
        private val extras = mutableMapOf<String, Extras>()
        private val pictures = mutableMapOf<String, Bitmap>()
        private val told = mutableSetOf<String>()

        override fun getItemCount() = moments.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_ankiquest_moment, parent, false))

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val moment = moments[position]
            val size = (ART_DP * host.resources.displayMetrics.density).toInt()
            val picture = pictures[moment.key]
            if (picture != null) {
                holder.art.isVisible = true
                holder.art.setImageBitmap(picture)
            } else {
                AnkiquestCompanion.show(holder.art, art(moment), size)
            }
            holder.title.text = moment.title
            holder.detail.text = moment.detail
            holder.detail.isVisible = moment.detail.isNotEmpty()
            holder.date.text = DateUtils.formatDateTime(host, moment.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
            holder.date.isVisible = moment.at > 0
            holder.open.isVisible = moment.conquest != null
            holder.open.setOnClickListener {
                moment.conquest?.let { host.startActivity(AnkiquestConquestActivity.intent(host, account.user, it)) }
            }
            val known = extras[moment.key]
            holder.tell.isVisible = tellable(moment) && moment.key !in told && known?.told != true
            holder.tell.setOnClickListener { tell(moment, position) }
            holder.share.setOnClickListener { share(moment) }
            if (known == null) load(moment, position)
        }

        /** Asks the server whether friends heard [moment] and for its picture, once per card. */
        private fun load(
            moment: Moment,
            position: Int,
        ) {
            val scope = scope ?: return
            if (account.token.isEmpty() || (moment.win == null && moment.conquest == null)) return
            extras[moment.key] = Extras(told = false, image = null, win = moment.win)
            scope.launch {
                val found =
                    try {
                        fetch(moment)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "AnkiQuest moment: no picture or sharing state")
                        null
                    } ?: return@launch
                extras[moment.key] = found
                found.image?.let { url -> withContext(Dispatchers.IO) { AnkiquestShare.picture(url) } }?.let { pictures[moment.key] = it }
                if (!host.isFinishing && !host.isDestroyed) notifyItemChanged(position)
            }
        }

        private suspend fun fetch(moment: Moment): Extras {
            var told = false
            var win = moment.win
            moment.conquest?.let { at ->
                val conquest = AnkiquestCommunity.conquest(account, account.user, at)
                told = conquest.told == true
                win = win ?: conquest.win
            }
            val listed = win?.let { AnkiquestSharing.win(account, it) }
            if (listed?.optBoolean("shared_with_friends") == true) told = true
            val image = listed?.optJSONObject("link")?.optString("image")?.ifEmpty { null }
            return Extras(told, image, win)
        }

        private fun current(): HomeAccount? = AnkiquestHomeData.account()?.takeIf { it.scope == account.scope && it.token.isNotEmpty() }

        private fun tell(
            moment: Moment,
            position: Int,
        ) {
            val scope = scope ?: return
            val signedIn = current() ?: return toast(R.string.ankiquest_share_account_changed)
            scope.launch {
                val message =
                    try {
                        val win = moment.win ?: extras[moment.key]?.win
                        val friends =
                            when {
                                moment.conquest != null -> AnkiquestCommunity.tell(signedIn, moment.conquest)
                                win != null -> Ankiquest.shareWinWithFriends(win, signedIn.scope)
                                else -> Ankiquest.shareWin(moment.notification, moment.place, signedIn.scope)
                            }
                        told += moment.key
                        notifyItemChanged(position)
                        if (friends == 0) {
                            host.getString(R.string.ankiquest_share_nobody)
                        } else {
                            host.resources.getQuantityString(R.plurals.ankiquest_share_sent, friends, friends)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: HomeAccountChanged) {
                        host.getString(R.string.ankiquest_share_account_changed)
                    } catch (e: Exception) {
                        Timber.w(e, "AnkiQuest moment: telling friends failed")
                        host.getString(R.string.ankiquest_share_failed)
                    }
                showThemedToast(host, message, shortLength = false)
            }
        }

        /**
         * The share sheet: the server's picture and first-person invitation with its link for a win,
         * the card's own words and page for a conquered card, or one line of text otherwise.
         */
        private fun share(moment: Moment) {
            val scope = scope ?: return
            val signedIn = current() ?: return toast(R.string.ankiquest_share_account_changed)
            scope.launch {
                try {
                    val win = moment.win ?: extras[moment.key]?.win
                    val chooser =
                        when {
                            moment.conquest != null -> {
                                val image = win?.let { Ankiquest.shareWinLink(it, signedIn.scope).image }
                                withContext(Dispatchers.IO) {
                                    AnkiquestShare.cardChooser(host.applicationContext, conquestText(host, signedIn, moment), image)
                                }
                            }
                            win != null -> {
                                val link = Ankiquest.shareWinLink(win, signedIn.scope)
                                withContext(Dispatchers.IO) { AnkiquestShare.linkChooser(host.applicationContext, link) }
                            }
                            else ->
                                AnkiquestShare.textChooser(
                                    host.applicationContext,
                                    moment.shareTitle ?: moment.title,
                                    moment.shareBody ?: moment.detail,
                                )
                        }
                    host.startActivity(chooser)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HomeAccountChanged) {
                    toast(R.string.ankiquest_share_account_changed)
                } catch (e: ActivityNotFoundException) {
                    Timber.w(e, "AnkiQuest moment: nothing to share with")
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest moment: share failed")
                    toast(R.string.ankiquest_share_failed)
                }
            }
        }

        private fun toast(message: Int) = showThemedToast(host, host.getString(message), shortLength = false)
    }

    /** What sharing a conquered card says, in the first person, with its deck and its page on the server. */
    @VisibleForTesting
    internal fun conquestText(
        context: Context,
        account: HomeAccount,
        moment: Moment,
    ): String {
        val at = requireNotNull(moment.conquest)
        val words = context.resources.getQuantityString(R.plurals.aq_conquest_share_mine, moment.lapses, moment.lapses)
        val from = moment.deck?.let { " " + context.getString(R.string.aq_conquest_share_deck, it) }.orEmpty()
        return "$words$from ${AnkiquestCommunity.conquestLink(account.server, account.user, at)}"
    }
}
