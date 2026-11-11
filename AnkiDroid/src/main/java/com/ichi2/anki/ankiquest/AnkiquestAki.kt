// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONObject

/** Aki encourages real progress without changing study, scoring or notification rules. */
internal object AnkiquestAki {
    enum class Mood(
        @DrawableRes val image: Int,
        @StringRes val message: Int,
    ) {
        WELCOME(R.drawable.aki_welcome, R.string.aki_welcome),
        REVIEW(R.drawable.aki_review, R.string.aki_review),
        CELEBRATE(R.drawable.aki_celebrate, R.string.aki_complete),
        STREAK(R.drawable.aki_streak, R.string.aki_streak),
        FREEZE(R.drawable.aki_freeze_used, R.string.aki_protected),
    }

    /** Aki's mood for today; the collection, not the server, says whether cards are still due. */
    fun mood(
        local: HomeLocal?,
        profile: JSONObject?,
        now: Long = TimeManager.time.intTimeMS(),
    ): Mood {
        val goal = local?.goal
        val day =
            if (goal == null) {
                AnkiquestDay.judge(AnkiquestDay.Server.profile(profile), emptyList(), now)
            } else {
                goal.withServerDay(profile, now).streakDay
            }
        if (day?.label == AnkiquestDay.State.PROTECTED && day.reviews == 0) {
            return Mood.FREEZE
        }
        if (local?.decks?.isNotEmpty() == true && local.decks.all { it.due == 0 } &&
            (day?.reviews ?: 0) > 0
        ) {
            return Mood.CELEBRATE
        }
        if ((local?.focus?.due ?: 0) > 0) return Mood.REVIEW
        if (day?.met == true && day.reviews > 0) {
            return Mood.STREAK
        }
        return Mood.WELCOME
    }

    fun image(
        context: Context,
        @DrawableRes resource: Int,
        size: Int = 80,
    ): ImageView =
        ImageView(context).apply {
            AnkiquestCompanion.show(this, resource, dp(context, size))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(context, size), dp(context, size))
        }

    fun encouragement(
        context: Context,
        mood: Mood,
    ): LinearLayout =
        LinearLayout(context).apply {
            visibility = if (AnkiquestCompanion.visible()) View.VISIBLE else View.GONE
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            addView(image(context, mood.image))
            addView(
                TextView(context).apply {
                    text = context.getString(mood.message)
                    textSize = 16f
                    setTextColor(context.getColor(R.color.aq_home_text))
                    setPadding(dp(context, 12), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    fun notificationIcon(
        context: Context,
        @DrawableRes art: Int = R.drawable.aki_face,
    ): Bitmap? =
        if (!AnkiquestCompanion.visible()) {
            null
        } else {
            AnkiquestArt.bitmap(
                context,
                AnkiquestCompanion.resource(art),
                context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
            )
        }

    private fun dp(
        context: Context,
        value: Int,
    ) = (value * context.resources.displayMetrics.density).toInt()
}
