// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Where a link inside the web view leads. */
internal enum class AnkiquestLink {
    /** Any document of the configured server; only [AnkiquestWebSession.allows] pages get the session. */
    SITE,

    /** The website's sign-in and account-linking flows, which the app replaces with its own sign-in. */
    SIGN_IN,

    /** Another site. */
    EXTERNAL,

    /** An address carrying credentials, which is never opened. */
    REFUSED,
}

/** Credentials are handed only to known AnkiQuest documents, and live in the page's memory. */
internal data class AnkiquestWebSession(
    val dashboard: String,
    val user: String,
    private val token: String,
    val language: String = "en",
) {
    private val base = dashboard.toHttpUrlOrNull()
    private val pages =
        "(?:|today|history|settings|friends|records|community|privacy|dev|hour|day|week|month|year|all|" +
            "join/[^/]+|conquered/[^/]+/[0-9]+)"
    private val pagePattern = Regex(pages)
    private val embeddedQuery = "embed=1"
    private val calendarQueries =
        listOf("day", "week", "month").flatMap { period ->
            listOf("period=$period", "period=$period&$embeddedQuery", "$embeddedQuery&period=$period")
        }

    val community: String
        get() = dashboard.substringBefore('#') + "community#reminders"

    /** Whether the app has a token; without one the website would ask the player to connect by hand. */
    val signedIn: Boolean
        get() = token.isNotEmpty()

    /** The server's own pages, with only the queries those pages link to. */
    fun allows(url: String?): Boolean {
        val expected = usableBase() ?: return false
        val actual = url?.toHttpUrlOrNull() ?: return false
        if (!sameSite(expected, actual)) return false
        val page = relativePath(expected, actual) ?: return false
        val queryAllowed =
            actual.encodedQuery == null || actual.encodedQuery == embeddedQuery ||
                (page == "community" && actual.encodedQuery in calendarQueries)
        return pagePattern.matches(page) && queryAllowed
    }

    /** Where a navigation to [url] belongs: the web view, the app's own sign-in, or another app. */
    fun link(url: String?): AnkiquestLink {
        val expected = usableBase() ?: return AnkiquestLink.EXTERNAL
        val actual = url?.toHttpUrlOrNull() ?: return AnkiquestLink.EXTERNAL
        if (actual.scheme != expected.scheme || actual.host != expected.host || actual.port != expected.port) {
            return AnkiquestLink.EXTERNAL
        }
        if (actual.username.isNotEmpty() || actual.password.isNotEmpty()) return AnkiquestLink.REFUSED
        val signIn =
            listOfNotNull(actual.encodedPath.removePrefix("/"), relativePath(expected, actual)).any { path ->
                path == "login" || path.startsWith("auth/")
            }
        return if (signIn) AnkiquestLink.SIGN_IN else AnkiquestLink.SITE
    }

    private fun usableBase(): HttpUrl? = base?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.encodedQuery == null }

    private fun sameSite(
        expected: HttpUrl,
        actual: HttpUrl,
    ): Boolean =
        actual.username.isEmpty() && actual.password.isEmpty() &&
            actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port

    private fun relativePath(
        expected: HttpUrl,
        actual: HttpUrl,
    ): String? = actual.encodedPath.takeIf { it.startsWith(expected.encodedPath) }?.removePrefix(expected.encodedPath)

    /** The page script; a navigation can finish after the native check, so it checks the actual document again. */
    fun script(url: String?): String? {
        if (!allows(url)) return null
        val expected = checkNotNull(base)
        val root = expected.encodedPath
        val origin =
            expected
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .removeSuffix("/")
        val session = if (token.isEmpty()) "null" else JSONObject().put("user", user).put("token", token).toString()
        return """
            (() => {
                const page = new URL(window.location.href);
                const root = ${JSONObject.quote(root)};
                const relative = page.pathname.startsWith(root) ? page.pathname.slice(root.length) : null;
                const queryAllowed = !page.href.split('#')[0].includes('?') ||
                    page.search.slice(1) === ${JSONObject.quote(embeddedQuery)} ||
                    (relative === 'community' && ${JSONArray(calendarQueries)}.includes(page.search.slice(1)));
                if (page.username || page.password || page.origin !== ${JSONObject.quote(origin)} || relative === null ||
                    !new RegExp(${JSONObject.quote("^$pages$")}).test(relative) || !queryAllowed) return;
                window.ankiquestLanguage = ${JSONObject.quote(language)};
                window.ankiquestSession = $session;
                window.dispatchEvent(new CustomEvent('ankiquest-auth'));
            })();
            """.trimIndent()
    }

    override fun toString(): String = "AnkiquestWebSession(dashboard=$dashboard, user=$user)"
}
