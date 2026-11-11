// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.NotificationManager
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestNotificationOwnerTest : RobolectricTest() {
    private enum class Change { SIGN_OUT, USER, SERVER, TOKEN }

    @Before
    fun setupImages() {
        mockkObject(AnkiquestShare)
    }

    @After
    fun restoreImages() {
        unmockkObject(AnkiquestShare)
    }

    @Test
    fun `sign out during an image download leaves the old batch pending`() = assertChanged(Change.SIGN_OUT)

    @Test
    fun `changing user during an image download leaves the old batch pending`() = assertChanged(Change.USER)

    @Test
    fun `changing server during an image download leaves the old batch pending`() = assertChanged(Change.SERVER)

    @Test
    fun `changing credentials during an image download leaves the old batch pending`() = assertChanged(Change.TOKEN)

    @Test
    fun `an account change between entries preserves only the delivered cursor`() {
        val prefs = AnkiDroidApp.sharedPrefs()
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
        val account = assertNotNull(AnkiquestHomeData.account(prefs))
        val cursor = "ankiquestCompletionCursor:${account.notificationAccount}"
        prefs.edit { putLong(cursor, 5) }
        val notices =
            JSONArray(
                listOf(6, 7).map { id ->
                    JSONObject()
                        .put("id", id)
                        .put("created_at", TimeManager.time.intTimeMS() / 1000)
                        .put("kind", "reply")
                        .put("title", "Message $id")
                        .put("body", "For the original account")
                },
            )
        val changed =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == cursor && prefs.getLong(cursor, 0) == 6L) {
                    prefs.edit { putString(Ankiquest.PREF_USER, "other") }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(changed)
        try {
            AnkiquestNotifier.onDeckCompletions(targetContext, account.notificationAccount, notices, account.scope)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(changed)
        }
        val manager = shadowOf(assertNotNull(targetContext.getSystemService<NotificationManager>()))
        assertNotNull(manager.getNotification(5_140_006))
        assertNull(manager.getNotification(5_140_007))
        assertEquals(6L, prefs.getLong(cursor, 0))
    }

    private fun assertChanged(change: Change) {
        val prefs = AnkiDroidApp.sharedPrefs()

        fun originalAccount() =
            prefs.edit {
                putString(Ankiquest.PREF_URL, "https://study.example.test")
                putString(Ankiquest.PREF_USER, "hill")
                putString(Ankiquest.PREF_TOKEN, "hill-token")
            }
        originalAccount()
        val account = assertNotNull(AnkiquestHomeData.account(prefs))
        val cursor = "ankiquestCompletionCursor:${account.notificationAccount}"
        prefs.edit { putLong(cursor, 5) }
        val image = "https://study.example.test/w/card.png"
        val notices =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", 6)
                        .put("created_at", TimeManager.time.intTimeMS() / 1000)
                        .put("kind", "share")
                        .put("sender", "friend")
                        .put("title", "A friend's win")
                        .put("body", "A message for the original account")
                        .put("image", image),
                ).put(
                    JSONObject()
                        .put("id", 7)
                        .put("created_at", TimeManager.time.intTimeMS() / 1000)
                        .put("kind", "reply")
                        .put("title", "A later message")
                        .put("body", "Also for the original account"),
                )
        val picture = createBitmap(8, 8)
        every { AnkiquestShare.picture(image) } answers {
            // This callback is the synchronous image request's return boundary.
            prefs.edit {
                when (change) {
                    Change.SIGN_OUT -> remove(Ankiquest.PREF_TOKEN)
                    Change.USER -> putString(Ankiquest.PREF_USER, "other")
                    Change.SERVER -> putString(Ankiquest.PREF_URL, "https://other.example.test")
                    Change.TOKEN -> putString(Ankiquest.PREF_TOKEN, "new-token")
                }
            }
            picture
        }
        AnkiquestNotifier.onDeckCompletions(targetContext, account.notificationAccount, notices, account.scope)
        val manager = shadowOf(assertNotNull(targetContext.getSystemService<NotificationManager>()))
        assertNull(manager.getNotification(5_140_006), "The old account's image must not post")
        assertNull(manager.getNotification(5_140_007), "The rest of its batch must stay pending")
        assertEquals(5L, prefs.getLong(cursor, 0))

        // Returning to the original account can still deliver the unconsumed entries.
        originalAccount()
        every { AnkiquestShare.picture(image) } returns picture
        AnkiquestNotifier.onDeckCompletions(targetContext, account.notificationAccount, notices, account.scope)
        assertNotNull(manager.getNotification(5_140_006))
        assertNotNull(manager.getNotification(5_140_007))
        assertEquals(7L, prefs.getLong(cursor, 0))
    }
}
