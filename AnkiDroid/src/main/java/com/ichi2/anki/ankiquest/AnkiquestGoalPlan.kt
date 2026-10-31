// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Choice
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The backlog plan and the estimates the goal dialog shows for each choice.
 *
 * A backlog goal of `value` days spreads the overdue cards over the days left in the plan, which
 * started on the server's Anki day `since`, so the backlog is gone on day `since + value` even when
 * some days fall short.
 */
internal object AnkiquestGoalPlan {
    const val DEFAULT_SECONDS_PER_CARD = 8.0
    const val FORECAST_DAYS = 7
    const val ANSWER_TIME_DAYS = 30

    /** The plan lengths offered as presets, besides a custom number of days. */
    val PRESET_DAYS = listOf(7, 14, 30, 60)

    private const val DAY_MS = 86_400_000L

    /** Old versions kept the backlog goal as a percentage a day; each maps to the plan length closest to it. */
    private val DAYS_FOR_PERCENT = listOf(5 to 60, 10 to 30, 20 to 14, 34 to 7, 100 to 1)

    /** What the collection says, besides the goal, for the estimates. */
    data class Forecast(
        /** Cards still to study today that are not overdue. */
        val due: Int,
        /** Review cards overdue from earlier days. */
        val backlog: Int,
        /** The average number of review cards due on each of the next [FORECAST_DAYS] days. */
        val dailyDue: Double,
        val secondsPerCard: Double,
    )

    /** What a choice means today: the [cards] and [seconds] it asks for, and the days until the backlog is gone. */
    data class Projection(
        val cards: Int,
        val seconds: Int,
        /** 0 when nothing is overdue, null when the goal never clears the backlog. */
        val clearedInDays: Int?,
    )

    /** The plan length closest to an old daily backlog [percent]. */
    fun daysForPercent(percent: Int): Int = DAYS_FOR_PERCENT.minBy { abs(it.first - percent) }.second

    /** Whether a plan of [value] days started on [since] has run out by [today], so it starts again. */
    fun planEnded(
        value: Int,
        since: Long?,
        today: Long,
    ): Boolean = since != null && value - (today - since) < 1

    /** Days left in a plan of [value] days started on [since], today included; a plan that ran out starts again. */
    fun daysLeft(
        value: Int,
        since: Long?,
        today: Long,
    ): Int {
        val days = value.coerceAtLeast(1)
        if (since == null || planEnded(days, since, today)) return days
        return (days - (today - since)).toInt().coerceAtMost(days)
    }

    /** The overdue cards to study today on top of the due ones: at least one while any is left. */
    fun extra(
        backlog: Int,
        daysLeft: Int,
    ): Int = if (backlog <= 0) 0 else ceil(backlog.toDouble() / daysLeft.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    /** The fewest cards a day that shrink the backlog: one more than the cards that come due. */
    fun shrinkingCards(dailyDue: Double): Int = floor(dailyDue.coerceAtLeast(0.0)).toInt() + 1

    /** The cards a day that keep up with what comes due, suggested when nothing is overdue. */
    fun keepingUpCards(dailyDue: Double): Int = dailyDue.coerceAtLeast(0.0).roundToInt()

    /** The estimates for [choice] on [forecast]; null for a goal that is off. */
    fun project(
        choice: Choice,
        forecast: Forecast,
    ): Projection? {
        val perCard = forecast.secondsPerCard.coerceAtLeast(1.0)
        return when (choice.mode) {
            Mode.OFF -> null
            Mode.BACKLOG -> {
                val days = choice.value.coerceAtLeast(1)
                val cards = forecast.due.coerceAtLeast(0) + extra(forecast.backlog, days)
                Projection(cards, (cards * perCard).roundToInt(), if (forecast.backlog <= 0) 0 else days)
            }
            Mode.CARDS -> {
                val cards = choice.value.coerceAtLeast(0)
                Projection(cards, (cards * perCard).roundToInt(), clearedIn(forecast, cards))
            }
            Mode.MINUTES -> {
                val seconds = choice.value.coerceAtLeast(0) * 60
                val cards = floor(seconds / perCard).toInt()
                Projection(cards, seconds, clearedIn(forecast, cards))
            }
        }
    }

    private fun clearedIn(
        forecast: Forecast,
        cards: Int,
    ): Int? {
        if (forecast.backlog <= 0) return 0
        val spare = cards - forecast.dailyDue
        return if (spare <= 0) null else ceil(forecast.backlog / spare).toInt()
    }

    /**
     * Reads the [Forecast] from [col]: `due` and `backlog` as [AnkiquestDailyGoal.load] counts them.
     *
     * `dailyDue` is the collection's due forecast without the backlog: the review and interday
     * learning cards (queues 2 and 3, so not suspended or buried) whose due day, the original one for
     * cards in a filtered deck, falls on one of the next [FORECAST_DAYS] Anki days
     * (`sched.today + 1` to `sched.today + 7`), divided by [FORECAST_DAYS]. Both queues keep their due
     * date as an Anki day number, so this is one indexed count. New cards and daily limits are left
     * out, like Anki's own forecast graph.
     *
     * `secondsPerCard` is the average answer time in the review log over the last [ANSWER_TIME_DAYS]
     * days (`revlog.time` is in milliseconds), leaving out manual reschedules, or
     * [DEFAULT_SECONDS_PER_CARD] without any history.
     */
    fun forecast(
        col: Collection,
        now: Long = TimeManager.time.intTimeMS(),
    ): Forecast {
        val load = AnkiquestDailyGoal.load(col)
        val today = col.sched.today
        val upcoming =
            col.db.queryScalar(
                "select count() from cards where queue in (2, 3) and " +
                    "(case when odid != 0 then odue else due end) between ? and ?",
                today + 1,
                today + FORECAST_DAYS,
            )
        val seconds =
            col.db
                .query(
                    "select avg(time) from revlog where id >= ? and time > 0 and type < 4",
                    now - ANSWER_TIME_DAYS * DAY_MS,
                ).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getDouble(0) / 1000.0
                }
        return Forecast(
            due = load.dueToday,
            backlog = load.backlog,
            dailyDue = upcoming.toDouble() / FORECAST_DAYS,
            secondsPerCard = seconds?.takeIf { it > 0 } ?: DEFAULT_SECONDS_PER_CARD,
        )
    }
}
