// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import anki.import_export.exportLimit
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.libanki.exportAnkiPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A friend receives an independent .apkg, including media but never the sender's review history. */
internal object AnkiquestDeckCopies {
    private const val MAX_BYTES = 25L * 1024 * 1024

    class TooLarge : IOException("Deck copy exceeds 25 MB")

    private val client =
        AnkiquestHttp.client
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

    suspend fun inbox(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            request(
                account,
                Request
                    .Builder()
                    .url(account.url("api/deck-copies/${account.encodedUser}"))
                    .get()
                    .build(),
            )
        }

    suspend fun share(
        account: HomeAccount,
        deckId: Long,
        deckName: String,
        recipients: List<String>,
        cache: File,
    ) = withContext(Dispatchers.IO) {
        if (AnkiquestHomeData.account()?.scope != account.scope) throw HomeAccountChanged()
        val packageFile = File(cache, "ankiquest-share-${UUID.randomUUID()}.apkg")
        try {
            CollectionManager.withCol {
                exportAnkiPackage(
                    packageFile.absolutePath,
                    withScheduling = false,
                    withDeckConfigs = true,
                    withMedia = true,
                    limit = exportLimit { this.deckId = deckId },
                    legacy = false,
                )
            }
            if (AnkiquestHomeData.account()?.scope != account.scope) throw HomeAccountChanged()
            if (packageFile.length() > MAX_BYTES) throw TooLarge()
            val url =
                account
                    .url("api/deck-copies/${account.encodedUser}")
                    .newBuilder()
                    .addQueryParameter("deck", deckName)
                    .addQueryParameter("recipients", JSONArray(recipients).toString())
                    .build()
            request(
                account,
                Request
                    .Builder()
                    .url(url)
                    .post(packageFile.asRequestBody("application/octet-stream".toMediaType()))
                    .build(),
            )
        } finally {
            packageFile.delete()
        }
    }

    suspend fun download(
        account: HomeAccount,
        id: Long,
        cache: File,
    ): File =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("ankiquest-friend-", ".apkg", cache)
            try {
                val request =
                    Request
                        .Builder()
                        .url(account.url("api/deck-copies/${account.encodedUser}/$id"))
                        .get()
                        .build()
                client.newCall(authorized(account, request)).execute().use { response ->
                    if (!response.isSuccessful) throw Ankiquest.HttpStatusException(response.code)
                    val body = response.body
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var size = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                size += read
                                if (size > MAX_BYTES) throw TooLarge()
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                if (file.length() == 0L) throw IOException("Empty deck copy")
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    suspend fun dismiss(
        account: HomeAccount,
        id: Long,
    ) = withContext(Dispatchers.IO) {
        request(
            account,
            Request
                .Builder()
                .url(account.url("api/deck-copies/${account.encodedUser}/$id"))
                .delete()
                .build(),
        )
    }

    private fun authorized(
        account: HomeAccount,
        request: Request,
    ): Request = request.newBuilder().header("Authorization", "Bearer ${account.token}").build()

    private fun request(
        account: HomeAccount,
        request: Request,
    ): JSONObject =
        client.newCall(authorized(account, request)).execute().use { response ->
            if (!response.isSuccessful) throw Ankiquest.HttpStatusException(response.code)
            val body = response.body.string()
            if (body.isEmpty()) JSONObject() else JSONObject(body)
        }
}
