// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestIcon.Mood
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import kotlin.test.assertEquals

/** The icon and the widgets follow the profile each upload answers with, without waiting for the poll. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestOwnProfileTest : RobolectricTest() {
    private val hour = 3_600_000L
    private lateinit var server: HttpServer
    private val paths = mutableListOf<String>()

    @Volatile
    private var answer: JSONObject = JSONObject()

    @Before
    fun startServer() {
        mockkObject(AnkiquestPoll)
        every { AnkiquestPoll.refreshNow(any()) } just Runs
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        AnkiquestWidget.forgetFresh()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val path = exchange.requestURI.path
            synchronized(paths) { paths.add(path) }
            val body = if (path.startsWith("/api/decks/")) JSONObject().put("decks", JSONArray()) else answer
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "token")
            putBoolean(AnkiquestIcon.PREF_ENABLED, true)
        }
    }

    @After
    fun stopServer() {
        try {
            if (::server.isInitialized) server.stop(0)
        } finally {
            AnkiquestWidget.forgetFresh()
            unmockkObject(AnkiquestPoll)
            AnkiDroidApp.sharedPreferencesTestingOverride = null
        }
    }

    private fun profile(
        state: String,
        streak: Int,
        hoursLeft: Long,
        minutes: Int = 0,
    ) = JSONObject()
        .put("display", "Hill")
        .put("level", 3)
        .put("xp_total", 120)
        .put("week_xp", 40)
        .put("periods", JSONObject().put("week", 40).put("day", 10))
        .put("streak", streak)
        .put("streak_state", state)
        .put("day_ends_at", TimeManager.time.intTimeMS() + hoursLeft * hour)
        .put("today", JSONObject().put("reviews", if (state == "studied") 12 else 0).put("minutes", minutes).put("streak_minutes", 0))
        .put("lifetime", JSONObject().put("reviews", 1))

    @Test
    fun `an upload that makes the day count turns a worried icon happy without a poll`() =
        runBlocking {
            registerAliases()
            answer = profile("pending", 12, 2)
            Ankiquest.uploadPending()
            assertEquals(listOf(Mood.WORRIED), enabled(), "the last hours of an open day worry Aki")

            answer = profile("studied", 13, 2)
            Ankiquest.uploadPending()

            assertEquals(listOf(Mood.HAPPY), enabled())
            assertEquals("studied", AnkiquestIcon.lastKnown()!!.getString("streak_state"), "the next app start begins from it")
            assertEquals(0, synchronized(paths) { paths.count { it.startsWith("/api/profile") } }, "the upload's answer is enough")
            verify(exactly = 0) { AnkiquestPoll.refreshNow(any()) }
        }

    @Test
    fun `offline reviews update the icon and widget for their own account`() {
        registerAliases()
        val now = TimeManager.time.intTimeMS()
        val pending = profile("pending", 12, 2).put("last_review_id", now - 10)
        pending.getJSONObject("today").put("starts_at", now - hour).put("streak_minutes", 10)
        val scope = checkNotNull(HomeAccount.current()).scope
        AnkiquestDay.remember(pending)
        val day = checkNotNull(AnkiquestDay.Server.profile(pending))
        AnkiquestDay.rememberLocal(scope, day, listOf(AnkiquestDay.Review(now, 600_000)))

        AnkiquestIcon.showLastKnown(targetContext, now)
        assertEquals(listOf(Mood.HAPPY), enabled())
        val widget =
            AnkiquestStreakWidget
                .views(targetContext, JSONArray(), "hill", now = now)
                .apply(targetContext, FrameLayout(targetContext))
        assertEquals("13", widget.findViewById<TextView>(R.id.ankiquest_streak_number).text.toString())
        assertEquals(
            targetContext.getString(R.string.aq_goal_streak_safe_unsent),
            widget.findViewById<TextView>(R.id.ankiquest_streak_status).text.toString(),
        )

        // A new token for the same player cannot reuse the previous session's offline verdict.
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "new-token") }
        AnkiquestDay.remember(pending)
        AnkiquestIcon.showLastKnown(targetContext, now)
        assertEquals(listOf(Mood.WORRIED), enabled())
    }

    @Test
    fun `the streak widget is drawn again right after an upload`() =
        runBlocking {
            val manager = AppWidgetManager.getInstance(targetContext)
            val id = shadowOf(manager).createWidget(AnkiquestStreakWidget::class.java, R.layout.widget_ankiquest_streak)
            val row =
                JSONObject()
                    .put("user", "hill")
                    .put("display", "Hill")
                    .put("streak", 12)
                    .put("streak_state", "pending")
                    .put("today_reviews", 0)
                    .put("today_minutes", 0)
                    .put("streak_minutes", 0)
                    .put("day_ends_at", TimeManager.time.intTimeMS() + 2 * hour)
            val fetchedAt = TimeManager.time.intTimeMS() - 60_000
            AnkiquestWidget.render(targetContext, JSONArray().put(row), fetchedAt, offline = false)
            val text = { view: Int ->
                shadowOf(manager)
                    .getViewFor(id)
                    .findViewById<TextView>(view)
                    .text
                    .toString()
            }
            assertEquals("12", text(R.id.ankiquest_streak_number))
            assertEquals("Not studied yet", text(R.id.ankiquest_streak_status))

            answer = profile("studied", 13, 2)
            Ankiquest.uploadPending()

            assertEquals("13", text(R.id.ankiquest_streak_number))
            assertEquals(targetContext.getString(R.string.aq_goal_streak_safe), text(R.id.ankiquest_streak_status))
            assertEquals(
                1,
                synchronized(paths) { paths.count { it.startsWith("/api/") && !it.startsWith("/api/decks/") } },
                "only the upload",
            )
        }

    @Test
    fun `a newer poll board wins over the row from an older upload`() {
        val account = AnkiquestHomeData.account()!!
        val now = TimeManager.time.intTimeMS()
        AnkiquestWidget.onOwnProfile(targetContext, account, profile("studied", 13, 2), now)
        val row =
            JSONObject()
                .put("user", "hill")
                .put("streak", 20)
                .put("period", "week")
                .put("xp", 5)

        val older = AnkiquestWidget.withFreshRow(JSONArray().put(row), now - 1)
        assertEquals(13, older.getJSONObject(0).getInt("streak"))
        assertEquals(40, older.getJSONObject(0).getInt("xp"), "the row's period takes the profile's XP")

        val newer = AnkiquestWidget.withFreshRow(JSONArray().put(row), now + 1)
        assertEquals(20, newer.getJSONObject(0).getInt("streak"))

        val ranked = JSONArray().put(JSONObject(row.toString()).put("rank", 4))
        assertEquals(20, AnkiquestWidget.withFreshRow(ranked, now - 1).getJSONObject(0).getInt("streak"), "a ranked board keeps its rows")
    }

    /** The aliases come from the quest build's manifest, which unit tests do not merge. */
    private fun registerAliases() {
        for (mood in Mood.entries) {
            shadowOf(targetContext.packageManager).addOrUpdateActivity(
                ActivityInfo().apply {
                    packageName = targetContext.packageName
                    name = component(mood).className
                    targetActivity = "com.ichi2.anki.IntentHandler"
                    enabled = mood == Mood.DEFAULT
                },
            )
        }
    }

    private fun component(mood: Mood) = ComponentName(targetContext.packageName, "com.ichi2.anki.ankiquest.${mood.alias}")

    private fun enabled(): List<Mood> =
        Mood.entries.filter { mood ->
            val state = targetContext.packageManager.getComponentEnabledSetting(component(mood))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && mood == Mood.DEFAULT)
        }
}
