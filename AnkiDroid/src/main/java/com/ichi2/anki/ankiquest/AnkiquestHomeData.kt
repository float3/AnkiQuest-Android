// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A captured identity: queued work must never borrow credentials from changed preferences. */
internal data class HomeAccount(
    val server: String,
    val user: String,
    val token: String,
    val language: String = AnkiquestLanguage.tag(null),
) {
    val encodedUser: String = URLEncoder.encode(user, "UTF-8").replace("+", "%20")
    val notificationAccount: String = "$server/$encodedUser"
    val scope: String = sha256(server, user, token)

    fun url(path: String): HttpUrl = requireNotNull("$server/$path".toHttpUrlOrNull())

    override fun toString(): String = "HomeAccount(server=$server, user=$user)"

    companion object {
        /** The configured account, read from one snapshot of [preferences] so its parts never come from different settings. */
        fun current(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): HomeAccount? {
            val settings = preferences.all
            return from(settings, (settings["username"] as? String).orEmpty())
        }

        fun from(
            settings: Map<String, *>,
            fallbackUser: String,
        ): HomeAccount? {
            val server = (settings[Ankiquest.PREF_URL] as? String).orEmpty().trim().trimEnd('/')
            val user = (settings[Ankiquest.PREF_USER] as? String).orEmpty().trim().ifEmpty { fallbackUser.trim() }
            val token = (settings[Ankiquest.PREF_TOKEN] as? String).orEmpty().trim()
            val parsed = server.toHttpUrlOrNull() ?: return null
            if (user.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null ||
                parsed.fragment != null
            ) {
                return null
            }
            return HomeAccount(server, user, token, AnkiquestLanguage.tag(settings["language"] as? String))
        }
    }
}

internal data class HomeDeck(
    val id: Long,
    val name: String,
    val new: Int,
    val learning: Int,
    val review: Int,
    val daily: DailyDeckState = DailyDeckState((new + learning + review).toLong(), (new + learning + review).toLong(), 0),
) {
    val due: Int get() = new + learning + review
}

internal data class HomeLocal(
    val decks: List<HomeDeck>,
    val selected: Long,
    val nextUpdateAt: Long? = null,
    val goal: AnkiquestDailyGoal.Status? = null,
) {
    val focus: HomeDeck? get() =
        decks.firstOrNull { it.id == selected && it.daily.status == DailyDeckStatus.REVIEW_NOW }
            ?: decks.firstOrNull { it.daily.status == DailyDeckStatus.REVIEW_NOW }
            ?: decks.firstOrNull { it.id == selected }
            ?: decks.firstOrNull()
}

internal object AnkiquestHomeData {
    fun account(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): HomeAccount? = HomeAccount.current(preferences)

    private val client =
        AnkiquestHttp.client
            .newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /** The profile of the captured [account], so changed settings never borrow its credentials. */
    suspend fun profile(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(account.url("api/profile/${account.encodedUser}"))
                    .header("Accept-Language", account.language)
                    .apply { if (account.token.isNotEmpty()) header("Authorization", "Bearer ${account.token}") }
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Ankiquest.HttpStatusException(response.code)
                JSONObject(response.body.string())
            }
        }

    suspend fun companion(account: HomeAccount): String = companionRequest(account, null)

    suspend fun saveCompanion(
        account: HomeAccount,
        choice: String,
    ): String {
        require(AnkiquestCompanion.normalize(choice) == choice)
        return companionRequest(account, choice)
    }

    private suspend fun companionRequest(
        account: HomeAccount,
        choice: String?,
    ): String =
        withContext(Dispatchers.IO) {
            check(account.token.isNotEmpty()) { "Set your AnkiQuest token to choose a companion." }
            val request =
                Request
                    .Builder()
                    .url(account.url("api/companion/${account.encodedUser}"))
                    .header("Authorization", "Bearer ${account.token}")
                    .apply {
                        if (choice != null) {
                            post(JSONObject().put("companion", choice).toString().toRequestBody("application/json".toMediaType()))
                        }
                    }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Ankiquest.HttpStatusException(response.code)
                AnkiquestCompanion.normalize(JSONObject(response.body.string()).getString("companion"))
            }
        }

    suspend fun local(): HomeLocal =
        CollectionManager.withCol {
            val now = TimeManager.time.intTimeMS()
            val tree = sched.deckDueTree()
            val daily = AnkiquestDecks.dailyStates(this, now, tree)
            val rows =
                tree.filter { it.did != 0L }.map {
                    HomeDeck(
                        it.did,
                        it.fullDeckName,
                        it.newCount,
                        it.lrnCount,
                        it.revCount,
                        daily[it.did]
                            ?: DailyDeckState(
                                (it.newCount + it.lrnCount + it.revCount).toLong(),
                                (it.newCount + it.lrnCount + it.revCount).toLong(),
                                0,
                            ),
                    )
                }
            val cutoff = sched.dayCutoff * 1000L
            val next = (daily.values.mapNotNull { it.nextLearningAt } + cutoff).filter { it > now }.minOrNull()
            val goal = runCatching { AnkiquestDailyGoal.status(AnkiquestDailyGoal.load(this), AnkiDroidApp.sharedPrefs()) }.getOrNull()
            HomeLocal(rows, decks.selected(), next, goal)
        }
}
