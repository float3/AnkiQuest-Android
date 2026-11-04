// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream

/**
 * How the player signs in, their devices, their data and deleting their account, as the server keeps
 * them (`/api/sign-in`, `/api/devices`, `/api/export` and `/api/accounts` in the server's
 * `docs/mobile-api.md`). Every request carries the device token of [HomeAccount].
 */
internal object AnkiquestAccountSettings {
    /** The server lets the app link a provider or sign in again in a browser it opens. */
    const val APP_SIGN_IN = "app_sign_in"

    data class Provider(
        val key: String,
        val name: String,
        val linked: Boolean,
    )

    /** [account] is false for a player from the server configuration, who has no password to change. */
    data class Methods(
        val account: Boolean,
        val password: Boolean,
        val providers: List<Provider>,
    ) {
        companion object {
            fun parse(json: JSONObject) =
                Methods(
                    json.getBoolean("account"),
                    json.getBoolean("password"),
                    json.getJSONArray("providers").objects().map {
                        Provider(it.getString("provider"), it.getString("name"), it.getBoolean("linked"))
                    },
                )
        }
    }

    /** A signed-in app ([kind] `app`, [name] the device) or website session ([kind] `web`, no [name]). */
    data class Device(
        val id: String,
        val kind: String,
        val name: String?,
        val createdAt: Long,
        val lastUsedAt: Long?,
        val current: Boolean,
    ) {
        companion object {
            fun list(json: JSONObject): List<Device> =
                json.getJSONArray("devices").objects().map { device ->
                    Device(
                        device.getString("id"),
                        device.getString("kind"),
                        if (device.isNull("name")) null else device.optString("name").ifBlank { null },
                        device.getLong("created_at"),
                        if (device.isNull("last_used_at")) null else device.optLong("last_used_at"),
                        device.getBoolean("current"),
                    )
                }
        }
    }

    /** A self-service account; [confirmedUntil] is set while a fresh provider sign-in counts. */
    data class Summary(
        val user: String,
        val password: Boolean,
        val confirmedUntil: Long?,
    ) {
        companion object {
            fun parse(json: JSONObject) =
                Summary(
                    json.getString("user"),
                    json.getBoolean("password"),
                    if (json.isNull("confirmed_until")) null else json.optLong("confirmed_until"),
                )
        }
    }

    enum class Purpose(
        val value: String,
    ) {
        LINK("link"),
        REAUTH("reauth"),
    }

    /** The server sent a browser address on a different site than the account's. */
    class ForeignAddress : IOException("The server sent an address on another site")

    suspend fun methods(account: HomeAccount): Methods = io { Methods.parse(get(account, signInPath(account))) }

    suspend fun unlink(
        account: HomeAccount,
        provider: String,
    ): Methods = io { Methods.parse(post(account, signInPath(account), JSONObject().put("action", "unlink").put("provider", provider))) }

    /** Sets a new password; [current] is null for an account that has none yet. */
    suspend fun setPassword(
        account: HomeAccount,
        current: String?,
        password: String,
        signOutOthers: Boolean,
    ): Methods =
        io {
            val body =
                JSONObject()
                    .put("action", "password")
                    .put("password", password)
                    .put("sign_out_others", signOutOthers)
            if (current != null) body.put("current", current)
            Methods.parse(post(account, signInPath(account), body))
        }

    /**
     * An address on [account]'s server to open in the browser, to link [provider] or sign in with it
     * again; it works once, for a few minutes.
     *
     * @throws ForeignAddress when the address is not on the account's server
     */
    suspend fun browser(
        account: HomeAccount,
        provider: String,
        purpose: Purpose,
    ): String =
        io {
            val answer =
                post(
                    account,
                    signInPath(account),
                    JSONObject().put("action", "browser").put("provider", provider).put("purpose", purpose.value),
                )
            val url = answer.getString("url")
            val parsed = url.toHttpUrlOrNull()
            val server = account.url("")
            if (parsed == null || parsed.host != server.host || parsed.scheme != server.scheme || parsed.port != server.port) {
                throw ForeignAddress()
            }
            url
        }

    suspend fun devices(account: HomeAccount): List<Device> = io { Device.list(get(account, devicesPath(account))) }

    suspend fun signOutDevice(
        account: HomeAccount,
        id: String,
    ): List<Device> = io { Device.list(post(account, devicesPath(account), JSONObject().put("action", "sign_out").put("id", id))) }

    suspend fun signOutOthers(account: HomeAccount): List<Device> =
        io { Device.list(post(account, devicesPath(account), JSONObject().put("action", "sign_out_others"))) }

    suspend fun summary(account: HomeAccount): Summary = io { Summary.parse(get(account, "api/accounts/${account.encodedUser}")) }

    /** Writes the player's data download to [out] as the server sent it, and answers how many bytes it was. */
    suspend fun export(
        account: HomeAccount,
        out: OutputStream,
    ): Long =
        io {
            val request = AnkiquestApi.request(account, "api/export/${account.encodedUser}").get().build()
            AnkiquestApi.call(account, request, AnkiquestApi.Timeout.TRANSFER) { response ->
                response.body.byteStream().use { it.copyTo(out) }
            }
        }

    /**
     * Deletes the account, with its [password], or with none for an account without one that has
     * signed in with its provider again, and then signs this device out.
     */
    suspend fun delete(
        account: HomeAccount,
        password: String?,
    ) {
        io {
            val body = if (password == null) JSONObject() else JSONObject().put("password", password)
            val request =
                AnkiquestApi
                    .request(account, "api/accounts/${account.encodedUser}")
                    .delete(AnkiquestApi.body(body))
                    .build()
            AnkiquestApi.call(account, request) { }
        }
        signOut(account)
    }

    /** Forgets [account]'s player and token on this device, when it is still the signed-in one. */
    fun signOut(account: HomeAccount) {
        if (AnkiquestHomeData.account()?.scope != account.scope) return
        AnkiDroidApp.sharedPrefs().edit {
            remove(Ankiquest.PREF_TOKEN)
            remove(Ankiquest.PREF_USER)
        }
    }

    private fun signInPath(account: HomeAccount) = "api/sign-in/${account.encodedUser}"

    private fun devicesPath(account: HomeAccount) = "api/devices/${account.encodedUser}"

    private fun get(
        account: HomeAccount,
        path: String,
    ): JSONObject = AnkiquestApi.json(account, AnkiquestApi.request(account, path).get().build())

    private fun post(
        account: HomeAccount,
        path: String,
        body: JSONObject,
    ): JSONObject = AnkiquestApi.json(account, AnkiquestApi.request(account, path).post(AnkiquestApi.body(body)).build())

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
