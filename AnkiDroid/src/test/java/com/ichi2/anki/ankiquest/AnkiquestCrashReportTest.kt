// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.acraCoreConfigBuilder
import com.ichi2.anki.common.crashreporting.CrashReportService
import org.acra.ReportField
import org.acra.builder.ReportBuilder
import org.acra.collector.SharedPreferencesCollector
import org.acra.data.CrashReportData
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AnkiquestCrashReportTest : RobolectricTest() {
    @Test
    fun `crash reports omit AnkiQuest credentials and account caches`() {
        val keys =
            listOf(
                Ankiquest.PREF_URL,
                Ankiquest.PREF_USER,
                Ankiquest.PREF_TOKEN,
                "ankiquestMomentsPending:account-scope",
                "ankiquestWidgetLeaderboard:account-scope",
                "ankiquestMoodIconProfile:account-scope",
                "ankiquestLastUsername:server-scope",
                "AnkiQuestFuturePrivateCache",
            )
        AnkiDroidApp.sharedPrefs().edit {
            keys.forEach { putString(it, "synthetic-private-value") }
            putLong("ankiquestLastSuccessfulPoll:account-scope", 123L)
        }

        val collected = collect()
        val leaked = (keys + "ankiquestLastSuccessfulPoll:account-scope").filter { collected.has(it) }
        assertEquals(emptyList(), leaked, "private preferences must not enter a crash report")
    }

    @Test
    fun `safe diagnostics remain while existing upstream secrets stay excluded`() {
        AnkiDroidApp.sharedPrefs().edit {
            putString("theme", "light")
            putString("username", "synthetic-ankiweb-user")
            putString("hkey", "synthetic-ankiweb-secret")
            putString("currentSyncUri", "https://private.example")
            putString("browser_search_history", "private search")
        }

        val collected = collect()
        assertEquals("light", collected.getString("theme"))
        for (key in listOf("username", "hkey", "currentSyncUri", "browser_search_history")) {
            assertFalse(collected.has(key), key)
        }
    }

    private fun collect(): JSONObject {
        val report = CrashReportData()
        SharedPreferencesCollector().collect(
            ReportField.SHARED_PREFERENCES,
            targetContext,
            CrashReportService.acraCoreConfigBuilder.build(),
            ReportBuilder(),
            report,
        )
        return (report[ReportField.SHARED_PREFERENCES.name] as JSONObject).getJSONObject("default")
    }
}
