// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import com.ichi2.anki.R

/**
 * The mascot art for each moment the server tells the app about, decoded no larger than it is
 * shown. The pictures are far larger than any place they appear, so decoding them whole would cost
 * several megabytes each time.
 */
internal object AnkiquestArt {
    private const val CACHE_BYTES = 4 * 1024 * 1024

    private val cache =
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /**
     * [resource] downsampled by a power of two to the smallest size that still covers [size] pixels
     * on its shorter side, or null when it cannot be decoded. The bitmap is shared and must not be changed.
     */
    fun bitmap(
        context: Context,
        @DrawableRes resource: Int,
        size: Int,
    ): Bitmap? {
        if (size <= 0) return null
        val key = "$resource:$size"
        cache.get(key)?.let { return it }
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
        BitmapFactory.decodeResource(context.resources, resource, bounds)
        val edge = minOf(bounds.outWidth, bounds.outHeight)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = if (edge > 0) sampleSize(edge, size) else 1
                inScaled = false
            }
        val decoded = BitmapFactory.decodeResource(context.resources, resource, options) ?: return null
        cache.put(key, decoded)
        return decoded
    }

    /**
     * Art for an inbox notification of [kind]. [topic] is what a celebration or a friend's shared win
     * is about ("streak", "goal", "secured", "achievement", …) or "place:N" for a weekly recap; older
     * servers send none.
     */
    @DrawableRes
    fun forNotice(
        kind: String,
        topic: String?,
    ): Int =
        when (kind) {
            "celebration", "share" -> forTopic(topic)
            "challenge_invite", "challenge_accepted" -> R.drawable.aki_challenge
            "challenge_complete" -> R.drawable.aki_challenge_won
            "reminder_weekly_recap" -> place(topic?.removePrefix("place:")?.toIntOrNull()) ?: R.drawable.aki_face
            else -> R.drawable.aki_face
        }

    @DrawableRes
    private fun forTopic(topic: String?): Int =
        when {
            topic == "streak" -> R.drawable.aki_streak_milestone
            topic == "secured" -> R.drawable.aki_streak
            topic == "goal" -> R.drawable.aki_celebrate
            topic?.startsWith("place:") == true -> place(topic.removePrefix("place:").toIntOrNull()) ?: R.drawable.aki_achievement
            else -> R.drawable.aki_achievement
        }

    /** Art for finishing or climbing to [place] (1-based) on the weekly leaderboard, for the top three. */
    @DrawableRes
    fun place(place: Int?): Int? =
        when (place) {
            1 -> R.drawable.aki_place_1
            2 -> R.drawable.aki_place_2
            3 -> R.drawable.aki_place_3
            else -> null
        }

    /** The medal shown beside the top three leaderboard rows. */
    @DrawableRes
    fun medal(place: Int): Int? =
        when (place) {
            1 -> R.drawable.icon_place_1
            2 -> R.drawable.icon_place_2
            3 -> R.drawable.icon_place_3
            else -> null
        }
}

/** The largest power of two that keeps an [edge] of pixels at least [size] after sampling. */
internal fun sampleSize(
    edge: Int,
    size: Int,
): Int {
    var sample = 1
    while (edge / (sample * 2) >= size) sample *= 2
    return sample
}
