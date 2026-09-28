// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        FREEZE(R.drawable.aki_freeze, R.string.aki_protected),
    }

    fun mood(
        local: HomeLocal?,
        profile: JSONObject?,
        now: Long = TimeManager.time.intTimeMS(),
    ): Mood {
        val dayEndsAt = profile?.optLong("day_ends_at") ?: 0L
        val current = profile?.takeUnless { dayEndsAt > 0L && dayEndsAt <= now }
        // The collection is the source of truth for cards still due locally.
        if (current?.optString("streak_state") == "protected" && current.optJSONObject("today")?.optLong("reviews", 0) == 0L) {
            return Mood.FREEZE
        }
        if (local?.decks?.isNotEmpty() == true && local.decks.all { it.due == 0 } &&
            (current?.optJSONObject("today")?.optLong("reviews", 0) ?: 0) > 0
        ) {
            return Mood.CELEBRATE
        }
        if ((local?.focus?.due ?: 0) > 0) return Mood.REVIEW
        if (current?.optString("streak_state") == "studied" &&
            (current.optJSONObject("today")?.optLong("reviews", 0) ?: 0) > 0
        ) {
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
            AnkiquestCompanion.show(this, resource)
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

    fun notificationIcon(context: Context): Bitmap? =
        if (!AnkiquestCompanion.visible()) {
            null
        } else {
            BitmapFactory.decodeResource(
                context.resources,
                AnkiquestCompanion.resource(R.drawable.aki_face),
                BitmapFactory.Options().apply { inSampleSize = 8 },
            )
        }

    private fun dp(
        context: Context,
        value: Int,
    ) = (value * context.resources.displayMetrics.density).toInt()
}
