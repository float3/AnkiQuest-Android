// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import org.json.JSONObject
import java.io.IOException
import kotlin.math.ceil

/**
 * Today's two targets:
 * - the streak minimum: the review minutes the server needs for today to count toward the streak
 *   (`today.streak_minutes`; 0 means any review counts), and
 * - the daily goal, which the server keeps for every client of the player: a number of cards or
 *   minutes, which the server judges, or the cards due today plus a share of the overdue backlog,
 *   which only this device can count, so it reports when that goal is reached.
 *
 * Goals chosen on this device before the server kept them are moved there once. The server's goal is
 * cached per account, so it is shown offline.
 */
internal object AnkiquestDailyGoal {
    const val PREF_MODE = "ankiquestGoalMode"
    const val PREF_BACKLOG_PERCENT = "ankiquestGoalBacklogPercent"
    const val PREF_CARDS = "ankiquestGoalCards"
    const val PREF_MINUTES = "ankiquestGoalMinutes"
    private const val PREF_STREAK_MINUTES = "ankiquestStreakMinutes"
    private const val PREF_DAY_GOAL = "ankiquestDayGoal"
    private const val PREF_SERVER_GOAL = "ankiquestServerGoal"
    private const val PREF_MIGRATED = "ankiquestGoalMigrated"
    private const val PREF_REPORTED_DAY = "ankiquestGoalReportedDay"

    const val DEFAULT_BACKLOG_PERCENT = 10
    const val DEFAULT_CARDS = 100
    const val DEFAULT_MINUTES = 20

    enum class Mode(
        val key: String,
    ) {
        BACKLOG("backlog"),
        CARDS("cards"),
        MINUTES("minutes"),
        OFF("off"),
        ;

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: BACKLOG
        }
    }

    /** The goal as the server keeps it: a [mode] and its [value], cards, minutes or a backlog percentage. */
    data class Choice(
        val mode: Mode,
        val value: Int,
    ) {
        val valid: Boolean get() = range(mode)?.contains(value) ?: true

        /** The body the server takes; a goal that is off has no value. */
        fun json(): JSONObject = JSONObject().put("mode", mode.key).apply { if (mode != Mode.OFF) put("value", value) }

        companion object {
            val DEFAULT = Choice(Mode.CARDS, 100)

            /** The values the server accepts for [mode]; any for [Mode.OFF]. */
            fun range(mode: Mode): IntRange? =
                when (mode) {
                    Mode.CARDS -> 1..5000
                    Mode.MINUTES -> 1..600
                    Mode.BACKLOG -> 1..100
                    Mode.OFF -> null
                }

            /** The server's value for a goal switched to [mode]. */
            fun defaultValue(mode: Mode): Int =
                when (mode) {
                    Mode.MINUTES -> 20
                    else -> 100
                }

            fun parse(json: JSONObject?): Choice? {
                if (json == null) return null
                val mode = Mode.entries.firstOrNull { it.key == json.optString("mode") } ?: return null
                return Choice(mode, json.optInt("value", defaultValue(mode)))
            }
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
        /** Reviews or minutes so far as the server counts them, for goals the server judges. */
        val serverProgress: Int? = null,
        /** The server says today's goal is reached. */
        val reached: Boolean = false,
    ) {
        val progress: Int get() = serverProgress ?: if (mode == Mode.MINUTES) minutes else studiedCards

        /** Today counts toward the streak, by the same rule as the server. */
        val minimumDone: Boolean get() = studiedCards > 0 && minutes >= streakMinutes

        val goalDone: Boolean get() = mode != Mode.OFF && (reached || (progress > 0 && progress >= goal.target))
    }

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
            Mode.OFF -> Goal(0)
        }

    /** The configured account's goal: the server's, as last seen, or until then the one chosen on this device. */
    fun settings(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Settings {
        val local = localSettings(prefs)
        val choice = cached(prefs, AnkiquestHomeData.account(prefs)?.scope) ?: return local
        return when (choice.mode) {
            Mode.BACKLOG -> local.copy(mode = Mode.BACKLOG, backlogPercent = choice.value)
            Mode.CARDS -> local.copy(mode = Mode.CARDS, cards = choice.value)
            Mode.MINUTES -> local.copy(mode = Mode.MINUTES, minutes = choice.value)
            Mode.OFF -> local.copy(mode = Mode.OFF)
        }
    }

    private fun localSettings(prefs: SharedPreferences): Settings =
        Settings(
            Mode.of(prefs.getString(PREF_MODE, null)),
            prefs.getString(PREF_BACKLOG_PERCENT, null)?.toIntOrNull() ?: DEFAULT_BACKLOG_PERCENT,
            prefs.intOr(PREF_CARDS, DEFAULT_CARDS),
            prefs.intOr(PREF_MINUTES, DEFAULT_MINUTES),
        )

    /** The goal chosen on this device by older versions, in the server's limits; null when none was ever chosen. */
    internal fun localChoice(prefs: SharedPreferences): Choice? {
        if (listOf(PREF_MODE, PREF_BACKLOG_PERCENT, PREF_CARDS, PREF_MINUTES).none { prefs.contains(it) }) return null
        val choice = localSettings(prefs).asChoice()
        return Choice.range(choice.mode)?.let { choice.copy(value = choice.value.coerceIn(it)) } ?: choice
    }

    /** The goal to show for the configured account, as [settings] has it. */
    fun choice(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Choice = settings(prefs).asChoice()

    private fun Settings.asChoice(): Choice =
        when (mode) {
            Mode.BACKLOG -> Choice(Mode.BACKLOG, backlogPercent)
            Mode.CARDS -> Choice(Mode.CARDS, cards)
            Mode.MINUTES -> Choice(Mode.MINUTES, minutes)
            Mode.OFF -> Choice(Mode.OFF, cards)
        }

    /** The server's goal for the account with [scope], as last seen. */
    fun cached(
        prefs: SharedPreferences,
        scope: String?,
    ): Choice? =
        prefs
            .getString(scopedKey(PREF_SERVER_GOAL, scope), null)
            ?.let { runCatching { Choice.parse(JSONObject(it)) }.getOrNull() }

    private fun cache(
        prefs: SharedPreferences,
        scope: String,
        choice: Choice,
    ) {
        val value = choice.json().toString()
        if (prefs.getString(scopedKey(PREF_SERVER_GOAL, scope), null) != value) {
            prefs.edit { putString(scopedKey(PREF_SERVER_GOAL, scope), value) }
        }
    }

    private fun migrated(
        prefs: SharedPreferences,
        scope: String?,
    ): Boolean = prefs.getBoolean(scopedKey(PREF_MIGRATED, scope), false)

    /**
     * Keeps the server's streak minimum, so it is known offline and right after studying, and its goal
     * once any goal chosen on this device has moved to the server.
     */
    fun remember(
        profile: JSONObject,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        profile.optJSONObject("today")?.let { today ->
            val minutes = today.optInt("streak_minutes", 0).coerceAtLeast(0)
            val key = streakMinutesKey(prefs)
            if (prefs.getInt(key, -1) != minutes) prefs.edit { putInt(key, minutes) }
        }
        val scope = AnkiquestHomeData.account(prefs)?.scope ?: return
        val choice = Choice.parse(profile.optJSONObject("goal")) ?: return
        if (!migrated(prefs, scope)) {
            if (localChoice(prefs) != null) return
            prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, scope), true) }
        }
        cache(prefs, scope, choice)
    }

    /** The streak minimum of the configured account; another account's minimum is never assumed. */
    fun streakMinutes(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Int = prefs.getInt(streakMinutesKey(prefs), 0)

    private fun streakMinutesKey(prefs: SharedPreferences) = scopedKey(PREF_STREAK_MINUTES, AnkiquestHomeData.account(prefs)?.scope)

    /** The goal [account] keeps on the server, after moving there the goal chosen on this device. */
    suspend fun fetch(
        account: HomeAccount,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): Choice {
        val server = Choice.parse(AnkiquestSharing.goal(account)) ?: throw IOException("The server sent no goal")
        val choice = if (migrated(prefs, account.scope)) server else migrate(account, server, prefs)
        cache(prefs, account.scope, choice)
        return choice
    }

    /** Stores [choice] as [account]'s goal and answers what the server keeps. */
    suspend fun save(
        account: HomeAccount,
        choice: Choice,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): Choice {
        require(choice.valid)
        val saved = Choice.parse(AnkiquestSharing.saveGoal(account, choice.json())) ?: choice
        prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, account.scope), true) }
        cache(prefs, account.scope, saved)
        return saved
    }

    /**
     * A goal chosen on this device replaces the server's only while the server still has its
     * default, so a goal set on another client is never overwritten. It happens once per account.
     */
    private suspend fun migrate(
        account: HomeAccount,
        server: Choice,
        prefs: SharedPreferences,
    ): Choice {
        val local = localChoice(prefs)
        val choice =
            if (server == Choice.DEFAULT && local != null && local != server) {
                Choice.parse(AnkiquestSharing.saveGoal(account, local.json())) ?: local
            } else {
                server
            }
        prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, account.scope), true) }
        return choice
    }

    /**
     * After an upload for [account]: moves a goal chosen on this device to the server, and reports a
     * backlog goal reached today once, since only this device knows how many cards are due.
     */
    suspend fun afterUpload(
        account: HomeAccount,
        profile: JSONObject,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val goal = profile.optJSONObject("goal") ?: return
        val server = Choice.parse(goal) ?: return
        val choice = if (migrated(prefs, account.scope)) server else migrate(account, server, prefs)
        cache(prefs, account.scope, choice)
        if (choice.mode != Mode.BACKLOG || (choice == server && goal.optBoolean("reached"))) return
        val (status, day) =
            CollectionManager.withCol {
                val today = AnkiquestDailyGoal.status(AnkiquestDailyGoal.load(this), prefs)
                today to AnkiquestDecks.studyDay(this, TimeManager.time.intTimeMS())
            }
        val reportedKey = scopedKey(PREF_REPORTED_DAY, account.scope)
        if (!status.goalDone || prefs.getLong(reportedKey, Long.MIN_VALUE) == day) return
        AnkiquestSharing.goalReached(account, day)
        prefs.edit { putLong(reportedKey, day) }
    }

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
        val newCards =
            col.sched
                .deckDueTree()
                .children
                .sumOf { it.newCount }
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

    /**
     * [status] with what the server says in the owner's [profile] about today's goal: for cards and
     * minutes the server's count and verdict, for a backlog goal whether a reach was reported. A profile
     * from a day that has ended, or from before a goal chosen on this device moved to the server, says nothing.
     */
    fun withProfile(
        status: Status,
        profile: JSONObject?,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
        now: Long = TimeManager.time.intTimeMS(),
    ): Status {
        if (profile == null || !migrated(prefs, AnkiquestHomeData.account(prefs)?.scope)) return status
        if (profile.optLong("day_ends_at") in 1..now) return status
        val goal = profile.optJSONObject("goal") ?: return status
        val choice = Choice.parse(goal) ?: return status
        val reached = goal.optBoolean("reached")
        return when (choice.mode) {
            Mode.OFF -> status.copy(mode = Mode.OFF)
            Mode.BACKLOG -> if (status.mode == Mode.BACKLOG) status.copy(reached = reached) else status
            Mode.CARDS, Mode.MINUTES ->
                status.copy(
                    mode = choice.mode,
                    goal = Goal(choice.value.coerceAtLeast(1)),
                    serverProgress = if (goal.isNull("progress")) null else goal.optInt("progress"),
                    reached = reached,
                )
        }
    }

    /** "34 of 50 cards" or "12 of 20 minutes", toward the daily goal. */
    fun progress(
        context: Context,
        status: Status,
    ): String =
        context.getString(
            if (status.mode == Mode.MINUTES) R.string.aq_goal_progress_minutes else R.string.aq_goal_progress_cards,
            status.progress.coerceAtMost(status.goal.target),
            status.goal.target,
        )

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
