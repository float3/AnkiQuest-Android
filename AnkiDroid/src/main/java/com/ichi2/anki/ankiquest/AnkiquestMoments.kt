// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.StudyOptionsActivity
import com.ichi2.anki.pages.CongratsPage
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.NumberFormat

/**
 * The player's big moments (a card conquered, a level, a streak milestone, an achievement, a climb
 * on the board, …), shown one card at a time when studying stops, never over a review.
 *
 * Moments wait in a queue kept per account in preferences, so one learned of mid-session, or just
 * before the app was closed, is still there when the deck list, the congrats screen or Today next
 * shows. Every moment is shown once: its key, the server's event key such as `conquered:{at}` or
 * `level:4`, is remembered, and the inbox poll uses the same memory so a moment seen in the app
 * never comes back as a system notification, nor one already notified as a pop-up.
 */
internal object AnkiquestMoments {
    private const val PREF_PENDING = "ankiquestMomentsPending"
    private const val PREF_SHOWN = "ankiquestMomentsShown"
    private const val PREF_QUIET = "ankiquestMomentsQuiet"
    private const val PREF_MINIMUM_DAY = "ankiquestMinimumMomentDay"
    private const val EXTRA_MOMENT = "ankiquest.moment"
    private const val EXTRA_OWNER = "ankiquest.moment_owner"
    private val milestoneDays = setOf(7, 30, 100, 365)

    /** Kept for continuity with the conquest pop-up this replaces: the review ids of conquests already seen. */
    private const val PREF_CONQUESTS_SEEN = "ankiquestConquestsShown"

    /** How many shown keys are remembered; the server stops sending a moment long before this many newer ones. */
    private const val SHOWN_LIMIT = 300

    /** The server lists conquests for longer, but only announces them this long; neither does this. */
    private const val FRESH_MS = 2 * 24 * 60 * 60 * 1000L

    /** What a moment is about, most important first when several wait. */
    enum class Kind(
        val priority: Int,
    ) {
        SECURED(110),
        STREAK(100),
        LEVEL(90),
        RANK(80),
        ACHIEVEMENT(70),
        RECORD(60),
        LEECH(55),
        CONQUERED(50),
        GOAL(40),
        UNLOCKS(30),
    }

    /**
     * One moment as the card shows it. [key] names it for "shown once"; [win] and [notification] are
     * the server's ids to share it by, [place] a top-three finish to share without a win, and
     * [conquest] the review id of a conquered card. [shareTitle] and [shareBody] are the server's
     * first-person words for sharing, when it sends them.
     */
    data class Moment(
        val key: String,
        val kind: Kind,
        val title: String,
        val detail: String,
        val at: Long,
        val win: Long? = null,
        val notification: Long? = null,
        val place: Int? = null,
        val conquest: Long? = null,
        val lapses: Int = 0,
        val deck: String? = null,
        val shareTitle: String? = null,
        val shareBody: String? = null,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("key", key)
                .put("kind", kind.name)
                .put("title", title)
                .put("detail", detail)
                .put("at", at)
                .putOpt("win", win)
                .putOpt("notification", notification)
                .putOpt("place", place)
                .putOpt("conquest", conquest)
                .put("lapses", lapses)
                .putOpt("deck", deck)
                .putOpt("share_title", shareTitle)
                .putOpt("share_body", shareBody)

        companion object {
            fun parse(json: JSONObject): Moment? {
                val kind = Kind.entries.firstOrNull { it.name == json.optString("kind") } ?: return null

                fun long(name: String) = json.optLong(name).takeIf { json.has(name) && it != 0L }

                fun text(name: String) = if (json.isNull(name)) null else json.optString(name).ifEmpty { null }
                return Moment(
                    key = json.optString("key").ifEmpty { return null },
                    kind = kind,
                    title = json.optString("title"),
                    detail = json.optString("detail"),
                    at = json.optLong("at"),
                    win = long("win"),
                    notification = long("notification"),
                    place = json.optInt("place").takeIf { it > 0 },
                    conquest = long("conquest"),
                    lapses = json.optInt("lapses"),
                    deck = text("deck"),
                    shareTitle = text("share_title"),
                    shareBody = text("share_body"),
                )
            }
        }
    }

    /** What the inbox poll should do with a row: notify as before, or leave it to the app. */
    enum class Route {
        /** Not one of the player's moments, or the app is not in front: a system notification. */
        NOTIFY,

        /** Waits in the queue for the app to show. */
        QUEUED,

        /** Seen already, in the app or as a notification. */
        SEEN,

        /** Routine: only the quiet badge. */
        QUIET,
    }

    private var showing = WeakReference<Dialog>(null)

    private data class StudyDay(
        val start: Long,
        val end: Long,
    ) {
        val key: String get() = "minimum:$start"

        operator fun contains(at: Long): Boolean = at in start until end
    }

    /** Local crossing -> queued card -> confirmation from the server inbox. */
    @Synchronized
    internal fun claimMinimum(
        context: Context,
        prefs: SharedPreferences,
        account: HomeAccount,
        server: AnkiquestDay.Server,
        verdict: AnkiquestDay.Verdict,
        now: Long,
    ): Boolean {
        if (AnkiquestHomeData.account(prefs)?.scope != account.scope || !verdict.met) {
            return false
        }
        val owner = account.notificationAccount
        val day = StudyDay(server.startsAt ?: return false, server.dayEndsAt)
        if (now !in day || recordedDay(prefs, owner)?.contains(now) == true) {
            return false
        }
        rememberDay(prefs, owner, day)

        // A milestone keeps its server card. An uncertain count waits for confirmation.
        val streak = verdict.streak
        if (streak != null && streak !in milestoneDays) {
            val language = AnkiquestLanguage.context(context)
            offer(
                prefs,
                owner,
                listOf(
                    Moment(
                        key = day.key,
                        kind = Kind.SECURED,
                        title = language.getString(R.string.aq_streak_minimum_title),
                        detail = streakDays(language, streak),
                        at = now,
                    ),
                ),
            )
        }
        markShown(prefs, owner, listOf(day.key))
        return true
    }

    internal fun streakDays(
        context: Context,
        streak: Int,
    ): String {
        val count = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(streak)
        return context.resources.getQuantityString(R.plurals.aq_streak_minimum_days, streak, count)
    }

    private fun recordedDay(
        prefs: SharedPreferences,
        account: String,
    ): StudyDay? {
        val parts = prefs.getString("$PREF_MINIMUM_DAY:$account", null)?.split(':', limit = 2) ?: return null
        val start = parts.firstOrNull()?.toLongOrNull() ?: return null
        val end = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return StudyDay(start, end)
    }

    private fun rememberDay(
        prefs: SharedPreferences,
        account: String,
        day: StudyDay,
    ) {
        prefs.edit { putString("$PREF_MINIMUM_DAY:$account", "${day.start}:${day.end}") }
    }

    private fun ownServer(
        prefs: SharedPreferences,
        account: String,
    ): AnkiquestDay.Server? {
        if (AnkiquestHomeData.account(prefs)?.notificationAccount != account) {
            return null
        }
        return AnkiquestDay.last(prefs)
    }

    private fun routeMinimum(
        prefs: SharedPreferences,
        account: String,
        moment: Moment,
        foreground: Boolean,
    ): Route {
        val server = ownServer(prefs, account)
        val day = server?.let { snapshot -> snapshot.startsAt?.let { StudyDay(it, snapshot.dayEndsAt) } }
        if (day != null && moment.at < day.start) {
            return Route.SEEN
        }
        if (day != null && moment.at in day &&
            AnkiquestDay.judge(server, emptyList(), moment.at)?.let { it.confirmed && it.streak in milestoneDays } == true
        ) {
            return Route.SEEN
        }

        val recorded = recordedDay(prefs, account)?.takeIf { moment.at in it }
        if (recorded != null) {
            val queue = pending(prefs, account)
            val local = queue.firstOrNull { it.key == recorded.key }
            if (local != null) {
                val confirmed =
                    local.copy(
                        win = moment.win,
                        notification = moment.notification,
                        shareTitle = moment.shareTitle,
                        shareBody = moment.shareBody,
                    )
                save(prefs, account, queue.map { if (it.key == recorded.key) confirmed else it })
            }
            markShown(prefs, account, listOf(moment.key))
            return if (local == null) Route.SEEN else Route.QUEUED
        }

        if (!foreground) {
            return Route.NOTIFY
        }
        offer(prefs, account, listOf(moment))
        rememberMinimum(prefs, account, moment)
        return Route.QUEUED
    }

    /** Records a quiet notification or queued server card against the same window as a local crossing. */
    @Synchronized
    internal fun rememberMinimum(
        prefs: SharedPreferences,
        account: String,
        moment: Moment,
    ) {
        val server = ownServer(prefs, account) ?: return
        val day = StudyDay(server.startsAt ?: return, server.dayEndsAt)
        if (moment.at !in day) {
            return
        }
        rememberDay(prefs, account, day)
        markShown(prefs, account, listOf(day.key))
    }

    // ---- Sources ----------------------------------------------------------------------------

    /**
     * The newly conquered cards among an upload's [conquests] for [account]: those not seen before and
     * conquered within [FRESH_MS] of [now]. Every listed card is remembered, so none comes back, and on
     * the first look every card already there counts as seen, so signing in does not celebrate old cards.
     */
    @VisibleForTesting
    internal fun freshConquests(
        prefs: SharedPreferences,
        account: String,
        conquests: JSONArray?,
        now: Long,
    ): List<JSONObject> {
        val listed =
            (0 until (conquests?.length() ?: 0))
                .mapNotNull { conquests?.optJSONObject(it) }
                .filter { it.optLong("at") > 0 }
        val key = "$PREF_CONQUESTS_SEEN:$account"
        val known = prefs.contains(key)
        val seen =
            prefs
                .getString(key, "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull() }
                .toSet()
        val fresh = if (known) listed.filter { it.getLong("at") !in seen && now - it.getLong("at") < FRESH_MS } else emptyList()
        if (known && fresh.isEmpty()) return emptyList()
        // Cards leave the server's short list only for newer ones, so the list is all there is to remember.
        prefs.edit { putString(key, listed.map { it.getLong("at") }.distinct().joinToString(",")) }
        return fresh.sortedByDescending { it.getLong("at") }
    }

    /** A conquered card's moment, with its front [text] and [deck] from this phone when known. */
    fun conquest(
        json: JSONObject,
        title: String,
        detail: String,
        deck: String?,
    ): Moment {
        val at = json.getLong("at")
        return Moment(
            key = "conquered:$at",
            kind = if (json.optBoolean("leech")) Kind.LEECH else Kind.CONQUERED,
            title = title,
            detail = detail,
            at = at,
            conquest = at,
            lapses = json.optInt("lapses"),
            deck = deck,
        )
    }

    /**
     * The moment an inbox row of the player's own is about, or null when it is not one: only
     * `celebration` rows are. Newer servers name the win's event key in `moment`; for older ones the
     * row's id stands in.
     */
    fun fromNotice(entry: JSONObject): Moment? {
        if (entry.optString("kind") != "celebration") return null
        val id = entry.optLong("id")
        val topic = if (entry.isNull("topic")) null else entry.optString("topic").ifEmpty { null }
        val kind =
            when (topic) {
                "streak" -> Kind.STREAK
                "level" -> Kind.LEVEL
                "achievement" -> Kind.ACHIEVEMENT
                "record" -> Kind.RECORD
                "conquered" -> Kind.CONQUERED
                "goal" -> Kind.GOAL
                "secured" -> Kind.SECURED
                else -> Kind.UNLOCKS
            }
        val key = if (entry.isNull("moment")) "" else entry.optString("moment")
        return Moment(
            key = key.ifEmpty { "notice:$id" },
            kind = kind,
            title = entry.optString("title"),
            detail = entry.optString("body"),
            at = entry.optLong("created_at") * 1000,
            win = entry.optLong("win").takeIf { it > 0 },
            notification = id.takeIf { it > 0 },
            shareTitle = entry.optString("share_title").ifEmpty { null },
            shareBody = entry.optString("share_body").ifEmpty { null },
        )
    }

    /** A climb on the weekly board to [place] (1-based), as the server worded it in [change]. */
    fun rank(
        change: JSONObject,
        place: Int,
        week: Long,
        at: Long,
    ): Moment =
        Moment(
            key = "rank:$week:$place",
            kind = Kind.RANK,
            title = change.optString("title"),
            detail = change.optString("body"),
            at = at,
            place = place.takeIf { it in 1..3 },
            shareTitle = change.optString("share_title").ifEmpty { null },
            shareBody = change.optString("share_body").ifEmpty { null },
        )

    // ---- Queue ------------------------------------------------------------------------------

    /**
     * Decides what becomes of an inbox [entry] for [account]: its moment is queued when the app is in
     * [foreground], counted quietly when routine, or skipped when already seen. Anything else is left
     * to a system notification; call [markShown] once that is posted.
     */
    @Synchronized
    fun route(
        prefs: SharedPreferences,
        account: String,
        entry: JSONObject,
        foreground: Boolean,
    ): Route {
        val moment = fromNotice(entry) ?: return Route.NOTIFY
        if (known(prefs, account, moment.key)) return Route.SEEN
        if (moment.kind == Kind.SECURED) {
            return routeMinimum(prefs, account, moment, foreground)
        }
        if (entry.optBoolean("quiet")) {
            quietly(prefs, account, moment)
            return Route.QUIET
        }
        if (!foreground) {
            return Route.NOTIFY
        }
        // An older server does not say which card a conquest row is about, and the upload already queued it.
        if (moment.kind == Kind.CONQUERED && moment.key.startsWith("notice:")) return Route.SEEN
        offer(prefs, account, listOf(moment))
        return Route.QUEUED
    }

    /** Adds [moments] to [account]'s queue, leaving out any already shown or waiting. */
    @Synchronized
    fun offer(
        prefs: SharedPreferences,
        account: String,
        moments: List<Moment>,
    ) {
        val pending = pending(prefs, account).toMutableList()
        val shown = shown(prefs, account)
        for (moment in moments) {
            if (moment.key in shown || pending.any { it.key == moment.key }) continue
            pending += moment
        }
        save(prefs, account, pending)
    }

    /** Whether [key] was shown already or waits to be. */
    fun known(
        prefs: SharedPreferences,
        account: String,
        key: String,
    ): Boolean = key in shown(prefs, account) || pending(prefs, account).any { it.key == key }

    /** Remembers [keys] as shown, so neither the app nor the poll shows them again. */
    @Synchronized
    fun markShown(
        prefs: SharedPreferences,
        account: String,
        keys: Collection<String>,
    ) {
        if (keys.isEmpty()) return
        val list = shownList(prefs, account).filter { it !in keys } + keys
        prefs.edit { putString("$PREF_SHOWN:$account", JSONArray(list.takeLast(SHOWN_LIMIT)).toString()) }
    }

    /**
     * Takes everything waiting for [account], most important first and newest first within a kind,
     * and remembers it as shown. Empty when nothing waits.
     */
    @Synchronized
    fun take(
        prefs: SharedPreferences,
        account: String,
    ): List<Moment> {
        val pending = pending(prefs, account)
        if (pending.isEmpty()) return emptyList()
        val ordered = pending.sortedWith(compareByDescending<Moment> { it.kind.priority }.thenByDescending { it.at })
        save(prefs, account, emptyList())
        markShown(prefs, account, ordered.map { it.key })
        return ordered
    }

    fun pending(
        prefs: SharedPreferences,
        account: String,
    ): List<Moment> {
        val stored = prefs.getString("$PREF_PENDING:$account", null) ?: return emptyList()
        return try {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Moment::parse) }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    private fun save(
        prefs: SharedPreferences,
        account: String,
        moments: List<Moment>,
    ) = prefs.edit {
        if (moments.isEmpty()) {
            remove("$PREF_PENDING:$account")
        } else {
            putString("$PREF_PENDING:$account", JSONArray(moments.map { it.json() }).toString())
        }
    }

    private fun shownList(
        prefs: SharedPreferences,
        account: String,
    ): List<String> =
        try {
            val array = JSONArray(prefs.getString("$PREF_SHOWN:$account", null) ?: "[]")
            (0 until array.length()).map { array.optString(it) }
        } catch (_: org.json.JSONException) {
            emptyList()
        }

    private fun shown(
        prefs: SharedPreferences,
        account: String,
    ): Set<String> = shownList(prefs, account).toSet()

    // ---- Quiet badge ------------------------------------------------------------------------

    private fun quietly(
        prefs: SharedPreferences,
        account: String,
        moment: Moment,
    ) {
        markShown(prefs, account, listOf(moment.key))
        prefs.edit { putInt("$PREF_QUIET:$account", quiet(prefs, account) + 1) }
    }

    /** How many routine moments arrived since Today was last opened. */
    fun quiet(
        prefs: SharedPreferences,
        account: String,
    ): Int = prefs.getInt("$PREF_QUIET:$account", 0)

    /** Today was opened: the routine moments there have been seen. */
    fun clearQuiet(prefs: SharedPreferences) {
        val account = AnkiquestHomeData.account(prefs)?.notificationAccount ?: return
        prefs.edit { remove("$PREF_QUIET:$account") }
    }

    /** The badge on [navigation]'s Today tab: how many routine moments wait there, or none. */
    fun showBadge(
        navigation: BottomNavigationView?,
        prefs: SharedPreferences,
    ) {
        if (navigation?.menu?.findItem(R.id.ankiquest_nav_today) == null) return
        val account = AnkiquestHomeData.account(prefs)?.notificationAccount
        val count = account?.let { quiet(prefs, it) } ?: 0
        if (count > 0) {
            navigation.getOrCreateBadge(R.id.ankiquest_nav_today).apply {
                number = count
                isVisible = true
            }
        } else {
            navigation.removeBadge(R.id.ankiquest_nav_today)
        }
    }

    // ---- Showing ----------------------------------------------------------------------------

    internal fun noticeIntent(
        context: Context,
        account: String,
        moment: Moment,
    ): Intent =
        AnkiquestHomeActivity
            .intent(context)
            .putExtra(EXTRA_OWNER, account)
            .putExtra(EXTRA_MOMENT, moment.json().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notice(
        host: Activity,
        account: HomeAccount,
        prefs: SharedPreferences,
    ): Moment? {
        val intent = host.intent ?: return null
        val payload = intent.getStringExtra(EXTRA_MOMENT) ?: return null
        val owner = intent.getStringExtra(EXTRA_OWNER)
        intent.removeExtra(EXTRA_MOMENT)
        intent.removeExtra(EXTRA_OWNER)
        if (owner != account.notificationAccount || account.token.isEmpty()) {
            return null
        }
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            return null
        }
        val moment = runCatching { Moment.parse(JSONObject(payload)) }.getOrNull() ?: return null
        // Android can restore the original intent, including the removed extras.
        val key = "opened:${moment.key}"
        if (known(prefs, account.notificationAccount, key)) {
            return null
        }
        markShown(prefs, account.notificationAccount, listOf(key))
        return moment
    }

    /**
     * Whether [host] is a screen where studying has stopped: the deck list, the deck overview or
     * congrats screen, or Today. Moments never show anywhere else, above all not over a review.
     */
    fun resting(host: Activity): Boolean {
        val fragments = (host as? FragmentActivity)?.supportFragmentManager?.fragments.orEmpty()
        if (host is Reviewer || fragments.any { it is ReviewerFragment && it.isVisible }) {
            return false
        }
        return host is DeckPicker || host is StudyOptionsActivity || host is AnkiquestHomeActivity || fragments.any { it is CongratsPage }
    }

    /**
     * Shows what waits for the signed-in account over [host], when it is [resting] and no moment is
     * showing yet. Call it on the main thread.
     */
    fun present(
        host: Activity,
        prefs: SharedPreferences,
        resting: Boolean = resting(host),
    ) {
        if (!resting || host.isFinishing || host.isDestroyed) return
        val account = AnkiquestHomeData.account(prefs) ?: return
        val tapped = notice(host, account, prefs)
        val current = showing.get()?.takeIf { it.isShowing }
        if (current != null) {
            if (tapped == null) {
                return
            }
            current.dismiss()
        }
        val moments = if (tapped == null) take(prefs, account.notificationAccount) else listOf(tapped)
        if (moments.isEmpty()) return
        showing = WeakReference(AnkiquestMomentCard.show(host, account, moments))
    }
}
