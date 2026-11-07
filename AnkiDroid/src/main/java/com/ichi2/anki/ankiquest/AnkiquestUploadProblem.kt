// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.IOException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * Why the last upload for an account failed, kept until an upload succeeds, so the screens that say
 * reviews are not uploaded yet can also say why.
 *
 *     upload fails ──▶ of(error) ──▶ remember(problem) ──▶ Today: "Streak safe · sign in again to upload"
 *     upload works ──────────────▶ remember(null) ─────▶ Today: "Streak safe"
 */
internal enum class AnkiquestUploadProblem(
    val key: String,
) {
    /** The server could not be reached; uploads retry on their own. */
    OFFLINE("offline"),

    /** The server refused this device's token; signing in again fixes it. */
    SIGNED_OUT("signed_out"),

    /** The server failed; uploads retry on their own. */
    SERVER("server"),

    /** The server refused the upload for another reason; Test connection says which. */
    REFUSED("refused"),
    ;

    companion object {
        private const val PREF_PROBLEM = "ankiquestUploadProblem"

        /** The problem [error] shows, or null when it says nothing about the upload (the account changed, or a bug). */
        fun of(error: Throwable): AnkiquestUploadProblem? =
            when {
                error is HomeAccountChanged -> null
                error is AnkiquestHttpException && (error.code == HTTP_UNAUTHORIZED || error.code == HTTP_FORBIDDEN) -> SIGNED_OUT
                error is AnkiquestHttpException && error.code >= HTTP_INTERNAL_ERROR -> SERVER
                error is AnkiquestHttpException -> REFUSED
                error is IOException -> OFFLINE
                else -> null
            }

        /** Keeps [problem] for the account with [scope]; null forgets it, after an upload that worked. */
        fun remember(
            prefs: SharedPreferences,
            scope: String,
            problem: AnkiquestUploadProblem?,
        ) {
            val key = scopedKey(PREF_PROBLEM, scope)
            if (prefs.getString(key, null) == problem?.key) return
            prefs.edit {
                if (problem == null) remove(key) else putString(key, problem.key)
            }
        }

        /** Why the last upload for the account with [scope] failed, or null when it worked or none was made. */
        fun last(
            prefs: SharedPreferences,
            scope: String?,
        ): AnkiquestUploadProblem? {
            scope ?: return null
            val stored = prefs.getString(scopedKey(PREF_PROBLEM, scope), null) ?: return null
            return entries.firstOrNull { it.key == stored }
        }
    }
}
