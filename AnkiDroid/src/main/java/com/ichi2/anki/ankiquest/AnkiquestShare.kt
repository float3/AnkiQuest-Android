// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.chooser.ChooserAction
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.ext.getParcelableExtraCompat
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Passing a win on: to friends through the server, or anywhere through Android's share sheet with the server's card. */
internal object AnkiquestShare {
    const val EXTRA_TITLE = "ankiquestShareTitle"
    const val EXTRA_BODY = "ankiquestShareBody"
    const val EXTRA_WIN = "ankiquestShareWin"
    const val EXTRA_NOTIFICATION = "ankiquestShareNotification"
    const val EXTRA_PLACE = "ankiquestSharePlace"
    const val EXTRA_SCOPE = "ankiquestShareScope"
    const val EXTRA_TOPIC = "ankiquestShareTopic"

    /** Set on the share sheet's own "AnkiQuest friends" entry, which sends the win without asking again. */
    const val EXTRA_DESTINATION = "ankiquestShareDestination"
    const val DESTINATION_FRIENDS = "friends"

    /** The server keeps labels of conquered cards to one line of this many characters. */
    const val MAX_LABEL = 120

    /** Cards are 1080×1080 PNGs; anything far larger is not a card. */
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_PICTURE_SIDE = 1080
    private const val IMAGE_SECONDS = 10L
    private const val PICTURE_SECONDS = 1L
    private val CACHE_LIFETIME = TimeUnit.DAYS.toMillis(1)
    private val SHAREABLE = setOf("celebration", "challenge_complete", "reminder_weekly_recap")

    private val imageClient =
        AnkiquestHttp.client
            .newBuilder()
            .callTimeout(IMAGE_SECONDS, TimeUnit.SECONDS)
            .build()

    private val pictureClient = imageClient.newBuilder().callTimeout(PICTURE_SECONDS, TimeUnit.SECONDS).build()

    /** A win's public link, its card and the server's invitation text, which includes the link. */
    data class Link(
        val url: String,
        val image: String,
        val text: String,
    )

    /** Public display content selected for the picture, with no credentials or hidden fields. */
    data class Card(
        val title: String,
        val body: String,
        @DrawableRes val art: Int = R.drawable.aki_celebrate,
        val details: List<String> = emptyList(),
    )

    /** The same local renderer is used for shares, previews and gallery downloads. */
    fun picture(
        context: Context,
        moment: Card,
        image: String? = null,
    ): Bitmap =
        image?.let(::quickPng)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: AnkiquestPicture.render(AnkiquestLanguage.context(context), moment)

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
        topic: String? = null,
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
        topic?.let { intent.putExtra(EXTRA_TOPIC, it) }
        return NotificationCompat.Action
            .Builder(
                R.drawable.ic_share_notify,
                AnkiquestLanguage.context(context).getString(R.string.ankiquest_share),
                PendingIntent.getActivity(context, tag, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
    }

    /**
     * A win's picture and invitation text. A missing server image uses the local [card].
     * It downloads, so call it away from the main thread.
     */
    fun linkChooser(
        context: Context,
        link: Link,
        friends: PendingIntent? = null,
        card: Card = Card("AnkiQuest", link.text),
    ): Intent = cardChooser(context, link.text, link.image, friends, card)

    /**
     * Attach the server PNG when quickly available, otherwise draw [fallback] locally. Only a
     * failure to prepare the picture leaves a text-only share. Call away from the main thread.
     */
    fun cardChooser(
        context: Context,
        text: String,
        image: String?,
        friends: PendingIntent? = null,
        fallback: Card = Card("AnkiQuest", text),
    ): Intent {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        val card =
            try {
                val bytes = picturePng(context, fallback, image)
                val directory = File(context.cacheDir, "ankiquest-share").apply { mkdirs() }
                val expired = System.currentTimeMillis() - CACHE_LIFETIME
                directory.listFiles()?.filter { it.isFile && it.extension == "png" && it.lastModified() < expired }?.forEach { it.delete() }
                // A second share must never overwrite a picture another app is still reading.
                val file = File(directory, "${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
                FileProvider.getUriForFile(context, "${context.packageName}.apkgfileprovider", file)
            } catch (e: IOException) {
                Timber.w(e, "AnkiQuest share picture could not be prepared")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Timber.w(e, "AnkiQuest share picture could not be rendered or exposed")
                null
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "AnkiQuest share picture could not be allocated")
                null
            }
        if (card == null) {
            send.setType("text/plain")
        } else {
            send
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, card)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("AnkiQuest", card)
        }
        return chooser(context, send, friends)
    }

    private fun quickPng(url: String): ByteArray? =
        try {
            val bytes = readImage(url, pictureClient)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            bytes.takeIf {
                bounds.outMimeType == "image/png" && bounds.outWidth in 1..MAX_PICTURE_SIDE && bounds.outHeight in 1..MAX_PICTURE_SIDE
            }
        } catch (e: IOException) {
            Timber.d(e, "AnkiQuest server picture unavailable; drawing locally")
            null
        }

    private fun picturePng(
        context: Context,
        card: Card,
        image: String?,
    ): ByteArray {
        val bitmap = picture(context, card, image)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Could not encode the share picture")
                }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A local picture and invitation for servers that keep no win record. */
    fun textChooser(
        context: Context,
        title: String,
        body: String,
        friends: PendingIntent? = null,
        @DrawableRes art: Int = R.drawable.aki_celebrate,
    ): Intent {
        val language = AnkiquestLanguage.context(context)
        val text =
            Ankiquest.serverUrl()?.let { language.getString(R.string.ankiquest_share_text, title, body, it) }
                ?: language.getString(R.string.ankiquest_share_text_offline, title, body)
        return cardChooser(context, text, null, friends, Card(title, body, art))
    }

    /**
     * Android's share sheet for [send]. With [friends], and on Android 14 or later, the sheet itself
     * also offers "AnkiQuest friends", so friends and other apps are one choice in one place.
     */
    private fun chooser(
        context: Context,
        send: Intent,
        friends: PendingIntent?,
    ): Intent {
        val language = AnkiquestLanguage.context(context)
        val chooser =
            Intent
                .createChooser(send, language.getString(R.string.ankiquest_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        send.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.let { picture ->
            val download =
                Intent(context, AnkiquestGalleryActivity::class.java)
                    .setDataAndType(picture, "image/png")
                    .putExtra(EXTRA_SCOPE, AnkiquestHomeData.account()?.scope)
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(download))
        }
        if (friends != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val action =
                ChooserAction
                    .Builder(
                        Icon.createWithResource(context, R.drawable.ic_ankiquest_friends),
                        language.getString(R.string.ankiquest_share_friends_target),
                        friends,
                    ).build()
            chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
        }
        return chooser
    }

    /** Whether [chooser] can offer AnkiQuest friends itself, so no separate question is needed first. */
    fun sheetOffersFriends(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The share sheet's entry for AnkiQuest friends: [share] again, told to send the win to friends. */
    fun friendsTarget(
        context: Context,
        share: Intent,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            share.getLongExtra(EXTRA_WIN, 0).toInt(),
            Intent(share)
                .setClass(context, AnkiquestShareActivity::class.java)
                .putExtra(EXTRA_DESTINATION, DESTINATION_FRIENDS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The review id in a conquered card's win key `conquered:{at}`, or null for any other key. */
    fun conqueredAt(key: String?): Long? = key?.removePrefix("conquered:")?.takeIf { it != key }?.toLongOrNull()

    /**
     * A card's text as one line of plain text, at most [MAX_LABEL] characters, to offer as the
     * conquered card's label. Sounds, pictures, styles, scripts and markup are dropped, and cloze
     * deletions show their text.
     */
    fun cardLabel(field: String): String = oneLine(plainCardText(field))

    private fun plainCardText(field: String): String {
        val text =
            field
                .replace(HIDDEN, " ")
                .replace(SOUND, " ")
                .replace(CLOZE) { it.groupValues[1] }
                .replace(Regex("<[^>]*>"), " ")
        return HtmlCompat.fromHtml(text.replace("<", "&lt;"), HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    }

    /**
     * Offer the front template's field values, without static prompts or identifiers. Two short
     * values can give a word and its reading. Cloze notes keep their revealed text. If the template
     * cannot be read, clean the rendered [question]; an image-only front falls back to text fields.
     */
    fun suggestedLabel(
        question: String,
        fields: List<String>,
        cloze: Boolean,
        template: String? = null,
        names: List<String> = emptyList(),
    ): String? {
        if (cloze) {
            // Numeric deletions, such as historical years, are intentional card content.
            val front = fields.firstOrNull { CLOZE.containsMatchIn(it) }
            if (front != null) {
                return cleanLabel(front).ifEmpty { null }
            }
            return firstLabel(fields.asSequence())
        }

        val shown = frontValues(template, names.zip(fields).toMap())
        if (shown != null) {
            val labels = shown.map(::labelText).filter(String::isNotEmpty).distinct()
            if (labels.isNotEmpty()) {
                return if (labels.size > 1 && labels.take(2).all { it.length <= SHORT_LABEL }) {
                    labels.take(2).joinToString(" · ")
                } else {
                    labels.first()
                }
            }
            return firstLabel(fields.asSequence())
        }
        return firstLabel(sequenceOf(question).plus(fields))
    }

    private fun firstLabel(values: Sequence<String>): String? = values.map(::labelText).firstOrNull(String::isNotEmpty)

    private fun cleanLabel(value: String): String = oneLine(labelPlain(value))

    private fun labelPlain(value: String): String {
        // Remove placeholders before truncation, then again after decoding escaped HTML.
        val source = PLACEHOLDER.replace(CLOZE.replace(value) { it.groupValues[1] }, " ")
        return PLACEHOLDER.replace(plainCardText(source), " ")
    }

    private fun labelText(value: String): String =
        words(labelPlain(value))
            .filterNot { looksLikeId(it.trim('(', ')', '[', ']', '{', '}', '.', ',', ';')) }
            .joinToString(" ")
            .take(MAX_LABEL)

    /** Unbalanced or unsupported templates use the cleaned rendered-front fallback. */
    private fun frontValues(
        template: String?,
        fields: Map<String, String>,
    ): List<String>? {
        if (template == null) {
            return null
        }
        val source = HIDDEN.replace(template, " ")
        val tags = FIELD_TAG.findAll(source).toList()
        val remainder = FIELD_TAG.replace(source, "")
        if (tags.isEmpty() || remainder.contains("{{") || remainder.contains("}}")) {
            return null
        }

        val active = mutableListOf<Pair<String, Boolean>>()
        val found = mutableListOf<String>()
        for (match in tags) {
            val tag = match.groupValues[1].trim()
            when {
                tag.startsWith('#') || tag.startsWith('^') -> {
                    val name = tag.drop(1).trim()
                    val value = fields[name] ?: return null
                    val present = cardLabel(value).isNotEmpty() || LABEL_MEDIA.containsMatchIn(value)
                    active.add(name to if (tag.startsWith('#')) present else !present)
                }
                tag.startsWith('/') -> {
                    if (active.lastOrNull()?.first != tag.drop(1).trim()) {
                        return null
                    }
                    active.removeAt(active.lastIndex)
                }
                active.all { it.second } -> {
                    val parts = tag.split(':').map(String::trim)
                    if (parts.last() in LABEL_SPECIAL || parts.dropLast(1).any { it == "type" || it == "hint" }) {
                        continue
                    }
                    val name = if (tag in fields) tag else parts.last()
                    found.add(fields[name] ?: return null)
                }
            }
        }
        return found.takeIf { active.isEmpty() }
    }

    /** The label an earlier version offered: the first field, whatever it holds. */
    fun legacyLabel(fields: List<String>): String? = cardLabel(fields.firstOrNull().orEmpty()).ifEmpty { null }

    /** Whether [text] reads as a note's id or code rather than a word: `B1L02`, `0042`, `ID-17`, `2024-05-01`. */
    fun looksLikeId(text: String): Boolean = ID.matches(text) || (HEX_ID.matches(text) && text.any(Char::isDigit))

    private val HIDDEN = Regex("""(?is)<(style|script)\b[^>]*>.*?</\1\s*>|<!--.*?-->""")
    private val SOUND = Regex("""\[(?:sound|anki:play):[^\]]*\]""")
    private val CLOZE = Regex("""\{\{c\d+::(.*?)(?:::[^}]*)?\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val ID = Regex("""[A-Za-z]{0,4}[-_.#]?\d+(?:[-_.:/]?[A-Za-z]{0,2}\d+)*[A-Za-z]?""")
    private val HEX_ID = Regex("""[0-9A-Fa-f]{8,}(?:-[0-9A-Fa-f]{4,})*""")
    private val FIELD_TAG = Regex("""\{\{([^{}]*)\}\}""")
    private val PLACEHOLDER = Regex("""\[\[type:[\s\S]*?(?:\]\]|$)|\{\{[\s\S]*?(?:\}\}|$)""")
    private val LABEL_MEDIA = Regex("""(?i)<(?:img|audio|video)\b|\[sound:""")
    private val LABEL_SPECIAL = setOf("Tags", "Type", "Deck", "Subdeck", "CardFlag", "Card", "FrontSide")
    private const val SHORT_LABEL = 40

    /** The label to share: the player's [text] as one line, only when they chose to [include] the card and wrote something. */
    fun chosenLabel(
        include: Boolean,
        text: String?,
    ): String? = oneLine(text.orEmpty()).takeIf { include && it.isNotEmpty() }

    private fun oneLine(text: String): String = words(text).joinToString(" ").take(MAX_LABEL)

    private fun words(text: String): List<String> =
        text
            .replace(' ', ' ')
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }

    /** What the card answered at review [at] says on its front ([suggestedLabel]), for the player to choose to share; null when it is gone. */
    suspend fun conqueredCardText(at: Long): String? = conqueredCardLabels(at)?.suggested

    /** The label [suggestedLabel] offers for a card, and the one earlier versions offered and may have saved. */
    data class Labels(
        val suggested: String?,
        val legacy: String?,
    ) {
        /** [suggested], when a label saved as [legacy] would read better as it. */
        fun better(saved: String?): String? = suggested?.takeIf { saved != null && saved == legacy && it != legacy }
    }

    /** [Labels] for the card answered at review [at]; null when it is gone. */
    suspend fun conqueredCardLabels(at: Long): Labels? =
        try {
            CollectionManager.withCol {
                val cid = db.queryLongScalar("select cid from revlog where id = ?", at)
                if (cid == 0L) {
                    null
                } else {
                    val card = getCard(cid)
                    val note = card.note(this)
                    val question =
                        try {
                            card.question(this)
                        } catch (e: Exception) {
                            Timber.w(e, "ankiquest conquered card front cannot be rendered")
                            ""
                        }
                    val template =
                        try {
                            card.template(this).qfmt
                        } catch (e: Exception) {
                            Timber.w(e, "ankiquest conquered card template unavailable")
                            null
                        }
                    Labels(
                        suggestedLabel(question, note.fields, note.notetype.isCloze, template, note.notetype.fieldsNames),
                        legacyLabel(note.fields),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest conquered card text unavailable")
            null
        }

    /**
     * The name of the deck holding the card answered at review [at], as this phone's collection has
     * it; null when the card is gone. A filtered deck gives way to the card's home deck. Deck names
     * stay on the phone: only its owner sees this, and it leaves only in what they share themselves.
     */
    suspend fun conqueredCardDeck(at: Long): String? =
        try {
            CollectionManager.withCol {
                val cid = db.queryLongScalar("select cid from revlog where id = ?", at)
                if (cid == 0L) null else decks.nameIfExists(getCard(cid).currentDeckId())?.ifEmpty { null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest conquered card deck unavailable")
            null
        }

    /**
     * A card image from the server, at most [MAX_IMAGE_BYTES]. Cards are public, so no credentials are
     * sent with the request. Notifications retain their ten-second deadline; shares use the shorter picture deadline.
     *
     * @throws IOException when the address is not a web address, the server refuses, or the image is too large
     */
    fun image(url: String): ByteArray = readImage(url, imageClient)

    private fun readImage(
        url: String,
        client: OkHttpClient,
    ): ByteArray {
        val address = url.toHttpUrlOrNull() ?: throw IOException("Not a card address")
        return client.newCall(Request.Builder().url(address).build()).execute().use { response ->
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
