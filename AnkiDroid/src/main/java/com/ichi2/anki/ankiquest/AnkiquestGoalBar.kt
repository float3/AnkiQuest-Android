// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.ichi2.anki.R
import com.ichi2.anki.common.android.Animations
import com.ichi2.anki.common.preferences.sharedPrefs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * A thick, fully rounded bar toward today's goal. The fill is a gradient in the AnkiQuest blues with a
 * glossy stripe and a glowing knob at its leading edge; it grows from the value last shown today with a
 * slight overshoot, pops the milestones at 25, 50 and 75 % as it passes them, and turns gold with a
 * shine and a bounce the first time the goal is reached. Without animations it shows the final state at once.
 */
internal class AnkiquestGoalBar
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** What the bar shows for one day's goal. */
        data class Display(
            val day: Int,
            val fraction: Float,
            val reached: Boolean,
            /** "48 of 140 cards", or the done text once the goal is reached. */
            val label: String,
            /** "~9 min left", or null once the goal is reached. */
            val remaining: String?,
            /** How a backlog goal is made up, or null for other goals. */
            val detail: String?,
            val description: String,
        )

        /** A thinner bar for one-line summaries. */
        var compact: Boolean = false
            set(value) {
                field = value
                requestLayout()
            }

        /** The value the bar is heading to, from 0 to 1. */
        var fraction: Float = 0f
            private set

        /** The value drawn right now, which trails [fraction] while the fill animates. */
        var shownFraction: Float = 0f
            private set

        /** Whether the bar shows the goal as reached, in gold. */
        var gold: Boolean = false
            private set

        /** The milestones the drawn fill has passed. */
        val passedMilestones: Int get() = MILESTONES.count { shownFraction >= it }

        private var goldBlend = 0f
        private var shine = -1f
        private val pops = FloatArray(MILESTONES.size) { 1f }
        private val animators = mutableListOf<Animator>()

        private val density = resources.displayMetrics.density
        private val track = context.getColor(R.color.aq_goal_bar_track)
        private val tick = context.getColor(R.color.aq_goal_bar_tick)
        private val fillStart = context.getColor(R.color.aq_goal_bar_start)
        private val fillEnd = context.getColor(R.color.aq_goal_bar_end)
        private val goldStart = context.getColor(R.color.aq_goal_gold_start)
        private val goldEnd = context.getColor(R.color.aq_goal_gold_end)

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        private val glowMatrix = Matrix()
        private val rect = RectF()
        private val fillPath = Path()
        private val shinePath = Path()

        private val barHeight: Float get() = (if (compact) COMPACT_HEIGHT_DP else HEIGHT_DP) * density
        private val glowPadding: Float get() = (if (compact) 2 else 5) * density
        private val glowRadius: Float get() = barHeight / 2f + glowPadding

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        /**
         * Shows [display], animating from the value this [slot] last showed on the same day; the gold
         * celebration plays once per slot and day.
         */
        fun show(
            display: Display,
            slot: String,
        ) {
            if (animators.isNotEmpty()) {
                stop()
                remember()
            }
            this.slot = slot
            day = display.day
            contentDescription = display.description
            val remembered =
                context
                    .sharedPrefs()
                    .getString(PREF_SHOWN_PREFIX + slot, null)
                    ?.split(':')
                    ?.takeIf { it.size == 3 && it[0].toIntOrNull() == display.day }
            val from = remembered?.get(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            val celebrated = remembered?.get(2) == "1"
            fraction = display.fraction.coerceIn(0f, 1f)
            reached = display.reached
            if (!animationsEnabled(context) || (from == fraction && (!reached || celebrated))) {
                settle()
                return
            }
            shownFraction = from
            gold = false
            celebrationDone = false
            goldBlend = 0f
            shine = -1f
            pops.fill(1f)
            invalidate()
            if (from == fraction) {
                celebrate()
                return
            }
            ValueAnimator
                .ofFloat(from, fraction)
                .apply {
                    startDelay = START_DELAY_MS
                    duration = (FILL_BASE_MS + FILL_PER_BAR_MS * abs(fraction - from)).toLong()
                    interpolator = OvershootInterpolator(1.3f)
                    addUpdateListener {
                        val value = (it.animatedValue as Float).coerceIn(0f, 1f)
                        MILESTONES.forEachIndexed { index, milestone ->
                            if (shownFraction < milestone && value >= milestone && from < milestone) pop(index)
                        }
                        shownFraction = value
                        invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                animators.remove(animation)
                                shownFraction = fraction
                                invalidate()
                                if (reached && !celebrated) celebrate() else settle()
                            }
                        },
                    )
                }.also { play(it) }
        }

        private var slot: String? = null
        private var day = 0
        private var reached = false
        private var celebrationDone = false

        private fun remember() {
            val key = slot ?: return
            val value = "$day:$shownFraction:${if (gold && celebrationDone) 1 else 0}"
            val prefs = context.sharedPrefs()
            if (prefs.getString(PREF_SHOWN_PREFIX + key, null) != value) prefs.edit { putString(PREF_SHOWN_PREFIX + key, value) }
        }

        private fun stop() {
            val running = animators.toList()
            animators.clear()
            running.forEach {
                it.removeAllListeners()
                it.cancel()
            }
        }

        private fun settle() {
            shownFraction = fraction
            gold = reached
            celebrationDone = reached
            goldBlend = if (reached) 1f else 0f
            shine = -1f
            pops.fill(1f)
            scaleX = 1f
            scaleY = 1f
            invalidate()
            remember()
        }

        private fun pop(index: Int) {
            ValueAnimator
                .ofFloat(1f, 1.9f, 1f)
                .apply {
                    duration = POP_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        pops[index] = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                animators.remove(animation)
                            }
                        },
                    )
                }.also { play(it) }
        }

        private fun celebrate() {
            gold = true
            ValueAnimator
                .ofFloat(1f, 1.25f, 1f)
                .apply {
                    duration = BOUNCE_MS
                    interpolator = OvershootInterpolator(2f)
                    addUpdateListener {
                        val value = it.animatedValue as Float
                        scaleY = value
                        scaleX = 1f + (value - 1f) * 0.08f
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                animators.remove(animation)
                            }
                        },
                    )
                }.also { play(it) }
            ValueAnimator
                .ofFloat(0f, 1f)
                .apply {
                    duration = SHINE_MS
                    addUpdateListener {
                        val value = it.animatedValue as Float
                        goldBlend = (value * 3f).coerceAtMost(1f)
                        shine = value
                        invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                animators.remove(animation)
                                settle()
                            }
                        },
                    )
                }.also { play(it) }
        }

        private fun play(animator: Animator) {
            animators += animator
            animator.start()
        }

        override fun onDetachedFromWindow() {
            if (animators.isNotEmpty()) {
                stop()
                remember()
            }
            super.onDetachedFromWindow()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val height = ceil(barHeight + 2 * glowPadding).toInt()
            setMeasuredDimension(
                getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec),
            )
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            val width = max(w, 1).toFloat()
            fillPaint.shader = LinearGradient(0f, 0f, width, 0f, fillStart, fillEnd, Shader.TileMode.CLAMP)
            goldPaint.shader = LinearGradient(0f, 0f, width, 0f, goldStart, goldEnd, Shader.TileMode.CLAMP)
            glowPaint.shader =
                RadialGradient(
                    0f,
                    0f,
                    max(glowRadius, 1f),
                    intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 150), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP,
                )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val full = width.toFloat()
            val bar = barHeight
            val top = (height - bar) / 2f
            val radius = bar / 2f
            val center = top + radius
            canvas.save()
            if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, full / 2f, 0f)
            rect.set(0f, top, full, top + bar)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            val shown = shownFraction.coerceIn(0f, 1f)
            val end = if (shown <= 0f) 0f else max(bar, shown * full)
            if (end > 0f) {
                rect.set(0f, top, end, top + bar)
                if (goldBlend < 1f) canvas.drawRoundRect(rect, radius, radius, fillPaint)
                if (goldBlend > 0f) {
                    goldPaint.alpha = (goldBlend * 255).toInt()
                    canvas.drawRoundRect(rect, radius, radius, goldPaint)
                }
                if (end > bar) {
                    rect.set(radius * 0.7f, top + bar * 0.16f, end - radius * 0.7f, top + bar * 0.38f)
                    canvas.drawRoundRect(rect, bar * 0.11f, bar * 0.11f, glossPaint)
                }
                if (shine in 0f..1f) {
                    fillPath.reset()
                    rect.set(0f, top, end, top + bar)
                    fillPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
                    val band = bar * 1.2f
                    val x = -band * 2 + shine * (end + band * 3)
                    shinePath.reset()
                    shinePath.moveTo(x + band, top)
                    shinePath.lineTo(x + band * 2, top)
                    shinePath.lineTo(x + band, top + bar)
                    shinePath.lineTo(x, top + bar)
                    shinePath.close()
                    canvas.withClip(fillPath) { drawPath(shinePath, shinePaint) }
                }
                if (!gold && shown < 1f) {
                    val knob = end - radius
                    glowMatrix.setTranslate(knob, center)
                    glowPaint.shader?.setLocalMatrix(glowMatrix)
                    canvas.drawCircle(knob, center, glowRadius, glowPaint)
                    canvas.drawCircle(knob, center, radius * 0.42f, knobPaint)
                }
            }
            MILESTONES.forEachIndexed { index, milestone ->
                val x = milestone * full
                if (x <= radius || x >= full - radius) return@forEachIndexed
                val passed = shown >= milestone
                tickPaint.color = if (passed) Color.argb(220, 255, 255, 255) else tick
                canvas.drawCircle(x, center, bar * TICK_RADIUS * pops[index], tickPaint)
            }
            canvas.restore()
        }

        companion object {
            /** Prefix of the per-slot memory of the value last shown and the day it was shown on. */
            const val PREF_SHOWN_PREFIX = "ankiquestGoalBarShown."

            val MILESTONES = floatArrayOf(0.25f, 0.5f, 0.75f)

            private const val HEIGHT_DP = 18
            private const val COMPACT_HEIGHT_DP = 10
            private const val TICK_RADIUS = 0.14f
            private const val START_DELAY_MS = 150L
            private const val FILL_BASE_MS = 600f
            private const val FILL_PER_BAR_MS = 700f
            private const val POP_MS = 320L
            private const val SHINE_MS = 900L
            private const val BOUNCE_MS = 420L

            /**
             * Whether to animate: the system and AnkiDroid's own settings allow animations, and the
             * animator duration scale is not 0, which battery saver can also set.
             */
            fun animationsEnabled(context: Context): Boolean =
                Animations.areAnimationsEnabled(context) &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())

            /** The share of [target] that [progress] makes, from 0 to 1; a reached goal is always full. */
            fun fraction(
                progress: Int,
                target: Int,
                reached: Boolean,
            ): Float =
                when {
                    reached -> 1f
                    target <= 0 -> 0f
                    else -> (progress.toFloat() / target).coerceIn(0f, 1f)
                }

            /** The bar for [status], or null when no daily goal is set. */
            fun display(
                context: Context,
                status: AnkiquestDailyGoal.Status,
                secondsPerCard: Double,
            ): Display? {
                if (status.mode == AnkiquestDailyGoal.Mode.OFF) return null
                val reached = status.goalDone
                val progress = AnkiquestDailyGoal.progress(context, status)
                val left = (status.goal.target - status.progress).coerceAtLeast(0)
                val minutes =
                    when {
                        reached || left == 0 -> null
                        status.mode == AnkiquestDailyGoal.Mode.MINUTES -> left
                        else -> ceil(left * secondsPerCard.coerceAtLeast(1.0) / 60.0).toInt().coerceAtLeast(1)
                    }
                val detail =
                    if (status.mode == AnkiquestDailyGoal.Mode.BACKLOG && status.goal.backlog > 0) {
                        context.getString(R.string.aq_goal_backlog_explained, status.goal.extra, status.goal.backlog, status.goal.daysLeft)
                    } else {
                        null
                    }
                return Display(
                    day = status.day,
                    fraction = fraction(status.progress, status.goal.target, reached),
                    reached = reached,
                    label = if (reached) context.getString(R.string.aq_goal_done_title) else progress,
                    remaining = minutes?.let { context.getString(R.string.aq_goal_time_left, it) },
                    detail = detail,
                    description =
                        if (reached) {
                            context.getString(R.string.aq_goal_done_title)
                        } else {
                            context.getString(R.string.aq_goal_daily, progress)
                        },
                )
            }
        }
    }
