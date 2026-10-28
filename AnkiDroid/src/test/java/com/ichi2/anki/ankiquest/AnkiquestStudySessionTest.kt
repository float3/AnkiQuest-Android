// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class AnkiquestStudySessionTest : RobolectricTest() {
    private val clock = MockTime(1_750_000_000_000)

    @Before
    fun configure() {
        TimeManager.resetWith(clock)
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://anki.example.test")
            putString(Ankiquest.PREF_USER, "member")
            putString(Ankiquest.PREF_TOKEN, "member-token")
        }
    }

    private fun review(
        id: Long,
        ease: Int = 3,
        type: Int = 1,
    ) {
        col.db.execute(
            "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, 1, 0, ?, 1, 1, 2500, 90000, ?)",
            id,
            ease,
            type,
        )
    }

    @Test
    fun `recap counts actual session reviews and honors undo and manual rescheduling`() =
        runBlocking {
            val began = clock.intTimeMS()
            review(began - 1000)
            AnkiquestStudySession.start()
            review(began + 1000, ease = 1)
            review(began + 2000)
            review(began + 3000, ease = 0)
            review(began + 4000, type = 4)
            col.db.execute("delete from revlog where id = ?", began + 2000)
            clock.addM(2)
            val summary = assertNotNull(AnkiquestStudySession.finish())
            assertEquals(1, summary.reviews)
            assertEquals(1, summary.minutes)
            assertEquals(summary, AnkiquestStudySession.latest())
            assertNull(AnkiquestStudySession.finish())
        }

    @Test
    fun `empty and previous-account sessions produce no recap`() =
        runBlocking {
            AnkiquestStudySession.start()
            assertNull(AnkiquestStudySession.finish())
            AnkiquestStudySession.start()
            review(clock.intTimeMS() + 1000)
            clock.addM(2)
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_USER, "someone-else") }
            assertNull(AnkiquestStudySession.finish())
            assertNull(AnkiquestStudySession.latest())
        }

    @Test
    fun `credential changes remove the persisted recap`() =
        runBlocking {
            AnkiquestStudySession.start()
            review(clock.intTimeMS() + 1000)
            clock.addM(2)
            assertNotNull(AnkiquestStudySession.finish())
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "new-token") }
            assertNull(AnkiquestStudySession.latest())
        }
}
