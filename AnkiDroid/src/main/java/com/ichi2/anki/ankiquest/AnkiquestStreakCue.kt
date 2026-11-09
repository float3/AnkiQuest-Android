// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ichi2.anki.R
import kotlin.math.roundToInt

/** The reviewer's short, touch-through celebration. */
internal object AnkiquestStreakCue {
    private const val TAG = "ankiquest_streak_cue"
    private const val TOP_DP = 72
    private const val ICON_DP = 32
    private const val PAD_DP = 8
    private const val CORNER_DP = 20
    private const val ELEVATION_DP = 8
    private const val HOLD_MS = 2_400L
    private const val FILL_MS = 320L
    private const val FADE_MS = 240L

    fun showing(root: FrameLayout): Boolean = root.findViewWithTag<View>(TAG) != null

    fun show(
        host: Activity,
        streak: Int?,
        fraction: Float,
    ): View? {
        if (host.isFinishing || host.isDestroyed) {
            return null
        }
        val root = host.findViewById<FrameLayout>(android.R.id.content) ?: return null
        if (showing(root)) {
            return null
        }
        val language = AnkiquestLanguage.context(host)
        val density = host.resources.displayMetrics.density
        val pad = (PAD_DP * density).roundToInt()
        val icon = (ICON_DP * density).roundToInt()
        val animate = AnkiquestGoalBar.animationsEnabled(host)
        val ring =
            AnkiquestStreakRing(host).apply {
                this.fraction = if (animate) fraction else 1f
                safe = !animate
            }

        // Overlay the reviewer without changing the toolbar or intercepting answers.
        val banner =
            LinearLayout(host).apply {
                tag = TAG
                gravity = Gravity.CENTER_VERTICAL
                isClickable = false
                isFocusable = false
                elevation = ELEVATION_DP * density
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        cornerRadius = CORNER_DP * density
                        setColor(host.getColor(R.color.aq_home_background))
                    }
                addView(ring, LinearLayout.LayoutParams(icon, icon))
                if (AnkiquestCompanion.visible()) {
                    addView(AnkiquestAki.image(host, R.drawable.aki_minimum, ICON_DP))
                }
                addView(
                    TextView(host).apply {
                        val title = language.getString(R.string.aq_goal_streak_safe)
                        text = if (streak == null) title else "$title · ${AnkiquestMoments.streakDays(language, streak)}"
                        setTextColor(host.getColor(R.color.aq_home_text))
                        setPadding(pad, 0, 0, 0)
                    },
                )
            }
        val placement =
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (TOP_DP * density).roundToInt()
            }
        root.addView(banner, placement)

        // Reduced motion shows a filled ring and removes it after the same pause.
        if (animate) {
            ValueAnimator.ofFloat(fraction, 1f).apply {
                duration = FILL_MS
                addUpdateListener {
                    ring.fraction = it.animatedValue as Float
                    ring.safe = ring.fraction >= 1f
                }
                start()
            }
            banner
                .animate()
                .alpha(0f)
                .setStartDelay(HOLD_MS)
                .setDuration(FADE_MS)
                .withEndAction { root.removeView(banner) }
                .start()
        } else {
            banner.postDelayed({ root.removeView(banner) }, HOLD_MS)
        }
        return banner
    }
}
