// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * Phone screens for the README banner (`docs/graphics/logos/banner_readme.png`), drawn from sample
 * players only. Skipped unless `-PreadmeBanner` is given, so the screenshot workflows ignore it:
 * `tools/ankiquest_banner.py` documents the commands that record these and compose the banner.
 */
class AnkiquestBannerScreenshots : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "mira", "banner-sample-token")

    override fun applyGlobalConfig() {
        assumeTrue("Only recorded for the README banner (-PreadmeBanner)", java.lang.Boolean.getBoolean("screenshot.banner"))
        assumeTrue(theme == ThemeConfig.LIGHT && device == DeviceConfig.PHONE)
        super.applyGlobalConfig()
    }

    override fun applyDeviceConfig() {
        // A 360x780dp phone at twice the density, so it scales down crisply into the mockups.
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-xhdpi")
    }

    @Before
    fun prepareSamples() {
        TimeManager.resetWith(MockTime(NOW))
        Settings.Global.putFloat(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestFeed, AnkiquestShare, AnkiquestChallenges)
        every { AnkiquestHomeData.account(any()) } returns identity
    }

    @After
    fun clearSamples() {
        unmockkAll()
    }

    @Test
    fun today() {
        val goal =
            AnkiquestDailyGoal.Status(
                day = 1,
                minutes = 14,
                streakMinutes = 10,
                studiedCards = 92,
                mode = AnkiquestDailyGoal.Mode.CARDS,
                goal = AnkiquestDailyGoal.Goal(140),
            )
        val decks =
            listOf(
                HomeDeck(12, "Spanish", 8, 3, 27),
                HomeDeck(13, "Japanese::Kanji", 5, 2, 14),
                HomeDeck(14, "Biology", 0, 1, 9),
            )
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(decks, 12, goal = goal, secondsPerCard = 6.0)
        coEvery { AnkiquestHomeData.profile(any()) } returns JSONObject(HOME)
        // The sample server does not exist: answer at once instead of waiting on its socket.
        coEvery { AnkiquestHomeData.companion(identity) } throws IOException("offline")
        val home =
            startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, AnkiquestHomeActivity.intent(targetContext))
        advanceRobolectricLooperUntil { !home.findViewById<View>(R.id.aq_home_loading).isVisible }
        advanceRobolectricLooper()
        // Scroll just far enough to show the whole daily goal card under the streak.
        val scroll = home.findViewById<NestedScrollView>(R.id.aq_home_scroll)
        val goalCard =
            scroll.allViews
                .filterIsInstance<TextView>()
                .first {
                    it.text.toString() ==
                        home.getString(
                            R.string.aq_goal_today,
                        )
                }.parent as View
        val bounds = Rect().also { goalCard.getDrawingRect(it) }
        scroll.offsetDescendantRectToMyCoords(goalCard, bounds)
        scroll.scrollTo(
            0,
            maxOf(
                0,
                bounds.bottom + scroll.paddingBottom + (16 * home.resources.displayMetrics.density).toInt() - scroll.height,
            ),
        )
        advanceRobolectricLooper()
        captureScreen("1_today")
    }

    @Test
    fun leaderboard() {
        val rows = JSONArray()
        listOf(
            Player("lucia", "Lucía", level = 9, week = 1840, today = 62, streak = 41),
            Player("mira", "Mira", level = 7, week = 1515, today = 92, streak = 23),
            Player("kenji", "Kenji", level = 8, week = 1290, today = 40, streak = 12),
            Player("priya", "Priya", level = 5, week = 960, today = 35, streak = 8),
            Player("tomas", "Tomás", level = 6, week = 715, today = 0, streak = 0),
            Player("amara", "Amara", level = 4, week = 530, today = 21, streak = 5),
            Player("jonas", "Jonas", level = 3, week = 260, today = 12, streak = 2),
        ).forEach { rows.put(it.row()) }
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } returns
            AnkiquestBoards.parseLeaderboard(rows, AnkiquestBoards.Period.WEEK)
        coEvery { AnkiquestBoards.weekEnds(identity) } returns NOW + 2 * DAY
        val board =
            startRegularActivity<AnkiquestLeaderboardActivity>(
                AnkiquestLeaderboardActivity.intent(targetContext, AnkiquestBoards.Period.WEEK.query, null),
            )
        advanceRobolectricLooperUntil { !board.findViewById<View>(R.id.aq_board_loading).isVisible }
        captureScreen("2_leaderboard")
    }

    @Test
    fun profile() {
        val page =
            AnkiquestBoards.PlayerPage(
                AnkiquestBoards.parseProfile(JSONObject(PROFILE)),
                own = true,
                wins =
                    listOf(
                        AnkiquestBoards.Win("Daily goal reached!", "140 cards today in 23 minutes.", NOW / 1000 - 600),
                        AnkiquestBoards.Win("3 week streak!", "21 days in a row.", NOW / 1000 - 2 * DAY / 1000),
                    ),
            )
        coEvery { AnkiquestBoards.player(identity, "mira") } returns page
        val screen = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, null))
        advanceRobolectricLooperUntil { !screen.findViewById<View>(R.id.aq_board_loading).isVisible }
        captureScreen("3_profile")
    }

    @Test
    fun feed() {
        val win = BitmapFactory.decodeResource(targetContext.resources, R.drawable.aki_streak_milestone)
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns AnkiquestFeed.parsePage(JSONObject(FEED))
        every { AnkiquestShare.picture(any()) } returns win
        startActivityNormallyOpenCollectionWithIntent(AnkiquestFeedActivity::class.java, AnkiquestFeedActivity.intent(targetContext))
        advanceRobolectricLooper()
        captureScreen("4_feed")
    }

    @Test
    fun friendGoals() {
        val overview = AnkiquestChallenges.parse(JSONObject(sample("challenges")))
        coEvery { AnkiquestChallenges.overview(identity) } returns overview
        coEvery { AnkiquestChallenges.weekly(identity, any(), any()) } returns overview
        val goals = startRegularActivity<AnkiquestChallengesActivity>(AnkiquestChallengesActivity.intent(targetContext, null, null))
        advanceRobolectricLooperUntil { !goals.findViewById<View>(R.id.aq_feed_loading).isVisible }
        captureScreen("5_friend_goals")
    }

    private data class Player(
        val user: String,
        val display: String,
        val level: Int,
        val week: Long,
        val today: Int,
        val streak: Int,
    ) {
        fun row(): JSONObject =
            AnkiquestBoardsTest
                .row(user, week = week, hour = today / 2L)
                .put("display", display)
                .put("level", level)
                .put("xp_total", week * 6)
                .put("today_reviews", today)
                .put("today_minutes", today / 5)
                .put("streak", streak)
                .put("streak_state", if (today > 0) "studied" else "pending")
    }

    private companion object {
        /** Wednesday 28 October 2026, mid-afternoon, as in the API examples. */
        const val NOW = 1_793_183_460_000L
        const val DAY = 24 * 60 * 60 * 1000L

        /** An API example with its sample players renamed to the banner's. */
        fun sample(name: String): String =
            requireNotNull(AnkiquestBannerScreenshots::class.java.classLoader?.getResource("ankiquest-api/$name.json"))
                .readText(Charsets.UTF_8)
                .replace("\"Hill\"", "\"Mira\"")
                .replace("\"hill\"", "\"mira\"")
                .replace("\"Friend\"", "\"Kenji\"")
                .replace("\"friend\"", "\"kenji\"")
                .replace("\"Cerro\"", "\"Lucía\"")
                .replace("\"cerro\"", "\"lucia\"")

        const val HOME = """{
            "level": 7, "streak": 23, "streak_state": "studied", "xp_into_level": 640, "xp_for_next": 900,
            "freezes": 1, "stored_freezes": 2, "freezes_enabled": true,
            "quests": [
                {"title": "Review 100 cards", "progress": 92, "target": 100, "done": false, "reward": 50},
                {"title": "Study for 10 minutes", "progress": 10, "target": 10, "done": true, "reward": 30}
            ]
        }"""

        const val PROFILE = """{
            "user": "mira", "display": "Mira", "level": 7, "xp_total": 8915, "xp_into_level": 640, "xp_for_next": 900,
            "periods": {"week": 1515}, "streak": 23, "streak_state": "studied", "freezes": 1, "stored_freezes": 2,
            "freezes_enabled": true, "at_risk": false,
            "today": {"reviews": 92, "minutes": 14, "xp": 230, "streak_minutes": 10},
            "lifetime": {"reviews": 6120, "hours": 31, "best_streak": 23, "best_combo": 64, "days_active": 58},
            "records": {"hour": {"xp": 410, "reviews": 88}},
            "quests": [
                {"title": "Review 100 cards", "progress": 92, "target": 100, "done": false, "reward": 50},
                {"title": "Study for 10 minutes", "progress": 10, "target": 10, "done": true, "reward": 30}
            ],
            "achievements": [
                {"title": "Month of focus", "description": "A 30 day streak", "reward": 100, "progress": 23, "target": 30, "unlocked": null},
                {"title": "Night owl", "description": "Study after 10pm", "reward": 20, "progress": 1, "target": 1, "unlocked": "2026-10-20"},
                {"title": "First thousand", "description": "1,000 reviews", "reward": 50, "progress": 1000, "target": 1000,
                 "unlocked": "2026-09-30"}
            ],
            "conquests": [{"at": 1793183400000, "lapses": 9, "answers": 20, "since": 1790000000000, "leech": true}]
        }"""

        const val FEED = """{
            "items": [
                {"id": 42, "title": "Challenge invitation", "body": "Kenji invited you to Fifty reviews each.", "day": 20754,
                 "created_at": 1793183300, "sender": "kenji", "replied": false, "kind": "challenge_invite", "read_at": null,
                 "challenge_id": 4, "route": "/community#challenge-4", "action_required": true},
                {"id": 41, "action_required": false, "body": "21 day streak: “Three weeks in a row.”", "challenge_id": null,
                 "created_at": 1793182000, "day": 20754, "image": "https://anki.example.test/w/sample-win.png", "kind": "share",
                 "reacted": false, "read_at": null, "replied": false, "route": null, "sender": "lucia",
                 "title": "🎉 Lucía shared a win", "topic": "streak", "win": 2},
                {"id": 40, "action_required": false, "body": "“Daily goal reached!”", "challenge_id": null, "created_at": 1793181000,
                 "day": 20754, "kind": "reaction", "read_at": null, "replied": false, "route": null, "sender": "priya",
                 "title": "🎉 Priya cheered", "win": 1},
                {"id": 39, "title": "Kenji replied", "body": "Same time tomorrow?", "day": 20754, "created_at": 1793180000,
                 "sender": "kenji", "replied": true, "kind": "reply", "read_at": 1793183000, "challenge_id": null, "route": null,
                 "action_required": false, "reply_to": {"title": "Deck complete", "body": "Spanish is done for today."}}
            ],
            "attention": [
                {"id": 42, "title": "Challenge invitation", "body": "Kenji invited you to Fifty reviews each.", "day": 20754,
                 "created_at": 1793183300, "sender": "kenji", "replied": false, "kind": "challenge_invite", "read_at": null,
                 "challenge_id": 4, "route": "/community#challenge-4", "action_required": true}
            ],
            "unread_count": 3, "action_count": 1, "latest_id": 42, "retention_days": 90, "window_days": 90, "next_before": null
        }"""
    }
}
