// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.servicelayer

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestCapabilities
import com.ichi2.anki.ankiquest.scopedKey
import com.ichi2.anki.common.preferences.sharedPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class DebugInfoServiceTest : RobolectricTest() {
    @Test
    fun `AnkiQuest debug info excludes credentials and the server path`() =
        runTest {
            val user = "private-player@example.test"
            val token = "private-device-token"
            val path = "private-instance-path"
            val server = "https://study.example.test:8443/$path"
            targetContext.sharedPrefs().edit {
                putString(Ankiquest.PREF_URL, server)
                putString(Ankiquest.PREF_USER, user)
                putString(Ankiquest.PREF_TOKEN, token)
                putString(
                    scopedKey(AnkiquestCapabilities.PREF_CAPABILITIES, server),
                    JSONObject().put("version", 1).put("features", JSONArray(listOf(token, user))).toString(),
                )
            }

            val report = DebugInfoService.getDebugInfo(targetContext)

            assertContains(report, "AnkiQuest Release = ")
            assertContains(report, "Server host = study.example.test  \n")
            assertContains(report, "Signed in = true")
            assertContains(report, "Device token present = true")
            assertContains(report, "Server API version = 1")
            for (privateValue in listOf(user, token, path, ":8443")) {
                assertFalse(report.contains(privateValue), "A private account field appeared in the report")
            }
        }

    @Test
    fun `AnkiQuest debug info keeps only the host of an invalid connection URL`() =
        runTest {
            targetContext.sharedPrefs().edit {
                putString(
                    Ankiquest.PREF_URL,
                    "https://private-user:private-password@study.example.test/?email=private@example.test#private-fragment",
                )
            }

            val report = DebugInfoService.getDebugInfo(targetContext)

            assertContains(report, "Server host = study.example.test  \n")
            assertContains(report, "Signed in = false")
            assertFalse(report.contains("private-"))
            assertFalse(report.contains("private@example.test"))
        }
}
