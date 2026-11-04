// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestChallengesTest {
    private val goal = AnkiquestChallenges.NewGoal("Three study days together", false, false, 3, 7, listOf("cerro"), false)

    @Test
    fun `a new goal is checked the way the server checks it`() {
        assertNull(goal.problem())
        assertEquals(AnkiquestChallenges.Problem.NO_TITLE, goal.copy(title = " ").problem())
        assertEquals(AnkiquestChallenges.Problem.RECIPIENTS, goal.copy(recipients = emptyList()).problem())
        assertEquals(AnkiquestChallenges.Problem.RECIPIENTS, goal.copy(recipients = (1..31).map { "p$it" }).problem())
        assertEquals(AnkiquestChallenges.Problem.NUMBERS, goal.copy(days = 32).problem())
        assertEquals(AnkiquestChallenges.Problem.NUMBERS, goal.copy(target = 0).problem())
        assertEquals(AnkiquestChallenges.Problem.TOO_MANY_DAYS, goal.copy(target = 8).problem())
        assertNull(goal.copy(target = 14, cooperative = true).problem(), "two people have 14 days together")
        assertNull(goal.copy(target = 500, reviews = true).problem())
    }

    @Test
    fun `a new goal is sent with its request id`() {
        val sent = goal.copy(title = "  Spanish  ", reviews = true, target = 50, startWhenReady = true).json("request-1")
        assertEquals("Spanish", sent.getString("title"))
        assertEquals("reviews", sent.getString("kind"))
        assertEquals(50, sent.getInt("target"))
        assertEquals(7, sent.getInt("duration_days"))
        assertEquals("cerro", sent.getJSONArray("recipients").getString(0))
        assertTrue(sent.getBoolean("start_when_ready"))
        assertEquals("request-1", sent.getString("request_id"))
    }

    @Test
    fun `goals without an id, people without a name and a missing suggestion are skipped`() {
        val overview =
            AnkiquestChallenges.parse(
                JSONObject(
                    """
                    {"challenges": [{"id": 0, "title": "broken"}, {"id": 5, "title": "Kept", "kind": "study_days",
                      "members": [{"user": "", "display": "Nobody"}, {"user": "cerro", "display": null, "status": "accepted", "progress": 2}],
                      "end_at": 2000, "status": "active"}],
                     "recipients": [{"user": "cerro", "display": "Cerro"}, {"display": "No user"}],
                     "weekly_suggestion": null}
                    """,
                ),
            )
        val kept = overview.challenges.single()
        assertEquals(5, kept.id)
        assertEquals(listOf(AnkiquestChallenges.Member("cerro", "cerro", "accepted", 2)), kept.members)
        assertTrue(kept.started, "older servers without `started` started their goals at once")
        assertTrue(kept.ended(2000), "a goal is over at its deadline")
        assertEquals(listOf("cerro"), overview.recipients.map { it.user })
        assertNull(overview.suggestion)
    }
}
