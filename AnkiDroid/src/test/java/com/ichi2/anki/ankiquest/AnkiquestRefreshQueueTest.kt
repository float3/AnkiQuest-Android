// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnkiquestRefreshQueueTest {
    @Test
    fun `answers made while a refresh waits share it`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("me", showFeedback = true, resync = false))
        repeat(50) { assertFalse(queue.offer("me", showFeedback = true, resync = false)) }
        assertEquals(true to false, queue.take("me"))
    }

    @Test
    fun `a waiting refresh keeps feedback and resync asked for by any request`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("me", showFeedback = false, resync = true))
        assertFalse(queue.offer("me", showFeedback = true, resync = false))
        assertEquals(true to true, queue.take("me"))
    }

    @Test
    fun `a request after the refresh started queues the next one`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("me", showFeedback = true, resync = true))
        queue.take("me")
        assertTrue(queue.offer("me", showFeedback = false, resync = false))
        assertEquals(false to false, queue.take("me"), "flags of the earlier refresh are not repeated")
    }

    @Test
    fun `different accounts queue independently without borrowing feedback or resync`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("old", showFeedback = true, resync = true))
        assertTrue(queue.offer("new", showFeedback = false, resync = false))
        repeat(50) { assertFalse(queue.offer("new", showFeedback = false, resync = false)) }
        assertEquals(true to true, queue.take("old"))
        assertEquals(false to false, queue.take("new"))
    }

    @Test
    fun `discarding an old request leaves the new account's flags queued`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("old", showFeedback = false, resync = false))
        assertTrue(queue.offer("new", showFeedback = true, resync = true))
        assertEquals(false to false, queue.take("old"))
        assertFalse(queue.offer("new", showFeedback = false, resync = false))
        assertEquals(true to true, queue.take("new"))
    }

    @Test
    fun `retrying a cancelled waiter keeps feedback and resync for the next poll`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("me", showFeedback = false, resync = true))
        assertFalse(queue.offer("me", showFeedback = true, resync = false))
        queue.retry("me")
        assertTrue(queue.offer("me", showFeedback = false, resync = false))
        assertFalse(queue.offer("me", showFeedback = false, resync = false))
        assertEquals(true to true, queue.take("me"))
    }

    @Test
    fun `a cancelled account's retry leaves another account's waiter intact`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer("old", showFeedback = false, resync = true))
        assertTrue(queue.offer("new", showFeedback = true, resync = false))
        queue.retry("old")
        assertFalse(queue.offer("new", showFeedback = false, resync = false))
        assertTrue(queue.offer("old", showFeedback = false, resync = false))
        assertEquals(false to true, queue.take("old"))
        assertEquals(true to false, queue.take("new"))
    }
}
