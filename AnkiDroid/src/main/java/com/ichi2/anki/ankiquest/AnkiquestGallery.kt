// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ichi2.utils.openInputStreamSafe
import java.io.IOException
import java.util.UUID

/** Saves the prepared share PNG, so the gallery and receiving app get identical bytes. */
internal object AnkiquestGallery {
    fun save(
        context: Context,
        source: Uri,
        account: HomeAccount,
    ): Uri {
        require(source.scheme == "content" && source.authority == "${context.packageName}.apkgfileprovider")
        AnkiquestApi.requireCurrent(account)

        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AnkiQuest-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AnkiQuest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
        val resolver = context.contentResolver
        val saved = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("No gallery destination")
        try {
            val input = resolver.openInputStreamSafe(source) ?: throw IOException("The share picture is unavailable")
            input.use {
                val output = resolver.openOutputStream(saved) ?: throw IOException("The gallery picture is unavailable")
                output.use { input.copyTo(it) }
            }
            AnkiquestApi.requireCurrent(account)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                if (resolver.update(saved, ready, null, null) != 1) {
                    throw IOException("The gallery picture could not be published")
                }
            }
            return saved
        } catch (error: Exception) {
            // Remove only the row this operation inserted; never leave a partial picture behind.
            runCatching { resolver.delete(saved, null, null) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
