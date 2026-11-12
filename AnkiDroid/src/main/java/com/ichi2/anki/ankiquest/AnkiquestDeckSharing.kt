// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Keeps each deck's previous recipients, including different choices in a bulk edit. */
internal object AnkiquestDeckSharing {
    enum class Mode { SHARED, PRIVATE }

    data class Setting(
        val deck: String,
        val mode: Mode,
        val recipients: List<String>,
    )

    fun snapshot(decks: List<JSONObject>): List<Setting> =
        decks.map {
            Setting(
                it.getString("id"),
                if (it.optBoolean("enabled")) Mode.SHARED else Mode.PRIVATE,
                it
                    .optJSONArray("recipients")
                    ?.strings()
                    .orEmpty()
                    .toList(),
            )
        }

    /** One captured account and one request restore the entire selection. */
    suspend fun restore(
        account: HomeAccount,
        previous: List<Setting>,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val decks =
                previous.map {
                    JSONObject()
                        .put("id", it.deck)
                        .put("enabled", it.mode == Mode.SHARED)
                        .put("recipients", JSONArray(it.recipients))
                }
            AnkiquestApi.json(
                account,
                AnkiquestApi
                    .request(account, "api/decks/${account.encodedUser}")
                    .post(AnkiquestApi.body(JSONObject().put("decks", JSONArray(decks))))
                    .build(),
            )
        }
}
