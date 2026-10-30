// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.R
import java.io.File

/** Passing a win on: to friends through the server, or anywhere through Android's share sheet. */
internal object AnkiquestShare {
    const val EXTRA_TITLE = "ankiquestShareTitle"
    const val EXTRA_BODY = "ankiquestShareBody"
    const val EXTRA_NOTIFICATION = "ankiquestShareNotification"
    const val EXTRA_PLACE = "ankiquestSharePlace"
    const val EXTRA_ART = "ankiquestShareArt"

    private const val CARD_SIZE = 1080
    private val SHAREABLE = setOf("celebration", "challenge_complete", "reminder_weekly_recap")

    /** Notification kinds that are a player's own win, and so worth sharing. */
    fun shareable(kind: String) = kind in SHAREABLE

    /**
     * The Share button for a win's notification: one of the player's own [notification]s, or a
     * [place] they climbed to. Null when the win belongs to an account that is no longer signed in.
     */
    fun action(
        context: Context,
        tag: Int,
        title: String,
        body: String,
        notification: Long?,
        place: Int?,
        scope: String?,
        @DrawableRes art: Int,
    ): NotificationCompat.Action? {
        val current = AnkiquestHomeData.account() ?: return null
        if (current.token.isEmpty() || (scope != null && current.scope != scope)) return null
        val intent =
            Intent(context, AnkiquestShareActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_ART, art)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        notification?.let { intent.putExtra(EXTRA_NOTIFICATION, it) }
        place?.let { intent.putExtra(EXTRA_PLACE, it) }
        return NotificationCompat.Action
            .Builder(
                R.drawable.ic_share_notify,
                AnkiquestLanguage.context(context).getString(R.string.ankiquest_share),
                PendingIntent.getActivity(context, tag, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
    }

    /** A square picture of the win with the mascot, to share outside the app. */
    fun card(
        context: Context,
        title: String,
        body: String,
        @DrawableRes art: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_SIZE, CARD_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(R.color.aq_home_background))
        val margin = 80
        var top = 90
        if (AnkiquestCompanion.visible()) {
            BitmapFactory
                .decodeResource(
                    context.resources,
                    AnkiquestCompanion.resource(art),
                    BitmapFactory.Options().apply { inSampleSize = 2 },
                )?.let { mascot ->
                    val side = 520
                    val left = (CARD_SIZE - side) / 2
                    canvas.drawBitmap(mascot, null, Rect(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG))
                    top += side + 40
                }
        }
        val width = CARD_SIZE - 2 * margin
        top = text(canvas, title, 72f, context.getColor(R.color.aq_home_text), Typeface.DEFAULT_BOLD, width, margin, top) + 24
        text(canvas, body, 44f, context.getColor(R.color.aq_home_muted), Typeface.DEFAULT, width, margin, top)
        text(
            canvas,
            context.getString(R.string.ankiquest_share_footer),
            36f,
            context.getColor(R.color.aq_home_primary),
            Typeface.DEFAULT_BOLD,
            width,
            margin,
            CARD_SIZE - 110,
        )
        return bitmap
    }

    /** Opens the share sheet with [card] and a line of text inviting people to the server. */
    fun shareElsewhere(
        context: Context,
        title: String,
        body: String,
        @DrawableRes art: Int,
    ) {
        val directory = File(context.cacheDir, "ankiquest-share").apply { mkdirs() }
        val file = File(directory, "win.png")
        file.outputStream().use { card(context, title, body, art).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apkgfileprovider", file)
        val language = AnkiquestLanguage.context(context)
        val text =
            Ankiquest.serverUrl()?.let { language.getString(R.string.ankiquest_share_text, title, body, it) }
                ?: language.getString(R.string.ankiquest_share_text_offline, title, body)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, language.getString(R.string.ankiquest_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun text(
        canvas: Canvas,
        value: String,
        size: Float,
        color: Int,
        typeface: Typeface,
        width: Int,
        left: Int,
        top: Int,
    ): Int {
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                this.typeface = typeface
            }
        val layout =
            StaticLayout.Builder
                .obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return top + layout.height
    }
}
