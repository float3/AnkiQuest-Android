// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject

/** Local, revisitable feedback. Review counts come from the log, including undo, never from XP. */
object AnkiquestStudySession {
    private const val PREF_ACTIVE_SESSION = "ankiquestActiveStudySession"
    private const val PREF_LATEST_SESSION = "ankiquestLastStudySession"
    private const val MAX_DURATION = 24 * 60 * 60 * 1000L

    data class Summary(
        val reviews: Int,
        val minutes: Int,
        val remaining: Int,
        val finishedAt: Long,
    ) {
        fun duration(context: Context): String =
            if (minutes == 0) {
                context.getString(R.string.ankiquest_summary_under_minute)
            } else {
                context.resources.getQuantityString(R.plurals.ankiquest_summary_minutes, minutes, minutes)
            }
    }

    fun start() {
        if (!AnkiquestNavigation.enabled()) return
        val value =
            JSONObject()
                .put("account", AnkiquestNavigation.accountFingerprint())
                .put("started", TimeManager.time.intTimeMS())
        AnkiDroidApp.sharedPrefs().edit { putString(PREF_ACTIVE_SESSION, value.toString()) }
    }

    suspend fun finish(): Summary? {
        val prefs = AnkiDroidApp.sharedPrefs()
        val active = prefs.getString(PREF_ACTIVE_SESSION, null) ?: return null
        prefs.edit { remove(PREF_ACTIVE_SESSION) }
        val data = runCatching { JSONObject(active) }.getOrNull() ?: return null
        val account = AnkiquestNavigation.accountFingerprint()
        if (!AnkiquestNavigation.enabled() || data.optString("account") != account) return null
        val now = TimeManager.time.intTimeMS()
        val started = data.optLong("started")
        if (started <= 0 || now < started || now - started > MAX_DURATION) return null
        val summary =
            CollectionManager.withCol {
                db
                    .query(
                        "select count(*), coalesce(sum(time), 0) from revlog where id > ? and id <= ? and ease > 0 and type < 4",
                        started,
                        now,
                    ).use { cursor ->
                        cursor.moveToFirst()
                        Summary(cursor.getInt(0), (cursor.getLong(1) / 60_000).toInt(), sched.totalCount(), now)
                    }
            }
        if (summary.reviews == 0 || account != AnkiquestNavigation.accountFingerprint()) return null
        val stored =
            JSONObject()
                .put("account", account)
                .put("reviews", summary.reviews)
                .put("minutes", summary.minutes)
                .put("remaining", summary.remaining)
                .put("finished_at", summary.finishedAt)
        prefs.edit { putString(PREF_LATEST_SESSION, stored.toString()) }
        return summary
    }

    fun latest(): Summary? {
        val prefs = AnkiDroidApp.sharedPrefs()
        val text = prefs.getString(PREF_LATEST_SESSION, null) ?: return null
        val data = runCatching { JSONObject(text) }.getOrNull()
        if (!AnkiquestNavigation.enabled() || data == null || data.optString("account") != AnkiquestNavigation.accountFingerprint()) {
            prefs.edit {
                remove(PREF_LATEST_SESSION)
                remove(PREF_ACTIVE_SESSION)
            }
            return null
        }
        return Summary(data.optInt("reviews"), data.optInt("minutes"), data.optInt("remaining"), data.optLong("finished_at"))
    }
}
