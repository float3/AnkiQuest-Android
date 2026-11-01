// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnkiquestReplyTextTest {
    @Test
    fun `line breaks and other control characters become spaces`() {
        assertEquals("Great job ! Keep going", AnkiquestReply.clean("\nGreat\njob\t!\nKeep\u0007going\u0085"))
        assertEquals("", AnkiquestReply.clean("\n\t\r"))
    }

    @Test
    fun `the limit counts UTF-8 bytes like the server`() {
        assertEquals("a".repeat(200), AnkiquestReply.clean("a".repeat(250)))
        assertEquals("é".repeat(100), AnkiquestReply.clean("é".repeat(150)))
        assertEquals("😀".repeat(50), AnkiquestReply.clean("😀".repeat(60)))
    }

    @Test
    fun `a character is never split at the limit`() {
        val cut = AnkiquestReply.clean("a" + "😀".repeat(60))
        assertEquals("a" + "😀".repeat(49), cut)
        val bytes = cut.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= AnkiquestReply.MAX_MESSAGE)
        assertEquals(cut, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `short replies are kept as written`() {
        assertEquals("¡Gracias! 🎉", AnkiquestReply.clean("  ¡Gracias! 🎉  "))
    }
}
