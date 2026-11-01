// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The optional features a server reports at `GET /api/capabilities`, so the app can use a feature as
 * soon as the server it talks to has it, whatever order the app and the server are updated in. A
 * server too old to answer reports nothing; while a server cannot be reached, the features it last
 * reported still count.
 */
internal object AnkiquestCapabilities {
    /** The server keeps a dashboard signed in by its HttpOnly cookie alone, without the token. */
    const val COOKIE_SESSION = "cookie_session"

    @VisibleForTesting
    internal const val PREF_CAPABILITIES = "ankiquestCapabilities"

    @VisibleForTesting
    internal const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    private data class Known(
        val features: Set<String>,
        val fetchedAt: Long,
        val scope: String,
    )

    private val known = ConcurrentHashMap<String, Known>()

    /** Whether [account]'s server last reported [feature], without asking it again. */
    fun has(
        account: HomeAccount,
        feature: String,
    ): Boolean = feature in features(account)

    /** The features [account]'s server last reported, without asking it again. */
    fun features(account: HomeAccount): Set<String> = lookup(account.server)?.features.orEmpty()

    /**
     * The features of [account]'s server, asked again when they were last fetched over [MAX_AGE_MS]
     * ago or for a different account.
     */
    suspend fun current(account: HomeAccount): Set<String> {
        val last = lookup(account.server)
        if (last != null && last.scope == account.scope && age(last) in 0 until MAX_AGE_MS) return last.features
        return refresh(account)
    }

    /** Asks [account]'s server for its features; when it cannot be reached, answers the ones it last reported. */
    suspend fun refresh(account: HomeAccount): Set<String> =
        withContext(Dispatchers.IO) {
            val fetched =
                try {
                    fetch(account)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "ankiquest capabilities unavailable")
                    return@withContext features(account)
                }
            store(account, fetched)
            fetched
        }

    /** The features in a `/api/capabilities` answer; anything unreadable counts as none. */
    fun parse(body: String): Set<String> =
        try {
            parse(JSONObject(body))
        } catch (_: JSONException) {
            emptySet()
        }

    private fun parse(answer: JSONObject): Set<String> {
        val features = answer.optJSONArray("features") ?: return emptySet()
        return (0 until features.length()).mapNotNull { features.opt(it) as? String }.filter { it.isNotEmpty() }.toSet()
    }

    private fun fetch(account: HomeAccount): Set<String> {
        val request = AnkiquestApi.request(account.url("api/capabilities"), "", account.language).build()
        return try {
            AnkiquestApi.call(request, AnkiquestApi.Timeout.QUICK) { parse(it.body.string()) }
        } catch (e: AnkiquestHttpException) {
            if (e.code == 404) emptySet() else throw e
        }
    }

    private fun age(entry: Known): Long = TimeManager.time.intTimeMS() - entry.fetchedAt

    private fun preferences(): SharedPreferences = AnkiDroidApp.sharedPrefs()

    private fun key(server: String) = scopedKey(PREF_CAPABILITIES, server)

    private fun lookup(server: String): Known? = known[server] ?: stored(server)?.also { known[server] = it }

    private fun stored(server: String): Known? =
        try {
            val value = JSONObject(preferences().getString(key(server), null) ?: return null)
            Known(parse(value), value.optLong("at"), value.optString("scope"))
        } catch (_: JSONException) {
            null
        } catch (_: ClassCastException) {
            null
        }

    private fun store(
        account: HomeAccount,
        features: Set<String>,
    ) {
        val entry = Known(features, TimeManager.time.intTimeMS(), account.scope)
        known[account.server] = entry
        preferences().edit {
            putString(
                key(account.server),
                JSONObject()
                    .put("features", JSONArray(features.sorted()))
                    .put("at", entry.fetchedAt)
                    .put("scope", entry.scope)
                    .toString(),
            )
        }
    }

    /** Forgets the features held in memory, as a restarted app would. */
    @VisibleForTesting
    internal fun forgetMemory() = known.clear()
}
