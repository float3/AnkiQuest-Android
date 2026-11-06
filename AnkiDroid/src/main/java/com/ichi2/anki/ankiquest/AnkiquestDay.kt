// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.libanki.Collection
import org.json.JSONObject

/**
 * Today's streak verdict, worked out one way for every screen.
 *
 * The server judges every day: `streak_state`, `today` and the heatmap's states say whether a day
 * counts, and screens show that. The one thing the app adds is reviews it holds but has not
 * uploaded yet, so the streak can read as secured right after answering offline. [judge] adds them
 * exactly as the server will once they arrive (docs/mobile-api.md, "day verdicts"): only reviews
 * after the server's last one, only those inside the server's today, their revlog time added to the
 * server's exact `today.time_ms`, compared with the server's own `today.streak_minutes`. It never
 * calls a day secured unless the server said so or that sum provably reaches the minimum, and then
 * [Verdict.awaitingUpload] says the server has not confirmed it yet.
 *
 * The rule is pinned by `day_verdicts.json`, which the server writes from its own calculation.
 */
internal object AnkiquestDay {
    private const val PREF_SERVER_DAY = "ankiquestServerToday"
    private const val MINUTE_MS = 60_000L

    /** Revlog rows the upload sends: answers, not manual rescheduling. */
    const val UPLOADED_REVIEWS = "ease > 0 and type < 4"

    /** What the server last said about today. */
    data class Server(
        val streak: Int,
        /** studied, protected or pending, as the server says it. */
        val streakState: String,
        val dayEndsAt: Long,
        /** The newest review the server has; later ones are not uploaded yet. */
        val lastReviewId: Long,
        /** When today began; null from servers that do not say, which leaves unsent reviews uncounted. */
        val startsAt: Long?,
        val reviews: Int,
        /** Exact review time today; from servers that only send minutes, those minutes. */
        val timeMs: Long,
        val streakMinutes: Int,
    ) {
        fun json(): JSONObject =
            JSONObject()
                .put("streak", streak)
                .put("streak_state", streakState)
                .put("day_ends_at", dayEndsAt)
                .put("last_review_id", lastReviewId)
                .put(
                    "today",
                    JSONObject()
                        .put("starts_at", startsAt ?: JSONObject.NULL)
                        .put("reviews", reviews)
                        .put("time_ms", timeMs)
                        .put("streak_minutes", streakMinutes),
                )

        companion object {
            /** From a profile, `/api/profile` or an upload's answer; null without today's numbers. */
            fun profile(json: JSONObject?): Server? {
                val today = json?.optJSONObject("today") ?: return null
                if (!json.has("streak_state") || !json.has("day_ends_at")) return null
                return Server(
                    streak = json.optInt("streak"),
                    streakState = json.optString("streak_state"),
                    dayEndsAt = json.optLong("day_ends_at"),
                    lastReviewId = json.optLong("last_review_id"),
                    startsAt = today.optLong("starts_at").takeIf { today.has("starts_at") && !today.isNull("starts_at") },
                    reviews = today.optInt("reviews"),
                    timeMs = if (today.has("time_ms")) today.optLong("time_ms") else today.optLong("minutes") * MINUTE_MS,
                    streakMinutes = today.optInt("streak_minutes"),
                )
            }

            /**
             * From a leaderboard row, which has today's counts but not the reviews themselves. Rows from
             * servers older than `streak_state` are judged by their counts alone.
             */
            fun row(json: JSONObject): Server? {
                if (!json.has("today_reviews") || json.isNull("today_reviews")) return null
                return Server(
                    streak = json.optInt("streak"),
                    streakState = json.optString("streak_state"),
                    dayEndsAt = json.optLong("day_ends_at").takeIf { it > 0 } ?: Long.MAX_VALUE,
                    lastReviewId = Long.MAX_VALUE,
                    startsAt = null,
                    reviews = json.optInt("today_reviews"),
                    timeMs = json.optLong("today_minutes") * MINUTE_MS,
                    streakMinutes = json.optInt("streak_minutes"),
                )
            }
        }
    }

    /** A review in the collection: its revlog id, the millisecond it was answered, and its time. */
    data class Review(
        val id: Long,
        val timeMs: Long,
    )

    data class Verdict(
        /** Today counts toward the streak: the server said so, or the unsent reviews provably make it. */
        val met: Boolean,
        /** studied, protected or pending; null when an unsent review from an earlier day leaves it open. */
        val streakState: String?,
        /** The streak with today; null when an unsent review from an earlier day may change it. */
        val streak: Int?,
        val reviews: Int,
        val timeMs: Long,
        val streakMinutes: Int,
        /** Reviews counted here that the server does not have yet. */
        val unsent: Int,
        /** The server itself said today counts. */
        val confirmed: Boolean,
    ) {
        val minutes: Int get() = (timeMs.coerceAtLeast(0) / MINUTE_MS).toInt()

        /** Secured by reviews the server has not received yet: say so until the upload lands. */
        val awaitingUpload: Boolean get() = met && !confirmed

        /** The words every screen uses: studied, protected, short (reviews under the minimum), pending or unknown. */
        val label: String
            get() =
                when {
                    met -> "studied"
                    streakState == null -> "unknown"
                    streakState == "protected" -> "protected"
                    reviews > 0 -> "short"
                    else -> "pending"
                }
    }

    /**
     * Today's verdict from what the [server] last said and the collection's [reviews] after it, at
     * [now]. Null when there is nothing to judge by: no answer yet, or one from a day that has ended.
     */
    fun judge(
        server: Server?,
        reviews: List<Review>,
        now: Long,
    ): Verdict? {
        if (server == null || now >= server.dayEndsAt) return null
        val unsent = reviews.filter { it.id > server.lastReviewId }
        val starts = server.startsAt
        val counted = if (starts == null) emptyList() else unsent.filter { it.id >= starts && it.id < server.dayEndsAt }
        val earlier = starts != null && unsent.any { it.id < starts }
        val count = server.reviews + counted.size
        val time = server.timeMs + counted.sumOf { it.timeMs }
        val confirmed = server.streakState == "studied"
        val met = confirmed || (count > 0 && time >= server.streakMinutes * MINUTE_MS)
        return Verdict(
            met = met,
            streakState =
                when {
                    met -> "studied"
                    earlier -> null
                    else -> server.streakState
                },
            streak = if (earlier) null else server.streak + if (met && !confirmed) 1 else 0,
            reviews = count,
            timeMs = time,
            streakMinutes = server.streakMinutes,
            unsent = counted.size,
            confirmed = confirmed,
        )
    }

    /** Keeps what [profile] says of today for the configured account, for [judge] offline and right after studying. */
    fun remember(
        profile: JSONObject,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val server = Server.profile(profile) ?: return
        val key = key(prefs)
        val value = server.json().toString()
        if (prefs.getString(key, null) != value) prefs.edit { putString(key, value) }
    }

    /** What the server last said of today for the configured account; another account's is never used. */
    fun last(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Server? =
        prefs.getString(key(prefs), null)?.let { runCatching { Server.profile(JSONObject(it)) }.getOrNull() }

    /** The collection's reviews the server may not have for [server]'s today: those after its last one. */
    fun unsent(
        col: Collection,
        server: Server?,
    ): List<Review> {
        if (server?.startsAt == null) return emptyList()
        val rows = mutableListOf<Review>()
        col.db
            .query(
                "select id, time from revlog where id > ? and $UPLOADED_REVIEWS order by id",
                server.lastReviewId,
            ).use { cursor ->
                while (cursor.moveToNext()) rows += Review(cursor.getLong(0), cursor.getLong(1))
            }
        return rows
    }

    /** One status line for a streak [state] from [Verdict.label], as every screen words it. */
    fun words(
        context: Context,
        state: String,
        minutes: Long,
        streakMinutes: Long,
    ): String =
        when (state) {
            "studied" -> context.getString(R.string.ankiquest_streak_studied)
            "protected" -> context.getString(R.string.ankiquest_streak_protected)
            "short" -> context.getString(R.string.ankiquest_streak_short, minOf(minutes, streakMinutes), streakMinutes)
            "pending" -> context.getString(R.string.ankiquest_streak_pending)
            else -> context.getString(R.string.ankiquest_streak_unknown)
        }

    /** [Verdict.label] for counts the server sent, with nothing unsent: a leaderboard row or a profile. */
    fun label(
        server: Server?,
        now: Long,
    ): String = judge(server, emptyList(), now)?.label ?: "unknown"

    private fun key(prefs: SharedPreferences) = scopedKey(PREF_SERVER_DAY, AnkiquestHomeData.account(prefs)?.scope)
}
