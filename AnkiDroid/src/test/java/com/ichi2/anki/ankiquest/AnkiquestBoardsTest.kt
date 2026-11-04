// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestBoardsTest : RobolectricTest() {
    @Test
    fun `periods are the website's pages and anything unknown is the week`() {
        assertEquals(listOf("/hour", "/day", "/week", "/month", "/year", "/all"), AnkiquestBoards.Period.entries.map { it.path })
        assertEquals(AnkiquestBoards.Period.DAY, AnkiquestBoards.Period.of("day"))
        assertEquals(AnkiquestBoards.Period.WEEK, AnkiquestBoards.Period.of(null))
        assertEquals(AnkiquestBoards.Period.WEEK, AnkiquestBoards.Period.of("records"))
    }

    @Test
    fun `a server that ignores the period is ordered by that period's XP`() {
        TimeManager.resetWith(MockTime(1_793_183_460_000L))
        val board =
            JSONArray()
                .put(row("ana", week = 90, hour = 5))
                .put(row("bo", week = 40, hour = 30).put("display", "  "))
                .put(JSONObject().put("display", "no user"))
        val hour = AnkiquestBoards.parseLeaderboard(board, AnkiquestBoards.Period.HOUR)
        assertEquals(listOf("bo", "ana"), hour.map { it.user })
        assertEquals(listOf(30L, 5L), hour.map { it.xp })
        assertEquals("bo", hour.first().display, "a blank display name shows the user name")
        assertEquals(listOf("ana", "bo"), AnkiquestBoards.parseLeaderboard(board, AnkiquestBoards.Period.WEEK).map { it.user })
    }

    @Test
    fun `an older row without periods falls back to its XP`() {
        val old = JSONArray().put(JSONObject().put("user", "ana").put("display", "Ana").put("week_xp", 12))
        assertEquals(12L, AnkiquestBoards.parseLeaderboard(old, AnkiquestBoards.Period.MONTH).single().xp)
        assertEquals("unknown", AnkiquestBoards.parseLeaderboard(old, AnkiquestBoards.Period.MONTH).single().state)
    }

    @Test
    fun `a profile reads freezes, records and achievements`() {
        val profile = AnkiquestBoards.parseProfile(JSONObject(PROFILE))
        assertEquals("Hill", profile.display)
        assertEquals(2, profile.freezes, "stored freezes count, not the ones protecting today")
        assertEquals(mapOf(AnkiquestBoards.Period.WEEK to 101L), profile.periods)
        assertEquals(AnkiquestBoards.Best(51, 5), profile.records[AnkiquestBoards.Period.HOUR])
        assertEquals(1, profile.unlocked)
        assertEquals(1, profile.conquered)
        assertTrue(profile.conquests.single().leech)
        assertEquals(listOf("Done", "Close", "Far"), AnkiquestBoards.ordered(profile.achievements).map { it.title })
        assertNull(AnkiquestBoards.parseProfile(JSONObject().put("user", "x")).records[AnkiquestBoards.Period.HOUR])
    }

    @Test
    fun `the closing week is read while the player's day still counts for it`() {
        // Just after the shared reset, a Californian's Sunday still counts toward last week.
        val endsAt = 1_793_200_000_000L
        val json = JSONObject(PROFILE).put("closing_week", JSONObject().put("xp", 1234).put("ends_at", endsAt))
        val closing = AnkiquestBoards.parseProfile(json).closingWeek
        assertEquals(AnkiquestBoards.ClosingWeek(1234, endsAt), closing)
        val line = AnkiquestBoards.closingLine(targetContext, closing, endsAt - 1)
        assertNotNull(line)
        assertTrue(line.startsWith("Last week still counting: 1,234 XP · closes at "), line)
        assertNull(AnkiquestBoards.closingLine(targetContext, closing, endsAt), "gone once their day ends")
        assertNull(AnkiquestBoards.parseProfile(JSONObject(PROFILE)).closingWeek, "older servers and most of the week have none")
        assertNull(AnkiquestBoards.closingLine(targetContext, null, 0))
        assertNull(AnkiquestBoards.parseClosingWeek(JSONObject().put("closing_week", JSONObject().put("xp", 5))))

        val rows =
            AnkiquestBoards.parseLeaderboard(
                JSONArray()
                    .put(row("ana", week = 0, hour = 0).put("closing_xp", 70).put("day_ends_at", endsAt))
                    .put(row("bo", week = 5, hour = 0)),
                AnkiquestBoards.Period.WEEK,
            )
        assertEquals(70L, rows.first { it.user == "ana" }.closingXp(endsAt - 1))
        assertNull(rows.first { it.user == "ana" }.closingXp(endsAt))
        assertNull(rows.first { it.user == "bo" }.closingXp(0))
    }

    @Test
    fun `percentages stay within the bar`() {
        assertEquals(0, AnkiquestBoards.percent(5, 0))
        assertEquals(50, AnkiquestBoards.percent(5, 10))
        assertEquals(100, AnkiquestBoards.percent(15, 10))
    }

    companion object {
        fun row(
            user: String,
            week: Long,
            hour: Long,
        ): JSONObject =
            JSONObject()
                .put("user", user)
                .put("display", user.replaceFirstChar { it.uppercase() })
                .put("level", 1)
                .put("xp", week)
                .put("week_xp", week)
                .put("xp_total", week)
                .put("periods", JSONObject().put("week", week).put("hour", hour).put("day", week))
                .put("streak", 3)
                .put("streak_state", "studied")
                .put("today_reviews", 4)
                .put("today_minutes", 0)
                .put("streak_minutes", 0)

        const val PROFILE = """{
            "user": "hill", "display": "Hill", "level": 2, "xp_total": 101, "xp_into_level": 1, "xp_for_next": 280,
            "periods": {"week": 101}, "streak": 1, "streak_state": "studied", "freezes": 1, "stored_freezes": 2,
            "freezes_enabled": true, "at_risk": false,
            "today": {"reviews": 5, "minutes": 0, "xp": 101, "streak_minutes": 0},
            "lifetime": {"reviews": 5, "hours": 0, "best_streak": 1, "best_combo": 5, "days_active": 1},
            "records": {"hour": {"xp": 51, "reviews": 5}},
            "quests": [{"title": "Review 15 cards", "progress": 5, "target": 15, "done": false, "reward": 50}],
            "achievements": [
                {"title": "Far", "description": "", "reward": 10, "progress": 1, "target": 100, "unlocked": null},
                {"title": "Close", "description": "", "reward": 10, "progress": 90, "target": 100, "unlocked": null},
                {"title": "Done", "description": "", "reward": 10, "progress": 1, "target": 1, "unlocked": "2026-10-28"}
            ],
            "conquests": [{"at": 1793183400000, "lapses": 9, "answers": 20, "since": 1790000000000, "leech": true}]
        }"""
    }
}
