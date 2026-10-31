// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import java.security.MessageDigest

/**
 * Hashes [parts] joined by NUL, so an identity can be compared or stored without exposing a credential.
 *
 * Scopes derived from this are persisted as preference keys; changing the input or encoding orphans them.
 */
internal fun sha256(vararg parts: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * The preference key holding [base] for the account with [scope], usually [HomeAccount.scope].
 * Caches and cursors must never carry over to another server, member or credential.
 */
internal fun scopedKey(
    base: String,
    scope: String?,
): String = "$base:${scope.orEmpty()}"
