// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Passing a win on: to friends through the server, or anywhere through Android's share sheet with the server's card. */
internal object AnkiquestShare {
    const val EXTRA_TITLE = "ankiquestShareTitle"
    const val EXTRA_BODY = "ankiquestShareBody"
    const val EXTRA_WIN = "ankiquestShareWin"
    const val EXTRA_NOTIFICATION = "ankiquestShareNotification"
    const val EXTRA_PLACE = "ankiquestSharePlace"
    const val EXTRA_SCOPE = "ankiquestShareScope"

    /** Cards are 1080×1080 PNGs; anything far larger is not a card. */
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_PICTURE_SIDE = 1080
    private val SHAREABLE = setOf("celebration", "challenge_complete", "reminder_weekly_recap")

    private val imageClient =
        AnkiquestHttp.client
            .newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    /** A win's public link, its card and the server's invitation text, which includes the link. */
    data class Link(
        val url: String,
        val image: String,
        val text: String,
    )

    /** Notification kinds that are a player's own win, and so worth sharing. */
    fun shareable(kind: String) = kind in SHAREABLE

    /**
     * The Share button for a win's notification: a [win] the server keeps, or for older servers one of
     * the player's own [notification]s or a [place] they climbed to. Null when the win belongs to an
     * account that is no longer signed in. The button carries the account's [scope], so a tap after
     * switching accounts shares nothing.
     */
    fun action(
        context: Context,
        tag: Int,
        title: String,
        body: String,
        win: Long?,
        notification: Long?,
        place: Int?,
        scope: String?,
    ): NotificationCompat.Action? {
        val current = AnkiquestHomeData.account() ?: return null
        if (current.token.isEmpty() || (scope != null && current.scope != scope)) return null
        val intent =
            Intent(context, AnkiquestShareActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_SCOPE, current.scope)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        win?.let { intent.putExtra(EXTRA_WIN, it) }
        notification?.let { intent.putExtra(EXTRA_NOTIFICATION, it) }
        place?.let { intent.putExtra(EXTRA_PLACE, it) }
        return NotificationCompat.Action
            .Builder(
                R.drawable.ic_share_notify,
                AnkiquestLanguage.context(context).getString(R.string.ankiquest_share),
                PendingIntent.getActivity(context, tag, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
    }

    /**
     * The share sheet for a win's [link]: the server's card and its invitation text. When the card
     * cannot be fetched the text alone is shared, since it carries the link. It downloads, so call it
     * away from the main thread.
     */
    fun linkChooser(
        context: Context,
        link: Link,
    ): Intent {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, link.text)
        val card =
            try {
                val directory = File(context.cacheDir, "ankiquest-share").apply { mkdirs() }
                File(directory, "win.png").apply { writeBytes(image(link.image)) }
            } catch (e: IOException) {
                Timber.w(e, "ankiquest card image unavailable; sharing the link only")
                null
            }
        if (card == null) {
            send.setType("text/plain")
        } else {
            send
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "${context.packageName}.apkgfileprovider", card))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return chooser(context, send)
    }

    /** The share sheet with a line about the win and an invitation to the server, for servers that keep no wins. */
    fun textChooser(
        context: Context,
        title: String,
        body: String,
    ): Intent {
        val language = AnkiquestLanguage.context(context)
        val text =
            Ankiquest.serverUrl()?.let { language.getString(R.string.ankiquest_share_text, title, body, it) }
                ?: language.getString(R.string.ankiquest_share_text_offline, title, body)
        return chooser(context, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }

    private fun chooser(
        context: Context,
        send: Intent,
    ): Intent =
        Intent
            .createChooser(send, AnkiquestLanguage.context(context).getString(R.string.ankiquest_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * A card image from the server, at most [MAX_IMAGE_BYTES]. Cards are public, so no credentials are
     * sent with the request. It blocks for up to ten seconds.
     *
     * @throws IOException when the address is not a web address, the server refuses, or the image is too large
     */
    fun image(url: String): ByteArray {
        val address = url.toHttpUrlOrNull() ?: throw IOException("Not a card address")
        return imageClient.newCall(Request.Builder().url(address).build()).execute().use { response ->
            if (!response.isSuccessful) throw AnkiquestHttpException(response.code)
            if (response.body.contentLength() > MAX_IMAGE_BYTES) throw IOException("The card image is too large")
            val source = response.body.source()
            if (source.request(MAX_IMAGE_BYTES + 1)) throw IOException("The card image is too large")
            source.buffer.readByteArray()
        }
    }

    /** A card image for a notification's big picture, or null when it cannot be fetched or decoded. It blocks. */
    fun picture(url: String): Bitmap? =
        try {
            val bytes = image(url)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PICTURE_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: IOException) {
            Timber.w(e, "ankiquest card picture unavailable")
            null
        }
}
