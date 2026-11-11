// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestMoments.Kind
import com.ichi2.anki.ankiquest.AnkiquestMoments.Moment
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The one card every moment gets: Aki in a pose for the moment (or the server's picture of it, once
 * it has one), a title, one line of detail and the date, with Share and, until friends were told,
 * Tell friends. A conquered card's telling can be undone while the server allows it. Several
 * moments fold into one card that swipes through them, "and N more" below.
 */
internal object AnkiquestMomentCard {
    private const val ART_DP = 128
    private const val RECAP_ART_DP = 112

    /** Review result -> recap and moment pages -> optional deck-sharing handoff. */
    data class Session(
        val summary: AnkiquestStudySession.Summary?,
        val onContinue: () -> Unit,
        val onClose: () -> Unit,
    )

    /** One owner and lifetime for the recap and any moments that arrive while it is open. */
    class Card(
        private val host: Activity,
        private val account: HomeAccount?,
        initial: List<Moment>,
        private val prefs: SharedPreferences,
    ) {
        private enum class Exit { DONE, CONTINUE, PROGRESS, REPLACED, CLOSED }

        private val owner = AnkiquestNavigation.accountFingerprint(prefs.all)
        private val lifecycle = (host as LifecycleOwner).lifecycle
        private val parentScope = (host as LifecycleOwner).lifecycleScope
        private val jobs = SupervisorJob(parentScope.coroutineContext[Job])
        private val scope = CoroutineScope(parentScope.coroutineContext + jobs)
        private val content = LayoutInflater.from(host).inflate(R.layout.dialog_ankiquest_moment, null)
        private val pager = content.findViewById<ViewPager2>(R.id.aq_moment_pager)
        private val more = content.findViewById<TextView>(R.id.aq_moment_more)
        private val recap = RecapPage(host)
        private val moments = initial.toMutableList()
        private val pages = account?.let { Pages(host, it, moments, scope) }
        private val adapter = ConcatAdapter(recap).apply { pages?.let(::addAdapter) }
        private var session: Session? = null
        private var exit = Exit.DONE

        val dialog: AlertDialog =
            AlertDialog
                .Builder(host)
                .setView(content)
                .setNegativeButton(CommonString.close, null)
                .setNeutralButton(R.string.ankiquest_summary_progress, null)
                .setPositiveButton(R.string.ankiquest_summary_continue, null)
                .create()

        private val accountListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                if (!ownsAccount()) {
                    abandon()
                }
            }
        private val observer =
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    abandon()
                }

                override fun onResume(owner: LifecycleOwner) {
                    if (!ownsAccount()) {
                        abandon()
                    }
                }
            }

        fun show(session: Session?) {
            pager.adapter = adapter
            more.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        describePage()
                    }
                },
            )
            dialog.setOnDismissListener {
                val reason = exit
                exit = Exit.CLOSED
                lifecycle.removeObserver(observer)
                prefs.unregisterOnSharedPreferenceChangeListener(accountListener)
                jobs.cancel()
                if (reason == Exit.CLOSED || reason == Exit.REPLACED || !ownsAccount()) {
                    return@setOnDismissListener
                }
                if (recap.neverAgain) {
                    prefs.edit { putBoolean(AnkiquestNavigation.PREF_SESSION_SUMMARY, false) }
                }
                if (reason == Exit.DONE && AnkiquestPostStudyPrompts.ready(host)) {
                    this.session?.onClose?.invoke()
                }
            }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { close(Exit.DONE) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { close(Exit.PROGRESS) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { close(Exit.CONTINUE) }
            attach(session)
            lifecycle.addObserver(observer)
            prefs.registerOnSharedPreferenceChangeListener(accountListener)
            describePage()
        }

        fun owns(host: Activity): Boolean = this.host === host && ownsAccount()

        fun hasSession(): Boolean = session != null

        /** A summary may finish loading after the first moment has already opened the card. */
        fun attach(session: Session?) {
            if (session != null) {
                this.session = session
                session.summary?.let {
                    recap.bind(it)
                    pager.setCurrentItem(0, false)
                }
            }
            val summary = this.session?.summary
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(
                if (summary == null) CommonString.close else CommonString.multimedia_editor_field_editing_done,
            )
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible = summary != null
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isVisible = summary != null && summary.remaining > 0
            describePage()
        }

        /** Keep the current page stable when an upload adds another moment. */
        fun append(added: List<Moment>) {
            val pages = pages ?: return
            val first = moments.size
            moments += added.filter { candidate -> moments.none { it.key == candidate.key } }.distinctBy { it.key }
            if (moments.size > first) {
                pages.notifyItemRangeInserted(first, moments.size - first)
                describePage()
            }
        }

        /** A notification can select its moment without throwing away the session recap. */
        fun select(moment: Moment) {
            append(listOf(moment))
            val position = moments.indexOfFirst { it.key == moment.key }
            if (position >= 0) {
                pager.setCurrentItem(recap.itemCount + position, true)
            }
        }

        fun abandon() {
            exit = Exit.REPLACED
            dialog.dismiss()
        }

        private fun ownsAccount(): Boolean = owner == AnkiquestNavigation.accountFingerprint(prefs.all)

        private fun close(destination: Exit) {
            if (!dialog.isShowing) {
                return
            }
            if (!ownsAccount()) {
                abandon()
                return
            }
            if (!AnkiquestPostStudyPrompts.resumed(host)) {
                return
            }
            exit = destination
            dialog.dismiss()
            when (destination) {
                Exit.CONTINUE -> session?.onContinue?.invoke()
                Exit.PROGRESS -> host.startActivity(AnkiquestProfileActivity.intent(host))
                else -> Unit
            }
        }

        private fun describePage() {
            val count = adapter.itemCount
            more.isVisible = count > 1
            if (count == 0) {
                return
            }
            val position = pager.currentItem.coerceAtMost(count - 1)
            more.text = foldLine(host, position, count)
            val title =
                if (recap.itemCount > 0 && position == 0) {
                    host.getString(R.string.ankiquest_summary_title)
                } else {
                    moments.getOrNull(position - recap.itemCount)?.title
                }
            ViewCompat.setAccessibilityPaneTitle(content, title)
            dialog.window?.setTitle(title.orEmpty())
        }
    }

    private class RecapPage(
        private val host: Activity,
    ) : RecyclerView.Adapter<RecapPage.Holder>() {
        private var summary: AnkiquestStudySession.Summary? = null
        var neverAgain = false
            private set

        class Holder(
            view: View,
        ) : RecyclerView.ViewHolder(view)

        override fun getItemCount(): Int = if (summary == null) 0 else 1

        fun bind(summary: AnkiquestStudySession.Summary) {
            val inserted = this.summary == null
            this.summary = summary
            if (inserted) {
                notifyItemInserted(0)
            } else {
                notifyItemChanged(0)
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_ankiquest_recap, parent, false))

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val summary = summary ?: return
            val view = holder.itemView
            AnkiquestCompanion.show(
                view.findViewById(R.id.aq_recap_art),
                if (summary.remaining == 0) R.drawable.aki_celebrate else R.drawable.aki_streak,
                (RECAP_ART_DP * host.resources.displayMetrics.density).toInt(),
            )
            view.findViewById<TextView>(R.id.aq_recap_body).text =
                host.getString(R.string.ankiquest_summary_body, summary.reviews, summary.duration(host), summary.remaining)
            ViewCompat.setAccessibilityHeading(view.findViewById(R.id.aq_recap_title), true)
            view.findViewById<CheckBox>(R.id.aq_recap_opt_out).apply {
                setOnCheckedChangeListener(null)
                isChecked = neverAgain
                setOnCheckedChangeListener { _, checked -> neverAgain = checked }
            }
        }
    }

    /** Shows a session, moments, or both in one scrollable, swipeable card. */
    fun show(
        host: Activity,
        account: HomeAccount?,
        moments: List<Moment>,
        prefs: SharedPreferences,
        session: Session? = null,
    ): Card = Card(host, account, moments, prefs).apply { show(session) }

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
        /** Whether telling friends about a conquered card can still be taken back. */
        val undoable: Boolean = false,
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

        /** Conquered cards whose telling friends can still be taken back. */
        private val undoable = mutableSetOf<String>()

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
            val undo = moment.key in undoable
            holder.tell.isVisible = tellable(moment) && (undo || (moment.key !in told && known?.told != true))
            holder.tell.setText(if (undo) CommonString.undo else R.string.aq_conquest_tell)
            holder.tell.setOnClickListener { if (undo) untell(moment, position) else tell(moment, position) }
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
                if (found.undoable) undoable += moment.key
                found.image?.let { url -> withContext(Dispatchers.IO) { AnkiquestShare.picture(url) } }?.let { pictures[moment.key] = it }
                if (!host.isFinishing && !host.isDestroyed) notifyItemChanged(position)
            }
        }

        private suspend fun fetch(moment: Moment): Extras {
            var told = false
            var win = moment.win
            var undo = false
            moment.conquest?.let { at ->
                val conquest = AnkiquestCommunity.conquest(account, account.user, at)
                told = conquest.told == true
                win = win ?: conquest.win
                undo = conquest.undoable(TimeManager.time.intTimeMS())
            }
            val listed = win?.let { AnkiquestSharing.win(account, it) }
            if (listed?.optBoolean("shared_with_friends") == true) told = true
            val image = listed?.optJSONObject("link")?.optString("image")?.ifEmpty { null }
            return Extras(told, image, win, undo)
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
                        if (moment.conquest != null) undoable += moment.key
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

        /** Takes back telling friends about a conquered card, from those who have not read it yet. */
        private fun untell(
            moment: Moment,
            position: Int,
        ) {
            val scope = scope ?: return
            val at = moment.conquest ?: return
            val signedIn = current() ?: return toast(R.string.ankiquest_share_account_changed)
            scope.launch {
                val message =
                    try {
                        AnkiquestCommunity.untell(signedIn, at)
                        told -= moment.key
                        undoable -= moment.key
                        extras[moment.key]?.let { extras[moment.key] = it.copy(told = false, undoable = false) }
                        host.getString(R.string.aq_conquest_untold)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AnkiquestHttpException) {
                        Timber.w(e, "AnkiQuest moment: taking back telling friends refused")
                        if (e.code == 409) {
                            undoable -= moment.key
                            host.getString(R.string.aq_conquest_untell_late)
                        } else {
                            host.getString(R.string.ankiquest_share_failed)
                        }
                    } catch (_: HomeAccountChanged) {
                        host.getString(R.string.ankiquest_share_account_changed)
                    } catch (e: Exception) {
                        Timber.w(e, "AnkiQuest moment: taking back telling friends failed")
                        host.getString(R.string.ankiquest_share_failed)
                    }
                if (!host.isFinishing && !host.isDestroyed) notifyItemChanged(position)
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
