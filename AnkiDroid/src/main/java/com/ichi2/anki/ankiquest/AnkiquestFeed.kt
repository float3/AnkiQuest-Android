// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The activity feed: friends' wins, cheers, messages, replies, deck completions and challenge news
 * from `GET /api/activity/{user}` (docs/mobile-api.md, "durable activity"), with the read state,
 * cheers and replies that go with it.
 */
internal object AnkiquestFeed {
    /** The filters the server knows, in the order the web feed shows them; [ALL] sends none. */
    enum class Category(
        val query: String?,
    ) {
        ALL(null),
        NEEDS_ACTION("needs_action"),
        MESSAGES("messages"),
        CHALLENGES("challenges"),
        DECK_COMPLETIONS("deck_completions"),
        STUDY_UPDATES("study_updates"),
    }

    data class Item(
        val id: Long,
        val title: String,
        val body: String,
        /** Unix seconds. */
        val createdAt: Long,
        /** Who the row is from, and so who a reply or a cheer goes to; empty for system rows. */
        val sender: String,
        val replied: Boolean,
        val kind: String,
        /** Unix seconds, or null while unread. */
        val readAt: Long?,
        val challengeId: Long?,
        val actionRequired: Boolean,
        /** The title and body of the message this row answers. */
        val replyTo: Pair<String, String>?,
        val image: String?,
        val reacted: Boolean,
    ) {
        val unread: Boolean get() = readAt == null

        /** A friend's win or deck completion, which can be cheered once. */
        val cheerable: Boolean get() = sender.isNotEmpty() && (kind == "share" || kind == "completion")

        /** A row from a person that has no reply yet; challenge news is answered on the goal itself. */
        val answerable: Boolean get() = sender.isNotEmpty() && !replied && challengeId == null

        /** The heading: a cheer names what was cheered, everything else its title. */
        val heading: String get() = if (kind == "reaction" && body.isNotEmpty()) "$title: $body" else title

        /** The body under the heading; a cheer has already said it there. */
        val text: String? get() = body.takeIf { it.isNotEmpty() && kind != "reaction" }
    }

    data class Page(
        val items: List<Item>,
        /** Invitations still waiting for an answer, shown first. */
        val attention: List<Item>,
        val unread: Int,
        val actionCount: Int,
        val latestId: Long?,
        val nextBefore: Long?,
        /** From a server without the activity feed: the last seven days, with no read state or filters. */
        val legacy: Boolean = false,
        /** The server ignored the filters asked for and answered the whole feed. */
        val unfiltered: Boolean = false,
    )

    data class Sent(
        val recipient: String,
        val item: Item,
    )

    data class SentPage(
        val items: List<Sent>,
        val nextBefore: Long?,
    )

    const val PAGE_SIZE = 50

    /** The most ids `POST /api/activity/{user}/read` accepts at once. */
    private const val READ_BATCH = 200

    /**
     * One page of [account]'s feed, newest first, starting before [before]. A server without the feed
     * (`404`) answers the older notification list instead, and one that does not know a filter (`400`)
     * the unfiltered feed, as the website does.
     */
    suspend fun page(
        account: HomeAccount,
        category: Category = Category.ALL,
        unreadOnly: Boolean = false,
        before: Long? = null,
    ): Page =
        withContext(Dispatchers.IO) {
            val filtered = category != Category.ALL || unreadOnly
            try {
                activity(account, category, unreadOnly, before)
            } catch (e: AnkiquestHttpException) {
                when {
                    e.code == 400 && filtered -> activity(account, Category.ALL, false, before).copy(unfiltered = true)
                    e.code == 404 && before == null -> legacy(account)
                    else -> throw e
                }
            }
        }

    private fun activity(
        account: HomeAccount,
        category: Category,
        unreadOnly: Boolean,
        before: Long?,
    ): Page {
        val url =
            account
                .url("api/activity/${account.encodedUser}")
                .newBuilder()
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .apply {
                    category.query?.let { addQueryParameter("category", it) }
                    if (unreadOnly) addQueryParameter("unread_only", "true")
                    before?.let { addQueryParameter("before", it.toString()) }
                }.build()
        val request = AnkiquestApi.request(url, account.token, account.language).build()
        return parsePage(AnkiquestApi.json(account, request))
    }

    private fun legacy(account: HomeAccount): Page {
        val request = AnkiquestApi.request(account, "api/notifications/${account.encodedUser}").build()
        return parseLegacy(AnkiquestApi.call(account, request) { AnkiquestApi.jsonArray(it) })
    }

    /** What [account] sent: replies and nudges, with when they were read. */
    suspend fun sent(
        account: HomeAccount,
        before: Long? = null,
    ): SentPage =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/activity/${account.encodedUser}/sent")
                    .newBuilder()
                    .addQueryParameter("limit", PAGE_SIZE.toString())
                    .apply { before?.let { addQueryParameter("before", it.toString()) } }
                    .build()
            parseSent(AnkiquestApi.json(account, AnkiquestApi.request(url, account.token, account.language).build()))
        }

    /** Marks [ids] read. Repeats are harmless; the server keeps the first read time. */
    suspend fun markRead(
        account: HomeAccount,
        ids: List<Long>,
    ) = withContext(Dispatchers.IO) {
        for (batch in ids.filter { it > 0 }.distinct().chunked(READ_BATCH)) {
            read(account, JSONObject().put("ids", JSONArray(batch)))
        }
    }

    /** Marks everything up to [through], the newest id the feed showed, read. */
    suspend fun markAllRead(
        account: HomeAccount,
        through: Long,
    ) = withContext(Dispatchers.IO) { read(account, JSONObject().put("through", through)) }

    private fun read(
        account: HomeAccount,
        body: JSONObject,
    ) {
        AnkiquestApi.call(
            account,
            AnkiquestApi.request(account, "api/activity/${account.encodedUser}/read").post(AnkiquestApi.body(body)).build(),
        ) {}
    }

    /** Cheers 🎉 on [item]; answers whether it had been cheered already. */
    suspend fun cheer(
        account: HomeAccount,
        item: Item,
    ): Boolean = Ankiquest.react(item.id, account.notificationAccount, account.scope)

    /** Answers [item] with [message], cleaned as the server accepts it; answers who it went to. */
    suspend fun reply(
        account: HomeAccount,
        item: Item,
        message: String,
    ): String {
        val text = AnkiquestReply.clean(message)
        require(text.isNotEmpty())
        return Ankiquest.reply(item.id, text, account.notificationAccount, account.scope)
    }

    /**
     * A card image of a friend's win is shown only from [account]'s own server, so a row can never
     * make the app fetch from anywhere else.
     */
    fun image(
        account: HomeAccount,
        item: Item,
    ): String? {
        if (item.kind != "share") return null
        val url = item.image?.toHttpUrlOrNull() ?: return null
        val server = account.server.toHttpUrlOrNull() ?: return null
        val same = url.scheme == server.scheme && url.host == server.host && url.port == server.port
        return item.image.takeIf { same && url.encodedPath.endsWith(".png") }
    }

    fun parsePage(json: JSONObject): Page =
        Page(
            items = items(json.optJSONArray("items")),
            attention = items(json.optJSONArray("attention")),
            unread = json.optInt("unread_count"),
            actionCount = json.optInt("action_count"),
            latestId = json.positive("latest_id"),
            nextBefore = json.positive("next_before"),
        )

    /** The older oldest-first notification array, newest first like the feed. */
    fun parseLegacy(array: JSONArray): Page =
        Page(
            items = items(array).sortedByDescending { it.id },
            attention = emptyList(),
            unread = 0,
            actionCount = 0,
            latestId = null,
            nextBefore = null,
            legacy = true,
        )

    fun parseSent(json: JSONObject): SentPage =
        SentPage(
            items =
                json.optJSONArray("items")?.objects().orEmpty().mapNotNull { row ->
                    item(row)?.let { Sent(row.optString("recipient"), it) }
                },
            nextBefore = json.positive("next_before"),
        )

    private fun items(array: JSONArray?): List<Item> = array?.objects().orEmpty().mapNotNull(::item)

    /** One row, or null for a row without a usable id. */
    fun item(json: JSONObject): Item? {
        val id = json.positive("id") ?: return null
        val replyTo =
            json.optJSONObject("reply_to")?.let { original ->
                original.text("title") to original.text("body")
            }
        return Item(
            id = id,
            title = json.text("title"),
            body = json.text("body"),
            createdAt = json.optLong("created_at"),
            sender = json.text("sender"),
            replied = json.optBoolean("replied"),
            kind = json.text("kind"),
            readAt = json.positive("read_at"),
            challengeId = json.positive("challenge_id"),
            actionRequired = json.optBoolean("action_required"),
            replyTo = replyTo?.takeIf { (title, body) -> title.isNotEmpty() || body.isNotEmpty() },
            image = json.text("image").ifEmpty { null },
            reacted = json.optBoolean("reacted"),
        )
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONObject.positive(key: String): Long? = if (isNull(key)) null else optLong(key).takeIf { it > 0 }
}
