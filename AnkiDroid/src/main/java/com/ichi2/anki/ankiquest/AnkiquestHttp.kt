// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** One connection pool and dispatcher for every AnkiQuest request; callers derive their timeouts with `newBuilder()`. */
internal object AnkiquestHttp {
    val client: OkHttpClient = OkHttpClient()
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
