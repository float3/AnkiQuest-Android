// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.setIntroductionSlidesShown
import io.mockk.coEvery
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestTabBarTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")

    @Before
    fun prepareTabs() {
        setIntroductionSlidesShown(true)
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, identity.server)
            putString(Ankiquest.PREF_USER, identity.user)
            putString(Ankiquest.PREF_TOKEN, identity.token)
        }
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestFeed, AnkiquestAvatars)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        coEvery { AnkiquestHomeData.profile(identity) } returns JSONObject().put("level", 2)
        coEvery { AnkiquestHomeData.companion(identity) } throws IOException("offline")
        coEvery { AnkiquestSocial.overview(identity) } throws IOException("offline")
        coEvery { AnkiquestBoards.player(identity, any()) } throws IOException("offline")
        coEvery { AnkiquestBoards.leaderboard(identity, any()) } throws IOException("offline")
        coEvery { AnkiquestFeed.page(identity, any(), any(), any()) } throws IOException("offline")
        coEvery { AnkiquestAvatars.refresh(any(), any()) } returns Unit
    }

    @After
    fun clearTabMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestBoards, AnkiquestFeed, AnkiquestAvatars)
    }

    @Test
    fun `every tab shows the same bar with its own item selected`() {
        val tabs =
            listOf(
                startActivityNormallyOpenCollectionWithIntent(
                    AnkiquestHomeActivity::class.java,
                    AnkiquestHomeActivity.intent(targetContext),
                )
                    to R.id.ankiquest_nav_today,
                startRegularActivity<AnkiquestFriendsActivity>(
                    AnkiquestFriendsActivity.intent(targetContext),
                ) to R.id.ankiquest_nav_friends,
                startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent()) to R.id.ankiquest_nav_decks,
            )
        for ((screen, selected) in tabs) {
            val bar = bar(screen)
            val name = screen::class.java.simpleName
            assertTrue(bar.isVisible, name)
            assertEquals(3, bar.menu.size, name)
            assertEquals(
                listOf(R.id.ankiquest_nav_decks, R.id.ankiquest_nav_today, R.id.ankiquest_nav_friends),
                (0 until bar.menu.size).map { bar.menu[it].itemId },
                name,
            )
            assertEquals(selected, bar.selectedItemId, name)
        }
    }

    @Test
    fun `every bar reaches the bottom edge and pads its items above the gesture area`() {
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                .build()
        val screens =
            listOf(
                startActivityNormallyOpenCollectionWithIntent(
                    AnkiquestHomeActivity::class.java,
                    AnkiquestHomeActivity.intent(targetContext),
                ),
                startRegularActivity<AnkiquestFriendsActivity>(AnkiquestFriendsActivity.intent(targetContext)),
            )
        for (screen in screens) {
            val bar = bar(screen)
            val root = bar.parent as View
            ViewCompat.dispatchApplyWindowInsets(root, insets)
            val name = screen::class.java.simpleName
            assertEquals(24, root.paddingTop, name)
            assertEquals(0, root.paddingBottom, "$name leaves the bottom inset to the bar")
            assertEquals(48, bar.paddingBottom, name)
        }
        val decks = bar(startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent()))
        ViewCompat.dispatchApplyWindowInsets(decks, insets)
        assertEquals(48, decks.paddingBottom, "the deck list's bar is padded the same way")
    }

    @Test
    fun `switching tabs replaces the open tab without an animation`() {
        val friends = startRegularActivity<AnkiquestFriendsActivity>(AnkiquestFriendsActivity.intent(targetContext))
        val bar = bar(friends)
        bar.selectedItemId = R.id.ankiquest_nav_today
        val today = assertNotNull(shadowOf(friends).nextStartedActivity)
        assertEquals(AnkiquestHomeActivity::class.java.name, today.component?.className)
        assertTrue(today.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertTrue(friends.isFinishing, "Friends is replaced, not left under Today")
        assertEquals(R.id.ankiquest_nav_friends, bar.selectedItemId)

        val home = startRegularActivity<AnkiquestHomeActivity>(AnkiquestHomeActivity.intent(targetContext))
        bar(home).selectedItemId = R.id.ankiquest_nav_decks
        val decks = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(DeckPicker::class.java.name, decks.component?.className)
        assertTrue(decks.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(decks.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertTrue(home.isFinishing)

        val same = startRegularActivity<AnkiquestFriendsActivity>(AnkiquestFriendsActivity.intent(targetContext))
        bar(same).selectedItemId = R.id.ankiquest_nav_friends
        assertNull(shadowOf(same).nextStartedActivity, "the open tab stays")
        assertFalse(same.isFinishing)
    }

    @Test
    fun `the deck list stays under the tab it opens`() {
        val picker = startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent())
        bar(picker).selectedItemId = R.id.ankiquest_nav_friends
        val friends = assertNotNull(shadowOf(picker).nextStartedActivity)
        assertEquals(AnkiquestFriendsActivity::class.java.name, friends.component?.className)
        assertFalse(picker.isFinishing)
    }

    @Test
    fun `the legacy Progress shortcut opens Me without needing a fourth tab`() {
        val picker = startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent())
        val key = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3, 0, KeyEvent.META_ALT_ON)
        assertTrue(AnkiquestDeckPickerHooks.dispatchNavigationKey(picker, key))
        val me = assertNotNull(shadowOf(picker).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, me.component?.className)
        assertFalse(picker.isFinishing)
        assertEquals(R.id.ankiquest_nav_decks, bar(picker).selectedItemId)
    }

    @Test
    fun `another player's profile is a page of its own without the bar`() {
        val other = startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, "ana"))
        assertNull(other.findViewById<View>(R.id.ankiquest_tab_bar))
    }

    @Test
    fun `tab screens have no back arrow and Back still leaves them`() {
        val home =
            startActivityNormallyOpenCollectionWithIntent(
                AnkiquestHomeActivity::class.java,
                AnkiquestHomeActivity.intent(targetContext),
            )
        val friends = startRegularActivity<AnkiquestFriendsActivity>(AnkiquestFriendsActivity.intent(targetContext))
        val feed = startRegularActivity<AnkiquestFriendsActivity>(AnkiquestFeedActivity.intent(targetContext))
        val board = startRegularActivity<AnkiquestFriendsActivity>(AnkiquestLeaderboardActivity.intent(targetContext))
        for (tab in listOf(home, friends, feed, board)) {
            val name = tab::class.java.simpleName
            assertTrue(backControls(tab).isEmpty(), "$name shows no back arrow")
        }
        val title = friends.findViewById<View>(R.id.aq_feed_title)
        val back = friends.findViewById<View>(R.id.aq_feed_back)
        assertEquals(
            back.paddingStart,
            (title.layoutParams as ViewGroup.MarginLayoutParams).marginStart,
            "the title starts where the arrow did",
        )

        friends.onBackPressedDispatcher.onBackPressed()
        assertTrue(friends.isFinishing, "Friends goes back to the deck list underneath")
    }

    @Test
    fun `pages without the bar keep their back arrow`() {
        val pages =
            listOf(
                startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext)),
                startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, "ana")),
            )
        for (page in pages) {
            val name = page::class.java.simpleName
            assertNull(page.findViewById<View>(R.id.ankiquest_tab_bar), name)
            val back = backControls(page).singleOrNull()
            assertNotNull(back, "$name has a back arrow")
            back.performClick()
            assertTrue(page.isFinishing, name)
        }
    }

    @Test
    fun `Today avatar opens Me above Today and Back returns to it`() {
        val home = startRegularActivity<AnkiquestHomeActivity>(AnkiquestHomeActivity.intent(targetContext))
        val avatar = assertNotNull(home.findViewById<View>(R.id.aq_home_account))
        assertEquals(home.getString(R.string.ankiquest_me), avatar.contentDescription)
        val node = avatar.createAccessibilityNodeInfo()
        assertEquals(android.widget.Button::class.java.name, node.className.toString())
        assertTrue(node.isClickable)
        val touchTarget = (48 * home.resources.displayMetrics.density).toInt()
        assertTrue(avatar.width >= touchTarget && avatar.height >= touchTarget)
        avatar.performClick()
        val destination = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, destination.component?.className)
        assertFalse(destination.hasExtra(AnkiquestProfileActivity.EXTRA_USER))
        assertFalse(home.isFinishing, "Me keeps Today underneath")
        val me = startRegularActivity<AnkiquestProfileActivity>(destination)
        assertNull(me.findViewById<View>(R.id.ankiquest_tab_bar))
        assertTrue(me.findViewById<View>(R.id.aq_board_back).isVisible)
        assertEquals(home.getString(R.string.ankiquest_me), me.findViewById<android.widget.TextView>(R.id.aq_board_title).text)
        me.onBackPressedDispatcher.onBackPressed()
        assertTrue(me.isFinishing)
    }

    /** The visible controls of [screen] announced as going back. */
    private fun backControls(screen: Activity): List<View> {
        val back = screen.getString(R.string.aq_feed_back)
        val found = mutableListOf<View>()

        fun visit(view: View) {
            if (!view.isShown) return
            if (view.contentDescription == back) found += view
            if (view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
        }
        visit(screen.window.decorView)
        return found
    }

    private fun bar(screen: Activity): BottomNavigationView =
        assertNotNull(
            screen.findViewById<BottomNavigationView>(R.id.ankiquest_tab_bar)
                ?: screen.findViewById<BottomNavigationView>(R.id.bottom_navigation),
            screen::class.java.simpleName,
        )
}
