// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnkiquestUploadProblemTest {
    @Test
    fun `each failure names what the player can do about it`() {
        assertEquals(AnkiquestUploadProblem.SIGNED_OUT, AnkiquestUploadProblem.of(AnkiquestHttpException(401)))
        assertEquals(AnkiquestUploadProblem.SIGNED_OUT, AnkiquestUploadProblem.of(AnkiquestHttpException(403)))
        assertEquals(AnkiquestUploadProblem.SERVER, AnkiquestUploadProblem.of(AnkiquestHttpException(502)))
        assertEquals(AnkiquestUploadProblem.REFUSED, AnkiquestUploadProblem.of(AnkiquestHttpException(400)))
        assertEquals(AnkiquestUploadProblem.OFFLINE, AnkiquestUploadProblem.of(UnknownHostException("anki.example.test")))
        assertEquals(AnkiquestUploadProblem.OFFLINE, AnkiquestUploadProblem.of(IOException("reset")))
    }

    @Test
    fun `a switched account or a bug says nothing about the upload`() {
        assertNull(AnkiquestUploadProblem.of(HomeAccountChanged()))
        assertNull(AnkiquestUploadProblem.of(IllegalStateException("bug")))
    }
}
