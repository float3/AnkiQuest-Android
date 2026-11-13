// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ichi2.utils.openInputStreamSafe
import java.io.File
import java.io.IOException
import java.util.UUID

/** Saves a share picture from the share cache to the gallery, in Pictures/AnkiQuest. */
internal object AnkiquestGallery {
    /** The type of every share picture, in the share cache and in the gallery. */
    const val PNG = "image/png"

    private const val FOLDER = "AnkiQuest"

    /** The FileProvider that exposes the share cache to other apps and to [save]. */
    fun authority(context: Context) = "${context.packageName}.apkgfileprovider"

    fun save(
        context: Context,
        source: Uri,
        account: HomeAccount,
    ): Uri {
        require(source.scheme == "content" && source.authority == authority(context))
        AnkiquestApi.requireCurrent(account)

        val name = "$FOLDER-${UUID.randomUUID()}.png"
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, PNG)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    put(MediaStore.MediaColumns.DATA, legacyFile(name).path)
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

    /**
     * Before Android 10, MediaProvider ignores DISPLAY_NAME and, without DATA, creates
     * Pictures/<millis>.jpg. Name the file here, in a folder that exists before the insert.
     */
    @Suppress("DEPRECATION")
    private fun legacyFile(name: String): File {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("No gallery folder")
        }
        return File(folder, name)
    }
}
