// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.provider.Settings
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.ReviewerTest
import com.ichi2.anki.ScreenshotTest
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmap
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.CardType
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.QueueType
import com.ichi2.anki.setIntroductionSlidesShown
import com.ichi2.testutils.BackupManagerTestUtilities
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Phone screens for the README banner (`docs/graphics/logos/banner_readme.png`), drawn from sample
 * decks and players only. Skipped unless `-PreadmeBanner` is given, so the screenshot workflows
 * ignore it: `tools/ankiquest_banner.py` documents the commands that record these and compose the
 * banner, and `.github/workflows/ankiquest_banner.yml` reruns both for each release.
 */
class AnkiquestBannerScreenshots : ScreenshotTest() {
    // The reviewer renders a card, which requires a media folder.
    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

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
        Settings.Global.putFloat(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun clearSamples() {
        unmockkAll()
        BackupManagerTestUtilities.reset()
    }

    /**
     * A Spanish card with its answer shown. Robolectric draws the card's WebView blank, so the card
     * is drawn natively over it from the HTML the collection rendered for it, in the default note
     * type's style (centred 20px sans-serif, black on white, a rule between question and answer).
     */
    @Test
    fun reviewer() {
        val spanish = addDeck("Spanish", setAsSelected = true)
        sampleCollection(spanish)
        val reviewer = ReviewerTest.startReviewer(this)
        advanceRobolectricLooper()
        reviewer.displayCardAnswer()
        advanceRobolectricLooper()
        reviewer.drawCardNatively()
        advanceRobolectricLooper()
        captureScreen("1_reviewer")
    }

    /** The deck list with each deck's daily status and the review heatmap below it. */
    @Test
    fun decks() {
        pinHeatmapToBannerDay()
        ensureCollectionLoadIsSynchronous()
        setIntroductionSlidesShown(true)
        BackupManagerTestUtilities.setupSpaceForBackup(targetContext)
        targetContext.sharedPrefs().edit { putBoolean("backupPromptDisabled", true) }
        sampleCollection(addDeck("Spanish"))
        val picker = startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent())
        runTest { picker.viewModel.flowOfDeckList.first { it.data.isNotEmpty() } }
        advanceRobolectricLooper()
        // Release builds are named AnkiQuest (-PcustomName); the debug build under test keeps AnkiDroid.
        picker.supportActionBar?.title = "AnkiQuest"
        advanceRobolectricLooper()
        captureScreen("2_decks")
    }

    @Test
    fun today() {
        social()
        val goal =
            AnkiquestDailyGoal.Status(
                day = 1,
                minutes = 14,
                streakMinutes = 10,
                studiedCards = 92,
                mode = AnkiquestDailyGoal.Mode.CARDS,
                goal = AnkiquestDailyGoal.Goal(140),
                streakDay = AnkiquestDay.Verdict(true, "studied", 12, 92, 14 * 60_000L, 10, 0, true),
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
        val goalTitle = home.getString(R.string.aq_goal_today)
        val goalCard =
            scroll.allViews
                .filterIsInstance<TextView>()
                .first { it.text.toString() == goalTitle }
                .parent as View
        val bounds = Rect().also { goalCard.getDrawingRect(it) }
        scroll.offsetDescendantRectToMyCoords(goalCard, bounds)
        val margin = (16 * home.resources.displayMetrics.density).toInt()
        scroll.scrollTo(0, maxOf(0, bounds.bottom + scroll.paddingBottom + margin - scroll.height))
        advanceRobolectricLooper()
        captureScreen("3_today")
    }

    @Test
    fun leaderboard() {
        social()
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
            startRegularActivity<AnkiquestFriendsActivity>(
                AnkiquestLeaderboardActivity.intent(targetContext, AnkiquestBoards.Period.WEEK.query, null),
            )
        advanceRobolectricLooperUntil { !board.findViewById<View>(R.id.aq_friends_board_loading).isVisible }
        captureScreen("4_leaderboard")
    }

    @Test
    fun profile() {
        social()
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
        captureScreen("5_profile")
    }

    /**
     * The backend dates reviews by the real clock, which a test cannot set. Move the loaded
     * history so the heatmap's today is always [BANNER_DAY], keeping each review's distance from
     * today, so the heatmap looks the same whenever the banner is rendered.
     */
    private fun pinHeatmapToBannerDay() {
        mockkObject(ReviewHeatmap)
        every { ReviewHeatmap.load(any()) } answers {
            val loaded = callOriginal()
            val shift = ChronoUnit.DAYS.between(loaded.today, BANNER_DAY)
            ReviewHeatmap.summarize(
                BANNER_DAY,
                loaded.reviews.mapKeys { (date, _) -> date.plusDays(shift) },
                loaded.scheduled.mapKeys { (date, _) -> date.plusDays(shift) },
            )
        }
    }

    /** The server screens: a signed-in sample player at a fixed time, with the API mocked. */
    private fun social() {
        TimeManager.resetWith(MockTime(NOW))
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestSocial)
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("overview unavailable")
        every { AnkiquestHomeData.account(any()) } returns identity
    }

    /**
     * Sample decks: some with cards due today, some already done today, and four months of
     * reviews behind them for the heatmap.
     */
    private fun sampleCollection(spanish: DeckId) {
        // The real clock, as the backend's own day and the heatmap use it: the tests' fixed 2020 clock
        // would put today's reviews years in the future for the deck list.
        TimeManager.reset()
        val due = mutableListOf<Card>()
        val studied = mutableListOf<Card>()
        // Every Spanish card is a review due today, with "despacio" the most overdue: the
        // scheduler shows the most overdue review first, so the reviewer always opens on it.
        val spanishCards = SPANISH.map { (front, back) -> addBasicNote(front, back).also { it.moveToDeck("Spanish") }.firstCard() }
        val first = spanishCards[SPANISH.indexOfFirst { it.first == "despacio" }]
        due += spanishCards - first

        fun deck(
            name: String,
            new: Int,
            review: Int,
            studiedToday: Int = 0,
        ) {
            repeat(new) { addBasicNote("$name $it", "").moveToDeck(name) }
            repeat(review) { due += addBasicNote("$name review $it", "").also { it.moveToDeck(name) }.firstCard() }
            repeat(studiedToday) { studied += addBasicNote("$name done $it", "").also { it.moveToDeck(name) }.firstCard() }
        }
        deck("Japanese::Kanji", new = 5, review = 14)
        deck("Japanese::Vocabulary", new = 0, review = 0, studiedToday = 6)
        deck("Biology", new = 2, review = 9)
        deck("Geography", new = 0, review = 0, studiedToday = 4)
        deck("Music theory", new = 3, review = 0)
        // The heatmap's history, on a card scheduled well after today.
        val history = addBasicNote("historia", "history").also { it.moveToDeck("Spanish") }.firstCard()
        check(col.decks.id("Spanish") == spanish)

        val day = col.sched.today

        fun Card.review(dueIn: Int) {
            queue = QueueType.Rev
            type = CardType.Rev
            this.due = day + dueIn
            ivl = 12
            factor = 2500
        }
        due.forEach { it.review(0) }
        first.review(-3)
        studied.forEach { it.review(9) }
        history.review(60)
        col.updateCards(due + first + studied + history, skipUndoEntry = true)

        val tomorrow = col.backend.schedTimingToday().nextDayAt * 1000L
        studied.forEachIndexed { i, card ->
            col.db.execute(
                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, -1, 3, 9, 4, 2500, 6000, 1)",
                tomorrow - DAY + 9 * HOUR + i * 60_000L,
                card.id,
            )
        }
        // A 23-day streak, with lighter weekends and a few missed days before it, the same on every run.
        for (ago in 1..120) {
            val missed = ago == 23 || (ago > 23 && (ago % 11 == 4 || ago % 17 == 9))
            val count = if (missed) 0 else 18 + (ago * 37 % 45) - (if (ago % 7 in 2..3) 12 else 0)
            if (count <= 0) continue
            col.db.execute(
                "with recursive n(i) as (select 0 union all select i + 1 from n where i < ?) " +
                    "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) " +
                    "select ? + i * 20000, ?, -1, 3, 10, 5, 2500, 7000, 1 from n",
                count - 1,
                tomorrow - (ago + 1) * DAY + 8 * HOUR,
                history.id,
            )
        }
    }

    /** Covers the blank WebView with the current card's rendered question and answer. */
    private fun Reviewer.drawCardNatively() {
        val card = checkNotNull(currentCard)
        val html = card.answer(this@AnkiquestBannerScreenshots.col).replace(Regex("(?s)<style>.*?</style>"), "")
        val parts = html.split(Regex("<hr id=answer>", RegexOption.IGNORE_CASE), limit = 2)
        val question = parts[0]
        val answer = parts.getOrElse(1) { "" }

        fun part(source: String) =
            TextView(this).apply {
                text = Html.fromHtml(source.trim(), Html.FROM_HTML_MODE_COMPACT)
                textSize = 20f
                typeface = Typeface.SANS_SERIF
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Color.BLACK)
            }
        val density = resources.displayMetrics.density
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
                addView(part(question))
                addView(
                    View(context).apply { setBackgroundColor(Color.rgb(0xCC, 0xCC, 0xCC)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, density.toInt())).apply {
                        topMargin = (20 * density).toInt()
                        bottomMargin = (20 * density).toInt()
                    },
                )
                addView(part(answer))
            }
        findViewById<FrameLayout>(R.id.flashcard).addView(
            ScrollView(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(page)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
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

        /** The day of [NOW], which the heatmap shows as today. */
        val BANNER_DAY: LocalDate = LocalDate.of(2026, 10, 28)
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR

        val SPANISH =
            listOf(
                "la mariposa" to "the butterfly<br><br><i>La mariposa vuela sobre las flores.</i><br>The butterfly flies over the flowers.",
                "el desayuno" to "breakfast<br><br><i>Tomo el desayuno a las ocho.</i><br>I have breakfast at eight.",
                "la biblioteca" to "the library<br><br><i>Estudio en la biblioteca.</i><br>I study in the library.",
                "despacio" to "slowly<br><br><i>Habla más despacio, por favor.</i><br>Speak more slowly, please.",
                "el paraguas" to "the umbrella<br><br><i>Lleva el paraguas, va a llover.</i><br>Take the umbrella, it's going to rain.",
                "madrugar" to "to get up early<br><br><i>Mañana tengo que madrugar.</i><br>Tomorrow I have to get up early.",
                "la cosecha" to
                    "the harvest<br><br><i>La cosecha de uvas empieza en septiembre.</i><br>The grape harvest starts in September.",
                "el ayuntamiento" to "the town hall<br><br><i>El ayuntamiento está en la plaza.</i><br>The town hall is on the square.",
                "sin embargo" to "however<br><br><i>Llueve; sin embargo, salimos.</i><br>It's raining; however, we're going out.",
                "el atardecer" to "the sunset<br><br><i>Vimos el atardecer desde la playa.</i><br>We watched the sunset from the beach.",
                "aprovechar" to
                    "to make the most of<br><br><i>Hay que aprovechar el buen tiempo.</i><br>We should make the most of the good weather.",
                "la sobremesa" to
                    "time spent chatting at the table after a meal<br><br><i>La sobremesa duró dos horas.</i><br>The chat after lunch lasted two hours.",
            )

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
    }
}
