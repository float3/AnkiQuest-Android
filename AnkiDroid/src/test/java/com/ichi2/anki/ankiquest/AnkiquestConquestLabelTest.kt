// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The label a conquered card is offered from this phone's collection. */
@RunWith(AndroidJUnit4::class)
class AnkiquestConquestLabelTest : RobolectricTest() {
    @Test
    fun `all shared label and fallback cases match desktop`() {
        val fixture = JSONObject(checkNotNull(javaClass.classLoader?.getResource("ankiquest/card_labels.json")).readText())
        val cases = fixture.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val pairs = case.getJSONArray("fields")
            val names = List(pairs.length()) { pairs.getJSONArray(it).getString(0) }
            val fields = List(pairs.length()) { pairs.getJSONArray(it).getString(1) }
            val template = if (case.isNull("template")) null else case.getString("template")
            assertEquals(
                case.getString("expected"),
                AnkiquestShare.suggestedLabel(case.getString("question"), fields, case.optBoolean("cloze"), template, names).orEmpty(),
                case.getString("name"),
            )
        }
    }

    @Test
    fun `shared labels use front fields without template prompts or identifiers`() =
        runTest {
            val fixture = JSONObject(checkNotNull(javaClass.classLoader?.getResource("ankiquest/card_labels.json")).readText())
            val cases = fixture.getJSONArray("cases")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                if (case.optBoolean("fallback_only")) {
                    continue
                }
                val fields = case.getJSONArray("fields")
                val names = Array(fields.length()) { fields.getJSONArray(it).getString(0) }
                val values = Array(fields.length()) { fields.getJSONArray(it).getString(1) }
                val note =
                    if (case.optBoolean("cloze")) {
                        addClozeNote(values[0])
                    } else {
                        val type = "Label fixture $index"
                        addStandardNoteType(type, names, case.getString("template"), "{{FrontSide}}")
                        addNoteUsingNoteTypeName(type, *values)
                    }
                val at = 1_793_183_500_000L + index
                col.db.execute(
                    "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, 0, 3, 25, 8, 2500, 7000, 1)",
                    at,
                    note.firstCard().id,
                )
                assertEquals(case.getString("expected"), AnkiquestShare.conqueredCardText(at).orEmpty(), case.getString("name"))
            }
        }

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
