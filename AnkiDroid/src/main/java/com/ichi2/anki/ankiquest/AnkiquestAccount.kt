// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.os.Build
import com.ichi2.anki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

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

    private val client =
        OkHttpClient
            .Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    fun device(): String = "AnkiDroid (${Build.MODEL})"

    internal fun httpRequest(
        request: Credentials,
        device: String,
    ): okhttp3.Request {
        val body =
            JSONObject()
                .put("user", request.user)
                .put("password", request.password)
                .put("device", device)
        if (request.create && request.display.isNotBlank()) body.put("display", request.display)
        return okhttp3.Request
            .Builder()
            .url(request.url.trimEnd('/') + if (request.create) "/api/accounts" else "/api/accounts/tokens")
            .header("Accept-Language", AnkiquestLanguage.tag())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** @throws Ankiquest.HttpStatusException when the server turns the request down */
    suspend fun submit(
        request: Credentials,
        device: String = device(),
    ): Signed =
        withContext(Dispatchers.IO) {
            client.newCall(httpRequest(request, device)).execute().use { response ->
                if (!response.isSuccessful) throw Ankiquest.HttpStatusException(response.code)
                val answer = JSONObject(response.body.string())
                Signed(request.url.trimEnd('/'), answer.getString("user"), answer.getString("token"))
            }
        }

    fun message(
        context: Context,
        error: Throwable,
        url: String,
    ): String =
        when (error) {
            is Ankiquest.HttpStatusException ->
                when (error.code) {
                    400 -> context.getString(R.string.ankiquest_sign_in_invalid)
                    401 -> context.getString(R.string.ankiquest_sign_in_wrong)
                    403 -> context.getString(R.string.ankiquest_sign_in_closed)
                    409 -> context.getString(R.string.ankiquest_sign_in_taken)
                    429 -> context.getString(R.string.ankiquest_sign_in_limited)
                    else -> context.getString(R.string.ankiquest_check_http, error.code)
                }
            is UnknownHostException -> context.getString(R.string.ankiquest_check_unreachable, url)
            is IllegalArgumentException -> context.getString(R.string.ankiquest_check_bad_url, url)
            else -> {
                Timber.w(error, "ankiquest sign-in failed")
                context.getString(R.string.ankiquest_check_failed, error.message ?: error.javaClass.simpleName)
            }
        }
}
