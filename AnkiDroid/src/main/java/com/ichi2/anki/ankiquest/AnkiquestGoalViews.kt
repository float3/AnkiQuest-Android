// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.ichi2.anki.R
import kotlin.math.min

/** Today's goal on the Today screen: what is done and the time left above an [AnkiquestGoalBar], and how a backlog goal is made up. */
internal class AnkiquestGoalProgress
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        val label: TextView = goalText(context, 16f, R.color.aq_home_text, bold = true)
        val remaining: TextView = goalText(context, 14f, R.color.aq_home_muted, bold = true)
        val bar = AnkiquestGoalBar(context)
        val detail: TextView = goalText(context, 14f, R.color.aq_home_muted)

        init {
            orientation = VERTICAL
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            remaining.gravity = Gravity.END
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(remaining, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                },
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                bar,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
            )
            addView(
                detail,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
            )
        }

        fun show(
            display: AnkiquestGoalBar.Display,
            slot: String,
        ) {
            label.text = display.label
            remaining.text = display.remaining
            remaining.isVisible = display.remaining != null
            detail.text = display.detail
            detail.isVisible = display.detail != null
            bar.show(display, slot)
        }
    }

/** A small ring around a flame that fills with today's streak minutes. */
internal class AnkiquestStreakRing
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** How much of the streak minimum is done, from 0 to 1. */
        var fraction: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        var safe: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        private val streak = context.getColor(R.color.aq_goal_streak)
        private val stroke = 2.5f * resources.displayMetrics.density
        private val trackPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = context.getColor(R.color.aq_goal_bar_track)
            }
        private val arcPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = streak
            }
        private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(streak, 60) }
        private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val bounds = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val radius = size / 2f - stroke
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            if (safe) canvas.drawCircle(cx, cy, radius, safePaint)
            canvas.drawCircle(cx, cy, radius, trackPaint)
            val sweep = if (safe) 360f else 360f * fraction
            if (sweep > 0f) canvas.drawArc(bounds, -90f, sweep, false, arcPaint)
            flamePaint.textSize = size * 0.5f
            canvas.drawText(FLAME, cx, cy - (flamePaint.descent() + flamePaint.ascent()) / 2f, flamePaint)
        }

        private companion object {
            const val FLAME = "🔥"
        }
    }

/** The streak minimum as a pill: a [AnkiquestStreakRing] and "7 / 10 min", or "Streak safe" once today counts. */
internal class AnkiquestStreakChip
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        /** What the chip shows for one day. */
        data class Streak(
            val text: String,
            val description: String,
            val fraction: Float,
            val safe: Boolean,
        )

        val ring = AnkiquestStreakRing(context)
        val label: TextView = goalText(context, 14f, R.color.aq_goal_chip_text, bold = true)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(12), dp(4))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(context.getColor(R.color.aq_goal_chip_background))
                }
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(ring, LayoutParams(dp(26), dp(26)))
            addView(
                label,
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) },
            )
        }

        fun show(status: AnkiquestDailyGoal.Status) {
            val streak = streak(context, status)
            label.text = streak.text
            contentDescription = streak.description
            ring.fraction = streak.fraction
            ring.safe = streak.safe
        }

        companion object {
            fun streak(
                context: Context,
                status: AnkiquestDailyGoal.Status,
            ): Streak {
                val minimum = status.streakMinutes
                val waiting = status.streakDay?.awaitingUpload == true
                return when {
                    status.minimumDone -> {
                        val done =
                            if (minimum > 0) {
                                context.getString(R.string.aq_goal_minimum_minutes_done, minimum)
                            } else {
                                context.getString(R.string.aq_goal_minimum_any_done)
                            }
                        Streak(
                            context.getString(if (waiting) R.string.aq_goal_streak_safe_unsent else R.string.aq_goal_streak_safe),
                            if (waiting) done + " " + context.getString(R.string.aq_goal_minimum_unsent) else done,
                            1f,
                            true,
                        )
                    }
                    minimum > 0 -> {
                        val minutes = status.streakProgress.coerceIn(0, minimum)
                        // Without a current verdict the collection's own minutes are progress only: never safe.
                        val checking = status.streakDay == null && minutes >= minimum
                        Streak(
                            if (checking) {
                                context.getString(R.string.aq_goal_streak_checking)
                            } else {
                                context.getString(R.string.aq_goal_streak_minutes, minutes, minimum)
                            },
                            context.getString(R.string.aq_goal_minimum_minutes, minimum) + ". " +
                                context.getString(R.string.ankiquest_streak_short, minutes, minimum),
                            minutes.toFloat() / minimum,
                            false,
                        )
                    }
                    else ->
                        Streak(
                            context.getString(R.string.aq_goal_streak_any),
                            context.getString(R.string.aq_goal_minimum_any),
                            0f,
                            false,
                        )
                }
            }
        }
    }

/** Today's goal in one tappable line above the deck list: what is done, a compact [AnkiquestGoalBar] and the streak ring. */
internal class AnkiquestCompactGoal
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        val label: TextView = goalText(context, 14f, R.color.aq_home_text, bold = true)
        val bar = AnkiquestGoalBar(context).apply { compact = true }
        val ring = AnkiquestStreakRing(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(context.getColor(R.color.aq_home_surface))
                    setStroke(dp(1), context.getColor(R.color.aq_home_outline))
                }
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                foreground = ContextCompat.getDrawable(context, ripple.resourceId)
            }
            label.maxLines = 1
            label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            bar.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(label, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bar, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            addView(ring, LayoutParams(dp(28), dp(28)).apply { marginStart = dp(10) })
        }

        fun show(
            display: AnkiquestGoalBar.Display,
            status: AnkiquestDailyGoal.Status,
            open: () -> Unit,
        ) {
            val streak = AnkiquestStreakChip.streak(context, status)
            label.text = display.label
            ring.fraction = streak.fraction
            ring.safe = streak.safe
            contentDescription = display.description + ". " + streak.description
            setOnClickListener { open() }
            bar.show(display, SLOT_DECKS)
        }

        companion object {
            const val SLOT_DECKS = "decks"
        }
    }

private fun goalText(
    context: Context,
    size: Float,
    @ColorRes color: Int,
    bold: Boolean = false,
): TextView =
    TextView(context).apply {
        textSize = size
        setTextColor(context.getColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
