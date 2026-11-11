// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import org.json.JSONObject
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks [ReviewHeatmap] against `tests/fixtures/review_heatmap.json` from the ankiquest repo,
 * which the desktop add-on's heatmap also follows. The ankiquest_fixtures workflow keeps this
 * copy identical.
 */
class ReviewHeatmapFixtureTest {
    private val fixture =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResource("ankiquest/review_heatmap.json")) { "missing review heatmap" }.readText(),
        )

    private fun JSONObject.days(): Map<LocalDate, Int> = keys().asSequence().associate { LocalDate.parse(it) to getInt(it) }

    private fun JSONObject.verdicts(): Map<LocalDate, DayVerdict> =
        keys().asSequence().associate { day ->
            val verdict = getJSONObject(day)
            val state = checkNotNull(DayState.of(verdict.getString("state")))
            LocalDate.parse(day) to DayVerdict(state, verdict.getInt("minutes"), verdict.getInt("streak_minutes"))
        }

    @Test
    fun `shared colour levels`() {
        val cases = fixture.getJSONArray("intensity_cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val average = case.getDouble("daily_average")
            val counts = case.getJSONArray("counts")
            val levels = case.getJSONArray("levels")
            val expected = (0 until levels.length()).map { levels.getInt(it) }
            val actual = (0 until counts.length()).map { ReviewHeatmap.intensity(counts.getInt(it), average) }
            assertEquals(expected, actual, "daily average $average")
        }
    }

    @Test
    fun `every shared case`() {
        val zone = ZoneId.of(fixture.getString("zone"))
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() > 0)

        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val today = LocalDate.parse(case.getString("today"))

            // The collection alone, then AnkiQuest's numbering and verdicts when there is a profile.
            var data = ReviewHeatmap.summarize(today, case.getJSONObject("reviews").days(), case.getJSONObject("scheduled").days())
            case.optJSONObject("profile")?.let { data = ReviewHeatmap.withServerDays(data, ReviewHeatmap.serverDays(it), zone) }

            val expected = case.getJSONObject("expected")
            val first = if (expected.isNull("first_review_date")) null else LocalDate.parse(expected.getString("first_review_date"))
            val protectedDays =
                (0 until expected.getJSONArray("protected_days").length())
                    .map { LocalDate.parse(expected.getJSONArray("protected_days").getString(it)) }
                    .toSet()
            assertEquals(expected.getJSONObject("reviews").days(), data.reviews, name)
            assertEquals(expected.getJSONObject("scheduled").days(), data.scheduled, name)
            assertEquals(expected.getInt("current_streak"), data.currentStreak, name)
            assertEquals(expected.getInt("longest_streak"), data.longestStreak, name)
            assertEquals(expected.getLong("total_reviews"), data.totalReviews, name)
            assertEquals(expected.getInt("days_studied"), data.daysStudied, name)
            assertEquals(first, data.firstReviewDate, name)
            assertEquals(expected.getDouble("daily_average"), data.dailyAverage, 1e-9, name)
            assertEquals(protectedDays, data.protectedDays, name)
            assertEquals(expected.getJSONObject("verdicts").verdicts(), data.verdicts, name)

            // Each named day's browser search, or none.
            val searches = expected.optJSONObject("searches") ?: continue
            for (day in searches.keys()) {
                val search = if (searches.isNull(day)) null else searches.getString(day)
                assertEquals(search, ReviewHeatmap.searchForDay(data, LocalDate.parse(day)), "$name: $day")
            }
        }
    }
}
