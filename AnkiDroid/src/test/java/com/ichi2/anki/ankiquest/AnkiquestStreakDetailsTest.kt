// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.TimeManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Goal and syncing evidence must belong to this account and the same unexpired study day. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 33])
class AnkiquestStreakDetailsTest : RobolectricTest() {
    private val now get() = TimeManager.time.intTimeMS()
    private val prefs get() = targetContext.sharedPrefs()

    @Before
    fun prepareAccount() {
        prefs.edit {
            putString(Ankiquest.PREF_URL, "https://study.example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
            putBoolean(AnkiquestIcon.PREF_ENABLED, false)
        }
        AnkiquestWidget.forgetFresh()
    }

    private fun profile(state: String = "studied"): JSONObject =
        JSONObject()
            .put("streak", 9)
            .put("streak_state", state)
            .put("day_ends_at", now + HOUR)
            .put("last_review_id", now - HOUR)
            .put(
                "today",
                JSONObject()
                    .put("starts_at", now - 2 * HOUR)
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

    private fun remember(profile: JSONObject) {
        AnkiquestDay.remember(profile, prefs)
        AnkiquestIcon.update(targetContext, profile, now, prefs)
    }

    private fun render(
        profile: JSONObject,
        size: AnkiquestStreakWidget.Size = AnkiquestStreakWidget.Size.MEDIUM,
    ): View {
        val user = assertNotNull(Ankiquest.player())
        val row = AnkiquestWidget.ownRow(profile, null).put("user", user)
        return AnkiquestStreakWidget
            .views(
                targetContext,
                JSONArray().put(row),
                user,
                size,
                now,
            ).apply(targetContext, FrameLayout(targetContext))
    }

    @Test
    fun `after the minimum every size shows the daily goal's progress`() {
        val profile = profile()
        remember(profile)
        for (size in AnkiquestStreakWidget.Size.entries) {
            val widget = render(profile, size)
            val meters =
                widget.allViews
                    .filterIsInstance<ProgressBar>()
                    .filter { it.isVisible }
                    .toList()
            assertEquals(listOf(10 to 10, 20 to 12), meters.map { it.max to it.progress }, size.name)
            assertContains(widget.contentDescription.toString(), "12 of 20 cards", message = size.name)
        }
    }

    @Test
    fun `a reached backlog goal is full without inventing a card count`() {
        val profile = profile()
        profile.put(
            "goal",
            JSONObject()
                .put("mode", "backlog")
                .put("value", 14)
                .put("progress", JSONObject.NULL)
                .put("reached", true),
        )
        remember(profile)
        val meters =
            render(profile)
                .allViews
                .filterIsInstance<ProgressBar>()
                .filter { it.isVisible }
                .toList()
        assertEquals(2, meters.size)
        assertEquals(meters.last().max, meters.last().progress)
        assertTrue(meters.last().max > 0)
    }

    @Test
    fun `goal progress stays hidden before the minimum and for an off goal`() {
        val profile = profile("pending")
        profile.getJSONObject("today").put("time_ms", 360_000).put("minutes", 6)
        remember(profile)
        assertEquals(1, render(profile).allViews.filterIsInstance<ProgressBar>().count { it.isVisible })
        profile.put("streak_state", "studied").put("goal", JSONObject().put("mode", "off"))
        remember(profile)
        assertEquals(1, render(profile).allViews.filterIsInstance<ProgressBar>().count { it.isVisible })
    }

    @Test
    fun `another account or a new day cannot reuse cached goal progress`() {
        val profile = profile()
        remember(profile)
        prefs.edit { putString(Ankiquest.PREF_USER, "other") }
        assertEquals(1, render(profile).allViews.filterIsInstance<ProgressBar>().count { it.isVisible })
        prefs.edit { putString(Ankiquest.PREF_USER, "hill") }
        profile.put("day_ends_at", now + 2 * HOUR)
        AnkiquestDay.remember(profile, prefs)
        assertEquals(1, render(profile).allViews.filterIsInstance<ProgressBar>().count { it.isVisible })
    }

    @Test
    fun `local progress is lighter until an upload confirms it`() {
        val profile = profile("pending")
        profile.getJSONObject("today").put("time_ms", 360_000).put("minutes", 6)
        remember(profile)
        val server = assertNotNull(AnkiquestDay.Server.profile(profile))
        AnkiquestDay.rememberLocal(
            assertNotNull(HomeAccount.current(prefs)).scope,
            server,
            listOf(AnkiquestDay.Review(now - 1, 240_000)),
            prefs,
        )
        val waiting = render(profile).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
        assertEquals(10, waiting.progress)
        assertTrue(waiting.alpha in 0f..<1f, "local progress must look different while syncing")
        profile.put("streak_state", "studied").put("last_review_id", now - 1)
        profile.getJSONObject("today").put("minutes", 10).put("time_ms", 600_000)
        remember(profile)
        val confirmed = render(profile).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
        assertEquals(1f, confirmed.alpha)
    }

    @Test
    fun `the meter distinguishes safe at-risk protected and no-streak states`() {
        val colors = mutableListOf<Int>()
        val tracks = mutableListOf<Int>()
        for (state in listOf("studied", "pending", "protected", "none")) {
            val profile = profile(if (state == "none") "pending" else state)
            if (state != "studied") {
                profile
                    .getJSONObject("today")
                    .put("reviews", 0)
                    .put("minutes", 0)
                    .put("time_ms", 0)
            }
            if (state == "none") profile.put("streak", 0)
            remember(profile)
            val meter = render(profile).findViewById<ProgressBar>(R.id.ankiquest_streak_progress)
            colors.add(assertNotNull(meter.progressTintList, state).defaultColor)
            tracks.add(assertNotNull(meter.progressBackgroundTintList, state).defaultColor)
        }
        assertEquals(4, colors.toSet().size, "each state needs its own tone")
        assertEquals(4, tracks.toSet().size, "state remains visible at zero progress")
        assertTrue(Color.red(colors[0]) > Color.blue(colors[0]), "safe today is warm")
        assertTrue(Color.blue(colors[2]) > Color.red(colors[2]), "protected is blue")
    }

    @Test
    fun `a minutes goal clamps progress and names its unit`() {
        val profile = profile()
        profile
            .getJSONObject("goal")
            .put("mode", "minutes")
            .put("value", 15)
            .put("progress", 22)
        remember(profile)
        val widget = render(profile)
        val goal = widget.findViewById<ProgressBar>(R.id.ankiquest_streak_goal)
        assertEquals(15, goal.progress)
        assertEquals(15, goal.max)
        assertContains(widget.contentDescription.toString(), "15 of 15 minutes")
    }

    @Test
    fun `unknown progress or an expired study day never draws a goal`() {
        val profile = profile()
        for (goal in listOf(
            JSONObject().put("mode", "cards").put("value", 20).put("progress", JSONObject.NULL),
            JSONObject().put("mode", "cards").put("value", 0).put("progress", 2),
            JSONObject().put("mode", "backlog").put("reached", false),
        )) {
            profile.put("goal", goal)
            remember(profile)
            assertEquals(View.GONE, render(profile).findViewById<ProgressBar>(R.id.ankiquest_streak_goal).visibility)
        }
        val stale = profile().put("day_ends_at", now)
        remember(stale)
        assertEquals(View.GONE, render(stale).findViewById<ProgressBar>(R.id.ankiquest_streak_goal).visibility)
    }

    @Test
    fun `reapplying stale data clears both meters and their description`() {
        val profile = profile()
        remember(profile)
        val widget = render(profile)
        assertEquals(View.VISIBLE, widget.findViewById<ProgressBar>(R.id.ankiquest_streak_goal).visibility)
        profile.put("day_ends_at", now)
        remember(profile)
        val user = assertNotNull(Ankiquest.player())
        AnkiquestStreakWidget
            .views(
                targetContext,
                JSONArray().put(AnkiquestWidget.ownRow(profile, null).put("user", user)),
                user,
                AnkiquestStreakWidget.Size.MEDIUM,
                now,
            ).reapply(targetContext, widget)
        assertEquals(View.GONE, widget.findViewById<ProgressBar>(R.id.ankiquest_streak_goal).visibility)
        assertEquals(View.GONE, widget.findViewById<ProgressBar>(R.id.ankiquest_streak_progress).visibility)
        assertTrue("of 20 cards" !in widget.contentDescription.toString())
    }

    @Test
    fun `a goal-only change redraws placed streak widgets immediately`() {
        mockkObject(AnkiquestPoll)
        every { AnkiquestPoll.schedule(any()) } just Runs
        every { AnkiquestPoll.refreshNow(any()) } just Runs
        try {
            val manager = AppWidgetManager.getInstance(targetContext)
            val id = shadowOf(manager).createWidget(AnkiquestStreakWidget::class.java, R.layout.widget_ankiquest_streak)
            val profile = profile()
            val account = assertNotNull(HomeAccount.current(prefs))
            remember(profile)
            AnkiquestWidget.onOwnProfile(targetContext, account, profile, now)
            assertEquals(12, shadowOf(manager).getViewFor(id).findViewById<ProgressBar>(R.id.ankiquest_streak_goal).progress)

            val later = JSONObject(profile.toString())
            later.getJSONObject("goal").put("progress", 14)
            remember(later)
            AnkiquestWidget.onOwnProfile(targetContext, account, later, now + 1)
            assertEquals(14, shadowOf(manager).getViewFor(id).findViewById<ProgressBar>(R.id.ankiquest_streak_goal).progress)
        } finally {
            unmockkObject(AnkiquestPoll)
        }
    }

    @Test
    // Native font metrics start at API 26. Exercise the pre-O RemoteViews path with native fonts here.
    @Config(sdk = [27])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the compact widget keeps the complete pending-upload status visible`() {
        val profile = profile("pending")
        profile.getJSONObject("today").put("time_ms", 360_000).put("minutes", 6)
        remember(profile)
        AnkiquestDay.rememberLocal(
            assertNotNull(HomeAccount.current(prefs)).scope,
            assertNotNull(AnkiquestDay.Server.profile(profile)),
            listOf(AnkiquestDay.Review(now - 1, 240_000)),
            prefs,
        )
        for (tag in listOf("en", "de", "es", "fr", "pt")) {
            prefs.edit { putString("language", tag) }
            val oldSdk = Build.VERSION.SDK_INT
            val remote =
                try {
                    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 24)
                    AnkiquestStreakWidget.views(
                        targetContext,
                        JSONArray().put(AnkiquestWidget.ownRow(profile, null).put("user", "hill")),
                        "hill",
                        AnkiquestStreakWidget.Size.MEDIUM,
                        now,
                    )
                } finally {
                    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", oldSdk)
                }
            val widget = remote.apply(targetContext, FrameLayout(targetContext))
            // Android 7 has no native autosizing; only our RemoteViews text sizes should fit these views.
            val status = widget.findViewById<TextView>(R.id.ankiquest_streak_status)
            TextViewCompat.setAutoSizeTextTypeWithDefaults(status, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            TextViewCompat.setAutoSizeTextTypeWithDefaults(
                widget.findViewById<TextView>(R.id.ankiquest_streak_number),
                TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE,
            )
            remote.reapply(targetContext, widget)
            widget.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val density = widget.resources.displayMetrics.density
            widget.measure(
                View.MeasureSpec.makeMeasureSpec((100 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((90 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            widget.layout(0, 0, widget.measuredWidth, widget.measuredHeight)
            val lastLine = minOf(status.maxLines, status.layout.lineCount) - 1
            assertEquals(status.text.length, status.layout.getLineEnd(lastLine), tag)
            assertTrue(status.layout.height <= status.height, "$tag: ${status.layout.height}px of text in ${status.height}px")
            assertEquals(
                AnkiquestLanguage.context(targetContext).getString(R.string.aq_goal_streak_safe_unsent),
                status.text.toString(),
                tag,
            )
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
    }
}
