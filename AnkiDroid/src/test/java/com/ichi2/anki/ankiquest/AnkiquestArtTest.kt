// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.R
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnkiquestArtTest {
    @Test
    fun `each moment gets its own art`() {
        assertEquals(R.drawable.aki_streak_milestone, AnkiquestArt.forNotice("celebration", "streak"))
        assertEquals(R.drawable.aki_achievement, AnkiquestArt.forNotice("celebration", "achievement"))
        assertEquals(R.drawable.aki_achievement, AnkiquestArt.forNotice("celebration", null), "older servers send no topic")
        assertEquals(R.drawable.aki_challenge, AnkiquestArt.forNotice("challenge_invite", null))
        assertEquals(R.drawable.aki_challenge_won, AnkiquestArt.forNotice("challenge_complete", null))
        assertEquals(R.drawable.aki_place_2, AnkiquestArt.forNotice("reminder_weekly_recap", "place:2"))
        assertEquals(R.drawable.aki_face, AnkiquestArt.forNotice("reminder_weekly_recap", "place:4"))
        assertEquals(R.drawable.aki_face, AnkiquestArt.forNotice("completion", null))
        assertEquals(R.drawable.aki_place_1, AnkiquestArt.forNotice("share", "place:1"), "a friend's shared win keeps its art")
        assertEquals(R.drawable.aki_streak_milestone, AnkiquestArt.forNotice("share", "streak"))
    }

    @Test
    fun `only the top three get a medal`() {
        assertEquals(R.drawable.icon_place_1, AnkiquestArt.medal(1))
        assertEquals(R.drawable.icon_place_3, AnkiquestArt.medal(3))
        assertNull(AnkiquestArt.medal(4))
    }

    @Test
    fun `place art is for climbing, not for slipping or staying`() {
        val before = listOf("a", "b", "c", "me")
        assertEquals(1, AnkiquestNotifier.climbedTo(before, listOf("me", "a", "b", "c"), "me"))
        assertEquals(3, AnkiquestNotifier.climbedTo(before, listOf("a", "b", "me", "c"), "me"))
        assertNull(AnkiquestNotifier.climbedTo(listOf("me", "a"), listOf("a", "me"), "me"))
        assertNull(AnkiquestNotifier.climbedTo(before, before, "me"))
        assertNull(AnkiquestNotifier.climbedTo(emptyList(), listOf("me"), "me"))
    }
}
