// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Which wins reach friends, and the daily goal, as kept by the server for every client of one player. */
internal object AnkiquestSharing {
    val DAILY_CAP = 1..10
    const val PRIVATE = "private"
    private const val ASK = "ask"

    /** The server's own starting policies; kinds it adds later start at [ASK]. */
    private val DEFAULT_POLICIES =
        mapOf(
            "daily_goal" to ASK,
            "streak_secured" to PRIVATE,
            "streak" to ASK,
            "level" to ASK,
            "achievement" to ASK,
            "record" to ASK,
            "quest" to PRIVATE,
            "conquered" to ASK,
            "place" to ASK,
            "challenge" to ASK,
        )

    /** A sharing policy, `private`, `ask` or `auto`, for each kind of win, and how many automatic wins each friend gets a day. */
    data class Settings(
        val policies: Map<String, String>,
        val dailyCap: Int,
    ) {
        /** Whether any kind of win can reach friends. */
        val shared: Boolean get() = policies.values.any { it != PRIVATE }

        /**
         * The policies that turn all sharing off, or back on as a new player starts: with every kind
         * private, or with the server's defaults.
         */
        fun switched(on: Boolean): Map<String, String> = policies.keys.associateWith { if (on) DEFAULT_POLICIES[it] ?: ASK else PRIVATE }

        companion object {
            fun parse(json: JSONObject): Settings {
                val policies = json.getJSONObject("policies")
                return Settings(policies.keys().asSequence().associateWith { policies.getString(it) }, json.getInt("daily_cap"))
            }
        }
    }

    suspend fun settings(account: HomeAccount): Settings = Settings.parse(call(account, "GET", "api/sharing/${account.encodedUser}"))

    /** Changes only the given [policies] and, when set, the [dailyCap]; answers the settings as now stored. */
    suspend fun saveSettings(
        account: HomeAccount,
        policies: Map<String, String>,
        dailyCap: Int?,
    ): Settings {
        val body = JSONObject().put("policies", JSONObject(policies))
        dailyCap?.let {
            require(it in DAILY_CAP)
            body.put("daily_cap", it)
        }
        return Settings.parse(call(account, "PUT", "api/sharing/${account.encodedUser}", body))
    }

    /** The goal as `{mode, value}`. */
    suspend fun goal(account: HomeAccount): JSONObject = call(account, "GET", "api/goal/${account.encodedUser}")

    /** Stores the goal; answers what the server keeps, or [goal] itself when the server answers without a body. */
    suspend fun saveGoal(
        account: HomeAccount,
        goal: JSONObject,
    ): JSONObject = call(account, "PUT", "api/goal/${account.encodedUser}", goal).takeIf { it.has("mode") } ?: goal

    /**
     * Tells the server a backlog goal was reached on Anki [day]. Answers false when the server will
     * not take it: already reported, another day, or a goal that is not a backlog goal.
     */
    suspend fun goalReached(
        account: HomeAccount,
        day: Long,
    ): Boolean =
        try {
            call(account, "POST", "api/goal/${account.encodedUser}/reached", JSONObject().put("day", day))
            true
        } catch (e: AnkiquestHttpException) {
            if (e.code != 409) throw e
            false
        }

    /** The server's event key of [account]'s win [id], such as `conquered:1793183400000`, or null when the server has no such win. */
    suspend fun winKey(
        account: HomeAccount,
        id: Long,
    ): String? = win(account, id)?.optString("key")?.ifEmpty { null }

    /** [account]'s win [id] as `/api/wins` lists it (with its `link` and `shared_with_friends`), or null when the server has no such win. */
    suspend fun win(
        account: HomeAccount,
        id: Long,
    ): JSONObject? =
        call(account, "GET", "api/wins/${account.encodedUser}?limit=1&before=${id + 1}")
            .optJSONArray("wins")
            ?.optJSONObject(0)
            ?.takeIf { it.optLong("id") == id }

    /** The label [account] gave the card they conquered at [at], shown with it wherever it is shared; null when there is none. */
    suspend fun conquestLabel(
        account: HomeAccount,
        at: Long,
    ): String? =
        call(account, "GET", "api/conquests/${account.encodedUser}/$at")
            .optString("label")
            .ifBlank { null }

    /** Shows [label] with the card conquered at [at] on its shared card, page and friends' messages, or stops showing one when null. */
    suspend fun setConquestLabel(
        account: HomeAccount,
        at: Long,
        label: String?,
    ) {
        val path = "api/conquests/${account.encodedUser}/$at/label"
        if (label == null) {
            call(account, "DELETE", path)
        } else {
            call(account, "POST", path, JSONObject().put("label", label))
        }
    }

    /** Tells [recipients] that [account] finished the deck [name] today; answers how many heard it now. */
    suspend fun shareFinishedDeck(
        account: HomeAccount,
        id: Long,
        name: String,
        recipients: List<String>,
    ): Int =
        call(
            account,
            "POST",
            "api/decks/${account.encodedUser}/share",
            JSONObject()
                .put("id", id.toString())
                .put("name", name)
                .put("recipients", JSONArray(recipients)),
        ).optInt("recipients")

    /** One request for the captured [account], which must still be the signed-in one when it is sent and when it answers. */
    private suspend fun call(
        account: HomeAccount,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val request =
                AnkiquestApi
                    .request(account, path)
                    .method(method, body?.let(AnkiquestApi::body))
                    .build()
            AnkiquestApi.call(request, check = { requireCurrent(account) }) { AnkiquestApi.jsonObject(it) }
        }

    private fun requireCurrent(account: HomeAccount) {
        AnkiquestApi.requireCurrent(account)
        if (account.token.isEmpty()) throw AnkiquestHttpException(401)
    }
}
