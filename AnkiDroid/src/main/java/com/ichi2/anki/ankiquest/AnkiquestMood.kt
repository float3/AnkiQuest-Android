// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import org.json.JSONArray

/**
 * How Aki feels about today's streak: the one rule for the streak widget, the streak moments and
 * their pictures. Sad before any study, hopeful after some, on fire once today counts, and frozen
 * after days a freeze kept. New poses for sad, hopeful and the frozen levels are requested in
 * float3/ankiquest#88; until then the closest existing poses stand in.
 */
internal object AnkiquestMood {
    private const val DAY_MS = 86_400_000L

    /** The streak lengths the widget and pictures count down to. */
    private val milestones = listOf(7, 30, 100, 365)

    /** Today's mood. The frozen levels count the frozen days in a row ending yesterday; 3 means 3 or more. */
    enum class Mood {
        /** Nothing is known about today. */
        UNKNOWN,

        /** No streak to lose. */
        ASLEEP,

        /** A streak and no study yet today. */
        SAD,

        /** A freeze kept yesterday and no study yet today. */
        FROZEN_1,
        FROZEN_2,
        FROZEN_3,

        /** Some study today, short of the streak minimum. */
        HOPEFUL,

        /** Today counts, or today's goal is reached. */
        FIRE,
    }

    /** Whether today's daily goal is reached. */
    enum class Goal { OPEN, REACHED }

    /**
     * How hot a streak burns, from the day it starts at: its pose, and the share picture's and moment
     * card's colours. White text on both gradient ends meets WCAG AA (4.5:1); the gold number meets
     * the large-text ratio (3:1).
     */
    enum class Heat(
        val days: Int,
        @DrawableRes val pose: Int,
        @ColorRes val top: Int,
        @ColorRes val bottom: Int,
    ) {
        WARM(1, R.drawable.aki_streak, R.color.aq_heat_warm_top, R.color.aq_heat_warm_bottom),
        HOT(7, R.drawable.aki_streak_hot, R.color.aq_heat_hot_top, R.color.aq_heat_hot_bottom),
        BLAZING(30, R.drawable.aki_streak_blazing, R.color.aq_heat_blazing_top, R.color.aq_heat_blazing_bottom),
        LEGEND(100, R.drawable.aki_streak_legend, R.color.aq_heat_legend_top, R.color.aq_heat_legend_bottom),
        ;

        companion object {
            /** The heat of a [streak] that counts today; an unknown or zero streak burns as day one. */
            fun of(streak: Int): Heat = entries.lastOrNull { streak >= it.days } ?: WARM
        }
    }

    /**
     * The mood for today's [state] with [reviews] so far, a [streak] of that many days and
     * [frozenDays] frozen days in a row ending yesterday. Once the player studies, the freeze no
     * longer decides the mood.
     */
    fun of(
        state: State,
        reviews: Int,
        streak: Int,
        frozenDays: Int,
        goal: Goal,
    ): Mood {
        // Today counts, or the goal says the player did what they planned.
        if (state == State.STUDIED || goal == Goal.REACHED) {
            return Mood.FIRE
        }
        if (state == State.UNKNOWN) {
            return Mood.UNKNOWN
        }
        if (reviews > 0) {
            return Mood.HOPEFUL
        }

        // A protected day means a freeze kept at least yesterday, even without the heatmap.
        val frozen = maxOf(frozenDays, if (state == State.PROTECTED) 1 else 0)
        return when {
            frozen >= 3 -> Mood.FROZEN_3
            frozen == 2 -> Mood.FROZEN_2
            frozen == 1 -> Mood.FROZEN_1
            streak == 0 -> Mood.ASLEEP
            else -> Mood.SAD
        }
    }

    /** Aki's pose for [mood] with a [streak] of that many days. */
    @DrawableRes
    fun pose(
        mood: Mood,
        streak: Int,
    ): Int =
        when (mood) {
            Mood.UNKNOWN -> R.drawable.aki_face
            Mood.ASLEEP -> R.drawable.aki_sleeping
            // Interim: at_risk is the most worried full-body pose until "sad" exists.
            Mood.SAD -> R.drawable.aki_at_risk
            Mood.FROZEN_1 -> R.drawable.aki_freeze_used
            Mood.FROZEN_2 -> R.drawable.aki_freeze
            // Interim: freeze_used has the snow and cold breath; no pose shows ice yet.
            Mood.FROZEN_3 -> R.drawable.aki_freeze_used
            // Interim: welcome steps forward with a card, smiling, until "hopeful" exists.
            Mood.HOPEFUL -> R.drawable.aki_welcome
            Mood.FIRE -> Heat.of(streak).pose
        }

    /** The poses the big widget rotates through for [mood], all of the same mood. */
    fun poses(
        mood: Mood,
        streak: Int,
    ): List<Int> {
        val pose = pose(mood, streak)
        if (mood == Mood.FIRE && Heat.of(streak) == Heat.LEGEND) {
            return listOf(pose, R.drawable.aki_streak_milestone)
        }
        return listOf(pose)
    }

    /** One of [poses] for the day ending at [dayEndsAt], the same all day and changing from day to day. */
    @DrawableRes
    fun bigPose(
        mood: Mood,
        streak: Int,
        dayEndsAt: Long,
    ): Int {
        val choices = poses(mood, streak)
        return choices[Math.floorMod(Math.floorDiv(dayEndsAt, DAY_MS), choices.size.toLong()).toInt()]
    }

    /**
     * The frozen days in a row ending yesterday in a profile's [heatmap], whose last cell is today.
     * Skipped days count as frozen, as the server's `frozen` flag says.
     */
    fun frozenRun(heatmap: JSONArray?): Int {
        if (heatmap == null) {
            return 0
        }
        var days = 0
        for (index in heatmap.length() - 2 downTo 0) {
            if (heatmap.optJSONObject(index)?.optBoolean("frozen") != true) {
                break
            }
            days++
        }
        return days
    }

    /** The next milestone after [streak], or null past the last one. */
    fun next(streak: Int): Int? = milestones.firstOrNull { it > streak }

    /** "36 days to 100 🔥", or null without a streak or past the last milestone. */
    fun nextLine(
        context: Context,
        streak: Int,
    ): String? {
        if (streak <= 0) {
            return null
        }
        val next = next(streak) ?: return null
        val remaining = next - streak
        return context.resources.getQuantityString(R.plurals.ankiquest_streak_milestone, remaining, remaining, next)
    }
}
