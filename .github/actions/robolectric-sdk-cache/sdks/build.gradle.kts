// SPDX-License-Identifier: GPL-3.0-or-later

// Downloads the Robolectric SDK jars that buildSrc's robolectric-downloader plugin lists,
// without configuring the app build. CI keeps the resulting Gradle cache as a read-only
// dependency cache (GRADLE_RO_DEP_CACHE), see ../action.yml.

// Same repositories, in the same order, as settings.gradle.kts, so the cached entries match.
repositories {
    google()
    mavenCentral()
    maven(url = "https://jitpack.io")
}

val pluginFile = rootDir.resolve("../../../../buildSrc/src/main/kotlin/ankidroid.plugins.robolectric-downloader.gradle.kts")
val pluginText = pluginFile.readText()

// The `-i<number>` suffix the plugin appends, from PREINSTRUMENTED_VERSION upstream
val instrumentedSuffix =
    Regex("""-i(\d+)"""")
        .find(pluginText)
        ?.groupValues
        ?.get(1)
        ?: error("No -i<number> suffix found in $pluginFile")

// Active entries only: commented-out lines start with `//`, so `^\s*mapOf` skips them
val sdkVersions =
    Regex("""^\s*mapOf\("androidVersion" to "([^"]+)", "frameworkSdkBuildVersion" to "([^"]+)"\)""", RegexOption.MULTILINE)
        .findAll(pluginText)
        .map { "${it.groupValues[1]}-robolectric-${it.groupValues[2]}-i$instrumentedSuffix" }
        .toList()

check(sdkVersions.isNotEmpty()) { "No Robolectric SDK versions found in $pluginFile" }

// One configuration per SDK, as in the plugin, so Gradle keeps every version instead of the newest
val sdkFiles =
    sdkVersions.map { version ->
        val configuration = configurations.create(version)
        dependencies.add(configuration.name, "org.robolectric:android-all-instrumented:$version")
        configuration.incoming.files
    }

tasks.register("downloadRobolectricSdks") {
    doLast {
        sdkFiles.flatMap { it.files }.forEach { println("${it.name} (${it.length()} bytes)") }
    }
}
