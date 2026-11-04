// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.compat

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Retrieve information about a package archive (APK) at [archiveFilePath].
 *
 * Uses the [PackageManager.PackageInfoFlags] overload on API 33+, where the `Int` flags
 * overload is deprecated.
 *
 * @see PackageManager.getPackageArchiveInfo
 */
fun PackageManager.getPackageArchiveInfoCompat(
    archiveFilePath: String,
    flags: PackageInfoFlagsCompat,
): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(archiveFilePath, PackageManager.PackageInfoFlags.of(flags.value))
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(archiveFilePath, flags.value.toInt())
    }
