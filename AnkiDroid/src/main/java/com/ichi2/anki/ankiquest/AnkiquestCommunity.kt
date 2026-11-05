// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.annotation.StringRes
import com.ichi2.anki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * The rest of the website's community pages, as they read them: the competition calendar
 * (`/community#calendar`, `GET /api/community`), the player's community reminders
 * (`/community#reminders`, `GET` and `POST /api/community/reminders/{user}`) and a conquered card's
 * page (`/conquered/{user}/{at}`, `GET /api/conquests/{user}/{at}` and its `tell` action), as
 * docs/mobile-api.md describes them.
 */
internal object AnkiquestCommunity {
    /** The server's reminder kinds, in the website's order, each a switch the player turns on. */
    enum class Reminder(
        val key: String,
        @StringRes val title: Int,
        @StringRes val summary: Int,
    ) {
        GENTLE_DAILY("gentle_daily", R.string.aq_reminder_gentle_daily, R.string.aq_reminder_gentle_daily_summary),
        URGENT_STREAK("urgent_streak", R.string.aq_reminder_urgent_streak, R.string.aq_reminder_urgent_streak_summary),
        FREEZE_USED("freeze_used", R.string.aq_reminder_freeze_used, R.string.aq_reminder_freeze_used_summary),
        FREEZE_REFILL("freeze_refill", R.string.aq_reminder_freeze_refill, R.string.aq_reminder_freeze_refill_summary),
        MILESTONE("milestone", R.string.aq_reminder_milestone, R.string.aq_reminder_milestone_summary),
        WEEKLY_CLOSING("weekly_closing", R.string.aq_reminder_weekly_closing, R.string.aq_reminder_weekly_closing_summary),
        WEEKLY_RECAP("weekly_recap", R.string.aq_reminder_weekly_recap, R.string.aq_reminder_weekly_recap_summary),
    }

    /** The player's reminder settings; the server replaces all of them at once. Hours are 0 to 23 in the player's time zone. */
    data class Reminders(
        val enabled: Set<Reminder>,
        val hour: Int,
        val quietStart: Int,
        val quietEnd: Int,
        val dailyLimit: Int,
    ) {
        /** Identical start and end hours switch quiet hours off. */
        val quiet: Boolean get() = quietStart != quietEnd

        fun json(): JSONObject =
            JSONObject().apply {
                for (reminder in Reminder.entries) put(reminder.key, reminder in enabled)
                put("reminder_hour", hour)
                put("quiet_start", quietStart)
                put("quiet_end", quietEnd)
                put("daily_limit", dailyLimit)
            }

        companion object {
            val HOURS = 0..23
            val LIMITS = 1..5

            /** The defaults the server documents, for fields an answer leaves out. */
            fun parse(json: JSONObject): Reminders =
                Reminders(
                    enabled = Reminder.entries.filterTo(mutableSetOf()) { json.optBoolean(it.key) },
                    hour = json.optInt("reminder_hour", 20).coerceIn(HOURS),
                    quietStart = json.optInt("quiet_start", 22).coerceIn(HOURS),
                    quietEnd = json.optInt("quiet_end", 9).coerceIn(HOURS),
                    dailyLimit = json.optInt("daily_limit", 2).coerceIn(LIMITS),
                )
        }
    }

    suspend fun reminders(account: HomeAccount): Reminders = Reminders.parse(call(account, "GET", remindersPath(account)))

    /** Saves [value] and answers what the server kept. */
    suspend fun saveReminders(
        account: HomeAccount,
        value: Reminders,
    ): Reminders = Reminders.parse(call(account, "POST", remindersPath(account), value.json()))

    private fun remindersPath(account: HomeAccount) = "api/community/reminders/${account.encodedUser}"

    /** The competition calendar's three views. */
    enum class Kind(
        val query: String,
        @StringRes val label: Int,
    ) {
        DAY("day", R.string.aq_calendar_days),
        WEEK("week", R.string.aq_calendar_weeks),
        MONTH("month", R.string.aq_calendar_months),
        ;

        companion object {
            fun of(name: String?): Kind = entries.firstOrNull { it.query == name } ?: DAY
        }
    }

    data class Standing(
        val user: String,
        val display: String,
        val xp: Long,
        val reviews: Long,
        val timeMs: Long,
        val newCards: Long,
        val daysActive: Long,
    )

    /** One day, week or month of the competition. [start] and [end] are `YYYY-MM-DD`, [end] exclusive. */
    data class Period(
        val key: String,
        val kind: Kind,
        val start: String,
        val end: String,
        /** `ongoing`, `provisional` (waiting for late syncs) or `final`. */
        val status: String,
        val reconstructed: Boolean,
        val partial: Boolean,
        /** Usernames; more than one for a shared win, none when nobody studied. */
        val winners: List<String>,
        val margin: Long,
        val standings: List<Standing>,
    ) {
        val final: Boolean get() = status == "final"

        /** What a calendar entry says about this period, such as `day:2026-10-28`. */
        val reference: String get() = "${kind.query}:$key"
    }

    data class Calendar(
        val year: Int,
        val month: Int,
        val days: List<Period>,
        val weeks: List<Period>,
        val seasons: List<Period>,
        /** Display names of every player the archive knows. */
        val names: Map<String, String>,
        val timeZone: String,
        val rolloverHour: Int,
        val graceHours: Int,
        val scoringNote: String,
        /** The first day with history, `YYYY-MM-DD`, or null before anyone studied. */
        val startDate: String?,
    ) {
        fun periods(kind: Kind): List<Period> =
            when (kind) {
                Kind.DAY -> days
                Kind.WEEK -> weeks
                Kind.MONTH -> seasons
            }

        fun name(user: String): String = names[user] ?: user

        fun find(reference: String): Period? {
            val kind = Kind.entries.firstOrNull { reference.startsWith(it.query + ":") } ?: return null
            val key = reference.substringAfter(':')
            return periods(kind).firstOrNull { it.key == key }
        }
    }

    /** The archive for [year], with the days of [month] (1 to 12). */
    suspend fun calendar(
        account: HomeAccount,
        year: Int,
        month: Int,
    ): Calendar =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/community")
                    .newBuilder()
                    .addQueryParameter("year", year.toString())
                    .addQueryParameter("month", month.toString())
                    .build()
            parseCalendar(AnkiquestApi.json(account, AnkiquestApi.request(url, account.token, account.language).build()))
        }

    fun parseCalendar(json: JSONObject): Calendar {
        val meta = json.getJSONObject("meta")
        val names = mutableMapOf<String, String>()
        json.optJSONArray("players")?.objects()?.forEach { player ->
            val user = player.text("user")
            if (user.isNotEmpty()) names[user] = player.text("display").trim().ifEmpty { user }
        }

        fun periods(
            field: String,
            kind: Kind,
        ) = json
            .optJSONArray(field)
            ?.objects()
            .orEmpty()
            .mapNotNull { period(it, kind, names) }
        return Calendar(
            year = meta.optInt("year"),
            month = meta.optInt("month"),
            days = periods("calendar", Kind.DAY),
            weeks = periods("weeks", Kind.WEEK),
            seasons = periods("seasons", Kind.MONTH),
            names = names,
            timeZone = meta.text("time_zone"),
            rolloverHour = meta.optInt("rollover_hour"),
            graceHours = meta.optInt("grace_hours", 24),
            scoringNote = meta.text("scoring_note"),
            startDate = meta.text("start_date").ifEmpty { null },
        )
    }

    private fun period(
        json: JSONObject,
        kind: Kind,
        names: MutableMap<String, String>,
    ): Period? {
        val key = json.text("key").ifEmpty { return null }
        val standings =
            json.optJSONArray("standings")?.objects().orEmpty().mapNotNull { row ->
                val user = row.text("user").ifEmpty { return@mapNotNull null }
                val display = row.text("display").trim().ifEmpty { names[user] ?: user }
                names.putIfAbsent(user, display)
                Standing(
                    user = user,
                    display = display,
                    xp = row.optLong("xp"),
                    reviews = row.optLong("reviews"),
                    timeMs = row.optLong("time_ms"),
                    newCards = row.optLong("new_cards"),
                    daysActive = row.optLong("days_active"),
                )
            }
        return Period(
            key = key,
            kind = kind,
            start = json.text("start").ifEmpty { key },
            end = json.text("end"),
            status = json.text("status"),
            reconstructed = json.optBoolean("reconstructed"),
            partial = json.optBoolean("partial"),
            winners = json.optJSONArray("winners")?.strings().orEmpty(),
            margin = json.optLong("winning_margin"),
            standings = standings,
        )
    }

    /** A card [user] forgot again and again and learned at last; [told] and [friends] are known to its owner only. */
    data class Conquest(
        val user: String,
        val display: String,
        /** The answer that conquered it, which also names it (Unix ms). */
        val at: Long,
        /** Its first answer in the retained history (Unix ms). */
        val since: Long,
        val lapses: Int,
        val answers: Int,
        val leech: Boolean,
        /** What the card was, when its owner chose to say. */
        val label: String?,
        val told: Boolean?,
        val friends: Int?,
        /** The owner's win for it, whose card they can share; null until the server keeps one, and for anyone else. */
        val win: Long? = null,
    ) {
        val mine: Boolean get() = told != null

        /** How long it took, at least one day, as the website counts it. */
        val days: Long get() = maxOf(1L, ((at - since) / DAY_MS.toDouble()).roundToLong())
    }

    /** The card does not exist, was undone, or belongs to a player this viewer may not see. */
    class Missing : Exception()

    /** @throws Missing when the server answers `404` */
    suspend fun conquest(
        account: HomeAccount,
        user: String,
        at: Long,
    ): Conquest =
        try {
            parseConquest(call(account, "GET", conquestPath(user, at)))
        } catch (e: AnkiquestHttpException) {
            if (e.code == 404) throw Missing()
            throw e
        }

    fun parseConquest(json: JSONObject): Conquest {
        val conquest = json.getJSONObject("conquest")
        val user = json.text("user")
        return Conquest(
            user = user,
            display = json.text("display").trim().ifEmpty { user },
            at = conquest.getLong("at"),
            since = conquest.optLong("since", conquest.getLong("at")),
            lapses = conquest.optInt("lapses"),
            answers = conquest.optInt("answers"),
            leech = conquest.optBoolean("leech"),
            label = json.text("label").trim().ifEmpty { null },
            told = if (json.has("told")) json.optBoolean("told") else null,
            friends = if (json.has("friends")) json.optInt("friends") else null,
            win = json.optLong("win").takeIf { it > 0 },
        )
    }

    /** Puts the owner's conquered card in each friend's activity, once; answers how many friends there are. */
    suspend fun tell(
        account: HomeAccount,
        at: Long,
    ): Int = call(account, "POST", conquestPath(account.user, at) + "/tell", JSONObject()).optInt("told")

    /** The card's page on [server], to share where the app is not installed. */
    fun conquestLink(
        server: String,
        user: String,
        at: Long,
    ): String = "${server.trimEnd('/')}/conquered/${encode(user)}/$at"

    private fun conquestPath(
        user: String,
        at: Long,
    ) = "api/conquests/${encode(user)}/$at"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private const val DAY_MS = 86_400_000L

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private suspend fun call(
        account: HomeAccount,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val request =
                AnkiquestApi
                    .request(account, path)
                    .method(method, body?.let(AnkiquestApi::body))
                    .build()
            AnkiquestApi.json(account, request)
        }
}
