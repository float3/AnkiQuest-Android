// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.ichi2.anki.R
import java.text.NumberFormat

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

    /** A streak picture's Aki and number. */
    private const val STREAK_HERO = 400
    private const val NUMBER_SIZE = 240f
    private val numberFont: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    fun render(
        context: Context,
        card: AnkiquestShare.Card,
    ): Bitmap {
        val streak = card.streak
        return if (streak == null) plain(context, card) else fiery(context, card, streak)
    }

    /** Brand, Aki, then the card's words on the app's own background. */
    private fun plain(
        context: Context,
        card: AnkiquestShare.Card,
    ): Bitmap {
        val scale = context.resources.configuration.fontScale
        val ink = context.getColor(R.color.aq_home_text)
        val pieces =
            buildList {
                add(Piece.Words(text("AnkiQuest", BRAND_SIZE * scale, ink, Typeface.DEFAULT_BOLD)))
                art(context, card, HERO)?.let { add(Piece.Art(it, HERO)) }
                add(Piece.Words(text(card.title, TITLE_SIZE * scale, ink, Typeface.DEFAULT_BOLD)))
                if (card.body.isNotBlank()) {
                    add(Piece.Words(text(card.body, BODY_SIZE * scale, ink)))
                }
                for (detail in card.details.filter(String::isNotBlank)) {
                    add(Piece.Words(text(detail, BODY_SIZE * scale, ink)))
                }
            }
        val background = context.getColor(R.color.aq_home_background)
        return draw(pieces, Align.TOP) { canvas, _ -> canvas.drawColor(background) }
    }

    /**
     * The streak in front: a larger Aki, the number in flame gold, the card's words and the next
     * milestone, on the gradient of the streak's heat.
     */
    private fun fiery(
        context: Context,
        card: AnkiquestShare.Card,
        streak: Int,
    ): Bitmap {
        val scale = context.resources.configuration.fontScale
        val ink = context.getColor(R.color.aq_heat_ink)
        val gold = context.getColor(R.color.aq_heat_number)
        val number = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale()).format(streak)
        val days = context.resources.getQuantityString(R.plurals.ankiquest_streak_widget_days, streak)
        val pieces =
            buildList {
                add(Piece.Words(text("AnkiQuest", BRAND_SIZE * scale, ink, Typeface.DEFAULT_BOLD)))
                art(context, card, STREAK_HERO)?.let { add(Piece.Art(it, STREAK_HERO)) }
                // The number already fills the width, so it does not grow with the font scale.
                add(Piece.Words(text(number, numberSize(number), gold, numberFont)))
                add(Piece.Words(text(days, TITLE_SIZE * scale, gold, Typeface.DEFAULT_BOLD)))
                add(Piece.Words(text(card.title, TITLE_SIZE * scale, ink, Typeface.DEFAULT_BOLD)))
                // The local card's "64 day streak" only repeats the number.
                if (card.body.isNotBlank() && card.body != AnkiquestMoments.streakDays(context, streak)) {
                    add(Piece.Words(text(card.body, BODY_SIZE * scale, ink)))
                }
                AnkiquestMood.nextLine(context, streak)?.let { add(Piece.Words(text(it, BODY_SIZE * scale, ink, Typeface.DEFAULT_BOLD))) }
            }
        val heat = AnkiquestMood.Heat.of(streak)
        val top = context.getColor(heat.top)
        val bottom = context.getColor(heat.bottom)
        return draw(pieces, Align.CENTER) { canvas, height ->
            val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), paint)
        }
    }

    /** One part of the picture, top to bottom. */
    private sealed interface Piece {
        val height: Int

        class Words(
            val layout: StaticLayout,
        ) : Piece {
            override val height get() = layout.height
        }

        class Art(
            val bitmap: Bitmap,
            val size: Int,
        ) : Piece {
            override val height get() = size
        }
    }

    /** The chosen companion's [card] art for a [size] pixel hero, or null when hidden. */
    private fun art(
        context: Context,
        card: AnkiquestShare.Card,
        size: Int,
    ): Bitmap? {
        if (!AnkiquestCompanion.visible()) {
            return null
        }
        return AnkiquestArt.bitmap(context, AnkiquestCompanion.resource(card.art), size)
    }

    /** The largest number size up to [NUMBER_SIZE] that keeps [number] on one line. */
    private fun numberSize(number: String): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = numberFont }
        paint.textSize = NUMBER_SIZE
        val width = paint.measureText(number)
        val room = (WIDTH - MARGIN * 2).toFloat()
        return if (width <= room) NUMBER_SIZE else NUMBER_SIZE * room / width
    }

    /** Where the pieces sit when the picture is taller than they are. */
    private enum class Align { TOP, CENTER }

    /** [pieces] on a square or taller picture over its [background]. */
    private fun draw(
        pieces: List<Piece>,
        align: Align,
        background: (Canvas, Int) -> Unit,
    ): Bitmap {
        val content = pieces.sumOf { it.height + GAP }
        val height = maxOf(WIDTH, MARGIN * 2 + content)
        require(height <= MAX_HEIGHT) { "The share card is too tall" }

        val bitmap = createBitmap(WIDTH, height)
        val canvas = Canvas(bitmap)
        background(canvas, height)
        var top =
            when (align) {
                Align.TOP -> MARGIN.toFloat()
                Align.CENTER -> (height - content + GAP) / 2f
            }
        for (piece in pieces) {
            when (piece) {
                is Piece.Words -> canvas.withTranslation(MARGIN.toFloat(), top) { piece.layout.draw(this) }
                is Piece.Art -> drawArt(canvas, piece, top)
            }
            top += piece.height + GAP
        }
        return bitmap
    }

    private fun drawArt(
        canvas: Canvas,
        art: Piece.Art,
        top: Float,
    ) {
        val ratio = minOf(art.size.toFloat() / art.bitmap.width, art.size.toFloat() / art.bitmap.height)
        val width = art.bitmap.width * ratio
        val height = art.bitmap.height * ratio
        val left = (WIDTH - width) / 2
        canvas.drawBitmap(art.bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
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
