// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMoments.Kind
import com.ichi2.anki.ankiquest.AnkiquestMoments.Moment
import com.ichi2.anki.ankiquest.AnkiquestMoments.Route
import com.ichi2.anki.common.preferences.sharedPrefs
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMomentsTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val account = identity.notificationAccount
    private val prefs get() = targetContext.sharedPrefs()

    @Before
    fun signIn() {
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } returns identity
    }

    @After
    fun signOut() {
        unmockkObject(AnkiquestHomeData)
    }

    private fun moment(
        key: String,
        kind: Kind,
        at: Long = NOW,
    ) = Moment(key = key, kind = kind, title = "title $key", detail = "detail $key", at = at)

    private fun conquest(
        at: Long,
        lapses: Int = 4,
        leech: Boolean = false,
    ) = JSONObject()
        .put("at", at)
        .put("lapses", lapses)
        .put("answers", 11)
        .put("since", at - DAY)
        .put("leech", leech)

    private fun fresh(
        vararg conquests: JSONObject,
        owner: String = account,
    ) = AnkiquestMoments.freshConquests(prefs, owner, JSONArray(conquests.toList()), NOW).map { it.getLong("at") }

    private fun celebration(
        id: Long,
        topic: String?,
        moment: String? = null,
        quiet: Boolean = false,
    ) = JSONObject()
        .put("id", id)
        .put("kind", "celebration")
        .put("title", "Level 4!")
        .put("body", "You reached level 4.")
        .put("created_at", NOW / 1000)
        .put("win", 70 + id)
        .putOpt("topic", topic)
        .putOpt("moment", moment)
        .put("quiet", quiet)

    /** A screen that is not a resting one; it opens signed out, so it asks the server nothing. */
    private fun host() = hostController().get()

    private fun hostController(): org.robolectric.android.controller.ActivityController<AnkiquestConquestActivity> {
        every { AnkiquestHomeData.account(any()) } returns null
        val controller =
            startActivityControllerNormallyOpenCollectionWithIntent(
                AnkiquestConquestActivity::class.java,
                AnkiquestConquestActivity.intent(targetContext, "hill", 1),
            )
        every { AnkiquestHomeData.account(any()) } returns identity
        return controller
    }

    @Test
    fun `pausing preserves the displayed moment until the player dismisses it`() {
        val controller = hostController()
        val host = controller.get()
        val moment = moment("level:4", Kind.LEVEL)
        AnkiquestMoments.offer(prefs, account, listOf(moment))
        AnkiquestMoments.present(host, prefs, resting = true)
        advanceRobolectricLooper()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog())
        assertTrue(AnkiquestMoments.pending(prefs, account).isEmpty())

        controller.pause()
        assertTrue(dialog.isShowing, "A pause must not discard a moment already consumed from the queue")
        controller.resume()
        AnkiquestMoments.present(host, prefs, resting = true)
        assertSame(dialog, ShadowDialog.getLatestDialog())
        dialog.dismiss()
        assertTrue(AnkiquestPostStudyPrompts.ready(host))
    }

    @Test
    fun `dismissed prompts do not retain lifecycle observers`() {
        val host = host()
        val lifecycle = host.lifecycle as LifecycleRegistry
        val before = lifecycle.observerCount
        repeat(3) {
            val dialog = AlertDialog.Builder(host).setMessage("Prompt").show()
            AnkiquestPostStudyPrompts.track(dialog)
            dialog.dismiss()
            advanceRobolectricLooper()
        }
        assertEquals(before, lifecycle.observerCount)
    }

    @Test
    fun `cards already there on the first look are never celebrated`() {
        assertEquals(emptyList(), fresh(conquest(NOW - 1000)))
        assertEquals(emptyList(), fresh(conquest(NOW - 1000)))
    }

    @Test
    fun `every newly conquered card is fresh once, and old ones never`() {
        assertEquals(emptyList(), fresh(conquest(NOW - 5 * DAY)))
        assertEquals(listOf(NOW - 10, NOW - 1000), fresh(conquest(NOW - 1000), conquest(NOW - 10), conquest(NOW - 5 * DAY)))
        assertEquals(emptyList(), fresh(conquest(NOW - 1000), conquest(NOW - 10)), "each card once")
        assertEquals(emptyList(), fresh(conquest(NOW - 3 * DAY), conquest(NOW - 10)), "learned of days late")
        assertEquals(emptyList(), fresh(owner = "https://anki.example.test/cerro"), "each account keeps its own cards")
    }

    @Test
    fun `the most important moment comes first, the newest first within a kind`() {
        AnkiquestMoments.offer(
            prefs,
            account,
            listOf(
                moment("goal:9", Kind.GOAL),
                moment("conquered:1", Kind.CONQUERED, at = NOW - 10),
                moment("level:4", Kind.LEVEL),
                moment("conquered:2", Kind.CONQUERED),
                moment("streak:1:30", Kind.STREAK),
            ),
        )
        assertEquals(
            listOf("streak:1:30", "level:4", "conquered:2", "conquered:1", "goal:9"),
            AnkiquestMoments.take(prefs, account).map { it.key },
        )
    }

    @Test
    fun `a moment is shown once, however often it is offered`() {
        val level = moment("level:4", Kind.LEVEL)
        AnkiquestMoments.offer(prefs, account, listOf(level, level))
        assertEquals(1, AnkiquestMoments.pending(prefs, account).size)
        assertEquals(listOf(level), AnkiquestMoments.take(prefs, account))
        AnkiquestMoments.offer(prefs, account, listOf(level))
        assertEquals(emptyList(), AnkiquestMoments.take(prefs, account))
        assertEquals(emptyList(), AnkiquestMoments.pending(prefs, "https://anki.example.test/cerro"))
    }

    @Test
    fun `waiting moments survive being stored`() {
        val conquered = moment("conquered:5", Kind.LEECH).copy(conquest = 5, lapses = 9, deck = "Español", win = 3)
        AnkiquestMoments.offer(prefs, account, listOf(conquered))
        assertEquals(listOf(conquered), AnkiquestMoments.pending(prefs, account))
    }

    @Test
    fun `the inbox notifies in the background, queues in front, and never repeats a moment`() {
        val level = celebration(1, "level", moment = "level:4")
        assertEquals(Route.NOTIFY, AnkiquestMoments.route(prefs, account, level, foreground = false))
        assertEquals(Route.QUEUED, AnkiquestMoments.route(prefs, account, level, foreground = true))
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, level, foreground = false), "waiting in the app")
        AnkiquestMoments.take(prefs, account)
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, level, foreground = false), "shown in the app")

        val streak = celebration(2, "streak", moment = "streak:1:30")
        assertEquals(Route.NOTIFY, AnkiquestMoments.route(prefs, account, streak, foreground = false))
        AnkiquestMoments.markShown(prefs, account, listOf("streak:1:30"))
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, streak, foreground = true), "notified already")

        val message =
            JSONObject()
                .put("id", 3)
                .put("kind", "message")
                .put("title", "Hi")
                .put("body", "Hello")
        assertEquals(Route.NOTIFY, AnkiquestMoments.route(prefs, account, message, foreground = true))
    }

    @Test
    fun `a card the upload already queued does not come back from the inbox`() {
        AnkiquestMoments.offer(prefs, account, listOf(moment("conquered:42", Kind.CONQUERED)))
        assertEquals(
            Route.SEEN,
            AnkiquestMoments.route(prefs, account, celebration(1, "conquered", moment = "conquered:42"), foreground = false),
        )
        assertEquals(
            Route.SEEN,
            AnkiquestMoments.route(prefs, account, celebration(2, "conquered"), foreground = true),
            "an older server does not name the card, and the upload covers it",
        )
    }

    @Test
    fun `routine moments only count toward the badge on Today`() {
        assertEquals(
            Route.QUIET,
            AnkiquestMoments.route(prefs, account, celebration(1, "record", "record:hour:9", quiet = true), foreground = true),
        )
        assertEquals(
            Route.SEEN,
            AnkiquestMoments.route(prefs, account, celebration(1, "record", "record:hour:9", quiet = true), foreground = true),
        )
        assertEquals(emptyList(), AnkiquestMoments.pending(prefs, account))
        assertEquals(1, AnkiquestMoments.quiet(prefs, account))
        AnkiquestMoments.clearQuiet(prefs)
        assertEquals(0, AnkiquestMoments.quiet(prefs, account))
    }

    @Test
    fun `the daily minimum waits for the session and comes first`() {
        val secured = celebration(1, "secured", "secured:9", quiet = true)
        assertEquals(Route.QUEUED, AnkiquestMoments.route(prefs, account, secured, foreground = true))
        AnkiquestMoments.offer(prefs, account, listOf(moment("level:4", Kind.LEVEL)))
        assertEquals(listOf("secured:9", "level:4"), AnkiquestMoments.take(prefs, account).map { it.key })
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, secured, foreground = false))
    }

    @Test
    fun `a notified daily minimum does not also queue a card`() {
        val secured = celebration(1, "secured", "secured:9", quiet = true)
        assertEquals(Route.NOTIFY, AnkiquestMoments.route(prefs, account, secured, foreground = false))
        AnkiquestMoments.markShown(prefs, account, listOf("secured:9"))
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, secured, foreground = true))
        assertEquals(emptyList(), AnkiquestMoments.pending(prefs, account))
    }

    @Test
    fun `the local minimum is claimed once and the inbox upgrades its card`() {
        val server = AnkiquestDay.Server(12, "pending", NOW + DAY, 0, NOW - DAY, 0, 0, 10)
        val met = assertNotNull(AnkiquestDay.judge(server, listOf(AnkiquestDay.Review(NOW, 600_000)), NOW))
        assertTrue(AnkiquestMoments.claimMinimum(targetContext, prefs, identity, server, met, NOW))
        assertFalse(AnkiquestMoments.claimMinimum(targetContext, prefs, identity, server, met, NOW + 1000))

        val secured = celebration(1, "secured", "secured:9", quiet = true)
        assertEquals(Route.QUEUED, AnkiquestMoments.route(prefs, account, secured, foreground = true))
        val card = AnkiquestMoments.take(prefs, account).single()
        assertEquals(Kind.SECURED, card.kind)
        assertEquals(71L, card.win)
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, secured, foreground = false))
    }

    @Test
    fun `a milestone keeps its bigger card`() {
        val server = AnkiquestDay.Server(6, "pending", NOW + DAY, 0, NOW - DAY, 0, 0, 10)
        val met = assertNotNull(AnkiquestDay.judge(server, listOf(AnkiquestDay.Review(NOW, 600_000)), NOW))
        assertTrue(AnkiquestMoments.claimMinimum(targetContext, prefs, identity, server, met, NOW))
        assertTrue(AnkiquestMoments.pending(prefs, account).isEmpty())

        // The notice can arrive before the updated profile does.
        val secured = celebration(1, "secured", "secured:9", quiet = true)
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, secured, foreground = true))
        assertEquals(
            Route.QUEUED,
            AnkiquestMoments.route(prefs, account, celebration(2, "streak", "streak:3:7"), foreground = true),
        )
        assertEquals(Kind.STREAK, AnkiquestMoments.take(prefs, account).single().kind)
    }

    @Test
    fun `a local minimum with unknown streak length also consumes the server cue`() {
        val start = NOW - DAY
        val server = AnkiquestDay.Server(6, "pending", NOW + DAY, start - DAY, start, 0, 0, 10)
        val reviews = listOf(AnkiquestDay.Review(start - 1, 10_000), AnkiquestDay.Review(NOW, 600_000))
        val met = assertNotNull(AnkiquestDay.judge(server, reviews, NOW))
        assertNull(met.streak)
        assertTrue(AnkiquestMoments.claimMinimum(targetContext, prefs, identity, server, met, NOW))

        val secured = celebration(1, "secured", "secured:9", quiet = true)
        assertEquals(Route.SEEN, AnkiquestMoments.route(prefs, account, secured, foreground = false))
        assertTrue(AnkiquestMoments.pending(prefs, account).isEmpty())
    }

    @Test
    fun `the cue does not consume reviewer touches`() {
        val host = host()
        val cue = assertNotNull(AnkiquestStreakCue.show(host, 13, 0.9f))
        val tap = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 8f, 8f, 0)
        try {
            assertFalse(cue.isFocusable)
            assertFalse(cue.isClickable)
            assertFalse(cue.dispatchTouchEvent(tap))
            assertNull(AnkiquestStreakCue.show(host, 13, 0.9f))
        } finally {
            tap.recycle()
        }
    }

    @Test
    fun `reduce motion fills the ring at once and announces the cue politely`() {
        val host = host()
        mockkObject(AnkiquestGoalBar.Companion)
        try {
            every { AnkiquestGoalBar.animationsEnabled(host) } returns false
            val cue = assertNotNull(AnkiquestStreakCue.show(host, 13, 0.4f, prefs) as? LinearLayout)
            val ring = cue.getChildAt(0) as AnkiquestStreakRing
            val label = cue.getChildAt(cue.childCount - 1) as TextView
            assertEquals(1f, ring.fraction)
            assertTrue(ring.safe)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, label.accessibilityLiveRegion)
        } finally {
            unmockkObject(AnkiquestGoalBar.Companion)
        }
    }

    @Test
    fun `an important banner waits for the streak cue to finish`() {
        val host = host()
        val root = host.findViewById<FrameLayout>(android.R.id.content)
        ReflectionHelpers.setField(Ankiquest, "activity", WeakReference<Activity>(host))
        mockkObject(AnkiquestGoalBar.Companion)
        try {
            every { AnkiquestGoalBar.animationsEnabled(host) } returns false
            assertNotNull(
                AnkiquestStreakCue.show(host, 13, 0.4f, prefs) {
                    ReflectionHelpers.callInstanceMethod<Unit>(
                        Ankiquest,
                        "showDeferredBanner",
                        ClassParameter.from(Activity::class.java, host),
                        ClassParameter.from(FrameLayout::class.java, root),
                    )
                },
            )

            fun banner(
                text: String,
                important: Boolean,
            ) {
                ReflectionHelpers.callInstanceMethod<Unit>(
                    Ankiquest,
                    "showBanner",
                    ClassParameter.from(String::class.java, text),
                    ClassParameter.from(Boolean::class.javaPrimitiveType, important),
                )
            }
            banner("important", true)
            banner("routine", false)
            val deferred = root.findViewWithTag<TextView>("ankiquest_banner")
            assertEquals(0f, deferred.alpha)
            assertEquals("", deferred.text.toString())

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_401))

            assertFalse(AnkiquestStreakCue.showing(root))
            assertEquals("important", deferred.text.toString())
            assertNull(ReflectionHelpers.getField<Any?>(deferred, "pending"))
        } finally {
            unmockkObject(AnkiquestGoalBar.Companion)
            ReflectionHelpers.setField(Ankiquest, "activity", WeakReference<Activity>(null))
        }
    }

    @Test
    fun `the cue switch leaves the review screen alone`() {
        val host = host()
        prefs.edit { putBoolean(host.getString(R.string.ankiquest_streak_cue_key), false) }
        assertNull(AnkiquestStreakCue.show(host, 13, 0.4f, prefs))
    }

    @Test
    fun `tapping a notified moment opens its card without taking the queue`() {
        val host = host()
        val notified = moment("secured:9", Kind.SECURED)
        AnkiquestMoments.markShown(prefs, account, listOf(notified.key))
        AnkiquestMoments.offer(prefs, account, listOf(moment("level:4", Kind.LEVEL)))
        host.intent = AnkiquestMoments.noticeIntent(host, account, notified)

        AnkiquestMoments.present(host, prefs, resting = true)
        advanceRobolectricLooper()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        try {
            val pager = assertNotNull(dialog.findViewById<ViewPager2>(R.id.aq_moment_pager))
            assertEquals(1, pager.adapter?.itemCount)
            assertEquals(listOf("level:4"), AnkiquestMoments.pending(prefs, account).map { it.key })
        } finally {
            dialog.dismiss()
        }
    }

    @Test
    fun `a restored notification does not open its card twice`() {
        val host = host()
        val notice = AnkiquestMoments.noticeIntent(host, account, moment("secured:9", Kind.SECURED))
        host.intent = android.content.Intent(notice)
        AnkiquestMoments.present(host, prefs, resting = true)
        advanceRobolectricLooper()
        val first = assertNotNull(ShadowDialog.getLatestDialog())
        first.dismiss()

        try {
            host.intent = notice
            AnkiquestMoments.present(host, prefs, resting = true)
            advanceRobolectricLooper()
            assertEquals(first, ShadowDialog.getLatestDialog())
            assertFalse(first.isShowing)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
        }
    }

    @Test
    fun `a notification replaces the open moment card`() {
        val host = host()
        AnkiquestMoments.offer(prefs, account, listOf(moment("level:4", Kind.LEVEL)))
        AnkiquestMoments.present(host, prefs, resting = true)
        val first = assertNotNull(ShadowDialog.getLatestDialog())
        assertTrue(first.isShowing)

        host.intent = AnkiquestMoments.noticeIntent(host, account, moment("secured:9", Kind.SECURED))
        AnkiquestMoments.present(host, prefs, resting = true)
        advanceRobolectricLooper()
        try {
            assertFalse(first.isShowing)
            assertTrue(assertNotNull(ShadowDialog.getLatestDialog()).isShowing)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
        }
    }

    @Test
    fun `nothing pops up over a screen where studying goes on`() {
        val host = host()
        assertFalse(AnkiquestMoments.resting(host))
        AnkiquestMoments.offer(prefs, account, listOf(moment("level:4", Kind.LEVEL)))
        AnkiquestMoments.present(host, prefs)
        advanceRobolectricLooper()
        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(1, AnkiquestMoments.pending(prefs, account).size, "it waits")
    }

    @Test
    fun `when studying stops, one card shows the most important moment and folds in the rest`() {
        val host = host()
        AnkiquestMoments.offer(
            prefs,
            account,
            listOf(moment("goal:9", Kind.GOAL), moment("level:4", Kind.LEVEL), moment("achievement:7", Kind.ACHIEVEMENT)),
        )
        AnkiquestMoments.present(host, prefs, resting = true)
        advanceRobolectricLooper()
        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertTrue(dialog.isShowing)
        val pager = assertNotNull(dialog.findViewById<ViewPager2>(R.id.aq_moment_pager))
        assertEquals(3, pager.adapter?.itemCount)
        assertEquals(
            host.resources.getQuantityString(R.plurals.aq_moment_more, 2, 2),
            dialog.findViewById<TextView>(R.id.aq_moment_more)?.text.toString(),
        )
        assertEquals(emptyList(), AnkiquestMoments.pending(prefs, account), "all of them were shown")

        AnkiquestMoments.offer(prefs, account, listOf(moment("level:5", Kind.LEVEL)))
        AnkiquestMoments.present(host, prefs, resting = true)
        assertEquals(1, AnkiquestMoments.pending(prefs, account).size, "one card at a time")
        dialog.dismiss()
    }

    @Test
    fun `the fold line counts what is left, then where the swipe is`() {
        assertEquals(
            targetContext.resources.getQuantityString(R.plurals.aq_moment_more, 2, 2),
            AnkiquestMomentCard.foldLine(targetContext, 0, 3),
        )
        assertEquals(targetContext.getString(R.string.aq_moment_position, 2, 3), AnkiquestMomentCard.foldLine(targetContext, 1, 3))
    }

    @Test
    fun `sharing a conquered card speaks in the first person with its deck and page`() {
        val conquered = moment("conquered:5", Kind.CONQUERED).copy(conquest = 5, lapses = 9, deck = "Español")
        assertEquals(
            targetContext.resources.getQuantityString(R.plurals.aq_conquest_share_mine, 9, 9) + " " +
                targetContext.getString(R.string.aq_conquest_share_deck, "Español") +
                " https://anki.example.test/conquered/hill/5",
            AnkiquestMomentCard.conquestText(targetContext, identity, conquered),
        )
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_793_097_000_000L
    }
}
