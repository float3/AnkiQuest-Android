// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Manifest.permission.POST_NOTIFICATIONS is API 33; this test runs on SDK 34, but lint checks against minSdk 24. */
private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

/** A notification's Share button, from the posted action to the share sheet, with the server mocked at the API layer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnkiquestNotificationShareTest : RobolectricTest() {
    private val requests = CopyOnWriteArrayList<Pair<Request, AnkiquestApi.Timeout>>()
    private val art = AtomicReference<Int?>()
    private val linked = AtomicReference<AnkiquestShare.Link?>()

    @Before
    fun prepare() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
        shadowOf(targetContext as Application).grantPermissions(POST_NOTIFICATIONS_PERMISSION)
        mockkObject(AnkiquestApi, AnkiquestShare)
        every { AnkiquestShare.textChooser(any(), any(), any(), any(), any()) } answers {
            art.set(arg(4))
            Intent(Intent.ACTION_CHOOSER)
        }
        every { AnkiquestShare.linkChooser(any(), any(), any(), any()) } answers {
            linked.set(arg(1))
            Intent(Intent.ACTION_CHOOSER)
        }
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestApi, AnkiquestShare)
    }

    /** Answers every API request with [answer], recording the deadline each one was given. */
    private fun server(answer: () -> JSONObject) {
        every { AnkiquestApi.json(any(), any(), any()) } answers {
            requests.add(arg<Request>(1) to arg(2))
            answer()
        }
    }

    private fun share(intent: Intent) {
        val controller = Robolectric.buildActivity(AnkiquestShareActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        advanceRobolectricLooperUntil { shadowOf(controller.get()).peekNextStartedActivity() != null }
    }

    @Test
    fun `a win's public link gets the standard deadline`() {
        val link =
            JSONObject()
                .put("url", "https://quest.example.test/w/token")
                .put("image", "https://quest.example.test/w/token.png")
                .put("text", "Level 54! https://quest.example.test/w/token")
        server { link }
        share(
            Intent(targetContext, AnkiquestShareActivity::class.java)
                .putExtra(AnkiquestShare.EXTRA_TITLE, "Level 54")
                .putExtra(AnkiquestShare.EXTRA_BODY, "I reached level 54.")
                .putExtra(AnkiquestShare.EXTRA_WIN, 17L)
                .putExtra(AnkiquestShare.EXTRA_SCOPE, assertNotNull(AnkiquestHomeData.account()).scope),
        )
        val (request, timeout) = requests.single { it.first.url.encodedPath == "/api/share/hill" }
        assertEquals("link", JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("to"))
        assertEquals(AnkiquestApi.Timeout.STANDARD, timeout, "the link and its text must not fall back on a slow network")
        assertEquals("https://quest.example.test/w/token", linked.get()?.url)
    }

    @Test
    fun `a won challenge is drawn with the challenge art`() {
        server { throw IOException("offline") }
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val account = assertNotNull(AnkiquestHomeData.account())
        val won =
            JSONObject()
                .put("id", 1)
                .put("created_at", TimeManager.time.intTimeMS() / 1000 - 30)
                .put("kind", "challenge_complete")
                .put("title", "Challenge won")
                .put("body", "You won the challenge against Cerro.")
                .put("win", 17)
        AnkiquestNotifier.onDeckCompletions(targetContext, account.notificationAccount, JSONArray().put(won), account.scope)
        val action =
            assertNotNull(shadowOf(manager).getNotification(5_140_001), "the challenge is notified")
                .actions
                .single()
        share(shadowOf(action.actionIntent).savedIntent)
        assertEquals(R.drawable.aki_challenge_won, art.get(), "challenge_complete must not be drawn as a celebration")
    }
}
