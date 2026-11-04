// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.annotation.StringRes
import com.ichi2.anki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The leaderboard and player profiles, as the website's `/`, `/hour` … `/all` and `/week#<user>`
 * pages read them: `GET /api/leaderboard?period=`, `GET /api/week`, `GET /api/profile/{user}` and,
 * for the player's own profile, `GET /api/wins/{user}` (docs/mobile-api.md, "leaderboards and profiles").
 */
internal object AnkiquestBoards {
    /** The leaderboard periods, in the order the website's tabs show them; [path] is their web page. */
    enum class Period(
        val query: String,
        @StringRes val label: Int,
    ) {
        HOUR("hour", R.string.aq_board_hour),
        DAY("day", R.string.ankiquest_nav_today),
        WEEK("week", R.string.aq_board_week),
        MONTH("month", R.string.aq_board_month),
        YEAR("year", R.string.aq_board_year),
        ALL("all", R.string.aq_board_all),
        ;

        val path: String get() = "/$query"

        companion object {
            /** The period called [name], or the week the server answers for anything else. */
            fun of(name: String?): Period = entries.firstOrNull { it.query == name } ?: WEEK
        }
    }

    data class Standing(
        val user: String,
        val display: String,
        val level: Int,
        /** XP in the period the board was asked for. */
        val xp: Long,
        val xpTotal: Long,
        val streak: Int,
        /** How today stands for the streak, as [AnkiquestWidget.streakState] says it. */
        val state: String,
        val todayReviews: Long,
        val todayMinutes: Long,
        val streakMinutes: Long,
    )

    data class Quest(
        val title: String,
        val progress: Long,
        val target: Long,
        val done: Boolean,
        val reward: Long,
    )

    data class Achievement(
        val title: String,
        val description: String,
        val reward: Long,
        val progress: Long,
        val target: Long,
        /** When it was unlocked, as the server words it, or null while locked. */
        val unlocked: String?,
    )

    /** A card that kept slipping away and is learned at last. */
    data class Conquest(
        /** Unix milliseconds. */
        val at: Long,
        val lapses: Int,
        val leech: Boolean,
    )

    data class Best(
        val xp: Long,
        val reviews: Long,
    )

    data class Win(
        val title: String,
        val body: String,
        /** Unix seconds. */
        val createdAt: Long,
    )

    data class Profile(
        val user: String,
        val display: String,
        val level: Int,
        val xpTotal: Long,
        val xpIntoLevel: Long,
        val xpForNext: Long,
        val periods: Map<Period, Long>,
        val streak: Int,
        val streakState: String,
        val atRisk: Boolean,
        /** Saved freezes, 0 to 3. */
        val freezes: Int,
        val freezesEnabled: Boolean,
        val freezeEarnedToday: Boolean,
        val todayReviews: Long,
        val todayMinutes: Long,
        val todayXp: Long,
        /** Review minutes today needs for the streak; 0 when any review counts. */
        val streakMinutes: Long,
        val lifetimeReviews: Long,
        val lifetimeHours: Long,
        val bestStreak: Long,
        val bestCombo: Long,
        val daysActive: Long,
        val conquered: Int,
        val quests: List<Quest>,
        val achievements: List<Achievement>,
        val conquests: List<Conquest>,
        val records: Map<Period, Best>,
    ) {
        val unlocked: Int get() = achievements.count { it.unlocked != null }
    }

    /** What the player sees about [Profile.user]: the profile, and when it is their own, their latest wins. */
    data class PlayerPage(
        val profile: Profile,
        val own: Boolean,
        val wins: List<Win>,
    )

    /** The server knows no such player, or this viewer may not see them. */
    class NotVisible : Exception()

    /** How many wins the player's own profile shows. */
    const val WINS = 5

    /** The standings of [period] that [account] may see, best first. */
    suspend fun leaderboard(
        account: HomeAccount,
        period: Period,
    ): List<Standing> =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/leaderboard")
                    .newBuilder()
                    .addQueryParameter("period", period.query)
                    .build()
            val request = AnkiquestApi.request(url, account.token, account.language).build()
            parseLeaderboard(AnkiquestApi.call(account, request) { AnkiquestApi.jsonArray(it) }, period)
        }

    /** When this competition week ends (Unix ms), or null when the server does not say. */
    suspend fun weekEnds(account: HomeAccount): Long? =
        withContext(Dispatchers.IO) {
            try {
                parseWeekEnds(AnkiquestApi.json(account, AnkiquestApi.request(account, "api/week").build()))
            } catch (e: AnkiquestHttpException) {
                if (e.code != 404) throw e
                null
            }
        }

    /**
     * [user]'s profile as [account] may see it, with [account]'s own recent wins when it is theirs.
     * Wins are an extra: a server without them, or a failure loading them, shows the profile alone.
     *
     * @throws NotVisible when the server answers `404`
     */
    suspend fun player(
        account: HomeAccount,
        user: String,
    ): PlayerPage =
        withContext(Dispatchers.IO) {
            val request = AnkiquestApi.request(account, "api/profile/${encode(user)}").build()
            val json =
                try {
                    AnkiquestApi.json(account, request)
                } catch (e: AnkiquestHttpException) {
                    if (e.code == 404) throw NotVisible()
                    throw e
                }
            val own = user == account.user
            val wins =
                if (own) {
                    try {
                        wins(account)
                    } catch (e: AnkiquestHttpException) {
                        if (e.code == 401 || e.code == 403) throw e
                        emptyList()
                    } catch (e: org.json.JSONException) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            PlayerPage(parseProfile(json), own, wins)
        }

    private fun wins(account: HomeAccount): List<Win> {
        val url =
            account
                .url("api/wins/${account.encodedUser}")
                .newBuilder()
                .addQueryParameter("limit", WINS.toString())
                .build()
        return parseWins(AnkiquestApi.json(account, AnkiquestApi.request(url, account.token, account.language).build()))
    }

    /** The rows of a `/api/leaderboard` answer for [period], best first; older servers that ignore `period` are sorted by its XP. */
    fun parseLeaderboard(
        board: JSONArray,
        period: Period,
    ): List<Standing> =
        board
            .objects()
            .mapNotNull { row ->
                val user = row.text("user").ifEmpty { return@mapNotNull null }
                val xp =
                    row.optJSONObject("periods")?.takeIf { it.has(period.query) }?.optLong(period.query)
                        ?: row.optLong("xp", row.optLong("week_xp"))
                Standing(
                    user = user,
                    display = row.text("display").trim().ifEmpty { user },
                    level = row.optInt("level"),
                    xp = xp,
                    xpTotal = row.optLong("xp_total"),
                    streak = row.optInt("streak"),
                    state = AnkiquestWidget.streakState(row),
                    todayReviews = row.optLong("today_reviews"),
                    todayMinutes = row.optLong("today_minutes"),
                    streakMinutes = row.optLong("streak_minutes"),
                )
            }.sortedWith(compareByDescending<Standing> { it.xp }.thenByDescending { it.xpTotal }.thenBy { it.user })

    fun parseWeekEnds(json: JSONObject): Long? = json.optLong("ends_at").takeIf { it > 0 }

    fun parseProfile(json: JSONObject): Profile {
        val today = json.optJSONObject("today") ?: JSONObject()
        val lifetime = json.optJSONObject("lifetime") ?: JSONObject()
        val periods = json.optJSONObject("periods")
        val records = json.optJSONObject("records")
        val user = json.text("user")
        val conquests =
            json.optJSONArray("conquests")?.objects().orEmpty().map {
                Conquest(at = it.optLong("at"), lapses = it.optInt("lapses"), leech = it.optBoolean("leech"))
            }
        return Profile(
            user = user,
            display = json.text("display").trim().ifEmpty { user },
            level = json.optInt("level"),
            xpTotal = json.optLong("xp_total"),
            xpIntoLevel = json.optLong("xp_into_level"),
            xpForNext = json.optLong("xp_for_next"),
            periods =
                Period.entries
                    .filter { periods?.has(it.query) == true }
                    .associateWith { periods!!.optLong(it.query) },
            streak = json.optInt("streak"),
            streakState = json.text("streak_state"),
            atRisk = json.optBoolean("at_risk"),
            freezes = (if (json.has("stored_freezes")) json.optInt("stored_freezes") else json.optInt("freezes")).coerceIn(0, 3),
            freezesEnabled = json.optBoolean("freezes_enabled"),
            freezeEarnedToday = json.optBoolean("freeze_earned_today"),
            todayReviews = today.optLong("reviews"),
            todayMinutes = today.optLong("minutes"),
            todayXp = today.optLong("xp"),
            streakMinutes = today.optLong("streak_minutes"),
            lifetimeReviews = lifetime.optLong("reviews"),
            lifetimeHours = lifetime.optLong("hours"),
            bestStreak = lifetime.optLong("best_streak"),
            bestCombo = lifetime.optLong("best_combo"),
            daysActive = lifetime.optLong("days_active"),
            conquered = if (lifetime.has("conquered")) lifetime.optInt("conquered") else conquests.size,
            quests =
                json.optJSONArray("quests")?.objects().orEmpty().map {
                    Quest(
                        title = it.text("title"),
                        progress = it.optLong("progress"),
                        target = it.optLong("target"),
                        done = it.optBoolean("done"),
                        reward = it.optLong("reward"),
                    )
                },
            achievements =
                json.optJSONArray("achievements")?.objects().orEmpty().map {
                    Achievement(
                        title = it.text("title"),
                        description = it.text("description"),
                        reward = it.optLong("reward"),
                        progress = it.optLong("progress"),
                        target = it.optLong("target"),
                        unlocked = it.text("unlocked").ifEmpty { null },
                    )
                },
            conquests = conquests,
            records =
                Period.entries
                    .filter { it != Period.ALL }
                    .mapNotNull { period ->
                        records?.optJSONObject(period.query)?.let { period to Best(it.optLong("xp"), it.optLong("reviews")) }
                    }.toMap(),
        )
    }

    fun parseWins(json: JSONObject): List<Win> =
        json.optJSONArray("wins")?.objects().orEmpty().map {
            Win(title = it.text("title"), body = it.text("body"), createdAt = it.optLong("created_at"))
        }

    /**
     * Achievements in the order the website lists them: unlocked first, then the closest to
     * unlocking.
     */
    fun ordered(achievements: List<Achievement>): List<Achievement> =
        achievements.sortedWith(
            compareByDescending<Achievement> { it.unlocked != null }.thenByDescending { percent(it.progress, it.target) },
        )

    /** [part] of [whole] as a whole percentage, 0 to 100, as the website's bars draw it. */
    fun percent(
        part: Long,
        whole: Long,
    ): Int = if (whole <= 0) 0 else (part * 100 / whole).coerceIn(0, 100).toInt()

    private fun encode(user: String): String = URLEncoder.encode(user, "UTF-8").replace("+", "%20")

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}
