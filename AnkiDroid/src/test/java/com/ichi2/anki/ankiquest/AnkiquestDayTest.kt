// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks [AnkiquestDay] against `tests/fixtures/day_verdicts.json` from the ankiquest server, which
 * the server works out with its own calculation. The ankiquest_fixtures workflow keeps this copy identical.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
class AnkiquestDayTest {
    private val fixtures =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/day_verdicts.json")) { "missing day verdicts" }.readText(),
        )

    private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else getInt(name)

    private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)

    @Test
    fun `today's verdict with unsent reviews matches the server`() {
        val cases = fixtures.getJSONArray("cases").objects()
        check(cases.size >= 10)
        for (case in cases) {
            val name = case.getString("name")
            val reviews = case.getJSONArray("pending").objects().map { AnkiquestDay.Review(it.getLong("id"), it.getLong("time_ms")) }
            val verdict = AnkiquestDay.judge(AnkiquestDay.Server.profile(case.getJSONObject("server")), reviews, case.getLong("now_ms"))
            if (case.isNull("expected")) {
                assertNull(verdict, name)
                continue
            }
            val expected = case.getJSONObject("expected")
            checkNotNull(verdict) { name }
            assertEquals(expected.getBoolean("met"), verdict.met, name)
            assertEquals(expected.nullableString("streak_state"), verdict.streakState, name)
            assertEquals(expected.nullableInt("streak"), verdict.streak, name)
            assertEquals(expected.getInt("reviews"), verdict.reviews, name)
            assertEquals(expected.getLong("time_ms"), verdict.timeMs, name)
            assertEquals(expected.getInt("minutes"), verdict.minutes, name)
            assertEquals(expected.getInt("streak_minutes"), verdict.streakMinutes, name)
            assertEquals(expected.getInt("unsent"), verdict.unsent, name)
            assertEquals(
                verdict.met && case.getJSONObject("server").getString("streak_state") != "studied",
                verdict.awaitingUpload,
                name,
            )
        }
    }

    @Test
    fun `an older server's answer counts no unsent review and its minutes as a lower bound`() {
        val profile =
            JSONObject(
                """{"streak": 3, "streak_state": "pending", "day_ends_at": 2000, "last_review_id": 10,
                   "today": {"reviews": 2, "minutes": 9, "streak_minutes": 10}}""",
            )
        val verdict =
            checkNotNull(AnkiquestDay.judge(AnkiquestDay.Server.profile(profile), listOf(AnkiquestDay.Review(500, 600_000)), 1000))
        assertFalse(verdict.met, "without starts_at the unsent review cannot be placed in today")
        assertEquals("short", verdict.label)
        assertEquals(540_000, verdict.timeMs)
    }

    @Test
    fun `a leaderboard row reads as the server judged it`() {
        fun row(json: String) = AnkiquestDay.label(AnkiquestDay.Server.row(JSONObject(json)), 1000)
        assertEquals("studied", row("""{"streak_state": "studied", "today_reviews": 3, "day_ends_at": 2000}"""))
        assertEquals(
            "short",
            row("""{"streak_state": "pending", "today_reviews": 3, "today_minutes": 4, "streak_minutes": 10, "day_ends_at": 2000}"""),
        )
        assertEquals(
            "protected",
            row("""{"streak_state": "protected", "today_reviews": 3, "today_minutes": 2, "streak_minutes": 10, "day_ends_at": 2000}"""),
        )
        assertEquals("studied", row("""{"today_reviews": 1}"""), "an older server without a state or minimum counts any review")
        assertEquals("pending", row("""{"streak_state": "pending", "today_reviews": 0, "day_ends_at": 2000}"""))
        assertEquals("unknown", row("""{"streak_state": "studied", "today_reviews": 3, "day_ends_at": 1000}"""), "the row's day ended")
        assertEquals("unknown", row("""{"streak_state": "studied"}"""), "no counts")
        assertEquals("pending", row("""{"today_reviews": 0}"""))
    }

    @Test
    fun `what the server said of today is kept and read back`() {
        val prefs = RuntimeEnvironment.getApplication().sharedPrefs().also { it.edit { clear() } }
        val profile =
            JSONObject(
                """{"streak": 4, "streak_state": "pending", "day_ends_at": 2000, "last_review_id": 10,
                   "today": {"starts_at": 100, "reviews": 1, "time_ms": 30000, "minutes": 0, "streak_minutes": 1}}""",
            )
        AnkiquestDay.remember(profile, prefs)
        val kept = checkNotNull(AnkiquestDay.last(prefs))
        assertEquals(AnkiquestDay.Server.profile(profile), kept)
        val verdict = checkNotNull(AnkiquestDay.judge(kept, listOf(AnkiquestDay.Review(150, 30_000)), 1000))
        assertTrue(verdict.met)
        assertTrue(verdict.awaitingUpload)
        assertEquals(5, verdict.streak)
    }
}
