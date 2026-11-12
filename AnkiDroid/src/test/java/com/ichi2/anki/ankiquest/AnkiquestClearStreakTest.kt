// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Style
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The optional picker entry keeps the card's data, ownership and resize behavior. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 33])
class AnkiquestClearStreakTest : RobolectricTest() {
    private val now get() = TimeManager.time.intTimeMS()
    private val prefs get() = targetContext.sharedPrefs()

    @Before
    fun prepare() {
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
            putBoolean(AnkiquestIcon.PREF_ENABLED, false)
        }
        AnkiquestWidget.forgetFresh()
        mockkObject(AnkiquestPoll)
        justRun { AnkiquestPoll.schedule(any()) }
        justRun { AnkiquestPoll.refreshNow(any()) }
    }

    @After
    fun cleanup() = unmockkObject(AnkiquestPoll)

    private fun row(
        state: String,
        minutes: Int = 6,
    ): JSONObject =
        JSONObject()
            .put("user", "hill")
            .put("streak", if (state == "none") 0 else 9)
            .put("streak_state", state)
            .put("today_reviews", 12)
            .put("today_minutes", minutes)
            .put("streak_minutes", 10)
            .put("day_ends_at", if (state == "unknown") now else now + 3_600_000)

    private fun render(
        board: JSONArray?,
        size: Size,
        style: Style,
    ): View =
        AnkiquestStreakWidget
            .views(targetContext, board, "hill", size, now, style)
            .apply(targetContext, FrameLayout(targetContext))

    @Test
    fun `all sizes keep the card's content and progress with shadowed text`() {
        for (state in listOf("pending", "studied", "protected", "none", "unknown")) {
            for (size in Size.entries) {
                val board = JSONArray().put(row(state))
                val card = render(board, size, Style.CARD)
                val clear = render(board, size, Style.TRANSPARENT)
                assertNotNull(card.background)
                assertNull(clear.background, "transparent means no background card")
                assertEquals(card.contentDescription, clear.contentDescription)
                val number = clear.findViewById<TextView>(R.id.ankiquest_streak_number)
                assertEquals(card.findViewById<TextView>(number.id).text, number.text)
                assertEquals(Color.WHITE, number.currentTextColor)
                assertTrue(number.shadowRadius > 0)
                assertTrue(Color.alpha(number.shadowColor) > 0)
                val meter = clear.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
                val original = card.findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
                assertEquals(original.visibility, meter.visibility)
                assertEquals(original.progress, meter.progress)
                assertEquals(original.max, meter.max)
                if (meter.isVisible) {
                    assertNotNull(meter.background, "progress needs a contrasting outline")
                    assertTrue(meter.paddingTop > 0)
                }
            }
        }
    }

    @Test
    fun `render updates the transparent widget when no card is placed`() {
        val manager = AppWidgetManager.getInstance(targetContext)
        val shadow = shadowOf(manager)
        val clear = shadow.createWidget(AnkiquestClearStreakWidget::class.java, R.layout.widget_ankiquest_streak_clear)
        AnkiquestStreakWidget.render(targetContext, JSONArray().put(row("studied")))
        assertEquals(
            "9",
            shadow
                .getViewFor(clear)
                .findViewById<TextView>(R.id.ankiquest_streak_number)
                .text
                .toString(),
        )
        assertNull(shadow.getViewFor(clear).background)

        val card = shadow.createWidget(AnkiquestStreakWidget::class.java, R.layout.widget_ankiquest_streak)
        AnkiquestStreakWidget.render(targetContext, JSONArray().put(row("studied").put("streak", 30)))
        for (id in listOf(clear, card)) {
            assertEquals(
                "30",
                shadow
                    .getViewFor(id)
                    .findViewById<TextView>(R.id.ankiquest_streak_number)
                    .text
                    .toString(),
            )
        }
        assertNotNull(shadow.getViewFor(card).background)
        assertNull(shadow.getViewFor(clear).background)
    }

    private fun rememberGoal(): JSONArray {
        val profile =
            JSONObject()
                .put("streak", 365)
                .put("streak_state", "studied")
                .put("day_ends_at", now + 3_600_000)
                .put("last_review_id", now - 60_000)
                .put(
                    "today",
                    JSONObject()
                        .put("starts_at", now - 7_200_000)
                        .put("reviews", 12)
                        .put("minutes", 10)
                        .put("time_ms", 600_000)
                        .put("streak_minutes", 10),
                ).put(
                    "goal",
                    JSONObject()
                        .put("mode", "cards")
                        .put("value", 20)
                        .put("progress", 12)
                        .put("reached", false),
                )
        AnkiquestDay.remember(profile, prefs)
        AnkiquestIcon.update(targetContext, profile, now, prefs)
        return JSONArray().put(AnkiquestWidget.ownRow(profile, null).put("user", "hill"))
    }

    @Test
    fun `a goal uses the same owned progress and clears after an account change`() {
        val board = rememberGoal()
        for (size in Size.entries) {
            val widget = render(board, size, Style.TRANSPARENT)
            val goal = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_goal)
            assertEquals(View.VISIBLE, goal.visibility)
            assertEquals(20, goal.max)
            assertEquals(12, goal.progress)
            assertNotNull(goal.background)
        }
        prefs.edit { putString(Ankiquest.PREF_TOKEN, "replacement") }
        for (size in Size.entries) {
            assertEquals(View.GONE, render(board, size, Style.TRANSPARENT).findViewById<ProgressBar>(R.id.ankiquest_streak_goal).visibility)
        }
    }

    @Test
    @Config(sdk = [27])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `legacy numbers fit with the thicker goal outline at minimum sizes`() {
        val board = rememberGoal()
        val sizes =
            listOf(Triple(Size.SMALL, 57, 57), Triple(Size.MEDIUM, 100, 90), Triple(Size.LARGE, 200, 220), Triple(Size.HUGE, 300, 380))
        for ((size, width, height) in sizes) {
            // Native font metrics start at API 26; build the Android 7 RemoteViews actions first.
            val sdk = Build.VERSION.SDK_INT
            val remote =
                try {
                    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 24)
                    AnkiquestStreakWidget.views(targetContext, board, "hill", size, now, Style.TRANSPARENT)
                } finally {
                    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
                }
            val widget = remote.apply(targetContext, FrameLayout(targetContext))
            val number = widget.findViewById<TextView>(R.id.ankiquest_streak_number)
            TextViewCompat.setAutoSizeTextTypeWithDefaults(number, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            remote.reapply(targetContext, widget)
            widget.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val density = widget.resources.displayMetrics.density
            widget.measure(
                View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            widget.layout(0, 0, widget.measuredWidth, widget.measuredHeight)
            assertTrue(number.layout.height <= number.height, "$size clips its number")
            val needed = number.paint.measureText(number.text.toString()) + number.compoundPaddingLeft + number.compoundPaddingRight
            assertTrue(needed <= number.width + 1, "$size clips its digits or flame")
        }
    }

    @Test
    fun `the picker entry declares its own layout and delegates refresh`() {
        val component = ComponentName(targetContext, AnkiquestClearStreakWidget::class.java)
        val info = targetContext.packageManager.getReceiverInfo(component, android.content.pm.PackageManager.GET_META_DATA)
        assertTrue(info.exported)
        assertEquals(R.string.ankiquest_streak_clear_label, info.labelRes)
        assertEquals(R.xml.widget_provider_ankiquest_streak_clear, info.metaData.getInt("android.appwidget.provider"))
        val manager = AppWidgetManager.getInstance(targetContext)
        val widget = AnkiquestClearStreakWidget()
        widget.onEnabled(targetContext)
        widget.onUpdate(targetContext, manager, intArrayOf(1))
        verify(exactly = 2) { AnkiquestPoll.schedule(targetContext) }
        verify(exactly = 1) { AnkiquestPoll.refreshNow(targetContext) }

        mockkObject(AnkiquestWidget)
        try {
            justRun { AnkiquestWidget.redrawStreaks(any()) }
            widget.onAppWidgetOptionsChanged(targetContext, manager, 1, Bundle())
            verify(exactly = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) 1 else 0) {
                AnkiquestWidget.redrawStreaks(targetContext)
            }
        } finally {
            unmockkObject(AnkiquestWidget)
        }
    }
}
