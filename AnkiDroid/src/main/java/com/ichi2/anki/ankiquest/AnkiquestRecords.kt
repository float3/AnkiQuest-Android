// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmap
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The record boards (`GET /api/records`) and the player's own study calendar
 * (`GET /api/study/{user}`), the same JSON the website's `/records` and `/history` pages read
 * (docs/mobile-api.md, "records and study history").
 */
internal object AnkiquestRecords {
    /** The boards in the order the website shows them; [window] is the server's name for each. */
    enum class Kind(
        val window: String,
    ) {
        HOUR("hour"),
        DAY("day"),
        WEEK("week"),
        MONTH("month"),
        YEAR("year"),
        STREAK("streak"),
        DAYS("days"),
        ;

        /** A rolling window of XP, rather than a streak or a count of days. */
        val rolling: Boolean get() = this != STREAK && this != DAYS
    }

    data class Holder(
        val user: String,
        val display: String,
        val value: Long,
        /** Reviews in the window; 0 for streaks and study days. */
        val detail: Long,
        /** Unix milliseconds: a window's first review, when a streak ended, or the first study day. */
        val at: Long,
        /** Unix milliseconds: a window's last review; otherwise [at]. */
        val until: Long,
    )

    data class Board(
        val kind: Kind,
        /** `xp` or `days`. */
        val unit: String,
        /** Best first, at most three. */
        val holders: List<Holder>,
    )

    /** Who a board shows, as the website's board picker offers it; [value] is the `scope` query. */
    data class Scope(
        val value: String,
        /** A group's name; null for [CIRCLE] and [GLOBAL], which the screen names itself. */
        val name: String? = null,
    ) {
        companion object {
            val CIRCLE = Scope("circle")
            val GLOBAL = Scope("global")
        }
    }

    /** The boards among [scope], or the server's default (friends and groups, or everyone public) without one. */
    suspend fun records(
        account: HomeAccount,
        scope: Scope? = null,
    ): List<Board> =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/records")
                    .newBuilder()
                    .apply { scope?.let { addQueryParameter("scope", it.value) } }
                    .build()
            val request = AnkiquestApi.request(url, account.token, account.language).build()
            parseRecords(AnkiquestApi.call(account, request) { AnkiquestApi.jsonArray(it) })
        }

    /**
     * Which scopes the player can pick between: none for a player without friends or groups, as on
     * the website, otherwise friends and groups, everyone, and each group. Answers none when the
     * server cannot say, since the boards still show without a choice.
     */
    suspend fun scopes(account: HomeAccount): List<Scope> =
        withContext(Dispatchers.IO) {
            parseScopes(AnkiquestApi.json(account, AnkiquestApi.request(account, "api/social/${account.encodedUser}").build()))
        }

    fun parseRecords(array: JSONArray): List<Board> {
        val boards =
            array.objects().mapNotNull { board ->
                val kind = Kind.entries.firstOrNull { it.window == board.optString("window") } ?: return@mapNotNull null
                val holders =
                    board.optJSONArray("holders")?.objects().orEmpty().map { holder ->
                        val at = holder.optLong("at")
                        Holder(
                            user = holder.getString("user"),
                            display = holder.optString("display").ifEmpty { holder.getString("user") },
                            value = holder.optLong("value"),
                            detail = holder.optLong("detail"),
                            at = at,
                            until = holder.optLong("until", at),
                        )
                    }
                Board(kind, board.optString("unit", if (kind.rolling) "xp" else "days"), holders.filter { it.value > 0 })
            }
        return Kind.entries.mapNotNull { kind -> boards.firstOrNull { it.kind == kind } }
    }

    fun parseScopes(social: JSONObject): List<Scope> {
        val groups = social.optJSONArray("groups")?.objects().orEmpty()
        val friends = social.optJSONArray("friends")?.length() ?: 0
        if (groups.isEmpty() && friends == 0) return emptyList()
        return listOf(Scope.CIRCLE, Scope.GLOBAL) +
            groups.map { group -> Scope("group:${group.getLong("id")}", group.optString("name").ifEmpty { null }) }
    }

    data class Day(
        val date: LocalDate,
        val reviews: Int,
        val timeMs: Long,
        val xp: Long,
        val newCards: Int,
        /** The streak on that day. */
        val streak: Int,
        /** A streak freeze covered the day. */
        val frozen: Boolean,
    ) {
        val minutes: Long get() = Math.round(timeMs / 60_000.0)

        companion object {
            fun empty(date: LocalDate) = Day(date, 0, 0, 0, 0, 0, false)
        }
    }

    data class Study(
        val year: Int,
        /** Every year with history, newest first, always including [year]. */
        val years: List<Int>,
        /** One entry per Anki day of [year] from the player's first study day on, oldest first. */
        val days: List<Day>,
        /** Unix milliseconds of the newest uploaded review. */
        val lastReviewAt: Long?,
        /** Unix milliseconds of when reviews last arrived. */
        val lastReceivedAt: Long?,
    ) {
        val reviews: Long get() = days.sumOf { it.reviews.toLong() }
        val studyDays: Int get() = days.count { it.reviews > 0 }
        val xp: Long get() = days.sumOf { it.xp }

        fun day(date: LocalDate): Day = days.firstOrNull { it.date == date } ?: Day.empty(date)

        /** The day the screen opens on, as the website picks it: the latest with reviews, else the latest. */
        val latest: LocalDate? get() = days.lastOrNull { it.reviews > 0 }?.date ?: days.lastOrNull()?.date

        /** The calendar the review heatmap draws; [today] marks where the past ends. */
        fun heatmap(today: LocalDate): ReviewHeatmapData {
            val last = days.lastOrNull()?.date
            val end = if (last != null && last > today) last else today
            return ReviewHeatmap.summarize(
                end,
                days.associate { it.date to it.reviews },
                emptyMap(),
                days.filter { it.frozen }.map { it.date }.toSet(),
            )
        }
    }

    /** [account]'s own study calendar for [year], or for their current year without one. */
    suspend fun study(
        account: HomeAccount,
        year: Int? = null,
    ): Study =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/study/${account.encodedUser}")
                    .newBuilder()
                    .apply { year?.let { addQueryParameter("year", it.toString()) } }
                    .build()
            parseStudy(AnkiquestApi.json(account, AnkiquestApi.request(url, account.token, account.language).build()))
        }

    fun parseStudy(json: JSONObject): Study {
        val year = json.getInt("year")
        val days =
            json
                .optJSONArray("days")
                ?.objects()
                .orEmpty()
                .mapNotNull { day ->
                    val date = runCatching { LocalDate.parse(day.optString("date")) }.getOrNull() ?: return@mapNotNull null
                    Day(
                        date = date,
                        reviews = day.optInt("reviews"),
                        timeMs = day.optLong("time_ms"),
                        xp = day.optLong("xp"),
                        newCards = day.optInt("new_cards"),
                        streak = day.optInt("streak"),
                        frozen = day.optBoolean("frozen"),
                    )
                }.sortedBy { it.date }
        val years = json.optJSONArray("years")?.let { list -> (0 until list.length()).map { list.getInt(it) } }.orEmpty()
        return Study(
            year = year,
            years = (years + year).distinct().sortedDescending(),
            days = days,
            lastReviewAt = json.optMillis("last_review_at"),
            lastReceivedAt = json.optMillis("last_received_at"),
        )
    }

    private fun JSONObject.optMillis(name: String): Long? = if (isNull(name)) null else optLong(name).takeIf { it > 0 }
}
