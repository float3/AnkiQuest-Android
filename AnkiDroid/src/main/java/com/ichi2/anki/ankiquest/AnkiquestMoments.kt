// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.StudyOptionsActivity
import com.ichi2.anki.pages.CongratsPage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The player's big moments (a card conquered, a level, a streak milestone, an achievement, a climb
 * on the board, …), shown one card at a time when studying stops, never over a review.
 *
 * Moments wait in a queue kept per account in preferences, so one learned of mid-session, or just
 * before the app was closed, is still there when the deck list, the congrats screen or Today next
 * shows. Every moment is shown once: its key, the server's event key such as `conquered:{at}` or
 * `level:4`, is remembered, and the inbox poll uses the same memory so a moment seen in the app
 * never comes back as a system notification, nor one already notified as a pop-up. Routine moments,
 * such as securing the streak on an ordinary day, never pop up: they only add to a quiet badge on Today.
 */
internal object AnkiquestMoments {
    private const val PREF_PENDING = "ankiquestMomentsPending"
    private const val PREF_SHOWN = "ankiquestMomentsShown"
    private const val PREF_QUIET = "ankiquestMomentsQuiet"

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
        STREAK(100),
        LEVEL(90),
        RANK(80),
        ACHIEVEMENT(70),
        RECORD(60),
        LEECH(55),
        CONQUERED(50),
        GOAL(40),
        UNLOCKS(30),
        SECURED(20),
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
    fun route(
        prefs: SharedPreferences,
        account: String,
        entry: JSONObject,
        foreground: Boolean,
    ): Route {
        val moment = fromNotice(entry) ?: return Route.NOTIFY
        if (known(prefs, account, moment.key)) return Route.SEEN
        if (entry.optBoolean("quiet")) {
            quietly(prefs, account, moment)
            return Route.QUIET
        }
        if (!foreground) return Route.NOTIFY
        // An older server does not say which card a conquest row is about, and the upload already queued it.
        if (moment.kind == Kind.CONQUERED && moment.key.startsWith("notice:")) return Route.SEEN
        offer(prefs, account, listOf(moment))
        return Route.QUEUED
    }

    /** Adds [moments] to [account]'s queue, leaving out any already shown or waiting. */
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

    /**
     * Whether [host] is a screen where studying has stopped: the deck list, the deck overview or
     * congrats screen, or Today. Moments never show anywhere else, above all not over a review.
     */
    fun resting(host: Activity): Boolean =
        host is DeckPicker ||
            host is StudyOptionsActivity ||
            host is AnkiquestHomeActivity ||
            (host as? FragmentActivity)?.supportFragmentManager?.fragments?.any { it is CongratsPage } == true

    /**
     * Shows what waits for the signed-in account over [host], when it is [resting] and no moment is
     * showing yet. Call it on the main thread.
     */
    fun present(
        host: Activity,
        prefs: SharedPreferences,
        resting: Boolean = resting(host),
    ) {
        if (!resting || !AnkiquestPostStudyPrompts.ready(host)) {
            return
        }
        val account = AnkiquestHomeData.account(prefs) ?: return
        val moments = take(prefs, account.notificationAccount)
        if (moments.isEmpty()) return
        AnkiquestPostStudyPrompts.track(host, AnkiquestMomentCard.show(host, account, moments))
    }
}
