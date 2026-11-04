// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Goal
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Status
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import org.junit.After
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
class AnkiquestGoalBarTest : RobolectricTest() {
    @After
    fun restoreAnimations() {
        animatorScale(1f)
    }

    private fun animatorScale(scale: Float) {
        Settings.Global.putFloat(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private fun cards(
        studied: Int,
        target: Int,
        day: Int = 100,
    ) = Status(day, minutes = 4, streakMinutes = 10, studiedCards = studied, mode = Mode.CARDS, goal = Goal(target))

    private fun display(status: Status) = assertNotNull(AnkiquestGoalBar.display(targetContext, status, 8.0))

    @Test
    fun `the fraction stays between empty and full`() {
        assertEquals(0f, AnkiquestGoalBar.fraction(-5, 10, reached = false))
        assertEquals(0.5f, AnkiquestGoalBar.fraction(5, 10, reached = false))
        assertEquals(1f, AnkiquestGoalBar.fraction(150, 140, reached = false))
        assertEquals(0f, AnkiquestGoalBar.fraction(3, 0, reached = false))
        assertEquals(1f, AnkiquestGoalBar.fraction(0, 10, reached = true))
    }

    @Test
    fun `a card goal shows the cards done, the time left and a spoken description`() {
        val shown = display(cards(48, 140))
        val progress = targetContext.getString(R.string.aq_goal_progress_cards, 48, 140)
        assertEquals(progress, shown.label)
        assertEquals(targetContext.getString(R.string.aq_goal_time_left, 13), shown.remaining)
        assertEquals(targetContext.getString(R.string.aq_goal_daily, progress), shown.description)
        assertEquals(48f / 140, shown.fraction)
        assertFalse(shown.reached)
        assertNull(shown.detail)
    }

    @Test
    fun `a minute goal counts the minutes left`() {
        val shown = display(Status(100, 12, 10, 30, Mode.MINUTES, Goal(20)))
        assertEquals(targetContext.getString(R.string.aq_goal_progress_minutes, 12, 20), shown.label)
        assertEquals(targetContext.getString(R.string.aq_goal_time_left, 8), shown.remaining)
    }

    @Test
    fun `studying past the target shows the goal reached and full`() {
        val shown = display(cards(150, 140))
        assertTrue(shown.reached)
        assertEquals(1f, shown.fraction)
        assertEquals(targetContext.getString(R.string.aq_goal_done_title), shown.label)
        assertEquals(targetContext.getString(R.string.aq_goal_done_title), shown.description)
        assertNull(shown.remaining)
    }

    @Test
    fun `a backlog goal explains how it is made up and an off goal shows no bar`() {
        val backlog = display(Status(100, 3, 0, 4, Mode.BACKLOG, Goal(30, backlog = 50, extra = 5, daysLeft = 10)))
        assertEquals(targetContext.getString(R.string.aq_goal_backlog_explained, 5, 50, 10), backlog.detail)
        assertNull(AnkiquestGoalBar.display(targetContext, Status(100, 3, 0, 4, Mode.OFF, Goal(0)), 8.0))
    }

    @Test
    fun `without animations the bar shows the final value and gold at once`() {
        animatorScale(0f)
        assertFalse(AnkiquestGoalBar.animationsEnabled(targetContext))
        val bar = AnkiquestGoalBar(targetContext)
        bar.show(display(cards(84, 140)), "test")
        assertEquals(0.6f, bar.shownFraction, 0.001f)
        assertEquals(2, bar.passedMilestones)
        assertFalse(bar.gold)
        val progress = targetContext.getString(R.string.aq_goal_progress_cards, 84, 140)
        assertEquals(targetContext.getString(R.string.aq_goal_daily, progress), bar.contentDescription)

        bar.show(display(cards(140, 140)), "test")
        assertEquals(1f, bar.shownFraction)
        assertTrue(bar.gold)
        assertEquals(3, bar.passedMilestones)
    }

    @Test
    fun `the fill animates from the value shown earlier the same day`() {
        val first = AnkiquestGoalBar(targetContext)
        first.show(display(cards(28, 140)), "test")
        assertEquals(0f, first.shownFraction)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(0.2f, first.shownFraction, 0.001f)

        val again = AnkiquestGoalBar(targetContext)
        again.show(display(cards(70, 140)), "test")
        assertEquals(0.2f, again.shownFraction, 0.001f)
        assertEquals(0.5f, again.fraction, 0.001f)

        val tomorrow = AnkiquestGoalBar(targetContext)
        tomorrow.show(display(cards(70, 140, day = 101)), "test")
        assertEquals(0f, tomorrow.shownFraction)
    }

    @Test
    fun `reaching the goal turns the bar gold once the celebration ends`() {
        targetContext.sharedPrefs().edit { putString(AnkiquestGoalBar.PREF_SHOWN_PREFIX + "test", "100:0.9:0") }
        val bar = AnkiquestGoalBar(targetContext)
        bar.show(display(cards(140, 140)), "test")
        assertFalse(bar.gold)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(bar.gold)
        assertEquals(1f, bar.shownFraction)

        val later = AnkiquestGoalBar(targetContext)
        later.show(display(cards(140, 140)), "test")
        assertTrue(later.gold)
    }

    @Test
    fun `the streak chip counts minutes until today is safe`() {
        val counting = AnkiquestStreakChip(targetContext).apply { show(Status(100, 7, 10, 20, Mode.CARDS, Goal(100))) }
        assertEquals(targetContext.getString(R.string.aq_goal_streak_minutes, 7, 10), counting.label.text.toString())
        assertEquals(0.7f, counting.ring.fraction, 0.001f)
        assertFalse(counting.ring.safe)
        assertTrue(counting.contentDescription.startsWith(targetContext.getString(R.string.aq_goal_minimum_minutes, 10)))

        val safe = AnkiquestStreakChip(targetContext).apply { show(Status(100, 12, 10, 20, Mode.CARDS, Goal(100))) }
        assertEquals(targetContext.getString(R.string.aq_goal_streak_safe), safe.label.text.toString())
        assertTrue(safe.ring.safe)
        assertEquals(targetContext.getString(R.string.aq_goal_minimum_minutes_done, 10), safe.contentDescription)

        val any = AnkiquestStreakChip(targetContext).apply { show(Status(100, 0, 0, 0, Mode.OFF, Goal(0))) }
        assertEquals(targetContext.getString(R.string.aq_goal_streak_any), any.label.text.toString())
        assertEquals(targetContext.getString(R.string.aq_goal_minimum_any), any.contentDescription)

        val anyDone = AnkiquestStreakChip(targetContext).apply { show(Status(100, 0, 0, 1, Mode.OFF, Goal(0))) }
        assertEquals(targetContext.getString(R.string.aq_goal_minimum_any_done), anyDone.contentDescription)
    }

    @Test
    fun `the deck list shows a tappable goal line and hides it without a goal`() {
        animatorScale(0f)
        val parent = LinearLayout(targetContext).apply { addView(LinearLayout(targetContext)) }
        assertNull(AnkiquestDeckPickerHooks.showGoal(parent, Status(100, 3, 0, 4, Mode.OFF, Goal(0))))
        assertEquals(1, parent.childCount)

        var opened = 0
        val line = assertNotNull(AnkiquestDeckPickerHooks.showGoal(parent, cards(48, 140)) { opened++ })
        assertSame(line, parent.getChildAt(0))
        assertTrue(line.isVisible)
        assertEquals(targetContext.getString(R.string.aq_goal_progress_cards, 48, 140), line.label.text.toString())
        assertEquals(48f / 140, line.bar.shownFraction, 0.001f)
        line.performClick()
        assertEquals(1, opened)

        assertNull(AnkiquestDeckPickerHooks.showGoal(parent, null))
        assertFalse(line.isVisible)

        assertSame(line, AnkiquestDeckPickerHooks.showGoal(parent, cards(60, 140)))
        assertTrue(line.isVisible)
        assertEquals(2, parent.childCount)
    }
}
