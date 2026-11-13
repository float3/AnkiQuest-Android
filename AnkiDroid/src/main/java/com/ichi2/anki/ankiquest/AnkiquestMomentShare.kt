// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.ankiquest.AnkiquestSharing.Audience
import com.ichi2.anki.ankiquest.AnkiquestSharing.MomentRef
import com.ichi2.anki.ankiquest.AnkiquestSharing.Sent
import org.json.JSONArray
import org.json.JSONException

/**
 * Sending a moment to chosen AnkiQuest friends and groups from the share sheet: who can be
 * chosen, in which order, and the send and its undo. Servers without [AnkiquestSharing.FEATURE_MOMENTS]
 * keep the older ways to share.
 */
internal object AnkiquestMomentShare {
    private const val PREF_RECENT = "ankiquestShareRecent"
    private const val MAX_RECENT = 20

    /** Someone a moment can be sent to. [key] is stable across loads, for remembering recent choices. */
    sealed class Target {
        abstract val key: String
        abstract val label: String

        data class Friend(
            val user: String,
            override val label: String,
        ) : Target() {
            override val key: String get() = "user:$user"
        }

        data class Group(
            val id: Long,
            override val label: String,
        ) : Target() {
            override val key: String get() = "group:$id"
        }
    }

    /** What the player picked in the sheet: nobody yet, "All friends", or chosen friends and groups. */
    sealed class Choice {
        data object Nobody : Choice()

        data object Everyone : Choice()

        data class Picked(
            val targets: Set<Target>,
        ) : Choice()

        /** [target] added or removed; picking one replaces "All friends". */
        fun toggle(target: Target): Choice {
            val current = (this as? Picked)?.targets.orEmpty()
            val next = if (target in current) current - target else current + target
            return if (next.isEmpty()) Nobody else Picked(next)
        }

        /** "All friends" switched on, or off again. */
        fun toggleEveryone(): Choice = if (this == Everyone) Nobody else Everyone

        fun audience(): Audience? =
            when (this) {
                is Nobody -> null
                is Everyone -> Audience.Circle
                is Picked ->
                    Audience.Chosen(
                        targets.filterIsInstance<Target.Friend>().map { it.user },
                        targets.filterIsInstance<Target.Group>().map { it.id },
                    )
            }
    }

    /** Whether [account]'s server last reported that it takes chosen recipients and a note. */
    fun available(account: HomeAccount): Boolean = AnkiquestCapabilities.has(account, AnkiquestSharing.FEATURE_MOMENTS)

    /** [account]'s friends and groups, those sent to most recently first. */
    suspend fun targets(account: HomeAccount): List<Target> {
        val overview = AnkiquestSocial.overview(account)
        val friends = overview.friends.map { Target.Friend(it.user, it.display.ifEmpty { it.user }) }

        // A group reaches its members other than the player; one with nobody else reaches nobody.
        val groups =
            overview.groups
                .filter { group -> group.members.any { it.user != account.user } }
                .map { Target.Group(it.id, it.name) }
        return order(friends + groups, recent(account))
    }

    /** [targets] with the [recent] keys first, newest first, then friends and then groups by name. */
    fun order(
        targets: List<Target>,
        recent: List<String>,
    ): List<Target> {
        val rank = recent.withIndex().associate { (index, key) -> key to index }
        return targets.sortedWith(
            compareBy<Target> { rank[it.key] ?: Int.MAX_VALUE }
                .thenBy { if (it is Target.Friend) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

    /** Whether [target] matches what the player typed in the search field. */
    fun matches(
        target: Target,
        query: String,
    ): Boolean {
        val words = query.trim()
        if (words.isEmpty()) {
            return true
        }
        val user = (target as? Target.Friend)?.user.orEmpty()
        return target.label.contains(words, ignoreCase = true) || user.contains(words, ignoreCase = true)
    }

    /** The note as the server keeps it: one line, runs of spaces made one; null when nothing is left. */
    fun note(text: String): String? =
        text
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(AnkiquestSharing.MAX_NOTE)
            .ifEmpty { null }

    /** Sends [moment] to [choice] with [note], and remembers the chosen people for next time. */
    suspend fun send(
        account: HomeAccount,
        moment: MomentRef,
        choice: Choice,
        note: String?,
    ): Sent {
        val audience = requireNotNull(choice.audience()) { "Choose someone to send it to" }
        val sent = AnkiquestSharing.send(account, moment, audience, note?.let(::note))
        if (choice is Choice.Picked) {
            remember(account, choice.targets.map { it.key })
        }
        return sent
    }

    /** Takes a sent [win] back from those who have not seen it yet; answers how many. */
    suspend fun retract(
        account: HomeAccount,
        win: Long,
    ): Int = AnkiquestSharing.retract(account, win)

    /** The keys of the people [account] sent to lately, newest first. */
    private fun recent(account: HomeAccount): List<String> {
        val stored = preferences().getString(scopedKey(PREF_RECENT, account.scope), null) ?: return emptyList()
        return try {
            val keys = JSONArray(stored)
            (0 until keys.length()).map { keys.getString(it) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun remember(
        account: HomeAccount,
        keys: List<String>,
    ) {
        val kept = (keys + recent(account)).distinct().take(MAX_RECENT)
        preferences().edit { putString(scopedKey(PREF_RECENT, account.scope), JSONArray(kept).toString()) }
    }

    private fun preferences(): SharedPreferences = AnkiDroidApp.sharedPrefs()
}
