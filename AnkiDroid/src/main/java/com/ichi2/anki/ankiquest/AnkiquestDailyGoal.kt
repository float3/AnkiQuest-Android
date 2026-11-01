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
import timber.log.Timber
import java.io.IOException

/**
 * Today's two targets:
 * - the streak minimum: the review minutes the server needs for today to count toward the streak
 *   (`today.streak_minutes`; 0 means any review counts), and
 * - the daily goal, which the server keeps for every client of the player: a number of cards or
 *   minutes, which the server judges, or a plan to clear the overdue backlog in a number of days,
 *   which only this device can count, so it reports when that goal is reached. See [AnkiquestGoalPlan].
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

    const val DEFAULT_BACKLOG_DAYS = 30
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

    /** The goal as the server keeps it: a [mode] and its [value], cards, minutes or the days to clear the backlog in. */
    data class Choice(
        val mode: Mode,
        val value: Int,
    ) {
        val valid: Boolean get() = range(mode)?.contains(value) ?: true

        /** The body the server takes; a goal that is off has no value, and [restart] starts a backlog plan again from today. */
        fun json(restart: Boolean = false): JSONObject =
            JSONObject().put("mode", mode.key).apply {
                if (mode != Mode.OFF) put("value", value)
                if (restart) put("restart", true)
            }

        companion object {
            val DEFAULT = Choice(Mode.BACKLOG, DEFAULT_BACKLOG_DAYS)

            /** The values the server accepts for [mode]; any for [Mode.OFF]. */
            fun range(mode: Mode): IntRange? =
                when (mode) {
                    Mode.CARDS -> 1..5000
                    Mode.MINUTES -> 1..600
                    Mode.BACKLOG -> 1..365
                    Mode.OFF -> null
                }

            /** The server's value for a goal switched to [mode]. */
            fun defaultValue(mode: Mode): Int =
                when (mode) {
                    Mode.BACKLOG -> DEFAULT_BACKLOG_DAYS
                    Mode.CARDS -> DEFAULT_CARDS
                    Mode.MINUTES -> DEFAULT_MINUTES
                    Mode.OFF -> DEFAULT_CARDS
                }

            fun parse(json: JSONObject?): Choice? {
                if (json == null) return null
                val mode = Mode.entries.firstOrNull { it.key == json.optString("mode") } ?: return null
                return Choice(mode, json.optInt("value", defaultValue(mode)))
            }
        }
    }

    /**
     * The goal as the server answers it: the [choice], the server's Anki day [since] its backlog plan
     * started, and whether the player ever [chosen] a goal. Profiles and the cache do not say whether
     * one was chosen, so [chosen] is null there.
     */
    data class ServerGoal(
        val choice: Choice,
        val since: Long? = null,
        val chosen: Boolean? = null,
    ) {
        /** What the cache keeps: the goal and the day its plan started. */
        fun json(): JSONObject = choice.json().apply { since?.let { put("since", it) } }

        companion object {
            fun parse(json: JSONObject?): ServerGoal? {
                if (json == null) return null
                val choice = Choice.parse(json) ?: return null
                val since = if (json.isNull("since")) null else json.optLong("since")
                val chosen = if (json.isNull("chosen")) null else json.optBoolean("chosen")
                return ServerGoal(choice, since, chosen)
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
        /** Today as the server numbers Anki days, which backlog plans are counted in. */
        val studyDay: Long = 0,
    )

    data class Settings(
        val mode: Mode = Mode.BACKLOG,
        val backlogDays: Int = DEFAULT_BACKLOG_DAYS,
        val cards: Int = DEFAULT_CARDS,
        val minutes: Int = DEFAULT_MINUTES,
        /** The server's Anki day the backlog plan started; unknown until the server's goal is seen. */
        val since: Long? = null,
    ) {
        /** A changed goal works out today's target again; a plan starting again does not. */
        val signature: String get() = "${mode.key}:$backlogDays:$cards:$minutes"
    }

    /** The goal for one day. It is fixed when first worked out, so studying never moves it. */
    data class Goal(
        val target: Int,
        val backlog: Int = 0,
        /** Overdue cards to study today on top of the due ones. */
        val extra: Int = 0,
        /** Days left in the backlog plan, today included. */
        val daysLeft: Int = 0,
    )

    data class Status(
        val day: Int,
        val minutes: Int,
        val streakMinutes: Int,
        val studiedCards: Int,
        val mode: Mode,
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

    /**
     * The goal for [load] under [settings]; cards already studied today stay part of it. A backlog
     * goal is today's due cards plus the backlog spread over the days left in the plan.
     */
    fun goal(
        load: Load,
        settings: Settings,
    ): Goal =
        when (settings.mode) {
            Mode.CARDS -> Goal(settings.cards.coerceAtLeast(1))
            Mode.MINUTES -> Goal(settings.minutes.coerceAtLeast(1))
            Mode.BACKLOG -> {
                val days = AnkiquestGoalPlan.daysLeft(settings.backlogDays, settings.since, load.studyDay)
                val extra = AnkiquestGoalPlan.extra(load.backlog, days)
                Goal(load.studiedCards + load.dueToday + extra, load.backlog, extra, days)
            }
            Mode.OFF -> Goal(0)
        }

    /** The configured account's goal: the server's, as last seen, or until then the one chosen on this device. */
    fun settings(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Settings {
        val local = localSettings(prefs)
        val server = cached(prefs, AnkiquestHomeData.account(prefs)?.scope) ?: return local
        val choice = server.choice
        val settings =
            when (choice.mode) {
                Mode.BACKLOG -> local.copy(mode = Mode.BACKLOG, backlogDays = choice.value)
                Mode.CARDS -> local.copy(mode = Mode.CARDS, cards = choice.value)
                Mode.MINUTES -> local.copy(mode = Mode.MINUTES, minutes = choice.value)
                Mode.OFF -> local.copy(mode = Mode.OFF)
            }
        return settings.copy(since = server.since)
    }

    /** The goal older versions kept on this device; a daily backlog percentage becomes the closest plan length. */
    private fun localSettings(prefs: SharedPreferences): Settings =
        Settings(
            Mode.of(prefs.getString(PREF_MODE, null)),
            prefs.getString(PREF_BACKLOG_PERCENT, null)?.toIntOrNull()?.let(AnkiquestGoalPlan::daysForPercent)
                ?: DEFAULT_BACKLOG_DAYS,
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
            Mode.BACKLOG -> Choice(Mode.BACKLOG, backlogDays)
            Mode.CARDS -> Choice(Mode.CARDS, cards)
            Mode.MINUTES -> Choice(Mode.MINUTES, minutes)
            Mode.OFF -> Choice(Mode.OFF, cards)
        }

    /** The server's goal for the account with [scope], as last seen. */
    fun cached(
        prefs: SharedPreferences,
        scope: String?,
    ): ServerGoal? =
        prefs
            .getString(scopedKey(PREF_SERVER_GOAL, scope), null)
            ?.let { runCatching { ServerGoal.parse(JSONObject(it)) }.getOrNull() }

    private fun cache(
        prefs: SharedPreferences,
        scope: String,
        goal: ServerGoal,
    ) {
        val value = goal.json().toString()
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
        val goal = ServerGoal.parse(profile.optJSONObject("goal")) ?: return
        if (!migrated(prefs, scope)) {
            if (localChoice(prefs) != null) return
            prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, scope), true) }
        }
        cache(prefs, scope, goal)
    }

    /** The streak minimum of the configured account; another account's minimum is never assumed. */
    fun streakMinutes(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Int = prefs.getInt(streakMinutesKey(prefs), 0)

    private fun streakMinutesKey(prefs: SharedPreferences) = scopedKey(PREF_STREAK_MINUTES, AnkiquestHomeData.account(prefs)?.scope)

    /** The goal [account] keeps on the server, after moving there the goal chosen on this device. */
    suspend fun fetch(
        account: HomeAccount,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): ServerGoal {
        val server = ServerGoal.parse(AnkiquestSharing.goal(account)) ?: throw IOException("The server sent no goal")
        val goal = if (migrated(prefs, account.scope)) server else migrate(account, server, prefs)
        cache(prefs, account.scope, goal)
        return goal
    }

    /**
     * Stores [choice] as [account]'s goal and answers what the server keeps. A backlog plan saved with
     * [restart] starts again from today even when its length is unchanged.
     */
    suspend fun save(
        account: HomeAccount,
        choice: Choice,
        restart: Boolean = false,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): ServerGoal {
        require(choice.valid)
        val saved =
            ServerGoal.parse(AnkiquestSharing.saveGoal(account, choice.json(restart)))
                ?: ServerGoal(choice, chosen = true)
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
        server: ServerGoal,
        prefs: SharedPreferences,
    ): ServerGoal {
        val local = localChoice(prefs)
        val goal =
            if (server.choice == Choice.DEFAULT && server.chosen != true && local != null && local != server.choice) {
                ServerGoal.parse(AnkiquestSharing.saveGoal(account, local.json())) ?: ServerGoal(local, chosen = true)
            } else {
                server
            }
        prefs.edit { putBoolean(scopedKey(PREF_MIGRATED, account.scope), true) }
        return goal
    }

    /**
     * After an upload for [account]: moves a goal chosen on this device to the server, starts a backlog
     * plan that ran out again, and reports a backlog goal reached today once, since only this device
     * knows how many cards are due.
     */
    suspend fun afterUpload(
        account: HomeAccount,
        profile: JSONObject,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val goal = profile.optJSONObject("goal") ?: return
        val server = ServerGoal.parse(goal) ?: return
        val current = if (migrated(prefs, account.scope)) server else migrate(account, server, prefs)
        cache(prefs, account.scope, current)
        val choice = current.choice
        if (choice.mode != Mode.BACKLOG) return
        val today = CollectionManager.withCol { AnkiquestDecks.studyDay(this, TimeManager.time.intTimeMS()) }
        if (AnkiquestGoalPlan.planEnded(choice.value, current.since, today)) restart(account, current, today, prefs)
        if (choice == server.choice && goal.optBoolean("reached")) return
        val (status, day) =
            CollectionManager.withCol {
                AnkiquestDailyGoal.status(AnkiquestDailyGoal.load(this), prefs) to
                    AnkiquestDecks.studyDay(this, TimeManager.time.intTimeMS())
            }
        val reportedKey = scopedKey(PREF_REPORTED_DAY, account.scope)
        if (!status.goalDone || prefs.getLong(reportedKey, Long.MIN_VALUE) == day) return
        AnkiquestSharing.goalReached(account, day)
        prefs.edit { putLong(reportedKey, day) }
    }

    /**
     * Starts [goal]'s backlog plan again from [today] on the server. Until the server takes it,
     * [AnkiquestGoalPlan.daysLeft] already counts the plan as starting today.
     */
    private suspend fun restart(
        account: HomeAccount,
        goal: ServerGoal,
        today: Long,
        prefs: SharedPreferences,
    ) {
        try {
            val saved = ServerGoal.parse(AnkiquestSharing.saveGoal(account, goal.choice.json(restart = true)))
            cache(prefs, account.scope, if (saved?.since == null) goal.copy(since = today) else saved)
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest backlog plan could not start again")
        }
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
        return Load(
            today,
            studied,
            minutes,
            dueReviews + learning + newCards,
            backlog,
            AnkiquestDecks.studyDay(col, TimeManager.time.intTimeMS()),
        )
    }

    /** Today's targets and progress. The first call each day fixes the goal. */
    suspend fun status(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Status {
        val load = CollectionManager.withCol { load(this) }
        return status(load, prefs)
    }

    /** Today's targets and progress for [load]; the goal is fixed per account at the first call of the day. */
    fun status(
        load: Load,
        prefs: SharedPreferences,
    ): Status {
        val settings = settings(prefs)
        val key = scopedKey(PREF_DAY_GOAL, AnkiquestHomeData.account(prefs)?.scope)
        val stored = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val goal =
            if (stored != null && stored.optInt("day", -1) == load.day && stored.optString("settings") == settings.signature) {
                Goal(stored.optInt("target"), stored.optInt("backlog"), stored.optInt("extra"), stored.optInt("days"))
            } else {
                goal(load, settings).also { fresh ->
                    prefs.edit {
                        putString(
                            key,
                            JSONObject()
                                .put("day", load.day)
                                .put("settings", settings.signature)
                                .put("target", fresh.target)
                                .put("backlog", fresh.backlog)
                                .put("extra", fresh.extra)
                                .put("days", fresh.daysLeft)
                                .toString(),
                        )
                    }
                }
            }
        return Status(load.day, load.minutes, streakMinutes(prefs), load.studiedCards, settings.mode, goal)
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

    /** A short description of [choice], such as "Clear the backlog in 30 days". */
    fun describe(
        context: Context,
        choice: Choice,
    ): String =
        when (choice.mode) {
            Mode.BACKLOG -> context.resources.getQuantityString(R.plurals.ankiquest_goal_summary_backlog, choice.value, choice.value)
            Mode.CARDS -> context.resources.getQuantityString(R.plurals.ankiquest_goal_summary_cards, choice.value, choice.value)
            Mode.MINUTES -> context.resources.getQuantityString(R.plurals.ankiquest_goal_summary_minutes, choice.value, choice.value)
            Mode.OFF -> context.getString(R.string.ankiquest_goal_off)
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
