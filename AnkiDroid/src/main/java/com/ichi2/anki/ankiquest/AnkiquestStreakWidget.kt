// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.widget.getAppWidgetManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat

/**
 * Your own streak as one big number with the companion, like a daily check-in.
 * It reads your row of the leaderboard the poll already downloads, kept current between polls by the
 * profile each upload answers with, so it costs no extra request.
 */
class AnkiquestStreakWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AnkiquestPoll.schedule(context)
        AnkiquestPoll.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        AnkiquestPoll.schedule(context)
    }

    /** Before Android 12 a resized widget keeps its layout until it is drawn again for its new size. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) AnkiquestWidget.redrawStreaks(context)
    }

    /**
     * Like Duolingo's: a tiny face and number at 1x1, the usual card at 2x2, a big companion from 3x3
     * up, and from about 4x5 a giant number under a companion that fills the card.
     */
    enum class Size(
        @LayoutRes val layout: Int,
        val minWidthDp: Float,
        val minHeightDp: Float,
    ) {
        SMALL(R.layout.widget_ankiquest_streak_small, 0f, 0f),
        MEDIUM(R.layout.widget_ankiquest_streak, 100f, 90f),
        LARGE(R.layout.widget_ankiquest_streak_large, 200f, 220f),
        HUGE(R.layout.widget_ankiquest_streak_huge, 300f, 380f),
        ;

        companion object {
            /** The largest layout that fits a widget of [widthDp] x [heightDp]. */
            fun fitting(
                widthDp: Float,
                heightDp: Float,
            ): Size = entries.last { widthDp >= it.minWidthDp && heightDp >= it.minHeightDp }
        }
    }

    companion object {
        /** The companion's pose for a streak state. New artwork for these moments is requested in float3/ankiquest#88. */
        @DrawableRes
        internal fun pose(
            state: String,
            streak: Int,
        ): Int =
            when {
                state == "unknown" -> R.drawable.aki_face
                state == "protected" -> R.drawable.aki_freeze_used
                state == "studied" && streak >= 100 -> R.drawable.aki_streak_legend
                state == "studied" && streak >= 30 -> R.drawable.aki_streak_blazing
                state == "studied" && streak >= 7 -> R.drawable.aki_streak_hot
                // Day one of a streak is starting again; the leaderboard row does not say whether one was lost.
                state == "studied" && streak == 1 -> R.drawable.aki_comeback
                state == "studied" -> R.drawable.aki_streak
                streak == 0 -> R.drawable.aki_sleeping
                else -> R.drawable.aki_at_risk
            }

        /** Minutes past the server's streak minimum during which it still counts as just reached. */
        private const val JUST_REACHED_MINUTES = 5
        private const val DAY_MS = 86_400_000L

        /**
         * The poses that fit today for the big layouts, which have room to show more of the moment
         * than [pose]. The drawables come from the shared mascot masters (float3/ankiquest#88).
         */
        internal fun poses(
            state: String,
            streak: Int,
            entry: JSONObject?,
            goalReached: Boolean,
            now: Long,
        ): List<Int> {
            val minutes = entry?.optLong("today_minutes") ?: 0L
            val needed = entry?.optLong("streak_minutes") ?: 0L
            val dayEndsAt = entry?.optLong("day_ends_at") ?: 0L
            val studied = state == "studied"
            return when {
                state == "unknown" -> listOf(R.drawable.aki_face, R.drawable.aki_welcome)
                state == "protected" -> listOf(R.drawable.aki_freeze_used, R.drawable.aki_freeze)
                studied && goalReached -> listOf(R.drawable.aki_goal, R.drawable.aki_celebrate)
                studied && streak == 1 -> listOf(R.drawable.aki_comeback)
                studied && needed > 0 && minutes < needed + JUST_REACHED_MINUTES -> listOf(R.drawable.aki_minimum)
                studied && streak >= 100 -> listOf(R.drawable.aki_streak_legend, R.drawable.aki_streak_milestone)
                studied && streak >= 30 -> listOf(R.drawable.aki_streak_blazing, R.drawable.aki_celebrate)
                studied && streak >= 7 -> listOf(R.drawable.aki_streak_hot, R.drawable.aki_celebrate)
                studied -> listOf(R.drawable.aki_celebrate, R.drawable.aki_streak)
                streak == 0 -> listOf(R.drawable.aki_sleeping, R.drawable.aki_comeback)
                dayEndsAt > 0 && dayEndsAt - now <= AnkiquestIcon.WORRY_HOURS * 3_600_000L -> listOf(R.drawable.aki_at_risk)
                else -> listOf(R.drawable.aki_review, R.drawable.aki_welcome)
            }
        }

        /** One of [poses] for the day, the same all day, so the big widget changes from day to day but not between redraws. */
        @DrawableRes
        internal fun bigPose(
            state: String,
            streak: Int,
            entry: JSONObject?,
            goalReached: Boolean,
            now: Long,
        ): Int {
            val choices = poses(state, streak, entry, goalReached, now)
            val dayEndsAt = entry?.optLong("day_ends_at")?.takeIf { it > 0 } ?: now
            return choices[Math.floorMod(Math.floorDiv(dayEndsAt, DAY_MS), choices.size.toLong()).toInt()]
        }

        /** Whether the last own profile says today's goal is reached, for the study day of [entry]. */
        private fun goalReached(entry: JSONObject?): Boolean {
            val dayEndsAt = entry?.optLong("day_ends_at")?.takeIf { it > 0 } ?: return false
            val last = runCatching { AnkiquestIcon.lastKnown() }.getOrNull() ?: return false
            return last.optLong("day_ends_at") == dayEndsAt && last.optBoolean("goal_reached")
        }

        /** Draws your row of [board] into every placed streak widget; null means not configured. */
        fun render(
            context: Context,
            board: JSONArray?,
        ) {
            val manager = getAppWidgetManager(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, AnkiquestStreakWidget::class.java)) ?: return
            if (ids.isEmpty()) return
            val player = Ankiquest.player()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The launcher picks the layout for each size itself, also while resizing.
                val views =
                    RemoteViews(
                        Size.entries.associate { size ->
                            SizeF(size.minWidthDp, size.minHeightDp) to views(context, board, player, size)
                        },
                    )
                for (id in ids) manager.updateAppWidget(id, views)
            } else {
                for (id in ids) {
                    val options = manager.getAppWidgetOptions(id)
                    val size =
                        Size.fitting(
                            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110).toFloat(),
                            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).toFloat(),
                        )
                    manager.updateAppWidget(id, views(context, board, player, size))
                }
            }
        }

        internal fun views(
            context: Context,
            board: JSONArray?,
            player: String?,
            size: Size = Size.MEDIUM,
            now: Long = TimeManager.time.intTimeMS(),
        ): RemoteViews {
            val language = AnkiquestLanguage.context(context)
            val views = RemoteViews(context.packageName, size.layout)
            val me = board?.let { mine(it, player) }
            val streak = me?.optInt("streak") ?: 0
            val state = me?.let { AnkiquestWidget.streakState(it) } ?: "unknown"
            val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())

            views.setViewVisibility(R.id.ankiquest_streak_companion, if (AnkiquestCompanion.visible()) View.VISIBLE else View.GONE)
            if (AnkiquestCompanion.visible()) {
                val aki =
                    if (size == Size.LARGE || size == Size.HUGE) {
                        bigPose(state, streak, me, goalReached(me), now)
                    } else {
                        pose(state, streak)
                    }
                views.setImageViewResource(R.id.ankiquest_streak_companion, AnkiquestCompanion.resource(aki))
            }
            views.setTextViewText(R.id.ankiquest_streak_number, if (me == null) "–" else numbers.format(streak))
            views.setTextViewText(
                R.id.ankiquest_streak_label,
                language.resources.getQuantityString(R.plurals.ankiquest_streak_widget_days, streak),
            )
            val status =
                when {
                    board == null -> language.getString(R.string.ankiquest_widget_unconfigured)
                    me == null -> language.getString(R.string.ankiquest_streak_unknown)
                    else -> AnkiquestWidget.streakLabel(language, me, state)
                }
            views.setTextViewText(R.id.ankiquest_streak_status, status)
            views.setContentDescription(
                R.id.ankiquest_streak_root,
                if (me == null) {
                    status
                } else {
                    language.getString(R.string.ankiquest_streak_description, numbers.format(streak), status)
                },
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (open != null) {
                views.setOnClickPendingIntent(
                    R.id.ankiquest_streak_root,
                    PendingIntent.getActivity(
                        context,
                        3000 + size.ordinal,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            return views
        }

        private fun mine(
            board: JSONArray,
            player: String?,
        ): JSONObject? =
            (0 until board.length())
                .map { board.getJSONObject(it) }
                .firstOrNull { player != null && it.optString("user") == player }
    }
}
