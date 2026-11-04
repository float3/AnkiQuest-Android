// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestSocialTest {
    private val account = HomeAccount("https://anki.example.test/quest", "hill", "secret")

    @Test
    fun `the overview reads friends, requests, muted players and groups`() {
        val overview = AnkiquestSocial.parse(JSONObject(example("social")))
        assertEquals(listOf(AnkiquestSocial.Person("friend", "Friend")), overview.friends)
        assertEquals(listOf("ana"), overview.incoming.map { it.user })
        assertEquals(listOf("cerro"), overview.outgoing.map { it.user })
        assertTrue(overview.isMuted("cerro"))
        assertFalse(overview.isMuted("friend"))
        val (owls, club) = overview.groups
        assertFalse(owls.owner)
        assertNull(owls.invite, "only owners see the code")
        assertTrue(club.owner)
        assertEquals("example-invite-1", club.invite)
        assertEquals(listOf("Ana", "Hill"), club.members.map { it.display })
        assertTrue(overview.public)
    }

    @Test
    fun `unusable rows are skipped and a missing display name falls back to the username`() {
        val overview =
            AnkiquestSocial.parse(
                JSONObject(
                    """{"friends": [{"user": ""}, {"user": "ana", "display": null}], "groups": [{"id": 0, "name": "x"}, {"id": 3}]}""",
                ),
            )
        assertEquals(listOf(AnkiquestSocial.Person("ana", "ana")), overview.friends)
        assertEquals(listOf(3L), overview.groups.map { it.id })
        assertEquals(emptyList(), overview.incoming)
        assertFalse(overview.public)
        assertEquals(AnkiquestSocial.Invite("Spanish club", 2), AnkiquestSocial.parseInvite(JSONObject(example("invite"))))
    }

    @Test
    fun `each change sends the action and fields the server expects`() {
        fun body(change: AnkiquestSocial.Change) = change.body().toString()
        assertEquals("""{"action":"befriend","user":"ana"}""", body(AnkiquestSocial.Change.Befriend("ana")))
        assertEquals("""{"action":"unfriend","user":"ana"}""", body(AnkiquestSocial.Change.Unfriend("ana")))
        assertEquals("""{"action":"mute","user":"ana"}""", body(AnkiquestSocial.Change.Mute("ana")))
        assertEquals("""{"action":"unmute","user":"ana"}""", body(AnkiquestSocial.Change.Unmute("ana")))
        assertEquals("""{"action":"create_group","name":"Club"}""", body(AnkiquestSocial.Change.CreateGroup("Club")))
        assertEquals("""{"action":"rename_group","group":2,"name":"Club"}""", body(AnkiquestSocial.Change.RenameGroup(2, "Club")))
        assertEquals("""{"action":"new_invite","group":2}""", body(AnkiquestSocial.Change.NewInvite(2)))
        assertEquals("""{"action":"join","invite":"abc"}""", body(AnkiquestSocial.Change.Join("abc")))
        assertEquals("""{"action":"leave","group":2}""", body(AnkiquestSocial.Change.Leave(2)))
        assertEquals("""{"action":"remove","group":2,"member":"ana"}""", body(AnkiquestSocial.Change.Remove(2, "ana")))
        assertEquals("""{"action":"visibility","public":true}""", body(AnkiquestSocial.Change.Visibility(true)))
    }

    @Test
    fun `invite links are read only from this server`() {
        val code = "0123456789abcdef01234567"
        assertEquals(code, AnkiquestSocial.inviteCode(account, code))
        assertEquals(code, AnkiquestSocial.inviteCode(account, "  https://anki.example.test/quest/join/$code \n"))
        assertEquals("https://anki.example.test/quest/join/$code", AnkiquestSocial.inviteLink(account, code))
        assertEquals(code, AnkiquestSocial.inviteCode(account, AnkiquestSocial.inviteLink(account, code)))
        assertNull(AnkiquestSocial.inviteCode(account, "https://elsewhere.test/quest/join/$code"), "another server")
        assertNull(AnkiquestSocial.inviteCode(account, "http://anki.example.test/quest/join/$code"), "another scheme")
        assertNull(AnkiquestSocial.inviteCode(account, "https://anki.example.test/join/$code"), "outside the site")
        assertNull(AnkiquestSocial.inviteCode(account, "https://anki.example.test/quest/friends"))
        assertNull(AnkiquestSocial.inviteCode(account, "https://anki.example.test/quest/join/$code/more"))
        assertNull(AnkiquestSocial.inviteCode(account, "not a code"))
        assertNull(AnkiquestSocial.inviteCode(account, ""))
    }

    @Test
    fun `group names and usernames are checked as the server checks them`() {
        assertEquals("Spanish club", AnkiquestSocial.groupName("  Spanish club "))
        assertNull(AnkiquestSocial.groupName("   "))
        assertNull(AnkiquestSocial.groupName("x".repeat(41)))
        assertNotNull(AnkiquestSocial.groupName("x".repeat(40)))
        assertNull(AnkiquestSocial.groupName("tab\there"))
        assertEquals("ana", AnkiquestSocial.userName(" ana "))
        assertNull(AnkiquestSocial.userName(" "))
    }

    @Test
    fun `initials and ring colours follow the website`() {
        assertEquals("AL", AnkiquestFriendsActivity.initials("ada lovelace"))
        assertEquals("C", AnkiquestFriendsActivity.initials("Cerro"))
        assertEquals("?", AnkiquestFriendsActivity.initials("  "))
        // ((('h' * 31 + 'i') * 31 + 'l') * 31 + 'l') = 3_202_625, which is 65 modulo 360.
        assertEquals(65f, AnkiquestFriendsActivity.hue("hill"))
    }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/$name.json")).readText(Charsets.UTF_8)
}
