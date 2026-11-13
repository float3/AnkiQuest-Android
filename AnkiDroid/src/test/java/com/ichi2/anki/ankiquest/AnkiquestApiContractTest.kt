// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Notification
import android.app.NotificationManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.common.time.MockTime
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feeds the server's recorded answers in `src/test/resources/ankiquest-api`, which CI replaces with
 * the server's current ones, to the code the app reads them with, so a server change that would
 * break the app fails here before it is deployed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestApiContractTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = CopyOnWriteArrayList<String>()
    private val manager get() = targetContext.getSystemService<NotificationManager>()!!

    @Before
    fun serveExamples() {
        mockkObject(AnkiquestWidget, AnkiquestShare)
        every { AnkiquestWidget.requestUpdate(any()) } just Runs
        every { AnkiquestShare.picture(any()) } returns null
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        TimeManager.resetWith(MockTime(EXAMPLE_NOW))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += "${exchange.requestMethod} $path"
            val sent = exchange.requestBody.bufferedReader().use { it.readText() }
            val body = route(exchange.requestMethod, path, sent, exchange.requestURI.query)?.let(::example)?.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, url)
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "hill-token")
        }
    }

    @After
    fun stopServer() {
        try {
            if (::server.isInitialized) server.stop(0)
        } finally {
            unmockkObject(AnkiquestWidget, AnkiquestShare)
            SharedPreferencesTestHook.override = null
        }
    }

    private val account: HomeAccount get() = assertNotNull(AnkiquestHomeData.account())

    @Test
    fun `private Global hiding reads the server's updated player list`() =
        runBlocking {
            val expected = listOf(AnkiquestGlobalHides.Player("ana", "Ana"))
            assertEquals(expected, AnkiquestGlobalHides.load(account))
            assertEquals(expected, AnkiquestGlobalHides.change(account, "ana", AnkiquestGlobalHides.Action.HIDE))
            assertTrue(
                requests.containsAll(
                    listOf("GET /api/capabilities", "GET /api/leaderboard/hidden/hill", "POST /api/leaderboard/hidden/hill"),
                ),
            )
        }

    @Test
    fun `activity pages keep global counts and invitation actions`() =
        runBlocking {
            val first = AnkiquestFeed.page(account)
            assertFalse(first.legacy)
            assertEquals(7, first.unread)
            assertEquals(1, first.actionCount)
            assertEquals(17L, first.latestId)
            assertEquals(15L, first.nextBefore)
            assertEquals(listOf(17L, 15L), first.items.map { it.id })
            val invitation = first.attention.single()
            assertEquals(4L, invitation.challengeId)
            assertTrue(invitation.actionRequired)
            assertTrue(invitation.unread)
            assertFalse(invitation.answerable)

            val older = AnkiquestFeed.page(account, before = first.nextBefore)
            assertEquals(first.unread, older.unread)
            assertEquals(first.actionCount, older.actionCount)
            assertEquals(first.latestId, older.latestId)
            assertEquals(9L, older.nextBefore)
            assertEquals(listOf(12L, 9L), older.items.map { it.id })
            assertTrue(older.items.all { it.id < assertNotNull(first.nextBefore) })
            assertTrue("GET /api/activity/hill" in requests)
            assertFalse("GET /api/notifications/hill" in requests)
        }

    @Test
    fun `every example is either read by the app or known to be unused`() {
        val directory = File(assertNotNull(javaClass.classLoader?.getResource(EXAMPLES)).toURI())
        val examples =
            assertNotNull(directory.list())
                .filter { it.endsWith(".json") && it != "names.json" }
                .map { it.removeSuffix(".json") }
                .toSet()
        val catalog = JSONObject(example("names"))
        val groups = setOf("consumed", "unused", "boards", "challenges", "not_yet")
        assertEquals(groups, catalog.keys().asSequence().toSet())
        val owned =
            groups.flatMap { group ->
                val names = catalog.getJSONArray(group)
                (0 until names.length()).map { names.getString(it) }
            }
        assertEquals(owned.size, owned.toSet().size, "an example must belong to exactly one group")
        assertEquals(
            owned.toSet(),
            examples,
            "a new or removed example needs a reviewed entry in names.json",
        )
        val consumed = catalog.getJSONArray("consumed")
        assertTrue((0 until consumed.length()).map { consumed.getString(it) }.containsAll(ROUTES.values))
    }

    @Test
    fun `capabilities give the features and, from servers that name it, an https main address`() {
        val answer = JSONObject(example("capabilities"))
        assertTrue("devices" in AnkiquestCapabilities.parse(answer.toString()))
        // Servers before float3/ankiquest#171 do not name a main address.
        if (answer.has("public_url")) {
            assertEquals("https://anki.example.test", AnkiquestServerMove.mainAddress(answer))
        } else {
            assertNull(AnkiquestServerMove.mainAddress(answer))
        }
    }

    @Test
    fun `signing up and signing in keep the returned user and token`() =
        runBlocking {
            val created = AnkiquestAccount.submit(AnkiquestAccount.Credentials(url, "ana", "password", "Ana", create = true), "Pixel 9")
            assertEquals(AnkiquestAccount.Signed(url, "ana", "example-token-1"), created)
            val signed = AnkiquestAccount.submit(AnkiquestAccount.Credentials(url, "ana", "password", "", create = false), "Pixel 9")
            assertEquals(AnkiquestAccount.Signed(url, "ana", "example-token-2"), signed)
            assertTrue(requests.containsAll(listOf("POST /api/accounts", "POST /api/accounts/tokens")))
        }

    @Test
    fun `the profile gives the goal, the streak and the connection summary`() =
        runBlocking {
            val profile = Ankiquest.profile()
            AnkiquestNotifier.onProfile(targetContext, profile)
            assertEquals(
                AnkiquestDailyGoal.ServerGoal(AnkiquestDailyGoal.Choice(AnkiquestDailyGoal.Mode.BACKLOG, 14), since = 20754),
                AnkiquestDailyGoal.cached(AnkiDroidApp.sharedPrefs(), account.scope),
            )
            assertEquals(AnkiquestAki.Mood.STREAK, AnkiquestAki.mood(null, profile))
            assertTrue(assertNotNull(profile.optJSONArray("quests")).objects().all { it.optString("title").isNotEmpty() })

            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "") }
            assertEquals(
                targetContext.getString(R.string.ankiquest_check_ok, "Hill", 2, 101L, 5L) + "\n\n" +
                    targetContext.getString(R.string.ankiquest_check_no_token),
                Ankiquest.runFromSettings(targetContext, uploadAll = false),
            )
        }

    @Test
    fun `an upload answers the profile after reading which decks are shared`() =
        runBlocking {
            val message = Ankiquest.runFromSettings(targetContext, uploadAll = true)
            assertTrue(message.endsWith(targetContext.getString(R.string.ankiquest_check_ok, "Hill", 2, 101L, 5L)), message)
            assertTrue("GET /api/decks/hill" in requests)
            assertTrue("POST /api/reviews/hill" in requests)
            assertNull(Ankiquest.feedback(JSONObject(example("upload")).optJSONObject("feedback"), showFeedback = true))
        }

    @Test
    fun `deck settings list the friends to share with and the switches`() =
        runBlocking {
            val settings = Ankiquest.deckNotificationSettings()
            val people = settings.getJSONArray("recipients").objects()
            assertEquals(listOf("cerro", "friend"), people.map { it.getString("user") })
            assertTrue(people.all { it.getString("display").isNotEmpty() })
            settings.getJSONArray("decks").objects().forEach { deck ->
                deck.getString("id")
                deck.getBoolean("enabled")
                deck.getJSONArray("recipients")
            }
            val switches = Ankiquest.notificationSwitches()
            assertTrue(Ankiquest.celebrations(switches))
            assertFalse(switches.getBoolean(Ankiquest.NUDGES))
        }

    @Test
    fun `telling friends about a finished deck reads how many were told`() =
        runBlocking {
            assertEquals(1, AnkiquestSharing.shareFinishedDeck(account, 1L, "Spanish", listOf("cerro")))
            assertTrue(requests.contains("POST /api/decks/hill/share"))
        }

    @Test
    fun `the leaderboard orders the widget rows`() =
        runBlocking {
            val board = Ankiquest.leaderboard()
            val week = AnkiquestWidget.forPeriod(board, "week")
            assertEquals(listOf("hill", "cerro"), week.objects().map { it.getString("user") })
            val hour = AnkiquestWidget.forPeriod(board, "hour").objects()
            assertEquals(listOf(51L, 30L), hour.map { it.getLong("xp") })
            assertEquals(2, AnkiquestWidget.collection(targetContext, week).itemCount)
        }

    @Test
    fun `the native leaderboard reads each period, the week and both kinds of profile`() =
        runBlocking {
            val week = AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.WEEK)
            assertEquals(listOf("hill", "cerro"), week.map { it.user })
            assertEquals(listOf("Hill", "Cerro"), week.map { it.display })
            assertEquals(listOf(101L, 31L), week.map { it.xp })
            assertEquals(listOf(AnkiquestDay.State.STUDIED, AnkiquestDay.State.STUDIED), week.map { it.state })
            assertEquals(listOf(5L, 3L), week.map { it.todayReviews })
            val day = AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.DAY)
            assertEquals(listOf(101L, 31L), day.map { it.xp })
            assertEquals(1_793_592_000_000L, AnkiquestBoards.weekEnds(account))

            val own = AnkiquestBoards.player(account, "hill")
            assertTrue(own.own)
            assertEquals(2, own.profile.level)
            assertEquals(101L, own.profile.xpTotal)
            assertEquals(280L, own.profile.xpForNext)
            assertEquals(3, own.profile.quests.size)
            assertEquals(101L, own.profile.periods[AnkiquestBoards.Period.WEEK])
            assertEquals(51L, own.profile.records[AnkiquestBoards.Period.HOUR]?.xp)
            assertTrue(own.profile.achievements.isNotEmpty() && own.profile.achievements.all { it.title.isNotEmpty() })
            assertTrue("Daily goal reached!" in own.wins.map { it.title })
            assertTrue(own.wins.size <= AnkiquestBoards.WINS && own.wins.all { it.title.isNotEmpty() })

            val other = AnkiquestBoards.player(account, "cerro")
            assertFalse(other.own)
            assertEquals("Cerro", other.profile.display)
            assertEquals(1, other.profile.level)
            assertTrue(other.wins.isEmpty())
            assertEquals(1, requests.count { it == "GET /api/wins/hill" }, "only the player's own profile asks for wins")
        }

    @Test
    fun `the Global board reads ranks, the player's listing and strangers' summaries`() =
        runBlocking {
            // The player is listed: their full row, then a stranger's summary without today's counts.
            val day = AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.DAY, AnkiquestBoards.Source.Global)
            assertEquals(listOf("hill", "ana"), day.map { it.user })
            assertEquals(listOf("Hill", "Ana"), day.map { it.display })
            assertEquals(listOf(1, 2), day.map { it.rank })
            assertEquals(listOf(107L, 20L), day.map { it.xp })
            assertEquals(listOf(true, null), day.map { it.shown })
            assertEquals(listOf(true, false), day.map { it.hasToday })
            assertEquals(5L, day.first().todayReviews)
            assertEquals(listOf(AnkiquestDay.State.STUDIED, AnkiquestDay.State.UNKNOWN), day.map { it.state })

            // A private profile not yet listed: others' summaries, then the player's own row last with its rank.
            val hidden = AnkiquestBoards.leaderboard(account, AnkiquestBoards.Period.WEEK, AnkiquestBoards.Source.Global)
            assertEquals(listOf("hill", "cerro", "ana"), hidden.map { it.user })
            assertEquals(listOf(1, 2, 3), hidden.map { it.rank })
            assertEquals(listOf(246L, 31L, 20L), hidden.map { it.xp })
            assertEquals(listOf(null, null, false), hidden.map { it.shown })
            assertEquals(listOf(false, false, true), hidden.map { it.hasToday })
            assertEquals(2L, hidden.last().todayReviews)
            assertEquals(2, requests.count { it == "GET /api/leaderboard" })

            // The widget orders the same rows by rank and takes strangers' XP from `xp`.
            val widgetDay = AnkiquestWidget.forPeriod(JSONArray(example("leaderboard_global")), "day").objects()
            assertEquals(listOf(107L, 20L), widgetDay.map { it.getLong("xp") })
            val widgetWeek = AnkiquestWidget.forPeriod(JSONArray(example("leaderboard_global_hidden")), "week").objects()
            assertEquals(listOf("hill", "cerro", "ana"), widgetWeek.map { it.getString("user") })
            assertEquals(listOf(246L, 31L, 20L), widgetWeek.map { it.getLong("xp") })
        }

    @Test
    fun `the Global listing switch is offered from the settings and saved with the server's answer`() =
        runBlocking {
            val global = AnkiquestWidgetSettings.GLOBAL_SETTING
            assertTrue(AnkiquestBoards.hasScopes(account))
            assertTrue(AnkiquestWidgetSettings.hasScopes(account))
            assertEquals(true, Ankiquest.notificationSwitches().opt(global) as? Boolean)
            assertTrue(Ankiquest.setNotificationSwitch(global, true).getBoolean(global))
            assertTrue("POST /api/decks/hill" in requests)
        }

    @Test
    fun `a rank change is announced`() =
        runBlocking {
            AnkiquestNotifier.onLeaderboard(targetContext)
            val announced = shadowOf(manager).allNotifications.single()
            assertEquals("👑 You took the crown", announced.title())
            assertTrue("You passed Cerro." in announced.text())
            val change = JSONObject(example("rank")).getJSONObject("change")
            assertEquals(
                "👑 I took the crown" to "I passed Cerro.",
                AnkiquestNotifier.shareText(change),
                "friends read it in the first person",
            )
            change.remove("share_title")
            change.remove("share_body")
            assertEquals("👑 You took the crown" to "You passed Cerro.", AnkiquestNotifier.shareText(change), "older servers")
        }

    @Test
    fun `the inbox shows a celebration, a friend's win with its card and a cheer`() =
        runBlocking {
            val inbox = assertNotNull(Ankiquest.completionNotifications())
            AnkiquestNotifier.onDeckCompletions(targetContext, inbox.account, inbox.notifications, inbox.scope)
            val celebration = shadowOf(manager).getNotification(5_140_001)
            assertEquals("Daily goal reached!", celebration.title())
            assertEquals(listOf("Share"), celebration.actions.map { it.title.toString() })
            val sharesGoal = shadowOf(celebration.actions.single().actionIntent).savedIntent
            assertEquals("Daily goal reached!", sharesGoal.getStringExtra(AnkiquestShare.EXTRA_TITLE), "it reads the same for anyone")
            val level = shadowOf(manager).getNotification(5_140_009)
            assertEquals("You reached level 3 with 400 XP.", level.text(), "the player is told about it")
            val sharesLevel = shadowOf(level.actions.single().actionIntent).savedIntent
            assertEquals("Level 3", sharesLevel.getStringExtra(AnkiquestShare.EXTRA_TITLE))
            assertEquals(
                "I reached level 3 with 400 XP.",
                sharesLevel.getStringExtra(AnkiquestShare.EXTRA_BODY),
                "friends hear it from the player",
            )
            val share = shadowOf(manager).getNotification(5_140_006)
            assertEquals("🎉 Cerro shared a win", share.title())
            assertEquals(listOf("Reply"), share.actions.map { it.title.toString() }, "the example was already cheered")
            verify { AnkiquestShare.picture("https://anki.example.test/w/example-link-2.png") }
            val reaction = shadowOf(manager).getNotification(5_140_008)
            assertEquals("🎉 Cerro cheered", reaction.title())
            assertEquals(9L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:${inbox.account}", 0))
        }

    @Test
    fun `sharing a win, cheering one and the stored settings are read`() =
        runBlocking {
            assertEquals(2, Ankiquest.shareWinWithFriends(1, account.scope))
            val link = Ankiquest.shareWinLink(1, account.scope)
            assertTrue(link.url.contains("/w/"), link.url)
            assertTrue(link.image.endsWith(".png"), link.image)
            assertTrue(link.text.contains(link.url), link.text)
            assertFalse(Ankiquest.react(6, account.notificationAccount, account.scope))
            val sharing = AnkiquestSharing.settings(account)
            assertEquals(3, sharing.dailyCap)
            assertEquals("ask", sharing.policies["daily_goal"])
            assertTrue(sharing.policies.values.all { it in setOf("private", "ask", "auto") })
            assertEquals(
                AnkiquestDailyGoal.ServerGoal(AnkiquestDailyGoal.Choice(AnkiquestDailyGoal.Mode.BACKLOG, 14), since = 20754, chosen = true),
                AnkiquestDailyGoal.ServerGoal.parse(AnkiquestSharing.goal(account)),
            )
            assertEquals("aki", AnkiquestHomeData.companion(account))
            assertFalse(Ankiquest.streakProtectionEnabled())
        }

    @Test
    fun `friends and groups list requests, friends, muted players and groups, and every change answers the list`() =
        runBlocking {
            val overview = AnkiquestSocial.overview(account)
            assertEquals(listOf("friend"), overview.friends.map { it.user })
            assertEquals(listOf("ana"), overview.incoming.map { it.user })
            assertEquals(listOf("cerro"), overview.outgoing.map { it.user })
            assertTrue(overview.isMuted("cerro"))
            val owned = overview.groups.single { it.owner }
            val code = assertNotNull(owned.invite)
            assertTrue(owned.members.any { it.user == "hill" })
            assertTrue(overview.groups.filterNot { it.owner }.all { it.invite == null })
            assertTrue(overview.groups.all { group -> group.members.all { it.display.isNotEmpty() } })

            val invite = AnkiquestSocial.invite(account, code)
            assertEquals(owned.name, invite.name)
            assertEquals(owned.members.size, invite.members)

            val created = AnkiquestSocial.change(account, AnkiquestSocial.Change.CreateGroup("Spanish club"))
            assertNotNull(created.groups.single { it.name == "Spanish club" }.invite)
            val friends = AnkiquestSocial.change(account, AnkiquestSocial.Change.Befriend("ana"))
            assertTrue(friends.friends.any { it.user == "ana" })
            assertTrue(friends.incoming.isEmpty())
            val muted = AnkiquestSocial.change(account, AnkiquestSocial.Change.Mute("cerro"))
            assertEquals(listOf("cerro"), muted.muted.map { it.user })
            assertTrue(
                requests.containsAll(listOf("GET /api/social/hill", "POST /api/social/hill", "GET /api/invites/$code")),
            )
        }

    @Test
    fun `friend goals list invitations, progress and results, and answer every change with the list`() =
        runBlocking {
            val overview = AnkiquestChallenges.overview(account)
            val now = EXAMPLE_NOW
            val invitation = overview.challenges.single { it.invites("hill", now) }
            assertEquals("Fifty reviews each", invitation.title)
            assertTrue(invitation.reviews)
            assertEquals("friend", invitation.creator)
            val days = overview.challenges.single { it.title == "Three study days together" }
            assertEquals(listOf("accepted", "invited", "accepted"), days.members.map { it.status })
            assertEquals(1, days.progressFor("hill"))
            assertEquals(3, days.target)
            assertTrue(days.started && !days.ended(now))
            val together = overview.challenges.single { it.cooperative }
            assertEquals("complete", together.status)
            assertEquals(8, together.progressFor("hill"))
            assertTrue(overview.challenges.single { it.status == "cancelled" }.ended(now))
            assertEquals(listOf("cerro", "friend"), overview.recipients.map { it.user })
            assertEquals("Cerro", overview.suggestion?.friend?.display)
            assertTrue(overview.suggestion!!.weekStart > 0)

            val created =
                AnkiquestChallenges.create(
                    account,
                    AnkiquestChallenges.NewGoal("Three study days together", false, false, 3, 7, listOf("cerro", "friend"), false),
                    "example-request-1",
                )
            assertEquals(listOf("Three study days together"), created.challenges.map { it.title })
            val accepted = AnkiquestChallenges.act(account, invitation.id, "accept")
            assertFalse(accepted.challenges.any { it.invites("hill", now) })
            assertEquals(
                "accepted",
                accepted.challenges
                    .single { it.id == invitation.id }
                    .member("hill")
                    ?.status,
            )
            assertTrue(
                requests.containsAll(
                    listOf(
                        "GET /api/community/challenges/hill",
                        "POST /api/community/challenges/hill",
                        "POST /api/community/challenges/hill/${invitation.id}",
                    ),
                ),
            )
        }

    @Test
    fun `community reminders read the defaults and answer a save with what the server kept`() =
        runBlocking {
            val defaults = AnkiquestCommunity.reminders(account)
            assertEquals(AnkiquestCommunity.Reminders(emptySet(), hour = 20, quietStart = 22, quietEnd = 9, dailyLimit = 2), defaults)
            val wanted =
                AnkiquestCommunity.Reminders(
                    enabled =
                        setOf(
                            AnkiquestCommunity.Reminder.GENTLE_DAILY,
                            AnkiquestCommunity.Reminder.URGENT_STREAK,
                            AnkiquestCommunity.Reminder.MILESTONE,
                            AnkiquestCommunity.Reminder.WEEKLY_RECAP,
                        ),
                    hour = 19,
                    quietStart = 22,
                    quietEnd = 8,
                    dailyLimit = 2,
                )
            assertEquals(wanted, AnkiquestCommunity.saveReminders(account, wanted))
            assertTrue(requests.containsAll(listOf("GET /api/community/reminders/hill", "POST /api/community/reminders/hill")))
        }

    @Test
    fun `the community calendar has the month's days, the year's weeks and seasons and their standings`() =
        runBlocking {
            val calendar = AnkiquestCommunity.calendar(account, 2026, 10)
            assertEquals(2026 to 10, calendar.year to calendar.month)
            assertEquals(listOf("2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28"), calendar.days.map { it.key })
            val first = calendar.days.first()
            assertEquals(listOf("hill"), first.winners)
            assertTrue(first.final && first.reconstructed)
            assertEquals("provisional", calendar.days[2].status)
            assertTrue(calendar.days[2].winners.isEmpty())
            val week = calendar.weeks.last()
            assertEquals("2026-10-26" to "2026-11-02", week.start to week.end)
            assertEquals(listOf("hill", "cerro", "friend"), week.standings.map { it.user })
            assertEquals(132, week.margin)
            assertEquals(1, calendar.seasons.size)
            assertEquals("Cerro", calendar.name("cerro"))
            assertEquals("UTC", calendar.timeZone)
            assertEquals(4, calendar.rolloverHour)
            assertTrue(calendar.scoringNote.isNotEmpty())
            assertEquals(week, calendar.find("week:2026-10-26"))
            assertTrue(requests.contains("GET /api/community"))
        }

    @Test
    fun `a conquered card shows its numbers, its label and the owner's choices, and tells friends`() =
        runBlocking {
            val at = 1_793_097_000_000L
            val card = AnkiquestCommunity.conquest(account, "hill", at)
            assertEquals(at, card.at)
            assertEquals(4, card.lapses)
            assertEquals(11, card.answers)
            assertEquals(10, card.days)
            assertEquals("el murciélago", card.label)
            assertTrue(card.mine)
            assertEquals(false, card.told)
            assertEquals(0, card.friends)
            assertEquals(6L, card.win)
            assertEquals(0, AnkiquestCommunity.tell(account, at))
            assertTrue(requests.containsAll(listOf("GET /api/conquests/hill/$at", "POST /api/conquests/hill/$at/tell")))
            assertFalse(card.undoable(EXAMPLE_NOW), "nothing to take back before telling")
        }

    @Test
    fun `telling friends about a conquered card can be taken back`() =
        runBlocking {
            // Servers before taking it back was possible have no such example.
            assumeTrue(javaClass.classLoader?.getResource("$EXAMPLES/conquest_untell.json") != null)
            assertEquals(0, AnkiquestCommunity.untell(account, 1_793_097_000_000L))
            assertTrue(requests.contains("DELETE /api/conquests/hill/1793097000000/tell"))
        }

    @Test
    fun `account settings read sign-ins, devices and the account`() =
        runBlocking {
            val methods = AnkiquestAccountSettings.methods(account)
            assertTrue(methods.account)
            assertTrue(methods.password)
            assertEquals(
                listOf(
                    AnkiquestAccountSettings.Provider("google", "Google", linked = false),
                    AnkiquestAccountSettings.Provider("github", "GitHub", linked = true),
                ),
                methods.providers,
            )
            // Servers before linked_unavailable do not say.
            assertTrue(methods.linkedUnavailable != true, "the example's GitHub is offered")
            assertEquals(methods, AnkiquestAccountSettings.setPassword(account, "old password", "new password!", signOutOthers = false))

            val devices = AnkiquestAccountSettings.devices(account)
            assertEquals(listOf(true, false), devices.map { it.current })
            assertEquals(listOf("Pixel 9", "Pixel 8"), devices.map { it.name })
            assertEquals(listOf(1_793_183_400_000L, null), devices.map { it.lastUsedAt })
            assertEquals(listOf("example-device-1"), AnkiquestAccountSettings.signOutOthers(account).map { it.id })

            val summary = AnkiquestAccountSettings.summary(account)
            assertEquals(AnkiquestAccountSettings.Summary("ana", password = true, confirmedUntil = null), summary)

            assertFailsWith<AnkiquestAccountSettings.ForeignAddress>("the example points at another server than this test's") {
                AnkiquestAccountSettings.browser(account, "github", AnkiquestAccountSettings.Purpose.REAUTH)
            }
            assertEquals(
                "https://anki.example.test/auth/oauth/github?ticket=example-ticket-1",
                JSONObject(example("sign_in_browser")).getString("url"),
            )
            assertTrue(
                requests.containsAll(
                    listOf(
                        "GET /api/sign-in/hill",
                        "POST /api/sign-in/hill",
                        "GET /api/devices/hill",
                        "POST /api/devices/hill",
                        "GET /api/accounts/hill",
                    ),
                ),
            )
        }

    @Test
    fun `the record boards and the study calendar are read as the native screens show them`() =
        runBlocking {
            val boards = AnkiquestRecords.records(account)
            assertEquals(AnkiquestRecords.Kind.entries, boards.map { it.kind }, "every board, in the website's order")
            assertTrue(boards.all { board -> board.holders.isNotEmpty() && board.holders.all { it.display.isNotEmpty() && it.at > 0 } })
            assertEquals(listOf("hill", "cerro"), boards.first { it.kind == AnkiquestRecords.Kind.DAY }.holders.map { it.user })
            assertEquals(setOf("xp", "days"), boards.map { it.unit }.toSet())
            assertTrue("GET /api/records" in requests)

            val study = AnkiquestRecords.study(account)
            assertEquals(2026, study.year)
            assertTrue(study.year in study.years)
            assertTrue(study.days.zipWithNext().all { (earlier, later) -> earlier.date < later.date })
            assertTrue(study.reviews > 0 && study.studyDays > 0 && study.xp > 0)
            assertTrue(study.days.any { it.reviews == 0 }, "days off are listed too")
            assertNotNull(study.latest)
            assertNotNull(study.lastReceivedAt)
            assertEquals(study.reviews.toInt(), study.heatmap(study.days.last().date).totalReviews.toInt())
            assertTrue("GET /api/study/hill" in requests)
        }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun route(
        method: String,
        path: String,
        sent: String,
        query: String? = null,
    ): String? {
        val parts = path.removePrefix("/api/").split('/')
        val params = query.orEmpty().split('&')
        return when {
            path == "/api/capabilities" -> "capabilities"
            path == "/api/leaderboard/hidden/hill" -> "leaderboard_hide"
            path == "/api/activity/hill" && method == "GET" ->
                when (params.firstOrNull { it.startsWith("before=") }) {
                    null -> "activity"
                    "before=15" -> "activity_older"
                    else -> null
                }
            path == "/api/leaderboard" && "scope=global" in params ->
                if ("period=day" in params) "leaderboard_global" else "leaderboard_global_hidden"
            path == "/api/leaderboard" && "period=day" in params -> "leaderboard_day"
            path == "/api/week" -> "week"
            path == "/api/profile/cerro" -> "player_profile"
            parts.size == 2 && parts[0] == "wins" -> "wins"
            parts.firstOrNull() == "share" && sent.isNotBlank() && JSONObject(sent).optString("to") == "link" -> "share_to_link"
            path == "/api/accounts" && method == "POST" -> "sign_up"
            path == "/api/accounts/tokens" && method == "POST" -> "sign_in"
            path == "/api/leaderboard" -> "leaderboard"
            path == "/api/sign-in/hill" && method == "GET" -> "sign_in_methods"
            path == "/api/sign-in/hill" && JSONObject(sent).optString("action") == "password" -> "password_change"
            path == "/api/sign-in/hill" && JSONObject(sent).optString("action") == "browser" -> "sign_in_browser"
            path == "/api/decks/hill" && method == "POST" && isGlobalSwitch(sent) -> "global_leaderboard_on"
            path == "/api/devices/hill" -> if (method == "GET") "devices" else "devices_sign_out"
            path == "/api/accounts/hill" && method == "GET" -> "account"
            path == "/api/records" -> "records"
            parts.size == 3 && parts[0] == "decks" && parts[2] == "share" && method == "POST" -> "deck_share"
            parts.size == 2 && parts[0] == "social" -> if (method == "POST") socialChange(sent) else "social"
            parts.size == 2 && parts[0] == "invites" -> "invite"
            parts.size == 3 && parts[0] == "community" && parts[1] == "challenges" ->
                if (method ==
                    "POST"
                ) {
                    "challenge_create"
                } else {
                    "challenges"
                }
            parts.size == 4 && parts[0] == "community" && parts[1] == "challenges" && method == "POST" -> "challenge_action"
            path == "/api/community" -> "community"
            parts.size == 3 && parts[0] == "community" && parts[1] == "reminders" -> if (method == "POST") "reminders_save" else "reminders"
            parts.size == 3 && parts[0] == "conquests" && method == "GET" -> "conquest"
            parts.size == 4 && parts[0] == "conquests" && parts[3] == "tell" && method == "POST" -> "conquest_tell"
            parts.size == 4 && parts[0] == "conquests" && parts[3] == "tell" && method == "DELETE" -> "conquest_untell"
            parts.size == 2 && path.startsWith("/api/") -> ROUTES[parts[0]]
            else -> null
        }
    }

    /** Whether [sent] turns the Global listing on and changes no deck, as `global_leaderboard_on` records. */
    private fun isGlobalSwitch(sent: String): Boolean {
        if (sent.isBlank()) {
            return false
        }
        val body = JSONObject(sent)
        return body.optBoolean(AnkiquestWidgetSettings.GLOBAL_SETTING) && body.optJSONArray("decks")?.length() == 0
    }

    /** The recorded answer for one `POST /api/social` action; every action answers the same overview. */
    private fun socialChange(sent: String): String =
        when (JSONObject(sent).optString("action")) {
            "create_group" -> "group_create"
            "mute" -> "mute"
            else -> "social_change"
        }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("$EXAMPLES/$name.json"), "$name.json").readText(Charsets.UTF_8)

    private companion object {
        const val EXAMPLES = "ankiquest-api"
        const val EXAMPLE_NOW = 1_793_183_460_000L

        val ROUTES =
            mapOf(
                "profile" to "profile",
                "reviews" to "upload",
                "rank" to "rank",
                "notifications" to "notifications",
                "share" to "share_to_friends",
                "react" to "react",
                "goal" to "goal",
                "sharing" to "sharing",
                "decks" to "decks",
                "companion" to "companion",
                "streak-freezes" to "streak_freezes",
                "study" to "study",
            )
    }
}
