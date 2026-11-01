// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Which wins reach friends, and the daily goal, as kept by the server for every client of one player. */
internal object AnkiquestSharing {
    val DAILY_CAP = 1..10

    /** A sharing policy, `private`, `ask` or `auto`, for each kind of win, and how many automatic wins each friend gets a day. */
    data class Settings(
        val policies: Map<String, String>,
        val dailyCap: Int,
    ) {
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
