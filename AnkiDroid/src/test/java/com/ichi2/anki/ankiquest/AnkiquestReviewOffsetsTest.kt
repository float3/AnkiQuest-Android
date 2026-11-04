// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestReviewOffsetsTest : RobolectricTest() {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")
    private val buenosAires = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")

    // Sunday 25 October 2026, 00:30 UTC: summer time in Berlin, which ends at 01:00 UTC.
    private val beforeChange = 1_792_888_200_000L
    private val hour = 3_600_000L

    @Test
    fun `the offset is minutes west of UTC at the moment of the review`() {
        assertEquals(-120, AnkiquestReviewOffsets.offsetAt(beforeChange, berlin))
        assertEquals(-60, AnkiquestReviewOffsets.offsetAt(beforeChange + hour, berlin), "after the daylight-saving change")
        assertEquals(180, AnkiquestReviewOffsets.offsetAt(beforeChange, buenosAires))
    }

    @Test
    fun `answers keep the offset they were first recorded with`() {
        val prefs = targetContext.sharedPrefs()
        AnkiquestReviewOffsets.record(prefs, listOf(beforeChange, beforeChange + hour), berlin)
        AnkiquestReviewOffsets.record(prefs, listOf(beforeChange, beforeChange + 2 * hour), buenosAires)
        assertEquals(
            mapOf(beforeChange to -120, beforeChange + hour to -60, beforeChange + 2 * hour to 180),
            AnkiquestReviewOffsets.read(prefs),
        )
    }

    @Test
    fun `only journaled rows are stamped`() {
        val reviews = JSONArray().put(JSONObject().put("id", 1L)).put(JSONObject().put("id", 2L))
        AnkiquestReviewOffsets.stamp(reviews, mapOf(1L to -60, 3L to 0))
        assertEquals(-60, reviews.getJSONObject(0).getInt("offset_west_min"))
        assertFalse(reviews.getJSONObject(1).has("offset_west_min"))
    }

    @Test
    fun `uploaded and stale entries are pruned`() {
        val prefs = targetContext.sharedPrefs()
        val now = beforeChange + 40 * 24 * hour
        AnkiquestReviewOffsets.record(prefs, listOf(beforeChange, now - 2_000, now - 1_000, now + 1), berlin)
        AnkiquestReviewOffsets.prune(prefs, now - 1_000, now)
        assertEquals(setOf(now + 1), AnkiquestReviewOffsets.read(prefs).keys)
        AnkiquestReviewOffsets.prune(prefs, now + 1, now)
        assertFalse(prefs.contains(AnkiquestReviewOffsets.PREF_JOURNAL), "an empty journal is removed")
    }

    @Test
    fun `a malformed journal is read as far as it makes sense`() {
        val prefs = targetContext.sharedPrefs()
        prefs.edit { putString(AnkiquestReviewOffsets.PREF_JOURNAL, "5:60,x:1,6,7:y,,8:-120") }
        assertEquals(mapOf(5L to 60, 8L to -120), AnkiquestReviewOffsets.read(prefs))
    }
}
