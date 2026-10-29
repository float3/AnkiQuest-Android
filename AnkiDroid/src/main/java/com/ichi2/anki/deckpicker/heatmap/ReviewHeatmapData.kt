// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.deckpicker.heatmap

import com.ichi2.anki.libanki.Collection
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Collection history: counts are answers, not distinct cards or server points.
 *
 * [protectedDays] are days without reviews that AnkiQuest kept the streak through, with a
 * streak freeze or because moving the clock east skipped them. Both streaks count them as
 * AnkiQuest does: such a day keeps a streak going and does not lengthen it.
 */
data class ReviewHeatmapData(
    val today: LocalDate,
    val reviews: Map<LocalDate, Int>,
    val scheduled: Map<LocalDate, Int>,
    val currentStreak: Int,
    val longestStreak: Int,
    val totalReviews: Long,
    val daysStudied: Int,
    val firstReviewDate: LocalDate?,
    val dailyAverage: Double,
    val protectedDays: Set<LocalDate> = emptySet(),
) {
    val forecastEndDate: LocalDate get() = today.plusDays(ReviewHeatmap.FORECAST_DAYS)
}

object ReviewHeatmap {
    const val FORECAST_DAYS = 90L
    private const val DAY_MILLIS = 86_400_000L

    /** Call inside CollectionManager.withCol, away from the main thread. */
    fun load(col: Collection): ReviewHeatmapData {
        val timing = col.backend.schedTimingToday()
        val today = studyDate(timing.nextDayAt)
        val cutoffMillis = timing.nextDayAt * 1000L
        val reviews = mutableMapOf<LocalDate, Int>()
        // Anki's day searches use consecutive 24-hour windows ending at its current
        // scheduler cutoff. Revlog has no historical timezone, so use the same windows
        // as prop:rated, including the exact millisecond at the start of a study day.
        // Do not join cards: retained reviews of deleted cards still represent study.
        col.db
            .query(
                "select (? - 1 - id) / $DAY_MILLIS, count(*) from revlog " +
                    "where id >= 0 and id < ? and ease > 0 and type < 4 group by 1",
                cutoffMillis,
                cutoffMillis,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    reviews[today.minusDays(cursor.getLong(0))] = cursor.getInt(1)
                }
            }
        val scheduled = mutableMapOf<LocalDate, Int>()
        // Match the native browser's prop:due expression, including original due dates
        // in filtered decks and timestamp-based learning/preview queues. New, suspended,
        // and buried cards are not currently scheduled answers in this forecast.
        col.db
            .query(
                "select day_offset, count(*) from (" +
                    "select case when queue in (2, 3) then " +
                    "(case when odue != 0 then odue else due end) - ? " +
                    "else ((case when odue != 0 then odue else due end) - ?) / 86400 end as day_offset " +
                    "from cards where queue in (1, 2, 3, 4) and type != 0" +
                    ") where day_offset between 1 and ? group by day_offset",
                timing.daysElapsed,
                timing.nextDayAt,
                FORECAST_DAYS,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    scheduled[today.plusDays(cursor.getLong(0))] = cursor.getInt(1)
                }
            }
        return summarize(today, reviews, scheduled)
    }

    /** Native search keeps a card that was answered on this day and on other days. */
    fun searchForDay(
        data: ReviewHeatmapData,
        date: LocalDate,
    ): String? {
        val offset = ChronoUnit.DAYS.between(data.today, date)
        return when {
            offset <= 0 && (data.reviews[date] ?: 0) > 0 -> searchForOffset(offset)
            offset in 1..FORECAST_DAYS && (data.scheduled[date] ?: 0) > 0 -> searchForOffset(offset)
            else -> null
        }
    }

    /** Refresh the search's day offset at tap time without scanning collection history again. */
    fun searchForDay(
        col: Collection,
        date: LocalDate,
    ): String? {
        val today = studyDate(col.backend.schedTimingToday().nextDayAt)
        return searchForOffset(ChronoUnit.DAYS.between(today, date))
    }

    private fun searchForOffset(offset: Long): String? =
        when {
            offset <= 0 -> "prop:rated=$offset"
            offset <= FORECAST_DAYS -> "prop:due=$offset -is:new"
            else -> null
        }

    /** The scheduler cutoff is tomorrow's rollover, including before today's rollover. */
    internal fun studyDate(
        nextDayAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate =
        Instant
            .ofEpochSecond(nextDayAt)
            .atZone(zone)
            .toLocalDate()
            .minusDays(1)

    /**
     * What an AnkiQuest profile's heatmap says of each day: its reviews, and whether it was
     * frozen. [uploadedUntil] is the time of the last review the server has, whose id is the
     * millisecond it was made at.
     */
    data class ServerDays(
        val reviews: Map<LocalDate, Int>,
        val protectedDays: Set<LocalDate>,
        val uploadedUntil: Instant? = null,
    )

    fun serverDays(profile: JSONObject): ServerDays {
        val last = profile.optLong("last_review_id").takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        val cells = profile.optJSONArray("heatmap") ?: return ServerDays(emptyMap(), emptySet(), last)
        val reviews = mutableMapOf<LocalDate, Int>()
        val protectedDays = mutableSetOf<LocalDate>()
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val date = runCatching { LocalDate.parse(cell.optString("date")) }.getOrNull() ?: continue
            reviews[date] = cell.optInt("reviews")
            if (cell.optBoolean("frozen")) protectedDays += date
        }
        return ServerDays(reviews, protectedDays, last)
    }

    /**
     * The same history with AnkiQuest's numbering of the days it holds.
     *
     * The revlog records when a review happened but not under which clock, so the
     * collection can only number its days by the clock the device has now: a flight
     * through time zones moves the reviews studied on the plane onto a neighbouring
     * day and leaves an empty one. AnkiQuest records each clock as it is reported and
     * numbers every review by the one in force when it was made. Its counts are used
     * from the first day it has reviews for up to the day before the last review it was
     * sent, and never for today: those days may hold reviews not uploaded yet, so they
     * stay the collection's, as do days the server does not cover.
     */
    fun withServerDays(
        data: ReviewHeatmapData,
        server: ServerDays,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReviewHeatmapData {
        val first =
            server.reviews
                .filterValues { it > 0 }
                .keys
                .minOrNull()
        val uploaded = server.uploadedUntil?.atZone(zone)?.toLocalDate() ?: data.today
        val until = minOf(data.today, uploaded)
        val reviews =
            if (first == null || first >= until) {
                data.reviews
            } else {
                data.reviews.filterKeys { it < first || it >= until } +
                    server.reviews.filter { (date, count) -> date >= first && date < until && count > 0 }
            }
        return summarize(data.today, reviews, data.scheduled, server.protectedDays)
    }

    internal fun summarize(
        today: LocalDate,
        reviews: Map<LocalDate, Int>,
        scheduled: Map<LocalDate, Int>,
        protectedDays: Set<LocalDate> = emptySet(),
    ): ReviewHeatmapData {
        val history = reviews.filter { (date, count) -> date <= today && count > 0 }.toSortedMap()
        val forecastEnd = today.plusDays(FORECAST_DAYS)
        val forecast = scheduled.filter { (date, count) -> date > today && date <= forecastEnd && count > 0 }.toSortedMap()
        val kept = protectedDays.filter { it < today && !history.containsKey(it) }.toSortedSet()
        var currentStreak = 0
        var day = if (history.containsKey(today)) today else today.minusDays(1)
        while (history.containsKey(day) || day in kept) {
            if (history.containsKey(day)) currentStreak++
            day = day.minusDays(1)
        }
        var longestStreak = 0
        var run = 0
        var previousDay: LocalDate? = null
        for (studiedDay in (history.keys + kept).sorted()) {
            val continues = previousDay?.plusDays(1) == studiedDay
            if (studiedDay in kept) {
                if (!continues) run = 0
            } else {
                run = if (continues) run + 1 else 1
            }
            longestStreak = maxOf(longestStreak, run)
            previousDay = studiedDay
        }
        val firstReviewDate = history.keys.firstOrNull()
        val totalReviews = history.values.sumOf { it.toLong() }
        return ReviewHeatmapData(
            today = today,
            reviews = history,
            scheduled = forecast,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            totalReviews = totalReviews,
            daysStudied = history.size,
            firstReviewDate = firstReviewDate,
            dailyAverage = if (history.isNotEmpty()) totalReviews.toDouble() / history.size else 0.0,
            protectedDays = kept,
        )
    }
}
