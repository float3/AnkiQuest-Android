// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.utils.ext.getParcelableExtraCompat
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestFriendsActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val overview = AnkiquestSocial.parse(JSONObject(example("social")))
    private val accepted = AnkiquestSocial.parse(JSONObject(example("social_change")))
    private val code = "0123456789abcdef01234567"

    @Before
    fun prepareFriends() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestSocial.overview(identity) } returns overview
        coEvery { AnkiquestSocial.change(identity, any()) } returns overview
        coEvery { AnkiquestSocial.invite(identity, any()) } returns AnkiquestSocial.parseInvite(JSONObject(example("invite")))
    }

    @After
    fun clearFriendMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial)
    }

    @Test
    fun `requests come first, then friends, waiting requests, groups, muted players and the profile switch`() {
        val friends = launch()
        val texts = friends.texts()
        val requests = texts.indexOf(friends.getString(R.string.aq_friends_requests))
        val mine = texts.indexOf(friends.getString(R.string.aq_friends_friends))
        val waiting = texts.indexOf(friends.getString(R.string.aq_friends_waiting))
        val groups = texts.indexOf(friends.getString(R.string.aq_friends_groups))
        val muted = texts.indexOf(friends.getString(R.string.aq_friends_muted))
        assertTrue(requests in 0 until mine && mine < waiting && waiting < groups && groups < muted, texts.toString())
        assertTrue(texts.indexOf("Ana") in requests until mine, "Ana's request")
        assertTrue(texts.indexOf("Friend") in mine until waiting)
        assertTrue(texts.indexOf("Cerro") in waiting until groups)
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_goals_open)), "the way to friend goals")

        assertTrue(friends.hasText("Spanish club"))
        assertTrue(friends.hasText("Night owls"))
        assertEquals(2, texts.count { it == friends.resources.getQuantityString(R.plurals.aq_friends_members, 2, 2) })
        assertTrue(friends.hasText("https://anki.example.test/join/example-invite-1"), "the owner sees the invite link")
        assertEquals(1, texts.count { it == friends.getString(R.string.aq_friends_share_link) }, "only for the group Hill owns")
        assertEquals(2, texts.count { it == friends.getString(R.string.aq_friends_you, "Hill") })
        assertEquals(2, texts.count { it == friends.getString(R.string.aq_friends_leave) })
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_visibility_public)))
        assertTrue(friends.views<TextView>().any { it.text.toString() == friends.getString(R.string.aq_friends_unmute) })
    }

    @Test
    fun `accepting and declining a request send them and show the server's answer`() {
        coEvery { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Befriend("ana")) } returns accepted
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_accept))
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Befriend("ana")) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_accepted, "Ana")))
        assertFalse(friends.hasText(friends.getString(R.string.aq_friends_requests)), "nothing waits now")

        val again = launch()
        again.click(again.getString(R.string.aq_challenges_decline))
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Unfriend("ana")) }
        assertTrue(again.hasText(again.getString(R.string.aq_friends_declined)))
    }

    @Test
    fun `a friend request is sent by username, and an empty one is not`() {
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_add))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        send.performClick()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.hasText(friends.getString(R.string.aq_friends_add_empty)))
        dialog.field().setText(" bobby ")
        send.performClick()
        friends.settle()
        assertFalse(dialog.isShowing)
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Befriend("bobby")) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_request_sent, "bobby")))
    }

    @Test
    fun `removing asks first and unmuting is immediate`() {
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_remove))
        friends.confirm()
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Unfriend("friend")) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_removed, "Friend")))

        friends.click(friends.getString(R.string.aq_friends_unmute))
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Unmute("cerro")) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_unmuted, "Cerro")))
    }

    @Test
    fun `muting applies immediately and Undo unmutes the same friend`() {
        val friends = launch()
        friends.views<TextView>().first { it.text == friends.getString(R.string.aq_friends_mute) }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Mute("friend")) }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        val undo = assertNotNull(friends.findViewById<View>(com.google.android.material.R.id.snackbar_action))
        undo.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Unmute("friend")) }
    }

    @Test
    fun `a failed mute has no Undo action`() {
        coEvery { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Mute("friend")) } throws IOException("offline")
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_mute))
        assertTrue(friends.texts().any { it.contains(friends.getString(R.string.aq_friends_offline)) })
        assertNull(friends.findViewById<View>(com.google.android.material.R.id.snackbar_action))
    }

    @Test
    fun `a refused change shows the server's reason, and an unreachable server says so`() {
        coEvery { AnkiquestSocial.change(identity, any()) } throws
            AnkiquestHttpException(409, "Wait for some friend requests to be answered first.")
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_accept))
        val refused =
            friends.getString(
                R.string.aq_feed_failed_because,
                friends.getString(R.string.aq_friends_change_failed),
                "Wait for some friend requests to be answered first.",
            )
        assertTrue(friends.hasText(refused))
        assertTrue(friends.views<TextView>().any { it.text.toString() == friends.getString(R.string.aq_friends_accept) && it.isEnabled })

        coEvery { AnkiquestSocial.change(identity, any()) } throws IOException("offline")
        friends.click(friends.getString(R.string.aq_challenges_decline))
        assertTrue(
            friends.hasText(
                friends.getString(
                    R.string.aq_feed_failed_because,
                    friends.getString(R.string.aq_friends_change_failed),
                    friends.getString(R.string.aq_friends_offline),
                ),
            ),
        )
    }

    @Test
    fun `a group is created with a checked name`() {
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_group_create))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialog.field().setText("   ")
        create.performClick()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.hasText(friends.getString(R.string.aq_friends_group_name_bad)))
        dialog.field().setText("Verb drills")
        create.performClick()
        friends.settle()
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.CreateGroup("Verb drills")) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_group_created, "Verb drills")))
    }

    @Test
    fun `the owner shares, renews and renames the link, removes members, and anyone can leave`() {
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_share_link))
        val shared = assertNotNull(shadowOf(friends).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, shared.action)
        val text = shared.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)?.getStringExtra(Intent.EXTRA_TEXT)
        assertEquals(
            friends.getString(R.string.aq_friends_invite_share_text, "Spanish club", "https://anki.example.test/join/example-invite-1"),
            text,
        )

        friends.click(friends.getString(R.string.aq_friends_new_link))
        friends.confirm()
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.NewInvite(1)) }

        friends.click(friends.getString(R.string.aq_friends_rename))
        val rename = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Spanish club", rename.field().text.toString())
        rename.field().setText("Club español")
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        friends.settle()
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.RenameGroup(1, "Club español")) }

        // Friend's Remove comes first, then Ana's in Hill's own group.
        friends
            .views<TextView>()
            .filter { it.text.toString() == friends.getString(R.string.aq_friends_remove) }[1]
            .performClick()
        friends.confirm()
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Remove(1, "ana")) }

        friends.click(friends.getString(R.string.aq_friends_leave))
        friends.confirm()
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Leave(2)) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_left, "Night owls")))
    }

    @Test
    fun `an invite link shows the group before joining it`() {
        val friends = launch(invite = code)
        coVerify { AnkiquestSocial.invite(identity, code) }
        val texts = friends.texts()
        assertTrue(
            texts.indexOf(friends.getString(R.string.aq_friends_invite)) < texts.indexOf(friends.getString(R.string.aq_friends_friends)),
        )
        assertTrue(friends.hasText(friends.resources.getQuantityString(R.plurals.aq_friends_members, 2, 2)))
        friends.click(friends.getString(R.string.aq_friends_join))
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Join(code)) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_joined, "Spanish club")))
        assertFalse(friends.hasText(friends.getString(R.string.aq_friends_join)), "the invitation is answered")
    }

    @Test
    fun `an invite that is not valid any more says so, and not now closes an invitation`() {
        coEvery { AnkiquestSocial.invite(identity, "gone") } throws AnkiquestHttpException(404, "That invite link is not valid any more.")
        val gone = launch(invite = "gone")
        assertTrue(gone.hasText(gone.getString(R.string.aq_friends_invite_gone)))
        assertFalse(gone.hasText(gone.getString(R.string.aq_friends_join)))

        val later = launch(invite = code)
        later.click(later.getString(R.string.ankiquest_deck_share_not_now))
        assertFalse(later.hasText(later.getString(R.string.aq_friends_invite)))
        coVerify(exactly = 0) { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Join(code)) }
    }

    @Test
    fun `a pasted invite link from this server opens its invitation, and one from elsewhere does not`() {
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_group_join))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val next = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialog.field().setText("https://elsewhere.test/join/$code")
        next.performClick()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.hasText(friends.getString(R.string.aq_friends_group_join_bad)))
        dialog.field().setText("https://anki.example.test/join/$code")
        next.performClick()
        friends.settle()
        coVerify { AnkiquestSocial.invite(identity, code) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_join)))
    }

    @Test
    fun `friend goals and a friend's profile open natively`() {
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_goals_open))
        val goals = assertNotNull(shadowOf(friends).nextStartedActivity)
        assertEquals(AnkiquestChallengesActivity::class.java.name, goals.component?.className)

        friends.click(friends.getString(R.string.aq_profile_title))
        val profile = assertNotNull(shadowOf(friends).nextStartedActivity)
        assertEquals(AnkiquestProfileActivity::class.java.name, profile.component?.className)
        assertEquals("friend", profile.getStringExtra(AnkiquestProfileActivity.EXTRA_USER))
    }

    @Test
    fun `without friends or groups the screen says how to start`() {
        coEvery { AnkiquestSocial.overview(identity) } returns
            AnkiquestSocial.Overview(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), public = false)
        val friends = launch()
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_empty)))
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_groups_empty)))
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_visibility_private)))
        assertFalse(friends.hasText(friends.getString(R.string.aq_friends_requests)))
        assertFalse(friends.hasText(friends.getString(R.string.aq_friends_muted)))
        friends.click(friends.getString(R.string.aq_friends_public))
        coVerify { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Visibility(true)) }
    }

    @Test
    fun `offline and refused lists say what to do`() {
        coEvery { AnkiquestSocial.overview(identity) } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_friends_offline)))

        coEvery { AnkiquestSocial.overview(identity) } throws AnkiquestHttpException(401)
        val refused = launch()
        assertTrue(refused.hasText(refused.getString(R.string.aq_home_auth)))
        refused.click(refused.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(refused).nextStartedActivity.component?.className)
    }

    @Test
    fun `an older server without the endpoint says it needs updating`() {
        coEvery { AnkiquestSocial.overview(identity) } throws AnkiquestHttpException(404)
        val friends = launch(invite = code)
        assertTrue(friends.hasText(friends.getString(R.string.aq_server_outdated)))
        assertNull(shadowOf(friends).nextStartedActivity)
        assertFalse(friends.isFinishing)
    }

    @Test
    fun `a notification for another account shows this account's friends with a note`() {
        val friends = launch(account = "https://other.example.test/hill", invite = code)
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_account_route_changed)))
        assertFalse(friends.hasText(friends.getString(R.string.aq_friends_invite)), "another account's invitation never opens")
        coVerify(exactly = 0) { AnkiquestSocial.invite(any(), any()) }
    }

    @Test
    fun `without an account the screen asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val friends = launch()
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_connect)))
        coVerify(exactly = 0) { AnkiquestSocial.overview(any()) }
    }

    @Test
    fun `refreshing while a change is on its way keeps the change buttons waiting`() {
        val answer = CompletableDeferred<AnkiquestSocial.Overview>()
        coEvery { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Befriend("ana")) } coAnswers { answer.await() }
        val friends = launch()
        friends.click(friends.getString(R.string.aq_friends_accept))

        friends.findViewById<View>(R.id.aq_feed_refresh).performClick()
        friends.settle()
        val accept = friends.views<TextView>().first { it.text.toString() == friends.getString(R.string.aq_friends_accept) }
        assertFalse(accept.isEnabled, "a second change could be sent while the first is on its way")

        answer.complete(accepted)
        friends.settle()
        coVerify(exactly = 1) { AnkiquestSocial.change(identity, any()) }
        assertTrue(friends.hasText(friends.getString(R.string.aq_friends_accepted, "Ana")))
    }

    private fun launch(
        account: String? = null,
        invite: String? = null,
    ): AnkiquestFriendsActivity =
        startRegularActivity<AnkiquestFriendsActivity>(AnkiquestFriendsActivity.intent(targetContext, account, invite))
            .also { it.settle() }

    private fun AnkiquestFriendsActivity.settle() {
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_feed_loading).isVisible }
        advanceRobolectricLooper()
    }

    private inline fun <reified T : View> AnkiquestFriendsActivity.views(): List<T> =
        findViewById<View>(R.id.aq_feed_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestFriendsActivity.texts(): List<String> = views<TextView>().map { it.text.toString() }

    private fun AnkiquestFriendsActivity.hasText(value: String): Boolean = texts().any { it == value }

    private fun AnkiquestFriendsActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        assertTrue(control.isEnabled, label)
        control.performClick()
        settle()
    }

    private fun AnkiquestFriendsActivity.confirm() {
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
    }

    private fun AlertDialog.field(): EditText =
        window!!
            .decorView.allViews
            .filterIsInstance<EditText>()
            .first()

    private fun AlertDialog.hasText(value: String): Boolean =
        window!!.decorView.allViews.any {
            it is TextView &&
                it.text.toString() == value
        }

    private companion object {
        fun example(name: String): String =
            assertNotNull(AnkiquestFriendsActivityTest::class.java.classLoader?.getResource("ankiquest-api/$name.json"))
                .readText(Charsets.UTF_8)
    }
}
