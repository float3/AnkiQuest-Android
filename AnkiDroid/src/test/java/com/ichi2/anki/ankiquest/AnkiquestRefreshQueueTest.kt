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
        assertTrue(queue.offer(showFeedback = true, resync = false))
        repeat(50) { assertFalse(queue.offer(showFeedback = true, resync = false)) }
        assertEquals(true to false, queue.take())
    }

    @Test
    fun `a waiting refresh keeps feedback and resync asked for by any request`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer(showFeedback = false, resync = true))
        assertFalse(queue.offer(showFeedback = true, resync = false))
        assertEquals(true to true, queue.take())
    }

    @Test
    fun `a request after the refresh started queues the next one`() {
        val queue = RefreshQueue()
        assertTrue(queue.offer(showFeedback = true, resync = true))
        queue.take()
        assertTrue(queue.offer(showFeedback = false, resync = false))
        assertEquals(false to false, queue.take(), "flags of the earlier refresh are not repeated")
    }
}
