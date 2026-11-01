// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server answered with a status other than success; [explanation] is its own `{"error": ...}` text, when it gave one. */
internal class AnkiquestHttpException(
    val code: Int,
    val explanation: String? = null,
) : IOException(explanation ?: "HTTP $code")

/** The signed-in account is no longer the one a request was prepared for. */
internal class HomeAccountChanged : IOException("The AnkiQuest account changed")

/**
 * Every request to an AnkiQuest server goes through here, so they share one redirect policy, one set
 * of timeouts and one error type. Redirects are never followed: a bearer token or a private message
 * must not travel to a destination other than the one it was addressed to, and every caller treats a
 * redirect as a failure.
 */
internal object AnkiquestApi {
    /** How long a request may take. */
    enum class Timeout {
        /** Optional extras such as profile pictures, which must never hold up anything else. */
        QUICK,

        /** Ordinary JSON requests. */
        STANDARD,

        /** Deck packages of up to 25 MB, in either direction. */
        TRANSFER,
    }

    val json = "application/json".toMediaType()

    private val base =
        AnkiquestHttp.client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val quick = base.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    private val standard = base.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val transfer =
        base
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

    fun client(timeout: Timeout): OkHttpClient =
        when (timeout) {
            Timeout.QUICK -> quick
            Timeout.STANDARD -> standard
            Timeout.TRANSFER -> transfer
        }

    /** A request to [url] carrying [token], when there is one, and [language]. */
    fun request(
        url: HttpUrl,
        token: String,
        language: String,
    ): Request.Builder =
        Request
            .Builder()
            .url(url)
            .header("Accept-Language", language)
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }

    /** A request to [path] on [account]'s server, with its credentials and language. */
    fun request(
        account: HomeAccount,
        path: String,
    ): Request.Builder = request(account.url(path), account.token, account.language)

    fun body(value: JSONObject): RequestBody = value.toString().toRequestBody(json)

    /** Fails unless [account] is still the signed-in account. */
    fun requireCurrent(account: HomeAccount) {
        if (AnkiquestHomeData.account()?.scope != account.scope) throw HomeAccountChanged()
    }

    /**
     * Sends [request] and hands a successful response to [read]. [check] runs before sending and again
     * once the server has answered, so an answer for an account that was signed out in the meantime is
     * never used.
     *
     * @throws AnkiquestHttpException when the server answers with anything but success, redirects included
     */
    fun <T> call(
        request: Request,
        timeout: Timeout = Timeout.STANDARD,
        check: () -> Unit = {},
        read: (Response) -> T,
    ): T {
        check()
        return client(timeout).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = runCatching { response.peekBody(ERROR_BYTES).string() }.getOrNull()
                check()
                throw AnkiquestHttpException(response.code, text?.let(::explanation))
            }
            read(response).also { check() }
        }
    }

    /**
     * [call] for [account], which must be the signed-in account when the request is sent and, unless
     * [recheck] is false, when it is answered. Without a recheck the caller keeps the answer under the
     * account it was asked for, as the inbox does.
     */
    fun <T> call(
        account: HomeAccount,
        request: Request,
        timeout: Timeout = Timeout.STANDARD,
        recheck: Boolean = true,
        read: (Response) -> T,
    ): T {
        var sent = false
        return call(request, timeout, {
            if (!sent || recheck) requireCurrent(account)
            sent = true
        }, read)
    }

    /** [call] for [account], answering the JSON object the server sent, or an empty one for an empty body. */
    fun json(
        account: HomeAccount,
        request: Request,
        timeout: Timeout = Timeout.STANDARD,
    ): JSONObject = call(account, request, timeout) { objectOf(it) }

    /** An unauthenticated [call], for previews and signing in. */
    fun anonymous(request: Request): JSONObject = call(request) { objectOf(it) }

    fun objectOf(response: Response): JSONObject = response.body.string().let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    fun arrayOf(response: Response): JSONArray = JSONArray(response.body.string())

    /** The server explains refusals as `{"error": "..."}`. */
    fun explanation(body: String): String? =
        try {
            JSONObject(body).optString("error").trim().ifEmpty { null }
        } catch (_: JSONException) {
            null
        }

    private const val ERROR_BYTES = 16L * 1024
}

/** Which servers credentials may be sent to. Kept apart from [AnkiquestApi] so checking an address builds no HTTP client. */
internal object AnkiquestHosts {
    /**
     * Whether a token or password may be sent to [url]: over https, or over plain http only to this
     * device, the local network or the emulator's host, where it does not cross the internet unencrypted.
     */
    fun secure(url: HttpUrl): Boolean = url.isHttps || local(url.host)

    /** Whether [server], a typed server address, is a valid address that [secure] refuses. */
    fun insecure(server: String): Boolean {
        val url = server.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        return !secure(url)
    }

    /** Loopback, private IPv4 and unique local IPv6 addresses, and `.local` names; 10.0.2.2, the emulator's host, is in 10/8. */
    fun local(host: String): Boolean {
        val name = host.removeSuffix(".")
        if (name == "localhost" || name.endsWith(".local")) return true
        ipv4(name)?.let { (first, second) ->
            return first == 127 || first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
        }
        if (':' !in name) return false
        if (name == "::1") return true
        val group = name.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
        return (group and 0xfe00) == 0xfc00
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { part -> part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' } }) return null
        val numbers = parts.map { it.toInt() }
        if (numbers.any { it > 255 }) return null
        return numbers[0] to numbers[1]
    }
}
