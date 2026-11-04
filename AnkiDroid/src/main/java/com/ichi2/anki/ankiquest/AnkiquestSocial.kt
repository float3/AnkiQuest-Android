// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Friends, friend requests, muted players and groups from `GET /api/social/{user}`, and every change
 * to them through `POST /api/social/{user}`, which answers the whole overview again
 * (docs/mobile-api.md, "friends and groups for apps").
 */
internal object AnkiquestSocial {
    data class Person(
        val user: String,
        val display: String,
    )

    data class Group(
        val id: Long,
        val name: String,
        /** Whether the player owns it; only owners see [invite], rename it and remove members. */
        val owner: Boolean,
        val invite: String?,
        val members: List<Person>,
    )

    data class Overview(
        val friends: List<Person>,
        /** Requests waiting for the player's answer. */
        val incoming: List<Person>,
        /** The player's requests waiting for an answer. */
        val outgoing: List<Person>,
        val muted: List<Person>,
        val groups: List<Group>,
        /** Whether the player's profile is on the global leaderboard. */
        val public: Boolean,
    ) {
        fun isMuted(user: String): Boolean = muted.any { it.user == user }
    }

    /** What an invite link shows before joining. */
    data class Invite(
        val name: String,
        val members: Int,
    )

    /** One `POST /api/social/{user}` action, as the server names it. */
    sealed class Change(
        val action: String,
    ) {
        /** Sends a request, or accepts one from [user]. */
        data class Befriend(
            val user: String,
        ) : Change("befriend")

        /** Removes a friend, declines their request or withdraws the player's own. */
        data class Unfriend(
            val user: String,
        ) : Change("unfriend")

        data class Mute(
            val user: String,
        ) : Change("mute")

        data class Unmute(
            val user: String,
        ) : Change("unmute")

        data class CreateGroup(
            val name: String,
        ) : Change("create_group")

        data class RenameGroup(
            val group: Long,
            val name: String,
        ) : Change("rename_group")

        /** A new invite code for [group]; the old one stops working. */
        data class NewInvite(
            val group: Long,
        ) : Change("new_invite")

        data class Join(
            val invite: String,
        ) : Change("join")

        data class Leave(
            val group: Long,
        ) : Change("leave")

        data class Remove(
            val group: Long,
            val member: String,
        ) : Change("remove")

        data class Visibility(
            val public: Boolean,
        ) : Change("visibility")

        fun body(): JSONObject =
            JSONObject().put("action", action).apply {
                when (val change = this@Change) {
                    is Befriend -> put("user", change.user)
                    is Unfriend -> put("user", change.user)
                    is Mute -> put("user", change.user)
                    is Unmute -> put("user", change.user)
                    is CreateGroup -> put("name", change.name)
                    is RenameGroup -> put("group", change.group).put("name", change.name)
                    is NewInvite -> put("group", change.group)
                    is Join -> put("invite", change.invite)
                    is Leave -> put("group", change.group)
                    is Remove -> put("group", change.group).put("member", change.member)
                    is Visibility -> put("public", change.public)
                }
            }
    }

    /** The server's limits: group names are 1 to 40 characters, usernames at most 32. */
    const val MAX_GROUP_NAME = 40
    const val MAX_USER = 32

    private const val MAX_INVITE = 128
    private val inviteFormat = Regex("[A-Za-z0-9_-]{1,$MAX_INVITE}")

    suspend fun overview(account: HomeAccount): Overview =
        withContext(Dispatchers.IO) {
            parse(AnkiquestApi.json(account, AnkiquestApi.request(account, "api/social/${account.encodedUser}").build()))
        }

    suspend fun change(
        account: HomeAccount,
        change: Change,
    ): Overview =
        withContext(Dispatchers.IO) {
            val request =
                AnkiquestApi
                    .request(account, "api/social/${account.encodedUser}")
                    .post(AnkiquestApi.body(change.body()))
                    .build()
            parse(AnkiquestApi.json(account, request))
        }

    /** The group behind [code], to show before joining; a code that is not valid any more is `404`. */
    suspend fun invite(
        account: HomeAccount,
        code: String,
    ): Invite =
        withContext(Dispatchers.IO) {
            val url =
                account
                    .url("api/invites")
                    .newBuilder()
                    .addPathSegment(code)
                    .build()
            parseInvite(AnkiquestApi.json(account, AnkiquestApi.request(url, account.token, account.language).build()))
        }

    /** The link that joins a group with [code] on [account]'s server, as the website shares it. */
    fun inviteLink(
        account: HomeAccount,
        code: String,
    ): String =
        account
            .url("join")
            .newBuilder()
            .addPathSegment(code)
            .build()
            .toString()

    /**
     * The invite code in [text]: a bare code, or a `/join/<code>` link on [account]'s server. A link
     * to any other server answers null, so its code is never sent to this one.
     */
    fun inviteCode(
        account: HomeAccount,
        text: String,
    ): String? {
        val typed = text.trim()
        if (inviteFormat.matches(typed)) return typed
        val url = typed.toHttpUrlOrNull() ?: return null
        val server = account.url("").takeIf { it.username.isEmpty() } ?: return null
        if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) return null
        val base = server.pathSegments.filter { it.isNotEmpty() }
        val path = url.pathSegments.filter { it.isNotEmpty() }
        if (path.size != base.size + 2 || path.take(base.size) != base || path[base.size] != "join") return null
        return path.last().takeIf { inviteFormat.matches(it) }
    }

    /** A cleaned group name, or null for one the server would refuse. */
    fun groupName(text: String): String? =
        text.trim().takeIf {
            it.isNotEmpty() && it.length <= MAX_GROUP_NAME &&
                it.none(Char::isISOControl)
        }

    /** A cleaned username to send a request to, or null for an empty one. */
    fun userName(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_USER }

    fun parse(json: JSONObject): Overview =
        Overview(
            friends = people(json.optJSONArray("friends")),
            incoming = people(json.optJSONArray("incoming")),
            outgoing = people(json.optJSONArray("outgoing")),
            muted = people(json.optJSONArray("muted")),
            groups =
                json.optJSONArray("groups")?.objects().orEmpty().mapNotNull { group ->
                    val id = group.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
                    Group(
                        id = id,
                        name = group.text("name"),
                        owner = group.optBoolean("owner"),
                        invite = group.text("invite").ifEmpty { null },
                        members = people(group.optJSONArray("members")),
                    )
                },
            public = json.optBoolean("public"),
        )

    fun parseInvite(json: JSONObject): Invite = Invite(json.text("name"), json.optInt("members"))

    private fun people(array: JSONArray?): List<Person> =
        array?.objects().orEmpty().mapNotNull { person ->
            val user = person.text("user").ifEmpty { return@mapNotNull null }
            Person(user, person.text("display").ifEmpty { user })
        }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}
