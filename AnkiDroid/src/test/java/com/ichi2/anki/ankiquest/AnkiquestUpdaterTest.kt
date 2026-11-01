// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestUpdaterTest : RobolectricTest() {
    private val nightlyJson =
        JSONObject()
            .put("release", 17)
            .put("nightly", 3)
            .put("version_code", 50_017_003)
            .put("commit", "1a2b3c4d5e6f")
            .put("size", 90_000_000)

    @Test
    fun `nightly metadata points at the rolling pre-release`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson)!!
        assertTrue(nightly.nightly)
        assertEquals(50_017_003, nightly.versionCode)
        assertEquals("Nightly quest-17+3 (1a2b3c4)", nightly.name)
        assertEquals("https://github.com/float3/AnkiQuest-Android/releases/download/nightly/AnkiQuest.apk", nightly.apkUrl)
        assertNull(AnkiquestUpdater.parseNightly(JSONObject(nightlyJson.toString()).put("version_code", 0)))
    }

    @Test
    fun `a nightly is offered only when Android would install it as an upgrade`() {
        val nightly = AnkiquestUpdater.parseNightly(nightlyJson)!!
        assertTrue(AnkiquestUpdater.isNewer(nightly, installedRelease = 17, installedCode = 50_017_000))
        assertFalse(AnkiquestUpdater.isNewer(nightly, installedRelease = 17, installedCode = 50_017_003))
        assertFalse(AnkiquestUpdater.isNewer(nightly, installedRelease = 18, installedCode = 50_018_000))
    }

    @Test
    fun `a stable release is offered over any nightly built before it`() {
        val stable = AnkiquestUpdater.Release(number = 18, name = "AnkiQuest 18", apkUrl = "", apkSize = 0)
        assertTrue(AnkiquestUpdater.isNewer(stable, installedRelease = 17, installedCode = 50_017_009))
        assertFalse(AnkiquestUpdater.isNewer(stable, installedRelease = 18, installedCode = 50_018_000))
    }

    @Test
    fun `the channel follows the setting and defaults to stable`() {
        assertEquals(AnkiquestUpdater.Channel.STABLE, AnkiquestUpdater.channel())
        AnkiDroidApp.sharedPrefs().edit { putString(AnkiquestUpdater.PREF_CHANNEL, "nightly") }
        assertEquals(AnkiquestUpdater.Channel.NIGHTLY, AnkiquestUpdater.channel())
    }

    private fun downloads(): Pair<File, File> {
        val dir = File(targetContext.cacheDir, "ankiquest").apply { mkdirs() }
        val apk = File(dir, "AnkiQuest.apk").apply { writeText("apk") }
        val part = File(dir, "AnkiQuest.apk.part").apply { writeText("part") }
        return apk to part
    }

    @Test
    fun `a downloaded apk that is already installed is deleted`() {
        val (apk, part) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100) { 100 }
        assertFalse(apk.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `a downloaded apk newer than the installed build is kept`() {
        val (apk, part) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100) { 101 }
        assertTrue(apk.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `an unreadable downloaded apk is deleted`() {
        val (apk, _) = downloads()
        AnkiquestUpdater.cleanStale(targetContext, installedCode = 100)
        assertFalse(apk.exists())
    }
}
