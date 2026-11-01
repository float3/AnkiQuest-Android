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

/** A friend receives an independent .apkg, including media but never the sender's review history. */
internal object AnkiquestDeckCopies {
    private const val MAX_BYTES = 25L * 1024 * 1024

    class TooLarge : IOException("Deck copy exceeds 25 MB")

    suspend fun inbox(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            send(AnkiquestApi.request(account, "api/deck-copies/${account.encodedUser}").build())
        }

    suspend fun share(
        account: HomeAccount,
        deckId: Long,
        deckName: String,
        recipients: List<String>,
        cache: File,
    ) = withContext(Dispatchers.IO) {
        AnkiquestApi.requireCurrent(account)
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
            AnkiquestApi.requireCurrent(account)
            if (packageFile.length() > MAX_BYTES) throw TooLarge()
            val url =
                account
                    .url("api/deck-copies/${account.encodedUser}")
                    .newBuilder()
                    .addQueryParameter("deck", deckName)
                    .addQueryParameter("recipients", JSONArray(recipients).toString())
                    .build()
            send(
                AnkiquestApi
                    .request(url, account.token, account.language)
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
                val request = AnkiquestApi.request(account, "api/deck-copies/${account.encodedUser}/$id").build()
                AnkiquestApi.call(request, AnkiquestApi.Timeout.TRANSFER) { response ->
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
        send(AnkiquestApi.request(account, "api/deck-copies/${account.encodedUser}/$id").delete().build())
    }

    private fun send(request: Request): JSONObject =
        AnkiquestApi.call(request, AnkiquestApi.Timeout.TRANSFER) { response ->
            AnkiquestApi.jsonObject(response)
        }
}
