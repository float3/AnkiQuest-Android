// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject

/** Deck completion uses Anki's daily limits, including subdecks and learning later today. */
internal object AnkiquestDecks {
    private const val DAY_MS = 86_400_000L
    const val DECK_STATUS_KEY = "ankiquestDeckStatus"

    /** Whether the deck list marks decks finished today and learning due later. */
    fun statusInDeckList(): Boolean =
        com.ichi2.anki.AnkiDroidApp
            .sharedPrefs()
            .getBoolean(DECK_STATUS_KEY, true)

    fun dailyStates(
        col: Collection,
        now: Long,
        dueTree: com.ichi2.anki.libanki.sched.DeckNode? = null,
    ): Map<Long, DailyDeckState> {
        val tree = dueTree ?: col.sched.deckDueTree()
        val rows = snapshots(col, now, 0, 0, tree)
        val indexed = tree.associateBy { it.did }
        val names = col.decks.allNamesAndIds(includeFiltered = false)
        val next = mutableMapOf<Long, Pair<Long, Long>>()
        val detachedDue = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select case when odid != 0 then odid else did end, count(*) from cards " +
                    "where (queue in (1,4) and due < ?) " +
                    "or (odid != 0 and (queue = 0 or (queue in (2,3) and due <= ?))) group by 1",
                col.sched.dayCutoff,
                col.sched.today,
            ).use { cursor ->
                while (cursor.moveToNext()) detachedDue[cursor.getLong(0)] = cursor.getLong(1)
            }
        col.db
            .query(
                "select case when odid != 0 then odid else did end, count(*), min(due) from cards where queue in (1,4) and due > ? and due < ? group by 1",
                now / 1000,
                col.sched.dayCutoff,
            ).use { cursor ->
                while (cursor.moveToNext()) next[cursor.getLong(0)] = cursor.getLong(1) to cursor.getLong(2) * 1000
            }
        val ordinaryIds = names.map { it.id }.toSet()
        return (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id").toLong()
            val name = row.getString("name")
            val node = indexed[id]
            val descendants = names.filter { it.id == id || it.name.startsWith("$name::") }
            val nextAt = descendants.mapNotNull { next[it.id]?.second }.minOrNull()
            // snapshots uses a sentinel for a deck omitted by Anki's tree.
            // Use actual learning and filtered cards for display instead.
            val remaining = if (node == null) descendants.sumOf { detachedDue[it.id] ?: 0L } else row.getLong("remaining")
            val later = descendants.sumOf { next[it.id]?.first ?: 0L }
            val due = node?.let { (it.newCount + it.lrnCount + it.revCount).toLong() } ?: 0L
            id to DailyDeckState(maxOf(due, remaining - later), remaining, row.getLong("reviewed_today"), nextAt)
        } +
            indexed.filterKeys { id -> id !in ordinaryIds && id != 0L }.mapValues { (_, node) ->
                val due = (node.newCount + node.lrnCount + node.revCount).toLong()
                DailyDeckState(due, due, 0)
            }
    }

    fun day(
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
    ): Long = AnkiquestCompletionPolicy.studyDay(now, offsetWestMinutes, rolloverHour)

    fun snapshots(
        col: Collection,
        now: Long,
        offsetWestMinutes: Int,
        rolloverHour: Int,
        dueTree: com.ichi2.anki.libanki.sched.DeckNode? = null,
    ): JSONArray {
        val day = day(now, offsetWestMinutes, rolloverHour)
        val end = col.sched.dayCutoff * 1000
        val start = end - DAY_MS
        val names = col.decks.allNamesAndIds(includeFiltered = false)
        val tree = (dueTree ?: col.sched.deckDueTree()).associateBy { it.did }
        val reviewed = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select case when c.odid != 0 then c.odid else c.did end, count(*) " +
                    "from revlog r join cards c on c.id = r.cid " +
                    "where r.id >= ? and r.id < ? and r.ease > 0 and r.type < 4 group by 1",
                start,
                end,
            ).use { cursor ->
                while (cursor.moveToNext()) reviewed[cursor.getLong(0)] = cursor.getLong(1)
            }
        // The deck picker only shows learning inside the learn-ahead window. Any learning
        // still due before rollover prevents completion, even when that window is empty.
        val learning = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select did, count(*) from cards where odid = 0 and queue in (1, 4) and due < ? group by did",
                end / 1000,
            ).use { cursor ->
                while (cursor.moveToNext()) learning[cursor.getLong(0)] = cursor.getLong(1)
            }
        val filtered = mutableMapOf<Long, Long>()
        col.db
            .query(
                "select odid, count(*) from cards where odid != 0 and " +
                    "(queue = 0 or (queue in (1, 4) and due < ?) " +
                    "or (queue in (2, 3) and due <= ?)) group by odid",
                end / 1000,
                col.sched.today,
            ).use { cursor ->
                while (cursor.moveToNext()) filtered[cursor.getLong(0)] = cursor.getLong(1)
            }
        val snapshots = JSONArray()
        for (deck in names) {
            val node = tree[deck.id]
            val descendants = names.filter { it.id == deck.id || it.name.startsWith("${deck.name}::") }.map { it.id }
            val intradayInTree = node?.sumOf { it.node.intradayLearning.toLong() } ?: 0L
            // Replace Anki's short learning window (which can extend past rollover) with
            // learning before the study day ends, and attribute filtered cards to home decks.
            val due =
                if (node == null) {
                    1L // The empty Default deck may be omitted from the tree; unavailable is not completed.
                } else {
                    node.newCount.toLong() + node.revCount + maxOf(0L, node.lrnCount - intradayInTree) +
                        descendants.sumOf { (learning[it] ?: 0L) + (filtered[it] ?: 0L) }
                }
            snapshots.put(
                JSONObject()
                    .put("id", deck.id.toString())
                    .put("name", deck.name)
                    .put("remaining", due)
                    .put("reviewed_today", descendants.sumOf { reviewed[it] ?: 0L })
                    .put("day", day),
            )
        }
        return snapshots
    }
}
