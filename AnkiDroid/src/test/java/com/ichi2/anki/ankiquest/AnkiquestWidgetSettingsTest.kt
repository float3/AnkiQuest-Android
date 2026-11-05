// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestWidgetSettings.Scale
import com.ichi2.anki.ankiquest.AnkiquestWidgetSettings.Who
import com.ichi2.anki.common.time.TimeManager
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestWidgetSettingsTest : RobolectricTest() {
    private val account = HomeAccount("https://anki.example.test", "member", "member-token")

    @Test
    fun `a few dp either side of the old size steps barely change the scale`() {
        // The old steps were 1x below 340 x 220 dp, 1.25x from there and 1.6x from 480 x 380 dp.
        for ((width, height) in listOf(340f to 220f, 480f to 380f, 280f to 170f)) {
            for ((dw, dh) in listOf(4f to 0f, 0f to 4f, 4f to 4f)) {
                val below = AnkiquestWidgetSettings.autoScale(width - dw, height - dh)
                val above = AnkiquestWidgetSettings.autoScale(width + dw, height + dh)
                assertTrue(abs(above - below) < 0.05f, "$width x $height: $below vs $above")
                assertTrue(above >= below, "a larger widget never draws smaller")
            }
        }
    }

    @Test
    fun `auto scale grows smoothly and stays within its bounds`() {
        var last = AnkiquestWidgetSettings.autoScale(0f, 0f)
        assertEquals(1f, last, "the smallest widget keeps the layout's own sizes")
        for (step in 1..200) {
            val scale = AnkiquestWidgetSettings.autoScale(step * 4f, step * 3f)
            assertTrue(scale >= last && scale - last < 0.03f, "step $step: $last to $scale")
            assertTrue(scale in 1f..1.6f)
            last = scale
        }
        assertEquals(1.6f, last)
    }

    @Test
    fun `auto scale reads the minimum width and height, so the orientation does not change it`() {
        val portrait = options(minWidth = 300, minHeight = 200, maxWidth = 500, maxHeight = 330)
        val other = options(minWidth = 300, minHeight = 200, maxWidth = 700, maxHeight = 900)
        assertEquals(AnkiquestWidgetSettings.autoScale(300f, 200f), AnkiquestWidgetSettings.autoScale(portrait))
        assertEquals(AnkiquestWidgetSettings.autoScale(portrait), AnkiquestWidgetSettings.autoScale(other))
    }

    @Test
    fun `a fixed scale ignores the widget's size`() {
        val big = options(minWidth = 600, minHeight = 500)
        assertEquals(1.6f, AnkiquestWidgetSettings(scale = Scale.AUTO).scale(big))
        assertEquals(0.9f, AnkiquestWidgetSettings(scale = Scale.SMALL).scale(big))
        assertEquals(1f, AnkiquestWidgetSettings(scale = Scale.MEDIUM).scale(big))
        assertEquals(1.25f, AnkiquestWidgetSettings(scale = Scale.LARGE).scale(Bundle()))
        assertEquals(1.6f, AnkiquestWidgetSettings(scale = Scale.EXTRA_LARGE).scale(Bundle()))
    }

    @Test
    fun `settings are saved per widget and removed with it`() {
        val group = AnkiquestWidgetSettings(Who.GROUP, 7, "Study group", "day", Scale.LARGE)
        val global = AnkiquestWidgetSettings(Who.GLOBAL, period = "month", scale = Scale.SMALL)
        AnkiquestWidgetSettings.save(1, group)
        AnkiquestWidgetSettings.save(2, global)
        AnkiDroidApp.sharedPrefs().edit { putString("${AnkiquestWidget.BOARD_KEY}:1", "{}") }

        assertEquals(group, AnkiquestWidgetSettings.of(1))
        assertEquals(global, AnkiquestWidgetSettings.of(2))
        assertEquals(AnkiquestWidgetSettings(), AnkiquestWidgetSettings.of(3), "a new widget starts from the defaults")

        AnkiquestWidget().onDeleted(targetContext, intArrayOf(1))

        assertEquals(AnkiquestWidgetSettings(), AnkiquestWidgetSettings.of(1))
        assertNull(AnkiDroidApp.sharedPrefs().getString("${AnkiquestWidget.BOARD_KEY}:1", null))
        assertEquals(global, AnkiquestWidgetSettings.of(2), "other widgets keep theirs")
    }

    @Test
    fun `a widget from before settings keeps its period until saved again`() {
        AnkiDroidApp.sharedPrefs().edit { putString("ankiquestWidgetPeriod:4", "year") }
        assertEquals(AnkiquestWidgetSettings(period = "year"), AnkiquestWidgetSettings.of(4))
        AnkiquestWidgetSettings.save(4, AnkiquestWidgetSettings(period = "hour"))
        assertNull(AnkiDroidApp.sharedPrefs().getString("ankiquestWidgetPeriod:4", null))
        assertEquals("hour", AnkiquestWidgetSettings.of(4).period)
    }

    @Test
    fun `each choice changes the request`() {
        assertFalse(AnkiquestWidgetSettings().ownBoard, "friends widgets share the circle the poll already fetches")

        val group = AnkiquestWidgetSettings(Who.GROUP, 7, "Study group", "day").leaderboardUrl(account)
        assertEquals("/api/leaderboard", group.encodedPath)
        assertEquals("group", group.queryParameter("scope"))
        assertEquals("7", group.queryParameter("group"))
        assertEquals("day", group.queryParameter("period"))

        val global = AnkiquestWidgetSettings(Who.GLOBAL, period = "month").leaderboardUrl(account)
        assertEquals("global", global.queryParameter("scope"))
        assertNull(global.queryParameter("group"))
        assertEquals("month", global.queryParameter("period"))

        val friends = AnkiquestWidgetSettings(period = "all").leaderboardUrl(account)
        assertEquals("friends", friends.queryParameter("scope"))
        assertEquals("all", friends.queryParameter("period"))
    }

    @Test
    fun `the header says whose standings and which timeframe`() {
        assertEquals("Friends · this week", AnkiquestWidgetSettings().header(targetContext))
        assertEquals("Study group · today", AnkiquestWidgetSettings(Who.GROUP, 7, "Study group", "day").header(targetContext))
        assertEquals("Global · this month", AnkiquestWidgetSettings(Who.GLOBAL, period = "month").header(targetContext))

        val views =
            AnkiquestWidget
                .layout(
                    targetContext,
                    JSONArray(),
                    0,
                    false,
                    settings = AnkiquestWidgetSettings(Who.GROUP, 7, "Study group", "day"),
                    widgetId = 5,
                ).apply(targetContext, android.widget.FrameLayout(targetContext))
        val header = views.findViewById<TextView>(R.id.ankiquest_widget_period)
        assertEquals("Study group · today", header.text.toString())
        assertEquals("Study group · today, widget settings", header.contentDescription?.toString())
        assertTrue(header.hasOnClickListeners(), "the header opens the widget's settings")
    }

    @Test
    fun `the header opens this widget's settings`() {
        val intent = shadowOf(AnkiquestWidget.settingsIntent(targetContext, 9)).savedIntent
        assertEquals(AnkiquestWidgetConfig::class.java.name, intent.component?.className)
        assertEquals(9, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
        assertTrue(
            AnkiquestWidget.settingsIntent(targetContext, 9) != AnkiquestWidget.settingsIntent(targetContext, 10),
            "each widget opens its own settings",
        )
    }

    @Test
    fun `each scale choice sets the placed widget's text size`() {
        val manager = AppWidgetManager.getInstance(targetContext)
        val id = shadowOf(manager).createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)
        manager.updateAppWidgetOptions(id, options(minWidth = 600, minHeight = 500))
        val title = { shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.ankiquest_widget_title).textSize }
        val sp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, targetContext.resources.displayMetrics) }
        for (scale in Scale.entries) {
            AnkiquestWidgetSettings.save(id, AnkiquestWidgetSettings(scale = scale))
            AnkiquestWidget.render(targetContext, board(), TimeManager.time.intTimeMS(), offline = false)
            assertEquals(sp(15f * (scale.factor ?: 1.6f)), title(), scale.name)
        }
    }

    @Test
    fun `older servers get a group's standings from the circle`() {
        val overview =
            AnkiquestSocial.Overview(
                friends = emptyList(),
                incoming = emptyList(),
                outgoing = emptyList(),
                muted = emptyList(),
                groups =
                    listOf(
                        AnkiquestSocial.Group(7, "Study group", false, null, listOf(AnkiquestSocial.Person("ana", "Ana"))),
                        AnkiquestSocial.Group(8, "Other", false, null, listOf(AnkiquestSocial.Person("bo", "Bo"))),
                    ),
                public = false,
            )
        val names = { rows: JSONArray -> rows.objects().map { it.getString("user") } }
        assertEquals(listOf("member", "ana"), names(AnkiquestWidget.groupBoard(board(), overview, 7, "member")))
        assertEquals(listOf("member"), names(AnkiquestWidget.groupBoard(board(), overview, 99, "member")), "a group left since")
    }

    @Test
    fun `the settings screen saves the choices for its widget`() {
        AnkiquestWidgetSettings.save(3, AnkiquestWidgetSettings(period = "month"))
        val intent = Intent(targetContext, AnkiquestWidgetConfig::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 3)
        val controller = Robolectric.buildActivity(AnkiquestWidgetConfig::class.java, intent).setup()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode, "leaving without saving cancels placing it")

        val who = activity.findViewById<RadioGroup>(R.id.aq_widget_settings_who)
        assertEquals(listOf("FRIENDS"), who.tags(), "without an account only friends are offered")
        assertEquals("month", activity.findViewById<RadioGroup>(R.id.aq_widget_settings_period).checkedTag())
        assertEquals("AUTO", activity.findViewById<RadioGroup>(R.id.aq_widget_settings_scale).checkedTag())

        activity.findViewById<RadioGroup>(R.id.aq_widget_settings_period).click("hour")
        activity.findViewById<RadioGroup>(R.id.aq_widget_settings_scale).click("EXTRA_LARGE")
        activity.findViewById<android.view.View>(R.id.aq_widget_settings_save).performClick()

        assertEquals(AnkiquestWidgetSettings(period = "hour", scale = Scale.EXTRA_LARGE), AnkiquestWidgetSettings.of(3))
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(3, shadowOf(activity).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the settings screen keeps a saved group on offer`() {
        AnkiquestWidgetSettings.save(6, AnkiquestWidgetSettings(Who.GROUP, 7, "Study group"))
        val intent = Intent(targetContext, AnkiquestWidgetConfig::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 6)
        val controller = Robolectric.buildActivity(AnkiquestWidgetConfig::class.java, intent).setup()
        saveControllerForCleanup(controller)
        val who = controller.get().findViewById<RadioGroup>(R.id.aq_widget_settings_who)
        assertEquals(listOf("FRIENDS", "group:7"), who.tags())
        assertEquals("group:7", who.checkedTag())
        assertEquals("Study group", assertNotNull(who.findViewWithTag<RadioButton>("group:7")).text.toString())
    }

    @Test
    fun `global rows keep the server's places and their period xp`() {
        val rows =
            JSONArray(
                listOf(
                    JSONObject()
                        .put("rank", 1)
                        .put("user", "a")
                        .put("display", "A")
                        .put("level", 3)
                        .put("xp", 900)
                        .put("period", "week"),
                    JSONObject()
                        .put("rank", 2)
                        .put("user", "b")
                        .put("display", "B")
                        .put("level", 2)
                        .put("xp", 800)
                        .put("period", "week"),
                    JSONObject()
                        .put("rank", 250)
                        .put("user", "member")
                        .put("display", "Me")
                        .put("level", 1)
                        .put("xp", 5)
                        .put("me", true)
                        .put("shown", false),
                ),
            )
        val ordered = AnkiquestWidget.forPeriod(rows, "week")
        assertEquals(listOf("a", "b", "member"), ordered.objects().map { it.getString("user") })
        assertEquals(900, ordered.getJSONObject(0).getLong("xp"), "strangers have no periods, only the asked period's xp")
        val own =
            AnkiquestWidget
                .collection(targetContext, ordered)
                .getItemView(2)
                .apply(targetContext, android.widget.FrameLayout(targetContext))
        assertEquals("250", own.findViewById<TextView>(R.id.ankiquest_widget_rank).text.toString())
        assertEquals("5", own.findViewById<TextView>(R.id.ankiquest_widget_xp).text.toString())
    }

    @Test
    fun `a server with a global board is asked for each widget's own standings`() {
        withServer(decks = """{"decks":[],"global_leaderboard":false}""") { requests ->
            val manager = AppWidgetManager.getInstance(targetContext)
            val global = shadowOf(manager).createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)
            val group = shadowOf(manager).createWidget(AnkiquestTransparentWidget::class.java, R.layout.widget_ankiquest_transparent)
            val friends = shadowOf(manager).createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)
            AnkiquestWidgetSettings.save(global, AnkiquestWidgetSettings(Who.GLOBAL, period = "month"))
            AnkiquestWidgetSettings.save(group, AnkiquestWidgetSettings(Who.GROUP, 7, "Study group", "day"))
            AnkiquestWidget.render(targetContext, board(), TimeManager.time.intTimeMS(), offline = false)

            runBlocking { AnkiquestWidget.refreshOwnBoards(targetContext, board()) }

            val boards = requests.filter { it.startsWith("/api/leaderboard") }.toSet()
            assertEquals(
                setOf("/api/leaderboard?scope=global&period=month", "/api/leaderboard?scope=group&group=7&period=day"),
                boards,
                "friends widgets ask nothing more",
            )
            val title = { id: Int ->
                shadowOf(manager)
                    .getViewFor(id)
                    .findViewById<TextView>(R.id.ankiquest_widget_period)
                    .text
                    .toString()
            }
            assertEquals("Global · this month", title(global))
            assertEquals("Study group · today", title(group))
            assertEquals("Friends · this week", title(friends))
            val list = shadowOf(manager).getViewFor(global).findViewById<android.view.View>(R.id.ankiquest_widget_list)
            assertEquals(android.view.View.VISIBLE, list.visibility)
        }
    }

    @Test
    fun `a server without a global board says so and keeps a group from the circle`() {
        withServer(decks = """{"decks":[]}""") { requests ->
            val manager = AppWidgetManager.getInstance(targetContext)
            val global = shadowOf(manager).createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)
            val group = shadowOf(manager).createWidget(AnkiquestWidget::class.java, R.layout.widget_ankiquest)
            AnkiquestWidgetSettings.save(global, AnkiquestWidgetSettings(Who.GLOBAL))
            AnkiquestWidgetSettings.save(group, AnkiquestWidgetSettings(Who.GROUP, 7, "Study group"))
            AnkiquestWidget.render(targetContext, board(), TimeManager.time.intTimeMS(), offline = false)

            runBlocking { AnkiquestWidget.refreshOwnBoards(targetContext, board()) }

            assertTrue(requests.none { it.startsWith("/api/leaderboard") }, "$requests")
            val status = shadowOf(manager).getViewFor(global).findViewById<TextView>(R.id.ankiquest_widget_status)
            assertEquals("This server has no global leaderboard yet", status.text.toString())
            assertEquals(android.view.View.VISIBLE, status.visibility)
            val stored = JSONObject(assertNotNull(AnkiDroidApp.sharedPrefs().getString("${AnkiquestWidget.BOARD_KEY}:$group", null)))
            assertEquals(listOf("member", "ana"), stored.getJSONArray("board").objects().map { it.getString("user") })
        }
    }

    /** Signs in to a local server answering the deck settings with [decks], and hands the paths and queries it was asked. */
    private fun withServer(
        decks: String,
        block: (List<String>) -> Unit,
    ) {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""))
            val body =
                when {
                    path.startsWith("/api/decks/") -> decks
                    path.startsWith("/api/social/") ->
                        """{"groups":[{"id":7,"name":"Study group","members":[{"user":"ana","display":"Ana"}]}]}"""
                    path == "/api/leaderboard" -> board().toString()
                    else -> "{}"
                }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        // Placing a widget would start a poll in the background, drawing the widgets meanwhile.
        mockkObject(AnkiquestPoll)
        every { AnkiquestPoll.refreshNow(any()) } just Runs
        every { AnkiquestPoll.schedule(any()) } just Runs
        try {
            AnkiDroidApp.sharedPrefs().edit {
                putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
                putString(Ankiquest.PREF_USER, "member")
                putString(Ankiquest.PREF_TOKEN, "member-token")
            }
            block(requests)
        } finally {
            unmockkObject(AnkiquestPoll)
            server.stop(0)
        }
    }

    private fun RadioGroup.tags(): List<String> = (0 until childCount).map { getChildAt(it).tag as String }

    private fun RadioGroup.checkedTag(): String? = findViewById<RadioButton>(checkedRadioButtonId)?.tag as String?

    private fun RadioGroup.click(tag: String) = assertNotNull(findViewWithTag<RadioButton>(tag)).performClick()

    private fun board(): JSONArray =
        JSONArray(
            listOf("member", "ana", "bo").mapIndexed { i, user ->
                JSONObject()
                    .put("user", user)
                    .put("display", user)
                    .put("level", 1)
                    .put("week_xp", 300 - i * 100)
            },
        )

    private fun options(
        minWidth: Int,
        minHeight: Int,
        maxWidth: Int = minWidth,
        maxHeight: Int = minHeight,
    ) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight)
    }
}
