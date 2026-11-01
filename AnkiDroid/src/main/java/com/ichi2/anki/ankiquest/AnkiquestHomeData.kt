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
import org.json.JSONObject
import java.net.URLEncoder

/** A captured identity: queued work must never borrow credentials from changed preferences. */
internal data class HomeAccount(
    val server: String,
    val user: String,
    val token: String,
    val language: String = AnkiquestLanguage.tag(null),
) {
    val encodedUser: String = URLEncoder.encode(user, "UTF-8").replace("+", "%20")
    val notificationAccount: String = "$server/$encodedUser"
    val scope: String by lazy { sha256(server, user, token) }

    fun url(path: String): HttpUrl = requireNotNull("$server/$path".toHttpUrlOrNull())

    override fun toString(): String = "HomeAccount(server=$server, user=$user)"

    /** The preferences an account is made of, as read together. */
    private data class Stored(
        val server: String?,
        val user: String?,
        val token: String?,
        val fallbackUser: String?,
        val language: String?,
    )

    companion object {
        private const val MAX_READS = 5

        @Volatile
        private var resolved: Pair<Stored, HomeAccount?>? = null

        /**
         * The configured account. Its parts are read until two reads agree, so they never come from
         * different settings, and an unchanged configuration answers the same instance, whose [scope]
         * is then already known.
         */
        fun current(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): HomeAccount? {
            var stored = read(preferences)
            repeat(MAX_READS) {
                val again = read(preferences)
                if (again == stored) return resolve(stored)
                stored = again
            }
            return null
        }

        private fun read(preferences: SharedPreferences) =
            Stored(
                preferences.text(Ankiquest.PREF_URL),
                preferences.text(Ankiquest.PREF_USER),
                preferences.text(Ankiquest.PREF_TOKEN),
                preferences.text("username"),
                preferences.text("language"),
            )

        private fun SharedPreferences.text(key: String): String? =
            try {
                getString(key, null)
            } catch (_: ClassCastException) {
                null
            }

        private fun resolve(stored: Stored): HomeAccount? {
            resolved?.let { (key, account) ->
                if (key == stored && account?.language == AnkiquestLanguage.tag(stored.language)) return account
            }
            return from(stored.server, stored.user, stored.token, stored.fallbackUser.orEmpty(), stored.language).also {
                resolved = stored to it
            }
        }

        fun from(
            settings: Map<String, *>,
            fallbackUser: String,
        ): HomeAccount? =
            from(
                settings[Ankiquest.PREF_URL] as? String,
                settings[Ankiquest.PREF_USER] as? String,
                settings[Ankiquest.PREF_TOKEN] as? String,
                fallbackUser,
                settings["language"] as? String,
            )

        private fun from(
            url: String?,
            player: String?,
            credential: String?,
            fallbackUser: String,
            language: String?,
        ): HomeAccount? {
            val server = url.orEmpty().trim().trimEnd('/')
            val user = player.orEmpty().trim().ifEmpty { fallbackUser.trim() }
            val token = credential.orEmpty().trim()
            val parsed = server.toHttpUrlOrNull() ?: return null
            if (user.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null ||
                parsed.fragment != null
            ) {
                return null
            }
            return HomeAccount(server, user, token, AnkiquestLanguage.tag(language))
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

    /** The profile of the captured [account], so changed settings never borrow its credentials. */
    suspend fun profile(account: HomeAccount): JSONObject =
        withContext(Dispatchers.IO) {
            AnkiquestApi.call(AnkiquestApi.request(account, "api/profile/${account.encodedUser}").build()) {
                JSONObject(it.body.string())
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
                AnkiquestApi
                    .request(account, "api/companion/${account.encodedUser}")
                    .apply { if (choice != null) post(AnkiquestApi.body(JSONObject().put("companion", choice))) }
                    .build()
            AnkiquestApi.call(request) {
                AnkiquestCompanion.normalize(JSONObject(it.body.string()).getString("companion"))
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
