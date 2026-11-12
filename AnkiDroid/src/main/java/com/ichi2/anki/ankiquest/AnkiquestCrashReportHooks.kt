// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

/** Tokens and account-scoped caches belong on the device, never in ACRA preference reports. */
internal object AnkiquestCrashReportHooks {
    const val PRIVATE_PREFERENCES = "(?i)ankiquest.*"
}
