// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.servicelayer

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.getSystemService
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class DebugInfoServiceTest : RobolectricTest() {
    @Test
    @TargetApi(Build.VERSION_CODES.S_V2)
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun `debug reports leave notification channels unchanged`() =
        runTest {
            val manager = targetContext.getSystemService<NotificationManager>()!!
            shadowOf(manager).setNotificationsEnabled(true)
            val before = manager.notificationChannels.map { it.id }.toSet()

            val report = DebugInfoService.getDebugInfo(targetContext)

            assertEquals(before, manager.notificationChannels.map { it.id }.toSet())
            assertContains(report, "Notification channels = messages: true, nudges: true")

            // Reading a blocked channel must preserve that choice and avoid creating the other one.
            manager.createNotificationChannel(NotificationChannel("ankiquest", "AnkiQuest", NotificationManager.IMPORTANCE_NONE))
            val blocked = manager.notificationChannels.map { it.id }.toSet()

            val blockedReport = DebugInfoService.getDebugInfo(targetContext)

            assertEquals(blocked, manager.notificationChannels.map { it.id }.toSet())
            assertContains(blockedReport, "Notification channels = messages: false, nudges: true")
            assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("ankiquest").importance)
        }

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
    @Suppress("AuthLeak") // Deliberate fake credentials exercise report redaction.
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
