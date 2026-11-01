// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes

/**
 * Companion artwork decoded no larger than it is shown. The pictures are far larger than any place
 * they appear, so decoding them whole would cost several megabytes each time.
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
