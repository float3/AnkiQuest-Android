// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.testutils.EmptyApplication
import org.json.JSONObject
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestWebSessionTest {
    private val session = AnkiquestWebSession("https://quest.example/anki/#cerro", "cerro", "secret", "es-ES")

    @Test
    fun `navigation stays on the dashboard's own pages`() {
        val allowed =
            listOf(
                "https://quest.example/anki/",
                "https://quest.example/anki/#cerro",
                "https://quest.example:443/anki/week#cerro",
                "https://quest.example/anki/records?embed=1",
                "https://quest.example/anki/community#reminders",
                "https://quest.example/anki/community?period=day",
                "https://quest.example/anki/community?period=month&embed=1",
                "https://quest.example/anki/community?embed=1&period=week",
            )
        for (url in allowed) assertTrue(session.allows(url), url)
    }

    @Test
    @SuppressLint("AuthLeak")
    fun `navigation elsewhere is refused`() {
        val refused =
            listOf(
                null,
                "",
                "not a url",
                "javascript:alert(1)",
                "http://quest.example/anki/",
                "https://quest.example:8443/anki/",
                "https://other.example/anki/",
                "https://quest.example/",
                "https://quest.example/anki/auth/session",
                "https://quest.example/anki/week/",
                "https://quest.example/anki/?embed=2",
                "https://quest.example/anki/week?period=day",
                "https://quest.example/anki/community?period=year",
                "https://quest.example/anki/community?period=day&period=week",
                "https://member:secret@quest.example/anki/",
            )
        for (url in refused) assertFalse(session.allows(url), url.toString())
    }

    @Test
    @SuppressLint("AuthLeak")
    fun `a dashboard address that could carry credentials or another query allows nothing`() {
        for (dashboard in listOf("https://member:secret@quest.example/#cerro", "https://quest.example/?next=/#cerro", "not a url")) {
            val unusable = AnkiquestWebSession(dashboard, "cerro", "secret")
            assertFalse(unusable.allows("https://quest.example/"), dashboard)
            assertNull(unusable.script("https://quest.example/"), dashboard)
        }
    }

    @Test
    fun `the script hands over the member, its token and the app language for allowed pages only`() {
        assertNull(session.script("https://other.example/anki/"))
        val script = assertNotNull(session.script("https://quest.example/anki/community#reminders"))

        val handed = JSONObject(script.substringAfter("window.ankiquestSession = ").substringBefore(";\n"))
        assertEquals("cerro", handed.getString("user"))
        assertEquals("secret", handed.getString("token"))
        assertEquals(setOf("user", "token"), handed.keys().asSequence().toSet())
        assertTrue(script.contains("window.ankiquestLanguage = \"es-ES\";"))
        assertTrue(script.contains("page.origin !== ${JSONObject.quote("https://quest.example")}"), "the page checks its own origin first")
        assertTrue(script.contains("new CustomEvent('ankiquest-auth')"))
    }

    @Test
    fun `without a token the page is told it is signed out`() {
        val signedOut = AnkiquestWebSession("https://quest.example/anki/#cerro", "cerro", "")
        val script = assertNotNull(signedOut.script("https://quest.example/anki/"))

        assertTrue(script.contains("window.ankiquestSession = null;"))
        assertFalse(script.contains("secret"))
    }

    @Test
    fun `a member name cannot break out of the script`() {
        val user = "a\"b</script><script>alert(1)//"
        val hostile = AnkiquestWebSession("https://quest.example/#a", user, "secret")
        val script = assertNotNull(hostile.script("https://quest.example/"))

        val handed = JSONObject(script.substringAfter("window.ankiquestSession = ").substringBefore(";\n"))
        assertEquals(user, handed.getString("user"))
    }

    @Test
    fun `every page the server serves keeps the session`() {
        val pages =
            listOf(
                "today",
                "history",
                "settings",
                "friends",
                "join/abc-123",
                "privacy",
                "conquered/cerro/1793183400",
                "records",
                "community",
                "dev",
                "hour",
                "day",
                "week",
                "month",
                "year",
                "all",
            )
        for (page in pages) {
            for (query in listOf("", "?embed=1")) {
                val url = "https://quest.example/anki/$page$query#cerro"
                assertTrue(session.allows(url), url)
                assertNotNull(session.script(url), url)
                assertEquals(AnkiquestLink.SITE, session.link(url), url)
            }
        }
    }

    @Test
    fun `other documents of the server stay in the app without the session`() {
        val documents =
            listOf(
                "https://quest.example/anki/join/",
                "https://quest.example/anki/join/a/b",
                "https://quest.example/anki/conquered/cerro",
                "https://quest.example/anki/conquered/cerro/soon",
                "https://quest.example/anki/today/",
                "https://quest.example/anki/today?next=elsewhere",
                "https://quest.example/anki/settings?embed=1&unknown=x",
                "https://quest.example/w/example-link",
                "https://quest.example/anki/w/example-link",
            )
        for (url in documents) {
            assertFalse(session.allows(url), url)
            assertNull(session.script(url), url)
            assertEquals(AnkiquestLink.SITE, session.link(url), url)
        }
    }

    @Test
    @SuppressLint("AuthLeak")
    fun `the website sign-in is replaced by the app's and other sites open elsewhere`() {
        val signIn =
            listOf(
                "https://quest.example/login",
                "https://quest.example/login?next=%2Ftoday",
                "https://quest.example/login?signup=1",
                "https://quest.example/anki/login?next=%2Fanki%2Ftoday",
                "https://quest.example/auth/oauth/github?link=1",
                "https://quest.example/auth/oauth/google/callback?code=x",
                "https://quest.example/anki/auth/session",
            )
        for (url in signIn) {
            assertEquals(AnkiquestLink.SIGN_IN, session.link(url), url)
            assertFalse(session.allows(url), url)
        }
        val external =
            listOf(
                null,
                "",
                "javascript:alert(1)",
                "https://github.com/login/oauth/authorize",
                "http://quest.example/anki/today",
                "https://quest.example:8443/anki/today",
                "https://other.example/anki/today",
            )
        for (url in external) assertEquals(AnkiquestLink.EXTERNAL, session.link(url), url.toString())
        assertEquals(AnkiquestLink.REFUSED, session.link("https://member:secret@quest.example/anki/today"))
        assertFalse(session.allows("https://member:secret@quest.example/anki/today"))
    }

    @Test
    fun `a session without a token is signed out`() {
        assertTrue(session.signedIn)
        assertFalse(AnkiquestWebSession("https://quest.example/anki/#cerro", "cerro", "").signedIn)
    }

    @Test
    fun `the token never appears in logs`() {
        assertFalse(session.toString().contains("secret"))
    }
}
