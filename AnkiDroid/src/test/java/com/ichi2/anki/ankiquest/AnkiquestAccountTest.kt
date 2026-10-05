// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AnkiquestAccountTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val received = CopyOnWriteArrayList<Pair<String, JSONObject>>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = JSONObject(exchange.requestBody.readBytes().decodeToString())
            received += exchange.requestURI.path to body
            val (status, answer) =
                when {
                    exchange.requestURI.path == "/api/accounts/tokens" && body.getString("password") == "right password" ->
                        200 to JSONObject().put("user", body.getString("user")).put("token", "device-token")
                    exchange.requestURI.path == "/api/accounts/tokens" -> 401 to JSONObject().put("error", "no")
                    body.getString("user") == "taken" -> 409 to JSONObject().put("error", "taken")
                    else -> 201 to JSONObject().put("user", body.getString("user")).put("token", "first-token")
                }
            val bytes = answer.toString().toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun credentials(
        user: String = "ana",
        password: String = "right password",
        display: String = "",
        create: Boolean = false,
    ) = AnkiquestAccount.Credentials("$url/", user, password, display, create)

    @Test
    fun `signing in trades the password for a device token`() =
        runBlocking {
            val signed = AnkiquestAccount.submit(credentials(), device = "AnkiDroid (Pixel)")
            assertEquals(AnkiquestAccount.Signed(url, "ana", "device-token"), signed)
            val (path, body) = received.single()
            assertEquals("/api/accounts/tokens", path)
            assertEquals("AnkiDroid (Pixel)", body.getString("device"))
            assertFalse(body.has("display"))
        }

    @Test
    fun `creating an account sends the display name and returns the first token`() =
        runBlocking {
            val signed = AnkiquestAccount.submit(credentials(user = "bo", display = "Bo", create = true), device = "AnkiDroid")
            assertEquals("first-token", signed.token)
            val (path, body) = received.single()
            assertEquals("/api/accounts", path)
            assertEquals("Bo", body.getString("display"))
        }

    @Test
    fun `refusals explain themselves`() =
        runBlocking {
            val wrong = assertFailsWith<Ankiquest.HttpStatusException> { AnkiquestAccount.submit(credentials(password = "wrong")) }
            assertEquals(targetContext.getString(R.string.ankiquest_sign_in_wrong), AnkiquestAccount.message(targetContext, wrong, url))
            val taken =
                assertFailsWith<Ankiquest.HttpStatusException> {
                    AnkiquestAccount.submit(credentials(user = "taken", create = true))
                }
            assertEquals(targetContext.getString(R.string.ankiquest_sign_in_taken), AnkiquestAccount.message(targetContext, taken, url))
        }
}
