// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Global's private exclusions: controls -> captured account -> authenticated API. */
internal object AnkiquestGlobalHides {
    private const val FEATURE = "global_leaderboard_hide"

    enum class Action(
        val wire: String,
    ) {
        HIDE("hide"),
        SHOW("unhide"),
    }

    data class Player(
        val user: String,
        val display: String,
    )

    /** Null means an older server; an empty list means supported with nobody hidden. */
    suspend fun load(account: HomeAccount): List<Player>? {
        val features = AnkiquestCapabilities.current(account)
        AnkiquestApi.requireCurrent(account)
        if (FEATURE !in features) {
            return null
        }
        return withContext(Dispatchers.IO) {
            val request = AnkiquestApi.request(account, "api/leaderboard/hidden/${account.encodedUser}").build()
            parse(AnkiquestApi.call(account, request) { AnkiquestApi.jsonArray(it) })
        }
    }

    /** The server returns the complete updated list; ranks and other scopes are never rewritten. */
    suspend fun change(
        account: HomeAccount,
        user: String,
        action: Action,
    ): List<Player> =
        withContext(Dispatchers.IO) {
            require(user.isNotBlank() && user != account.user)
            val body = JSONObject().put("action", action.wire).put("user", user)
            val request =
                AnkiquestApi
                    .request(account, "api/leaderboard/hidden/${account.encodedUser}")
                    .post(AnkiquestApi.body(body))
                    .build()
            parse(AnkiquestApi.call(account, request) { AnkiquestApi.jsonArray(it) })
        }

    private fun parse(rows: JSONArray): List<Player> =
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val user = row.getString("user")
            require(user.isNotBlank())
            Player(user, row.getString("display").ifBlank { user })
        }
}
