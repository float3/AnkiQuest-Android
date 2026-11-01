// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.os.Build
import com.ichi2.anki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.UnknownHostException

/** Signs in with a username and password, or creates an account, and keeps only the device token it returns. */
object AnkiquestAccount {
    const val DEFAULT_SERVER = "https://ankiquest.rationality-munich.com"

    data class Credentials(
        val url: String,
        val user: String,
        val password: String,
        val display: String,
        val create: Boolean,
    )

    data class Signed(
        val url: String,
        val user: String,
        val token: String,
    )

    fun device(): String = "AnkiDroid (${Build.MODEL})"

    internal fun httpRequest(
        request: Credentials,
        device: String,
    ): Request {
        val body =
            JSONObject()
                .put("user", request.user)
                .put("password", request.password)
                .put("device", device)
        if (request.create && request.display.isNotBlank()) body.put("display", request.display)
        return Request
            .Builder()
            .url(request.url.trimEnd('/') + if (request.create) "/api/accounts" else "/api/accounts/tokens")
            .header("Accept-Language", AnkiquestLanguage.tag())
            .post(AnkiquestApi.body(body))
            .build()
    }

    /** The password would cross the internet unencrypted, so it is not sent. */
    class InsecureServer : IOException("The server address does not use https")

    /**
     * @throws InsecureServer when [Credentials.url] is plain http to a host outside the local network
     * @throws AnkiquestHttpException when the server turns the request down
     */
    suspend fun submit(
        request: Credentials,
        device: String = device(),
    ): Signed =
        withContext(Dispatchers.IO) {
            if (AnkiquestHosts.insecure(request.url)) throw InsecureServer()
            val answer = AnkiquestApi.anonymous(httpRequest(request, device))
            Signed(request.url.trimEnd('/'), answer.getString("user"), answer.getString("token"))
        }

    fun message(
        context: Context,
        error: Throwable,
        url: String,
    ): String =
        when (error) {
            is AnkiquestHttpException ->
                when (error.code) {
                    400 -> context.getString(R.string.ankiquest_sign_in_invalid)
                    401 -> context.getString(R.string.ankiquest_sign_in_wrong)
                    403 -> context.getString(R.string.ankiquest_sign_in_closed)
                    409 -> context.getString(R.string.ankiquest_sign_in_taken)
                    429 -> context.getString(R.string.ankiquest_sign_in_limited)
                    else -> context.getString(R.string.ankiquest_check_http, error.code)
                }
            is InsecureServer -> context.getString(R.string.ankiquest_check_insecure, url)
            is UnknownHostException -> context.getString(R.string.ankiquest_check_unreachable, url)
            is IllegalArgumentException -> context.getString(R.string.ankiquest_check_bad_url, url)
            else -> {
                Timber.w(error, "ankiquest sign-in failed")
                context.getString(R.string.ankiquest_check_failed, error.message ?: error.javaClass.simpleName)
            }
        }
}
