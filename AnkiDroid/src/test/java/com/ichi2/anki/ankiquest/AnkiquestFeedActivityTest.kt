// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestFeedActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "member", "secret")
    private val page = AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedTest.PAGE))

    @Before
    fun prepareFeed() {
        mockkObject(AnkiquestHomeData, AnkiquestFeed, AnkiquestShare)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns page
        coEvery { AnkiquestFeed.markRead(identity, any()) } returns Unit
        coEvery { AnkiquestFeed.markAllRead(identity, any()) } returns Unit
        coEvery { AnkiquestFeed.cheer(identity, any()) } returns false
        coEvery { AnkiquestFeed.reply(identity, any(), any()) } returns "Cerro"
        every { AnkiquestShare.picture(any()) } returns createBitmap(4, 4)
    }

    @After
    fun clearFeedMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestFeed, AnkiquestShare)
    }

    @Test
    fun `the feed shows friends' wins, cheers, replies and invitations`() {
        val feed = launch()
        assertTrue(feed.hasText("🎉 Cerro shared a win"))
        assertTrue(feed.hasText("7 day streak!: “A week in a row.”"))
        assertTrue(feed.hasText("🎉 Cerro cheered: “Daily goal reached!”"))
        assertFalse(feed.hasText("“Daily goal reached!”"), "a cheer's body is in its heading")
        assertTrue(feed.hasText("Cerro replied"))
        assertTrue(feed.hasText("Spanish is done for today."), "the message a reply answers is quoted")
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_reply_sent)))
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_needs_action_count, 1)))
        assertEquals(
            1,
            feed.views<TextView>().count { it.text.toString() == "Challenge invitation" },
            "the invitation is shown once, first",
        )
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_cheered)), "an already cheered win says so")
        advanceRobolectricLooperUntil {
            feed.views<ImageView>().any {
                it.contentDescription ==
                    feed.getString(R.string.aq_feed_card, "🎉 Cerro shared a win")
            }
        }
    }

    @Test
    fun `cheering a friend's deck completion sends a cheer and shows it`() {
        val completion =
            AnkiquestFeed.item(
                JSONObject()
                    .put("id", 31)
                    .put("title", "Cerro finished Spanish")
                    .put("body", "Spanish is done for today.")
                    .put("created_at", 1793183400)
                    .put("sender", "cerro")
                    .put("kind", "completion"),
            )!!
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns page.copy(items = listOf(completion), attention = emptyList())
        val feed = launch()
        feed.click(feed.getString(R.string.ankiquest_cheer))
        coVerify { AnkiquestFeed.cheer(identity, completion) }
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_cheer_sent)))
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_cheered)))
        assertFalse(feed.hasText(feed.getString(R.string.ankiquest_cheer)))
    }

    @Test
    fun `a quick reply answers the row and marks it read`() {
        val share = page.items[1]
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns
            page.copy(items = listOf(share), attention = emptyList(), unread = 1)
        val feed = launch()
        feed.click(feed.getString(R.string.ankiquest_reply_cheer))
        coVerify { AnkiquestFeed.reply(identity, share, "Good job!") }
        coVerify { AnkiquestFeed.markRead(identity, listOf(6L)) }
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_reply_done)))
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_reply_sent)))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_mark_read)))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_mark_all_read)), "nothing unread is left")
    }

    @Test
    fun `a failed reply says why and keeps the reply buttons`() {
        coEvery { AnkiquestFeed.reply(identity, any(), any()) } throws IOException("offline")
        val feed = launch()
        feed.click(feed.getString(R.string.ankiquest_reply_cheer))
        assertTrue(
            feed.hasText(
                feed.getString(
                    R.string.aq_feed_failed_because,
                    feed.getString(R.string.aq_feed_reply_failed),
                    feed.getString(R.string.aq_feed_offline),
                ),
            ),
        )
        assertTrue(feed.views<TextView>().any { it.text.toString() == feed.getString(R.string.ankiquest_reply_cheer) && it.isEnabled })
    }

    @Test
    fun `mark all as read marks through the newest row`() {
        val feed = launch()
        feed.click(feed.getString(R.string.aq_feed_mark_all_read))
        coVerify { AnkiquestFeed.markAllRead(identity, 42) }
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_all_read)))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_mark_all_read)))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_mark_read)))
    }

    @Test
    fun `filters ask the server for that category or unread rows`() {
        val feed = launch()
        feed.click(feed.getString(R.string.aq_feed_messages))
        coVerify { AnkiquestFeed.page(identity, AnkiquestFeed.Category.MESSAGES, false, null) }
        feed.click(feed.getString(R.string.aq_feed_unread_only))
        coVerify { AnkiquestFeed.page(identity, AnkiquestFeed.Category.MESSAGES, true, null) }
    }

    @Test
    fun `earlier activity is appended`() {
        val older = page.items[3].copy(id = 4, title = "An older message")
        coEvery { AnkiquestFeed.page(identity, any(), any(), 5) } returns page.copy(items = listOf(older), nextBefore = null)
        val feed = launch()
        feed.click(feed.getString(R.string.aq_feed_more))
        assertTrue(feed.hasText("An older message"))
        assertTrue(feed.hasText("🎉 Cerro shared a win"))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_more)))
    }

    @Test
    fun `an invitation opens its goal and is marked read`() {
        val feed = launch()
        feed.click(feed.getString(R.string.aq_feed_view_invitation))
        val opened = assertNotNull(shadowOf(feed).nextStartedActivity)
        assertEquals(AnkiquestChallengesActivity::class.java.name, opened.component?.className)
        assertEquals(8L, opened.getLongExtra(AnkiquestChallengesActivity.EXTRA_CHALLENGE, 0))
        coVerify { AnkiquestFeed.markRead(identity, listOf(42L)) }
    }

    @Test
    fun `sent messages are a tab of their own`() {
        coEvery { AnkiquestFeed.sent(identity, any()) } returns
            AnkiquestFeed.SentPage(listOf(AnkiquestFeed.Sent("cerro", page.items[3].copy(body = "Well done!", readAt = null))), null)
        val feed = launch()
        feed.click(feed.getString(R.string.aq_feed_sent))
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_sent_to, "cerro")))
        assertTrue(feed.hasText("Well done!"))
        assertTrue(feed.hasTextContaining(feed.getString(R.string.aq_feed_not_seen)))
        assertFalse(feed.hasText("🎉 Cerro shared a win"))
    }

    @Test
    fun `an older server's list has no filters or read state`() {
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } returns
            AnkiquestFeed.parseLegacy(org.json.JSONArray(AnkiquestFeedTest.LEGACY))
        val feed = launch()
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_legacy)))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_messages)))
        assertFalse(feed.hasText(feed.getString(R.string.aq_feed_mark_read)))
        assertTrue(feed.hasText("Daily goal reached!"))
    }

    @Test
    fun `offline and refused feeds say what to do`() {
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_feed_offline)))

        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } throws AnkiquestHttpException(401)
        val refused = launch()
        assertTrue(refused.hasText(refused.getString(R.string.aq_home_auth)))
        refused.click(refused.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(refused).nextStartedActivity.component?.className)
    }

    @Test
    fun `without an account the feed asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val feed = launch()
        assertTrue(feed.hasText(feed.getString(R.string.aq_feed_connect)))
        coVerify(exactly = 0) { AnkiquestFeed.page(any(), any(), any(), any()) }
    }

    @Test
    fun `a notification for another account shows this account's feed and says so`() {
        val feed = launch("https://other.example.test/member")
        assertTrue(feed.hasText(feed.getString(R.string.aq_home_account_route_changed)))
        assertTrue(feed.hasText("🎉 Cerro shared a win"))
        assertFalse(launch(identity.notificationAccount).hasText(feed.getString(R.string.aq_home_account_route_changed)))
    }

    private fun launch(account: String? = null): AnkiquestFeedActivity =
        startRegularActivity<AnkiquestFeedActivity>(AnkiquestFeedActivity.intent(targetContext, account))
            .also { feed -> advanceRobolectricLooperUntil { !feed.findViewById<View>(R.id.aq_feed_loading).isVisible } }

    private inline fun <reified T : View> AnkiquestFeedActivity.views(): List<T> =
        findViewById<View>(R.id.aq_feed_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestFeedActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestFeedActivity.hasTextContaining(value: String): Boolean =
        views<TextView>().any { it.text.toString().contains(value) }

    private fun AnkiquestFeedActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_feed_loading).isVisible }
        advanceRobolectricLooper()
    }
}
