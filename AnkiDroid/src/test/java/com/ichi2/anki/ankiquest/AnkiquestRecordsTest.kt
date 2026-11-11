// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [32])
@Category(EmptyApplicationCategory::class)
class AnkiquestRecordsTest : RobolectricTest() {
    private class Seen(
        val path: String,
        val query: String?,
        val authorization: String?,
    )

    private lateinit var server: HttpServer
    private lateinit var account: HomeAccount
    private val requests = CopyOnWriteArrayList<Seen>()

    /** Answers status and body by path; anything else is 404. */
    @Volatile private var answer: (Seen) -> Pair<Int, String> = { 404 to "" }

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val seen = Seen(exchange.requestURI.rawPath, exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Authorization"))
            requests += seen
            val (status, body) = answer(seen)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, "member name")
            putString(Ankiquest.PREF_TOKEN, "member-test-token")
        }
        account = AnkiquestHomeData.account()!!
    }

    @After
    fun stop() {
        server.stop(0)
        SharedPreferencesTestHook.override = null
    }

    @Test
    fun `the record boards read every board the website shows, in its order`() {
        val boards = AnkiquestRecords.parseRecords(JSONArray(example("records")))
        assertEquals(AnkiquestRecords.Kind.entries, boards.map { it.kind })
        val day = boards[1]
        assertEquals("xp", day.unit)
        assertEquals(listOf("hill", "cerro"), day.holders.map { it.user })
        val best = day.holders.first()
        assertEquals("Hill", best.display)
        assertEquals(101L, best.value)
        assertEquals(5L, best.detail)
        assertTrue(best.until >= best.at)
        val streak = boards.single { it.kind == AnkiquestRecords.Kind.STREAK }
        assertEquals("days", streak.unit)
        assertTrue(streak.holders.all { it.detail == 0L && it.until == it.at })
    }

    @Test
    fun `unknown boards and holders without anything are left out`() {
        val boards =
            AnkiquestRecords.parseRecords(
                JSONArray()
                    .put(JSONObject().put("window", "decade").put("unit", "xp").put("holders", JSONArray()))
                    .put(
                        JSONObject().put("window", "days").put(
                            "holders",
                            JSONArray()
                                .put(JSONObject().put("user", "ana").put("value", 3).put("at", 10))
                                .put(JSONObject().put("user", "bo").put("value", 0)),
                        ),
                    ),
            )
        val days = boards.single()
        assertEquals(AnkiquestRecords.Kind.DAYS, days.kind)
        assertEquals("days", days.unit, "a missing unit follows the board")
        val holder = days.holders.single()
        assertEquals("ana", holder.display, "a missing name shows the user")
        assertEquals(10L, holder.until)
    }

    @Test
    fun `scopes are offered only to players with friends or groups`() {
        assertEquals(emptyList(), AnkiquestRecords.parseScopes(JSONObject().put("friends", JSONArray()).put("groups", JSONArray())))
        val scopes =
            AnkiquestRecords.parseScopes(
                JSONObject()
                    .put("friends", JSONArray())
                    .put("groups", JSONArray().put(JSONObject().put("id", 2).put("name", "Spanish club"))),
            )
        assertEquals(
            listOf(AnkiquestRecords.Scope.CIRCLE, AnkiquestRecords.Scope.GLOBAL, AnkiquestRecords.Scope("group:2", "Spanish club")),
            scopes,
        )
    }

    @Test
    fun `the study history reads each day, the year's totals and when it was updated`() {
        val study = AnkiquestRecords.parseStudy(JSONObject(example("study")))
        assertEquals(2026, study.year)
        assertEquals(listOf(2026), study.years)
        assertEquals(LocalDate.of(2026, 10, 25), study.days.first().date)
        assertEquals(15L, study.reviews)
        assertEquals(3, study.studyDays)
        assertEquals(204L, study.xp)
        assertEquals(LocalDate.of(2026, 10, 28), study.latest)
        val day = study.day(LocalDate.of(2026, 10, 26))
        assertEquals(6, day.reviews)
        assertEquals(1L, day.minutes)
        assertEquals(62L, day.xp)
        assertEquals(2, day.streak)
        assertEquals(AnkiquestRecords.Day.empty(LocalDate.of(2026, 1, 1)), study.day(LocalDate.of(2026, 1, 1)))
        assertEquals(1_793_183_400_000L, study.lastReceivedAt)
        assertNotNull(study.lastReviewAt)
    }

    @Test
    fun `an empty history keeps its year and has no update time`() {
        val study =
            AnkiquestRecords.parseStudy(
                JSONObject()
                    .put(
                        "year",
                        2025,
                    ).put("years", JSONArray().put(2026))
                    .put("days", JSONArray())
                    .put("last_review_at", JSONObject.NULL),
            )
        assertEquals(listOf(2026, 2025), study.years)
        assertNull(study.latest)
        assertNull(study.lastReviewAt)
        assertNull(study.lastReceivedAt)
    }

    @Test
    fun `the heatmap shows the uploaded reviews and the frozen days`() {
        val frozen = JSONObject().put("date", "2026-03-02").put("reviews", 0).put("frozen", true)
        val studied = JSONObject().put("date", "2026-03-01").put("reviews", 4)
        val after = JSONObject().put("date", "2026-03-03").put("reviews", 2)
        val study = AnkiquestRecords.parseStudy(JSONObject().put("year", 2026).put("days", JSONArray().put(after).put(frozen).put(studied)))
        val data = study.heatmap(LocalDate.of(2026, 3, 3))
        assertEquals(mapOf(LocalDate.of(2026, 3, 1) to 4, LocalDate.of(2026, 3, 3) to 2), data.reviews)
        assertEquals(setOf(LocalDate.of(2026, 3, 2)), data.protectedDays)
        assertEquals(2, data.currentStreak, "the frozen day keeps the streak")
        assertEquals(LocalDate.of(2026, 3, 4), study.heatmap(LocalDate.of(2026, 3, 4)).today)
    }

    @Test
    fun `requests carry the token, the scope and the year`() =
        runBlocking {
            answer = { seen ->
                when (seen.path) {
                    "/api/records" -> 200 to example("records")
                    "/api/study/member%20name" -> 200 to example("study")
                    "/api/social/member%20name" -> 200 to """{"friends": [{"user": "ana"}], "groups": []}"""
                    else -> 404 to ""
                }
            }
            assertEquals(7, AnkiquestRecords.records(account).size)
            assertEquals(7, AnkiquestRecords.records(account, AnkiquestRecords.Scope("group:2")).size)
            assertEquals(2026, AnkiquestRecords.study(account, 2026).year)
            assertEquals(listOf(AnkiquestRecords.Scope.CIRCLE, AnkiquestRecords.Scope.GLOBAL), AnkiquestRecords.scopes(account))
            assertEquals(listOf(null, "scope=group%3A2"), requests.filter { it.path == "/api/records" }.map { it.query })
            assertEquals("year=2026", requests.single { it.path.startsWith("/api/study/") }.query)
            assertTrue(requests.all { it.authorization == "Bearer member-test-token" })
            answer = { 404 to "" }
            assertEquals(404, assertFailsWith<AnkiquestHttpException> { AnkiquestRecords.records(account) }.code)
        }

    private fun example(name: String): String =
        assertNotNull(javaClass.classLoader?.getResource("ankiquest-api/$name.json"), name).readText(Charsets.UTF_8)
}
