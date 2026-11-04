// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.CollectionManager
import org.json.JSONArray
import java.util.TimeZone

/**
 * The UTC offset each review answered on this device was made on, so an upload can say which
 * clock it was made on (`offset_west_min` on the review row, "review_offsets" in the server's
 * capabilities; older servers ignore the field).
 *
 * An upload's `clock` describes where the phone is when it is sent. Without stamps, a review
 * answered after a time-zone change but uploaded later, such as offline after a flight or
 * queued across a daylight-saving change, is read on the old clock and can land on the wrong
 * study day. Only answers seen here are recorded: rows that reached the collection through
 * AnkiWeb from another device are never in the journal and are uploaded unstamped.
 *
 * The journal lives in the app's preferences, not the collection, so it never syncs. An entry
 * is dropped once its review has been uploaded, or after [KEEP_MS] in any case.
 */
internal object AnkiquestReviewOffsets {
    const val PREF_JOURNAL = "ankiquestReviewOffsets"
    const val KEEP_MS = 30 * 24 * 60 * 60 * 1000L

    /** How far back an answer's revlog row may be when the reviewer's change arrives. */
    const val ANSWER_WINDOW_MS = 60 * 1000L

    /** Minutes west of UTC in [zone] at [epochMs], like the upload's `clock`. */
    fun offsetAt(
        epochMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Int = -zone.getOffset(epochMs) / 60_000

    fun read(prefs: SharedPreferences): Map<Long, Int> =
        prefs
            .getString(PREF_JOURNAL, "")
            .orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val id = entry.substringBefore(':').toLongOrNull()
                val offset = entry.substringAfter(':', "").toIntOrNull()
                if (id == null || offset == null) null else id to offset
            }.toMap()

    private fun write(
        prefs: SharedPreferences,
        journal: Map<Long, Int>,
    ) = prefs.edit {
        if (journal.isEmpty()) {
            remove(PREF_JOURNAL)
        } else {
            putString(PREF_JOURNAL, journal.entries.joinToString(",") { "${it.key}:${it.value}" })
        }
    }

    /** Records the offset of each of [answered] (revlog ids) not already in the journal. */
    fun record(
        prefs: SharedPreferences,
        answered: List<Long>,
        zone: TimeZone = TimeZone.getDefault(),
    ) {
        val journal = read(prefs)
        val fresh = answered.filter { it !in journal }
        if (fresh.isEmpty()) return
        write(prefs, journal + fresh.associateWith { offsetAt(it, zone) })
    }

    /**
     * Records the answers just made in the reviewer: the review rows written since
     * [ANSWER_WINDOW_MS] before [now], or since the last recorded one if that is later.
     */
    suspend fun recordAnswers(
        prefs: SharedPreferences,
        now: Long,
    ) {
        val since = maxOf(now - ANSWER_WINDOW_MS, read(prefs).keys.maxOrNull() ?: 0L)
        val answered =
            CollectionManager.withCol {
                db.queryLongList("select id from revlog where id > ? and ease > 0 and type < 4 order by id", since)
            }
        record(prefs, answered)
    }

    /** Adds `offset_west_min` to the review rows in [reviews] found in [journal]. */
    fun stamp(
        reviews: JSONArray,
        journal: Map<Long, Int>,
    ): JSONArray {
        if (journal.isEmpty()) return reviews
        for (i in 0 until reviews.length()) {
            val review = reviews.getJSONObject(i)
            journal[review.getLong("id")]?.let { review.put("offset_west_min", it) }
        }
        return reviews
    }

    /** Drops the entries of reviews uploaded through [uploadedThrough], and any older than [KEEP_MS]. */
    fun prune(
        prefs: SharedPreferences,
        uploadedThrough: Long,
        now: Long,
    ) {
        val journal = read(prefs)
        val kept = journal.filterKeys { it > uploadedThrough && it > now - KEEP_MS }
        if (kept.size != journal.size) write(prefs, kept)
    }
}
