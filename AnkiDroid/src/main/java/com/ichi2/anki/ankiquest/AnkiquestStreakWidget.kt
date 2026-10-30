// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import com.ichi2.anki.R
import com.ichi2.widget.getAppWidgetManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat

/**
 * Your own streak as one big number with the companion, like a daily check-in.
 * It reads your row of the leaderboard the poll already downloads, so it costs no extra request.
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

    companion object {
        /** The companion's pose for a streak state. New artwork for these moments is requested in float3/ankiquest#88. */
        @DrawableRes
        internal fun pose(
            state: String,
            streak: Int,
        ): Int =
            when {
                state == "unknown" -> R.drawable.aki_face
                state == "protected" -> R.drawable.aki_freeze
                state == "studied" -> R.drawable.aki_streak
                streak == 0 -> R.drawable.aki_welcome
                else -> R.drawable.aki_review
            }

        /** Draws your row of [board] into every placed streak widget; null means not configured. */
        fun render(
            context: Context,
            board: JSONArray?,
        ) {
            val manager = getAppWidgetManager(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, AnkiquestStreakWidget::class.java)) ?: return
            if (ids.isEmpty()) return
            val views = views(context, board, Ankiquest.player())
            for (id in ids) manager.updateAppWidget(id, views)
        }

        internal fun views(
            context: Context,
            board: JSONArray?,
            player: String?,
        ): RemoteViews {
            val language = AnkiquestLanguage.context(context)
            val views = RemoteViews(context.packageName, R.layout.widget_ankiquest_streak)
            val me = board?.let { mine(it, player) }
            val streak = me?.optInt("streak") ?: 0
            val state = me?.let { AnkiquestWidget.streakState(it) } ?: "unknown"
            val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())

            views.setViewVisibility(R.id.ankiquest_streak_companion, if (AnkiquestCompanion.visible()) View.VISIBLE else View.GONE)
            if (AnkiquestCompanion.visible()) {
                views.setImageViewResource(R.id.ankiquest_streak_companion, AnkiquestCompanion.resource(pose(state, streak)))
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
                    PendingIntent.getActivity(context, 3000, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
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
