// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Celebration
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Status

/** The popup after a study session that reached today's streak minimum or daily goal. */
internal object AnkiquestGoalCelebration {
    fun title(
        context: Context,
        celebration: Celebration,
    ): String =
        context.getString(
            if (celebration == Celebration.MINIMUM) R.string.aq_goal_minimum_title else R.string.aq_goal_done_title,
        )

    fun message(
        context: Context,
        celebration: Celebration,
        status: Status,
    ): String {
        val minimum =
            if (status.streakMinutes > 0) {
                context.getString(R.string.aq_goal_minimum_minutes_done, status.streakMinutes)
            } else {
                context.getString(R.string.aq_goal_minimum_any_done)
            }
        return when (celebration) {
            Celebration.MINIMUM -> context.getString(R.string.aq_goal_minimum_body, minimum, progress(context, status))
            Celebration.GOAL -> context.getString(R.string.aq_goal_done_body, progress(context, status))
            Celebration.BOTH -> context.getString(R.string.aq_goal_both_body, minimum, progress(context, status))
        }
    }

    /** "34 of 50 cards" or "12 of 20 minutes", toward the daily goal. */
    fun progress(
        context: Context,
        status: Status,
    ): String =
        context.getString(
            if (status.mode == Mode.MINUTES) R.string.aq_goal_progress_minutes else R.string.aq_goal_progress_cards,
            status.progress.coerceAtMost(status.goal.target),
            status.goal.target,
        )

    fun show(
        activity: Activity,
        celebration: Celebration,
        status: Status,
        session: String?,
        continueStudying: (() -> Unit)?,
        openProgress: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val padding = (20 * activity.resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, 0)
                addView(
                    AnkiquestAki
                        .image(
                            activity,
                            if (celebration == Celebration.MINIMUM) R.drawable.aki_streak else R.drawable.aki_celebrate,
                            128,
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams(layoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL }
                        },
                )
            }
        val text = listOfNotNull(message(activity, celebration, status), session).joinToString("\n\n")
        val builder =
            AlertDialog
                .Builder(activity)
                .setTitle(title(activity, celebration))
                .setView(content)
                .setMessage(text)
                .setNegativeButton(R.string.aq_goal_nice, null)
                .setNeutralButton(R.string.ankiquest_summary_progress) { _, _ -> openProgress() }
        if (continueStudying != null) builder.setPositiveButton(R.string.ankiquest_summary_continue) { _, _ -> continueStudying() }
        builder.show()
    }
}
