// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.ichi2.anki.R

/** Draws only the display content explicitly supplied for sharing; no account or collection reads. */
internal object AnkiquestPicture {
    private const val WIDTH = 1080
    private const val MAX_HEIGHT = 4096
    private const val MARGIN = 72
    private const val HERO = 288
    private const val GAP = 24
    private const val TITLE_SIZE = 60f
    private const val BODY_SIZE = 42f
    private const val BRAND_SIZE = 36f

    fun render(
        context: Context,
        card: AnkiquestShare.Card,
    ): Bitmap {
        val scale = context.resources.configuration.fontScale
        val ink = context.getColor(R.color.aq_home_text)
        val blocks =
            buildList {
                add(text("AnkiQuest", BRAND_SIZE * scale, ink, Typeface.DEFAULT_BOLD))
                add(text(card.title, TITLE_SIZE * scale, ink, Typeface.DEFAULT_BOLD))
                if (card.body.isNotBlank()) {
                    add(text(card.body, BODY_SIZE * scale, ink))
                }
                for (detail in card.details.filter(String::isNotBlank)) {
                    add(text(detail, BODY_SIZE * scale, ink))
                }
            }
        val art =
            if (AnkiquestCompanion.visible()) {
                AnkiquestArt.bitmap(context, AnkiquestCompanion.resource(card.art), HERO)
            } else {
                null
            }
        val heroSpace = if (art == null) 0 else HERO + GAP
        val height = maxOf(WIDTH, MARGIN * 2 + heroSpace + blocks.sumOf { it.height + GAP })
        require(height <= MAX_HEIGHT) { "The share card is too tall" }

        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(R.color.aq_home_background))
        var top = MARGIN.toFloat()
        for ((index, block) in blocks.withIndex()) {
            canvas.withTranslation(MARGIN.toFloat(), top) { block.draw(this) }
            top += block.height + GAP
            if (index == 0 && art != null) {
                val ratio = minOf(HERO.toFloat() / art.width, HERO.toFloat() / art.height)
                val width = art.width * ratio
                val artHeight = art.height * ratio
                val left = (WIDTH - width) / 2
                canvas.drawBitmap(art, null, RectF(left, top, left + width, top + artHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                top += HERO + GAP
            }
        }
        return bitmap
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        font: Typeface = Typeface.DEFAULT,
    ): StaticLayout {
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = font
            }
        return StaticLayout.Builder
            .obtain(value, 0, value.length, paint, WIDTH - MARGIN * 2)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
    }
}
