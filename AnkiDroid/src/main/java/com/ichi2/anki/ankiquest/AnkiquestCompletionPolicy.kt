// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.json.JSONObject

/** Pure boundaries shared by completion uploads, snapshots and the private inbox. */
internal object AnkiquestCompletionPolicy {
    fun studyDay(
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): Long = (now - offsetWestMinutes * 60_000L - rolloverHour * 3_600_000L).floorDiv(86_400_000L)

    /**
     * Whether an upload batch must not announce anything. The first upload sends the whole history,
     * so it stays silent until it finishes, also when it carries on after being cut off between batches.
     */
    fun silentUpload(
        initialSyncDone: Boolean,
        fullBatch: Boolean,
        onlyTest: Boolean,
    ): Boolean = !initialSyncDone || fullBatch || onlyTest

    fun freshNotification(
        createdAt: Long,
        now: Long,
    ): Boolean = createdAt > 0 && createdAt >= now - 86_400

    /** Recently uploaded reviews that were undone, and older window reviews the server never received. */
    data class Reconciliation(
        val deleted: List<Long>,
        val restored: List<Long>,
    )

    /**
     * Compares the local review [window] with the ids [recent]ly uploaded. Nothing is deleted
     * before the first upload ([mark] 0); only reviews at or below [known] can be restored.
     */
    fun reconcile(
        window: List<Long>,
        recent: Set<Long>,
        known: Long,
        mark: Long,
    ): Reconciliation =
        Reconciliation(
            deleted = if (mark == 0L) emptyList() else (recent - window.toSet()).sorted(),
            restored = window.filter { it <= known && it !in recent },
        )

    /** One revlog row `id, cid, lastIvl, time, type` as the server's review upload object. */
    fun review(
        id: Long,
        cid: Long,
        lastIvl: Long,
        ivl: Long,
        timeMs: Long,
        kind: Int,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("cid", cid)
            .put("last_ivl", lastIvl)
            .put("ivl", ivl)
            .put("time_ms", timeMs)
            .put("kind", kind)
}
