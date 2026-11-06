// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The label a conquered card is offered from this phone's collection. */
@RunWith(AndroidJUnit4::class)
class AnkiquestConquestLabelTest : RobolectricTest() {
    @Test
    fun `the collection's card gives its front as the label`() =
        runTest {
            addStandardNoteType("Vocabulary", arrayOf("ID", "German", "English"), "{{German}}", "{{FrontSide}}<hr>{{English}}")
            val note = addNoteUsingNoteTypeName("Vocabulary", "B1L02", "<b>der&nbsp;Hund</b>[sound:hund.mp3]", "the dog")
            val cloze = addClozeNote("{{c1::Paris}} is the capital of France")
            val at = 1_793_183_400_000L
            for ((offset, card) in listOf(note.firstCard(), cloze.firstCard()).withIndex()) {
                col.db.execute(
                    "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, 0, 3, 25, 8, 2500, 7000, 1)",
                    at + offset,
                    card.id,
                )
            }
            assertEquals(AnkiquestShare.Labels("der Hund", "B1L02"), AnkiquestShare.conqueredCardLabels(at))
            assertEquals("der Hund", AnkiquestShare.conqueredCardText(at))
            assertEquals("Paris is the capital of France", AnkiquestShare.conqueredCardText(at + 1))
            assertNull(AnkiquestShare.conqueredCardText(at + 2), "a review that is not here")
        }
}
