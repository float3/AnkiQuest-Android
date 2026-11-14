// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import com.ichi2.anki.ankiquest.AnkiquestMood.Goal
import com.ichi2.anki.ankiquest.AnkiquestMood.Heat
import com.ichi2.anki.ankiquest.AnkiquestMood.Mood
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMoodTest : RobolectricTest() {
    private fun mood(
        state: State,
        streak: Int,
        reviews: Int = 0,
        frozen: Int = 0,
        goal: Goal = Goal.OPEN,
    ) = AnkiquestMood.of(state, reviews, streak, frozen, goal)

    /** A heatmap ending today, oldest first: "f" frozen, "m" met, "n" none. */
    private fun heatmap(days: String): JSONArray =
        JSONArray().apply {
            for (day in days) {
                put(
                    JSONObject().put("frozen", day == 'f').put(
                        "state",
                        if (day == 'f') {
                            "frozen"
                        } else if (day == 'm') {
                            "met"
                        } else {
                            "none"
                        },
                    ),
                )
            }
        }

    @Test
    fun `no study yet is sad with a streak and asleep without one`() {
        for (streak in listOf(1, 6, 7, 29, 30, 99, 100, 365)) {
            assertEquals(Mood.SAD, mood(State.PENDING, streak), "$streak")
            assertEquals(R.drawable.aki_at_risk, AnkiquestMood.pose(Mood.SAD, streak))
        }
        assertEquals(Mood.ASLEEP, mood(State.PENDING, 0))
        assertEquals(R.drawable.aki_sleeping, AnkiquestMood.pose(Mood.ASLEEP, 0))
    }

    @Test
    fun `some study below the minimum is hopeful`() {
        for (streak in listOf(0, 1, 6, 7, 29, 30, 99, 100, 365)) {
            assertEquals(Mood.HOPEFUL, mood(State.SHORT, streak, reviews = 3), "$streak")
        }
        assertEquals(R.drawable.aki_welcome, AnkiquestMood.pose(Mood.HOPEFUL, 64))
    }

    @Test
    fun `a counted day burns by streak tier`() {
        val expected =
            mapOf(
                1 to R.drawable.aki_streak,
                6 to R.drawable.aki_streak,
                7 to R.drawable.aki_streak_hot,
                29 to R.drawable.aki_streak_hot,
                30 to R.drawable.aki_streak_blazing,
                64 to R.drawable.aki_streak_blazing,
                99 to R.drawable.aki_streak_blazing,
                100 to R.drawable.aki_streak_legend,
                365 to R.drawable.aki_streak_legend,
            )
        for ((streak, pose) in expected) {
            assertEquals(Mood.FIRE, mood(State.STUDIED, streak, reviews = 20), "$streak")
            assertEquals(pose, AnkiquestMood.pose(Mood.FIRE, streak), "$streak")
        }
        assertEquals(Heat.WARM, Heat.of(0), "an unknown streak burns as day one")
    }

    @Test
    fun `a reached goal is on fire before the minimum`() {
        assertEquals(Mood.FIRE, mood(State.SHORT, 64, reviews = 5, goal = Goal.REACHED))
        assertEquals(Mood.FIRE, mood(State.PENDING, 64, goal = Goal.REACHED))
    }

    @Test
    fun `unknown days show the face`() {
        assertEquals(Mood.UNKNOWN, mood(State.UNKNOWN, 10, reviews = 4))
        assertEquals(R.drawable.aki_face, AnkiquestMood.pose(Mood.UNKNOWN, 10))
    }

    @Test
    fun `frozen days before today set the frozen level until the player studies`() {
        assertEquals(Mood.FROZEN_1, mood(State.PROTECTED, 10), "the server's protected day alone")
        assertEquals(Mood.FROZEN_1, mood(State.PROTECTED, 10, frozen = 1))
        assertEquals(Mood.FROZEN_2, mood(State.PROTECTED, 10, frozen = 2))
        assertEquals(Mood.FROZEN_3, mood(State.PROTECTED, 10, frozen = 3))
        assertEquals(Mood.FROZEN_3, mood(State.PROTECTED, 10, frozen = 4), "3 means 3 or more")
        assertEquals(R.drawable.aki_freeze_used, AnkiquestMood.pose(Mood.FROZEN_1, 10))
        assertEquals(R.drawable.aki_freeze, AnkiquestMood.pose(Mood.FROZEN_2, 10))
        assertEquals(R.drawable.aki_freeze_used, AnkiquestMood.pose(Mood.FROZEN_3, 10))

        // Once the player studies, the freeze no longer decides.
        assertEquals(Mood.HOPEFUL, mood(State.PROTECTED, 10, reviews = 2, frozen = 2))
        assertEquals(Mood.FIRE, mood(State.STUDIED, 11, reviews = 20, frozen = 2))
        assertEquals(R.drawable.aki_streak_hot, AnkiquestMood.pose(mood(State.STUDIED, 11, reviews = 20, frozen = 2), 11))
    }

    @Test
    fun `the frozen run counts frozen days in a row ending yesterday`() {
        assertEquals(0, AnkiquestMood.frozenRun(null))
        assertEquals(0, AnkiquestMood.frozenRun(heatmap("mmn")))
        assertEquals(1, AnkiquestMood.frozenRun(heatmap("mmfn")))
        assertEquals(2, AnkiquestMood.frozenRun(heatmap("mffn")))
        assertEquals(3, AnkiquestMood.frozenRun(heatmap("mfffn")))
        assertEquals(4, AnkiquestMood.frozenRun(heatmap("ffffn")), "the whole history")
        assertEquals(1, AnkiquestMood.frozenRun(heatmap("ffmfn")), "a met day ends the run")
        assertEquals(0, AnkiquestMood.frozenRun(heatmap("fffmn")), "only the run ending yesterday counts")
        assertEquals(0, AnkiquestMood.frozenRun(heatmap("mmf")), "today's own cell is not before today")
    }

    @Test
    fun `the big widget rotates only within today's mood`() {
        for (mood in Mood.entries) {
            for (streak in listOf(0, 1, 7, 30, 100, 365)) {
                val poses = AnkiquestMood.poses(mood, streak)
                assertEquals(AnkiquestMood.pose(mood, streak), poses.first(), "$mood $streak")
                if (mood != Mood.FIRE || streak < 100) {
                    assertEquals(1, poses.size, "$mood $streak")
                }
            }
        }
        assertEquals(listOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone), AnkiquestMood.poses(Mood.FIRE, 365))
    }

    @Test
    fun `the big pose stays all day and changes between days`() {
        val day = 86_400_000L
        val today = 20_000 * day + 5 * 3_600_000
        val pose = { endsAt: Long -> AnkiquestMood.bigPose(Mood.FIRE, 120, endsAt) }
        assertEquals(
            setOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone),
            setOf(pose(today), pose(today + day)),
            "two days in a row show both poses",
        )
    }

    @Test
    fun `the next milestone counts down`() {
        assertEquals("36 days to 100 🔥", AnkiquestMood.nextLine(targetContext, 64))
        assertEquals("1 day to 7 🔥", AnkiquestMood.nextLine(targetContext, 6))
        assertNull(AnkiquestMood.nextLine(targetContext, 0))
        assertNull(AnkiquestMood.nextLine(targetContext, 365))
    }

    @Test
    fun `streak colours keep text readable`() {
        val ink = targetContext.getColor(R.color.aq_heat_ink)
        val gold = targetContext.getColor(R.color.aq_heat_number)
        for (heat in Heat.entries) {
            for (background in listOf(heat.top, heat.bottom).map(targetContext::getColor)) {
                assertTrue(ColorUtils.calculateContrast(ink, background) >= AA_TEXT, "$heat text")
                assertTrue(ColorUtils.calculateContrast(gold, background) >= AA_LARGE, "$heat number")
            }
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "notnight")
    fun `the widget flames stand out on the light card`() = assertFlames()

    @Test
    @Config(sdk = [33], qualifiers = "night")
    fun `the widget flames stand out on the dark card`() = assertFlames()

    private fun assertFlames() {
        val card = targetContext.getColor(R.color.ankiquest_widget_bg) or OPAQUE
        val flames =
            listOf(
                R.color.ankiquest_streak_flame_1,
                R.color.ankiquest_streak_flame_7,
                R.color.ankiquest_streak_flame_30,
                R.color.ankiquest_streak_flame_100,
                R.color.ankiquest_streak_flame_365,
                R.color.ankiquest_streak_warm,
            )
        for (flame in flames) {
            val contrast = ColorUtils.calculateContrast(targetContext.getColor(flame), card)
            assertTrue(contrast >= AA_LARGE, "${targetContext.resources.getResourceEntryName(flame)} is $contrast:1")
        }
    }

    private companion object {
        /** WCAG 2 AA contrast for normal text, and for large text and graphics. */
        const val AA_TEXT = 4.5
        const val AA_LARGE = 3.0
        const val OPAQUE = 0xFF000000.toInt()
    }
}
