// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.libanki.Collection
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Today's two targets, read from the local collection:
 * - the streak minimum: the review minutes the server needs for today to count toward the streak
 *   (`today.streak_minutes`; 0 means any review counts), and
 * - the daily goal: by default the cards due today plus a share of the overdue backlog, so a
 *   backlog shrinks a little every day instead of being due all at once. Players can set a
 *   fixed number of cards or minutes instead.
 */
internal object AnkiquestDailyGoal {
    const val PREF_MODE = "ankiquestGoalMode"
    const val PREF_BACKLOG_PERCENT = "ankiquestGoalBacklogPercent"
    const val PREF_CARDS = "ankiquestGoalCards"
    const val PREF_MINUTES = "ankiquestGoalMinutes"
    const val PREF_CELEBRATE = "ankiquestGoalCelebrate"
    private const val PREF_STREAK_MINUTES = "ankiquestStreakMinutes"
    private const val PREF_DAY_GOAL = "ankiquestDayGoal"
    private const val PREF_CELEBRATED = "ankiquestGoalCelebrated"

    const val DEFAULT_BACKLOG_PERCENT = 10
    const val DEFAULT_CARDS = 100
    const val DEFAULT_MINUTES = 20

    enum class Mode(
        val key: String,
    ) {
        BACKLOG("backlog"),
        CARDS("cards"),
        MINUTES("minutes"),
        ;

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: BACKLOG
        }
    }

    /** What the collection says about today, before any goal is chosen. */
    data class Load(
        val day: Int,
        val studiedCards: Int,
        val minutes: Int,
        /** Cards still to study today that are not overdue: reviews due today, learning and new cards. */
        val dueToday: Int,
        /** Review cards overdue from earlier days, including those past the daily review limit. */
        val backlog: Int,
    )

    data class Settings(
        val mode: Mode = Mode.BACKLOG,
        val backlogPercent: Int = DEFAULT_BACKLOG_PERCENT,
        val cards: Int = DEFAULT_CARDS,
        val minutes: Int = DEFAULT_MINUTES,
    ) {
        val signature: String get() = "${mode.key}:$backlogPercent:$cards:$minutes"
    }

    /** The goal for one day. It is fixed when first worked out, so studying never moves it. */
    data class Goal(
        val target: Int,
        val backlog: Int = 0,
        val backlogShare: Int = 0,
    )

    data class Status(
        val day: Int,
        val minutes: Int,
        val streakMinutes: Int,
        val studiedCards: Int,
        val mode: Mode,
        val backlogPercent: Int,
        val goal: Goal,
    ) {
        val progress: Int get() = if (mode == Mode.MINUTES) minutes else studiedCards

        /** Today counts toward the streak, by the same rule as the server. */
        val minimumDone: Boolean get() = studiedCards > 0 && minutes >= streakMinutes

        val goalDone: Boolean get() = progress > 0 && progress >= goal.target
    }

    enum class Celebration { MINIMUM, GOAL, BOTH }

    /** Cards from a [backlog] to work off today: at least one while any is left. */
    fun backlogShare(
        backlog: Int,
        percent: Int,
    ): Int =
        if (backlog <= 0 || percent <= 0) {
            0
        } else {
            ceil(backlog * percent.coerceAtMost(100) / 100.0).toInt().coerceIn(1, backlog)
        }

    /** The goal for [load] under [settings]; cards already studied today stay part of it. */
    fun goal(
        load: Load,
        settings: Settings,
    ): Goal =
        when (settings.mode) {
            Mode.CARDS -> Goal(settings.cards.coerceAtLeast(1))
            Mode.MINUTES -> Goal(settings.minutes.coerceAtLeast(1))
            Mode.BACKLOG -> {
                val share = backlogShare(load.backlog, settings.backlogPercent)
                Goal(load.studiedCards + load.dueToday + share, load.backlog, share)
            }
        }

    /** Which celebrations [status] has earned that were not [shown] yet today. */
    fun celebration(
        status: Status,
        shown: Set<Celebration>,
    ): Celebration? {
        val minimum = status.minimumDone && Celebration.MINIMUM !in shown
        val goal = status.goalDone && Celebration.GOAL !in shown
        return when {
            minimum && goal -> Celebration.BOTH
            goal -> Celebration.GOAL
            minimum -> Celebration.MINIMUM
            else -> null
        }
    }

    fun settings(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Settings =
        Settings(
            Mode.of(prefs.getString(PREF_MODE, null)),
            prefs.getString(PREF_BACKLOG_PERCENT, null)?.toIntOrNull() ?: DEFAULT_BACKLOG_PERCENT,
            prefs.intOr(PREF_CARDS, DEFAULT_CARDS),
            prefs.intOr(PREF_MINUTES, DEFAULT_MINUTES),
        )

    fun celebrationsEnabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_CELEBRATE, true)

    /** Keeps the server's streak minimum, so it is known offline and right after studying. */
    fun remember(
        profile: JSONObject,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val today = profile.optJSONObject("today") ?: return
        val minutes = today.optInt("streak_minutes", 0).coerceAtLeast(0)
        if (prefs.getInt(PREF_STREAK_MINUTES, -1) != minutes) prefs.edit { putInt(PREF_STREAK_MINUTES, minutes) }
    }

    fun streakMinutes(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Int = prefs.getInt(PREF_STREAK_MINUTES, 0)

    fun load(col: Collection): Load {
        val today = col.sched.today
        val cutoff = col.sched.dayCutoff
        val (studied, minutes) =
            col.db
                .query(
                    "select count(distinct cid), coalesce(sum(time), 0) from revlog where id >= ? and ease > 0 and type < 4",
                    (cutoff - 86_400) * 1000,
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0) to (cursor.getLong(1) / 60_000).toInt()
                }
        val (dueReviews, backlog) =
            col.db
                .query(
                    "select coalesce(sum(day = ?), 0), coalesce(sum(day < ?), 0) from " +
                        "(select case when odid != 0 then odue else due end as day from cards where queue = 2)",
                    today,
                    today,
                ).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0) to cursor.getInt(1)
                }
        val learning =
            col.db.queryScalar(
                "select count() from cards where (queue in (1, 4) and due < ?) or (queue = 3 and due <= ?)",
                cutoff,
                today,
            )
        val newCards = col.sched.deckDueTree().children.sumOf { it.newCount }
        return Load(today, studied, minutes, dueReviews + learning + newCards, backlog)
    }

    /** Today's targets and progress. The first call each day fixes the goal. */
    suspend fun status(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Status {
        val load = CollectionManager.withCol { load(this) }
        return status(load, prefs)
    }

    fun status(
        load: Load,
        prefs: SharedPreferences,
    ): Status {
        val settings = settings(prefs)
        val stored = prefs.getString(PREF_DAY_GOAL, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val goal =
            if (stored != null && stored.optInt("day", -1) == load.day && stored.optString("settings") == settings.signature) {
                Goal(stored.optInt("target"), stored.optInt("backlog"), stored.optInt("share"))
            } else {
                goal(load, settings).also { fresh ->
                    prefs.edit {
                        putString(
                            PREF_DAY_GOAL,
                            JSONObject()
                                .put("day", load.day)
                                .put("settings", settings.signature)
                                .put("target", fresh.target)
                                .put("backlog", fresh.backlog)
                                .put("share", fresh.backlogShare)
                                .toString(),
                        )
                    }
                }
            }
        return Status(load.day, load.minutes, streakMinutes(prefs), load.studiedCards, settings.mode, settings.backlogPercent, goal)
    }

    /** The celebration [status] earned since the last one shown, marking it shown. */
    fun claim(
        status: Status,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): Celebration? {
        val stored = prefs.getString(PREF_CELEBRATED, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val shown =
            if (stored != null && stored.optInt("day", -1) == status.day) {
                buildSet {
                    if (stored.optBoolean("minimum")) add(Celebration.MINIMUM)
                    if (stored.optBoolean("goal")) add(Celebration.GOAL)
                }
            } else {
                emptySet()
            }
        val earned = celebration(status, shown) ?: return null
        prefs.edit {
            putString(
                PREF_CELEBRATED,
                JSONObject()
                    .put("day", status.day)
                    .put("minimum", Celebration.MINIMUM in shown || earned != Celebration.GOAL)
                    .put("goal", Celebration.GOAL in shown || earned != Celebration.MINIMUM)
                    .toString(),
            )
        }
        return earned
    }

    private fun SharedPreferences.intOr(
        key: String,
        default: Int,
    ): Int =
        when (val value = all[key]) {
            is Int -> value
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
}
