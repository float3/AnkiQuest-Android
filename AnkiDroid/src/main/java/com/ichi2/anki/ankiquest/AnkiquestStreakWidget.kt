// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDay.State
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.widget.getAppWidgetManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import kotlin.math.roundToInt

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

    /** Separate picker entries share data and sizing, while keeping the existing card as the default. */
    internal enum class Style(
        val provider: Class<out AppWidgetProvider>,
    ) {
        CARD(AnkiquestStreakWidget::class.java),
        TRANSPARENT(AnkiquestClearStreakWidget::class.java),
        ;

        @LayoutRes
        fun layout(size: Size): Int =
            if (this == CARD) {
                size.layout
            } else {
                when (size) {
                    Size.SMALL -> R.layout.widget_ankiquest_streak_small_clear
                    Size.MEDIUM -> R.layout.widget_ankiquest_streak_clear
                    Size.LARGE -> R.layout.widget_ankiquest_streak_large_clear
                    Size.HUGE -> R.layout.widget_ankiquest_streak_huge_clear
                }
            }
    }

    companion object {
        /** The companion's pose for a streak state. New artwork for these moments is requested in float3/ankiquest#88. */
        @DrawableRes
        internal fun pose(
            state: State,
            streak: Int,
        ): Int =
            when {
                state == State.UNKNOWN -> R.drawable.aki_face
                state == State.PROTECTED -> R.drawable.aki_freeze_used
                state == State.STUDIED && streak >= 100 -> R.drawable.aki_streak_legend
                state == State.STUDIED && streak >= 30 -> R.drawable.aki_streak_blazing
                state == State.STUDIED && streak >= 7 -> R.drawable.aki_streak_hot
                // Day one of a streak is starting again; the leaderboard row does not say whether one was lost.
                state == State.STUDIED && streak == 1 -> R.drawable.aki_comeback
                state == State.STUDIED -> R.drawable.aki_streak
                streak == 0 -> R.drawable.aki_sleeping
                else -> R.drawable.aki_at_risk
            }

        /**
         * Largest companion bitmap edge, the size the streak poses are exported at (float3/ankiquest#253).
         * One such ARGB bitmap is 4 MiB, under the RemoteViews bitmap limit (screen width x height x 4 x 1.5)
         * of a 720 x 1280 screen.
         */
        private const val COMPANION_MAX_PX = 1024

        /** Minutes past the server's streak minimum during which it still counts as just reached. */
        private const val JUST_REACHED_MINUTES = 5
        private const val DAY_MS = 86_400_000L

        /** The next milestone and flame come from the same tier boundaries. */
        private enum class StreakTier(
            val days: Int,
            @DrawableRes val flame: Int,
        ) {
            START(1, R.drawable.ankiquest_streak_tier_1),
            WEEK(7, R.drawable.ankiquest_streak_tier_7),
            MONTH(30, R.drawable.ankiquest_streak_tier_30),
            HUNDRED(100, R.drawable.ankiquest_streak_tier_100),
            YEAR(365, R.drawable.ankiquest_streak_tier_365),
        }

        /** XML variants keep tint and pending alpha compatible with RemoteViews on Android 7. */
        private enum class Tone(
            @ColorRes val color: Int,
            @LayoutRes val ring: Int,
            @LayoutRes val pendingRing: Int,
            @LayoutRes val meter: Int,
            @LayoutRes val pendingMeter: Int,
        ) {
            WARM(
                R.color.ankiquest_streak_warm,
                R.layout.widget_ankiquest_streak_ring_warm,
                R.layout.widget_ankiquest_streak_ring_warm_pending,
                R.layout.widget_ankiquest_streak_meter_warm,
                R.layout.widget_ankiquest_streak_meter_warm_pending,
            ),
            AMBER(
                R.color.ankiquest_streak_amber,
                R.layout.widget_ankiquest_streak_ring_amber,
                R.layout.widget_ankiquest_streak_ring_amber_pending,
                R.layout.widget_ankiquest_streak_meter_amber,
                R.layout.widget_ankiquest_streak_meter_amber_pending,
            ),
            BLUE(
                R.color.ankiquest_streak_blue,
                R.layout.widget_ankiquest_streak_ring_blue,
                R.layout.widget_ankiquest_streak_ring_blue_pending,
                R.layout.widget_ankiquest_streak_meter_blue,
                R.layout.widget_ankiquest_streak_meter_blue_pending,
            ),
            GRAY(
                R.color.ankiquest_streak_gray,
                R.layout.widget_ankiquest_streak_ring_gray,
                R.layout.widget_ankiquest_streak_ring_gray_pending,
                R.layout.widget_ankiquest_streak_meter_gray,
                R.layout.widget_ankiquest_streak_meter_gray_pending,
            ),
        }

        /**
         * The poses that fit today for the big layouts, which have room to show more of the moment
         * than [pose]. The drawables are committed exports of the server masters (float3/ankiquest#88).
         */
        internal fun poses(
            state: State,
            streak: Int,
            entry: JSONObject?,
            goalReached: Boolean,
            now: Long,
        ): List<Int> {
            val minutes = entry?.optLong("today_minutes") ?: 0L
            val needed = entry?.optLong("streak_minutes") ?: 0L
            val dayEndsAt = entry?.optLong("day_ends_at") ?: 0L
            val studied = state == State.STUDIED
            return when {
                state == State.UNKNOWN -> listOf(R.drawable.aki_face, R.drawable.aki_welcome)
                state == State.PROTECTED -> listOf(R.drawable.aki_freeze_used, R.drawable.aki_freeze)
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
            state: State,
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
            for (style in Style.entries) {
                renderStyle(context, board, manager, style)
            }
        }

        private fun renderStyle(
            context: Context,
            board: JSONArray?,
            manager: AppWidgetManager,
            style: Style,
        ) {
            val ids = manager.getAppWidgetIds(ComponentName(context, style.provider)) ?: return
            if (ids.isEmpty()) {
                return
            }
            val player = Ankiquest.player()
            for (id in ids) {
                // Each widget's own size decides how large its companion is decoded.
                val options = manager.getAppWidgetOptions(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The launcher picks the layout for each size itself, also while resizing.
                    val views =
                        RemoteViews(
                            Size.entries.associate { size ->
                                val layout = views(context, board, player, size, style = style, options = options)
                                SizeF(size.minWidthDp, size.minHeightDp) to layout
                            },
                        )
                    manager.updateAppWidget(id, views)
                    continue
                }

                val size =
                    Size.fitting(
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110).toFloat(),
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).toFloat(),
                    )
                manager.updateAppWidget(id, views(context, board, player, size, style = style, options = options))
            }
        }

        internal fun views(
            context: Context,
            board: JSONArray?,
            player: String?,
            size: Size = Size.MEDIUM,
            now: Long = TimeManager.time.intTimeMS(),
            style: Style = Style.CARD,
            options: Bundle? = null,
        ): RemoteViews {
            val language = AnkiquestLanguage.context(context)
            val views = RemoteViews(context.packageName, style.layout(size))
            val me = board?.let { mine(it, player) }
            // The collection can secure today before the board reflects it.
            val ownDay = if (player != null && player == Ankiquest.player()) AnkiquestDay.own(now) else null
            val day = ownDay ?: AnkiquestDay.judge(me?.let(AnkiquestDay.Server::ownRow), emptyList(), now)
            val streak = if (ownDay == null) me?.optInt("streak") else ownDay.streak
            val state = day?.label ?: AnkiquestDay.State.UNKNOWN
            val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())
            val number = streak?.let { numbers.format(it) } ?: "–"

            val companionVisible = AnkiquestCompanion.visible()
            views.setViewVisibility(R.id.ankiquest_streak_companion, if (companionVisible) View.VISIBLE else View.GONE)
            if (companionVisible) {
                val aki =
                    if (size == Size.LARGE || size == Size.HUGE) {
                        val artDay =
                            if (ownDay == null) {
                                me
                            } else {
                                JSONObject()
                                    .put("today_minutes", ownDay.minutes)
                                    .put("streak_minutes", ownDay.streakMinutes)
                                    .put("day_ends_at", me?.optLong("day_ends_at") ?: now)
                            }
                        bigPose(state, streak ?: 0, artDay, goalReached(me), now)
                    } else {
                        pose(state, streak ?: 0)
                    }
                setCompanion(views, context, AnkiquestCompanion.resource(aki), companionPx(context, size, options))
            }
            views.setTextViewText(R.id.ankiquest_streak_number, number)
            views.setTextViewText(
                R.id.ankiquest_streak_label,
                language.resources.getQuantityString(R.plurals.ankiquest_streak_widget_days, streak ?: 0),
            )
            val status =
                when {
                    ownDay != null -> AnkiquestDay.words(language, ownDay)
                    board == null -> language.getString(R.string.ankiquest_widget_unconfigured)
                    me == null -> language.getString(R.string.ankiquest_streak_unknown)
                    else -> AnkiquestWidget.streakLabel(language, me, state)
                }
            views.setTextViewText(R.id.ankiquest_streak_status, status)
            fitLegacyStatus(views, language, size, status)
            val progress = setMinimumProgress(views, language, day, status, size)
            val goal = setGoalProgress(views, language, day, me.takeIf { player == Ankiquest.player() }, now)
            if (style == Style.TRANSPARENT && progress != null) {
                // The same RemoteViews progress keeps a contrasting edge on either wallpaper tone.
                val ring = size == Size.SMALL || size == Size.MEDIUM
                val outline = if (ring) R.drawable.ankiquest_streak_clear_ring else R.drawable.ankiquest_streak_clear_meter
                views.setInt(R.id.ankiquest_streak_progress, "setBackgroundResource", outline)
                val edge =
                    language.resources.displayMetrics.density
                        .toInt()
                        .coerceAtLeast(1)
                views.setViewPadding(R.id.ankiquest_streak_progress, edge, edge, edge, edge)
            }
            val milestone = setStreakTier(views, language, state, streak, size, number, style)
            if (size == Size.SMALL || size == Size.MEDIUM) {
                val badge = if (companionVisible || progress != null) View.VISIBLE else View.GONE
                views.setViewVisibility(R.id.ankiquest_streak_badge, badge)
            }
            val description = listOfNotNull(status, progress, goal, milestone).distinct().joinToString(" · ")
            views.setContentDescription(
                R.id.ankiquest_streak_root,
                if (streak == null) {
                    description
                } else {
                    language.getString(R.string.ankiquest_streak_description, number, description)
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

        /** A bitmap decoded for [pixels] keeps the launcher from upscaling the drawable; null [pixels] keeps the drawable. */
        private fun setCompanion(
            views: RemoteViews,
            context: Context,
            @DrawableRes art: Int,
            pixels: Int?,
        ) {
            val bitmap = pixels?.let { AnkiquestArt.bitmap(context, art, it) }
            if (bitmap == null) {
                views.setImageViewResource(R.id.ankiquest_streak_companion, art)
                return
            }
            views.setImageViewBitmap(R.id.ankiquest_streak_companion, bitmap)
        }

        /** The layout's largest companion; the small layouts keep theirs small. */
        @DimenRes
        private fun companionDimen(size: Size): Int? =
            when (size) {
                Size.LARGE -> R.dimen.ankiquest_streak_companion_large
                Size.HUGE -> R.dimen.ankiquest_streak_companion_huge
                Size.SMALL, Size.MEDIUM -> null
            }

        /**
         * The companion's largest edge in pixels for [size], or null for the small layouts. The square
         * pose fits within the widget's largest width and height from [options] and the layout's limit.
         */
        private fun companionPx(
            context: Context,
            size: Size,
            options: Bundle?,
        ): Int? {
            val dimen = companionDimen(size) ?: return null
            val density = context.resources.displayMetrics.density
            var edge = context.resources.getDimensionPixelSize(dimen)

            // Launchers report 0 or nothing before the widget is placed.
            val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            for (dp in listOf(widthDp, heightDp)) {
                if (dp > 0) {
                    edge = minOf(edge, (dp * density).roundToInt())
                }
            }
            return edge.coerceAtMost(COMPANION_MAX_PX)
        }

        /** Clear optional details on reapply when the cached day is no longer trustworthy. */
        private fun setStreakTier(
            views: RemoteViews,
            context: Context,
            state: State,
            streak: Int?,
            size: Size,
            number: String,
            style: Style,
        ): String? {
            val current = streak?.takeIf { state != State.UNKNOWN && it > 0 }
            val tier = current?.let { days -> StreakTier.entries.lastOrNull { it.days <= days } }
            views.setTextViewCompoundDrawablesRelative(R.id.ankiquest_streak_number, 0, 0, tier?.flame ?: 0, 0)
            fitLegacyNumber(views, context, size, number, tier, style)
            if (size == Size.SMALL || size == Size.MEDIUM) {
                return null
            }

            val next = current?.let { days -> StreakTier.entries.firstOrNull { it.days > days } }
            val label =
                if (next == null) {
                    null
                } else {
                    val remaining = next.days - current
                    context.resources.getQuantityString(R.plurals.ankiquest_streak_milestone, remaining, remaining, next.days)
                }
            views.setTextViewText(R.id.ankiquest_streak_milestone, label.orEmpty())
            views.setViewVisibility(R.id.ankiquest_streak_milestone, if (label == null) View.GONE else View.VISIBLE)
            return label
        }

        /** Android 7 lacks native autosizing; fit the guaranteed minimum widget width there. */
        private fun fitLegacyNumber(
            views: RemoteViews,
            context: Context,
            size: Size,
            number: String,
            tier: StreakTier?,
            style: Style,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return
            }
            val (widthDp, heightDp, textSp) =
                when (size) {
                    Size.SMALL -> Triple(49f, 20f, 20f)
                    Size.MEDIUM -> Triple(56f, 25f, 44f)
                    Size.LARGE -> Triple(168f, 54f, 72f)
                    Size.HUGE -> Triple(252f, 140f, 120f)
                }
            val metrics = context.resources.displayMetrics
            val paint =
                Paint().apply {
                    typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, metrics)
                }
            val flameWidth = tier?.let { AppCompatResources.getDrawable(context, it.flame)?.intrinsicWidth } ?: 0
            val available = widthDp * metrics.density - flameWidth
            // The transparent compact layouts reserve two more dp for the outlined goal meter.
            val height = heightDp - if (style == Style.TRANSPARENT && (size == Size.SMALL || size == Size.MEDIUM)) 2f else 0f
            val scale = minOf(1f, available / paint.measureText(number), height * metrics.density / paint.fontSpacing)
            views.setTextViewTextSize(R.id.ankiquest_streak_number, TypedValue.COMPLEX_UNIT_PX, paint.textSize * scale)
        }

        /** Before native autosizing, keep the complete status in the compact layout's minimum space. */
        private fun fitLegacyStatus(
            views: RemoteViews,
            context: Context,
            size: Size,
            status: String,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || size != Size.MEDIUM) return
            val metrics = context.resources.displayMetrics
            val width = (84 * metrics.density).toInt()
            val height = (28 * metrics.density).toInt()
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            var lower = metrics.density
            var upper = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, metrics)
            repeat(10) {
                val candidate = (lower + upper) / 2
                paint.textSize = candidate
                val layout =
                    StaticLayout.Builder
                        .obtain(status, 0, status.length, paint, width)
                        .setIncludePad(false)
                        .build()
                if (layout.height <= height && layout.lineCount <= 3) lower = candidate else upper = candidate
            }
            views.setTextViewTextSize(R.id.ankiquest_streak_status, TypedValue.COMPLEX_UNIT_PX, lower)
        }

        /** Use the same current day that supplies the widget's status. */
        private fun setMinimumProgress(
            views: RemoteViews,
            context: Context,
            day: AnkiquestDay.Verdict?,
            status: String,
            size: Size,
        ): String? {
            val id = R.id.ankiquest_streak_progress
            val container = R.id.ankiquest_streak_meter
            val tone =
                when {
                    day?.label == State.STUDIED -> Tone.WARM
                    day?.label == State.PROTECTED -> Tone.BLUE
                    (day?.streak ?: 0) > 0 -> Tone.AMBER
                    else -> Tone.GRAY
                }
            val pending = day != null && !day.confirmed && day.unsent > 0
            val ring = size == Size.SMALL || size == Size.MEDIUM
            val layout =
                when {
                    ring && pending -> tone.pendingRing
                    ring -> tone.ring
                    pending -> tone.pendingMeter
                    else -> tone.meter
                }
            views.removeAllViews(container)
            views.addView(container, RemoteViews(context.packageName, layout))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Keep the semantic tone, with a small contribution from the wallpaper accent.
                val color = ColorUtils.blendARGB(context.getColor(tone.color), context.getColor(android.R.color.system_accent1_500), 0.12f)
                views.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(color))
                val track = ColorUtils.blendARGB(context.getColor(R.color.ankiquest_widget_bg), color, 0.35f)
                views.setColorStateList(id, "setProgressBackgroundTintList", ColorStateList.valueOf(track))
            }
            if (day == null || day.label == AnkiquestDay.State.UNKNOWN || day.streakMinutes <= 0) {
                views.setViewVisibility(id, View.GONE)
                views.setViewVisibility(container, View.GONE)
                return null
            }
            val reached = day.met || day.label == AnkiquestDay.State.PROTECTED
            val minutes = if (reached) day.streakMinutes else day.minutes.coerceIn(0, day.streakMinutes)
            views.setProgressBar(id, day.streakMinutes, minutes, false)
            views.setViewVisibility(id, View.VISIBLE)
            views.setViewVisibility(container, View.VISIBLE)
            return if (reached) status else context.getString(R.string.ankiquest_streak_short, minutes, day.streakMinutes)
        }

        /** Only the configured account's unexpired profile can supply this optional second meter. */
        private fun setGoalProgress(
            views: RemoteViews,
            context: Context,
            day: AnkiquestDay.Verdict?,
            entry: JSONObject?,
            now: Long,
        ): String? {
            val id = R.id.ankiquest_streak_goal
            views.setViewVisibility(id, View.GONE)
            if (day?.met != true) return null
            val endsAt = entry?.optLong("day_ends_at")?.takeIf { it > now } ?: return null
            val last = AnkiquestIcon.lastKnown()?.takeIf { it.optLong("day_ends_at") == endsAt } ?: return null
            val goal = last.optJSONObject("goal") ?: return null
            val reached = goal.optBoolean("reached")
            val mode = goal.optString("mode")
            val maximum: Int
            val progress: Int
            val label: String
            if (mode == "backlog") {
                // Backlog progress is unknown until the client reports completion; do not invent a count.
                if (!reached) return null
                maximum = 1
                progress = 1
                label = context.getString(R.string.aq_goal_done_title)
            } else {
                if (mode != "cards" && mode != "minutes") return null
                maximum = goal.optInt("value").takeIf { it > 0 } ?: return null
                if (goal.isNull("progress") && !reached) return null
                progress = if (reached) maximum else goal.optInt("progress").coerceIn(0, maximum)
                val format = if (mode == "cards") R.string.aq_goal_progress_cards else R.string.aq_goal_progress_minutes
                label = context.getString(R.string.aq_goal_daily, context.getString(format, progress, maximum))
            }
            views.setProgressBar(id, maximum, progress, false)
            views.setViewVisibility(id, View.VISIBLE)
            return label
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
