// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import timber.log.Timber

/**
 * Moves the saved account to the server's main address, the `public_url` it names at
 * `GET /api/capabilities`, when the app still uses an older address of the same server.
 *
 * The move happens only for an https address, and only once one signed-in request with the saved
 * token works there; otherwise, or offline, the saved address stays. Everything the app keeps per
 * address moves with the account (upload cursors, inbox cursors, caches, widget boards, goal
 * records), so nothing is uploaded or shown again. It is silent either way.
 */
internal object AnkiquestServerMove {
    /** Which main addresses may be moved to; only https outside tests. */
    @VisibleForTesting
    internal var acceptable: (HttpUrl) -> Boolean = { it.isHttps }

    /** The main address in a `/api/capabilities` answer, without a trailing slash, or null. */
    fun mainAddress(answer: JSONObject): String? {
        val text = answer.optString("public_url").trim().trimEnd('/')
        if (text.isEmpty()) return null
        val url = text.toHttpUrlOrNull() ?: return null
        if (!acceptable(url) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            return null
        }
        return text
    }

    /** Whether [account], saved at its server address, should try the main address [main]. */
    fun differs(
        account: HomeAccount,
        main: String?,
    ): Boolean {
        if (main == null || account.token.isEmpty()) return false
        val saved = account.server.toHttpUrlOrNull() ?: return false
        val there = main.toHttpUrlOrNull() ?: return false
        return saved.toString().trimEnd('/') != there.toString().trimEnd('/')
    }

    /**
     * Moves [account] to [main] when it differs from the saved address and the saved token works
     * there. True when the account moved.
     */
    suspend fun consider(
        account: HomeAccount,
        main: String?,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): Boolean {
        if (!differs(account, main)) return false
        val moved = HomeAccount(requireNotNull(main), account.user, account.token, account.language)
        val works =
            withContext(Dispatchers.IO) {
                try {
                    // The device list answers only to a working token; the profile also answers without one.
                    val request = AnkiquestApi.request(moved, "api/devices/${moved.encodedUser}").build()
                    AnkiquestApi.call(request, AnkiquestApi.Timeout.QUICK) { }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "ankiquest main address unavailable")
                    false
                }
            }
        return works && move(prefs, account, moved)
    }

    /**
     * Saves [to]'s address and moves every record kept for [from] to it. Nothing happens when the
     * signed-in account is no longer [from]. True when moved.
     */
    @Synchronized
    @VisibleForTesting
    internal fun move(
        prefs: SharedPreferences,
        from: HomeAccount,
        to: HomeAccount,
    ): Boolean {
        if (HomeAccount.current(prefs)?.scope != from.scope) return false
        val settings = prefs.all
        val hashes =
            mapOf(
                from.scope to to.scope,
                AnkiquestNavigation.accountFingerprint(settings) to
                    AnkiquestNavigation.accountFingerprint(settings + (Ankiquest.PREF_URL to to.server)),
                sha256(from.server, from.user) to sha256(to.server, to.user),
            )
        prefs.edit(commit = true) {
            for ((key, value) in settings) {
                if (!key.startsWith("ankiquest") || key in ACCOUNT_KEYS) continue
                val newKey = movedKey(key, from.server, to.server, hashes)
                val newValue = movedValue(value, hashes)
                if (newKey == key && newValue == value) continue
                remove(key)
                put(newKey, newValue)
            }
            putString(Ankiquest.PREF_URL, to.server)
        }
        return true
    }

    private val ACCOUNT_KEYS = setOf(Ankiquest.PREF_URL, Ankiquest.PREF_USER, Ankiquest.PREF_TOKEN)

    /** [key] with the account's hashes replaced, and the address after a `:` when it is the old one. */
    private fun movedKey(
        key: String,
        from: String,
        to: String,
        hashes: Map<String, String>,
    ): String {
        var moved = replaceHashes(key, hashes)
        val marker = ":$from"
        val at = moved.indexOf(marker)
        if (at >= 0) {
            val end = at + marker.length
            if (end == moved.length || moved[end] == '/') moved = moved.substring(0, at) + ":$to" + moved.substring(end)
        }
        return moved
    }

    private fun movedValue(
        value: Any?,
        hashes: Map<String, String>,
    ): Any? =
        when (value) {
            is String -> replaceHashes(value, hashes)
            is Set<*> -> value.map { if (it is String) replaceHashes(it, hashes) else it }.toSet()
            else -> value
        }

    private fun replaceHashes(
        text: String,
        hashes: Map<String, String>,
    ): String = hashes.entries.fold(text) { moved, (old, new) -> moved.replace(old, new) }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.put(
        key: String,
        value: Any?,
    ) {
        when (value) {
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
            is Long -> putLong(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is Float -> putFloat(key, value)
            else -> Unit
        }
    }
}
