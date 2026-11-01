// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import kotlin.test.assertEquals

class AnkiquestArtTest {
    @Test
    fun `artwork is sampled down as far as it still covers the shown size`() {
        assertEquals(16, sampleSize(1254, 64))
        assertEquals(2, sampleSize(1254, 520))
        assertEquals(1, sampleSize(100, 200))
        assertEquals(1, sampleSize(1254, 1254))
    }
}
