// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.ichi2.anki.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Says so the moment a card is conquered. Every upload answers the player's recent `conquests`, so
 * the answer to the review that conquers a card already carries it; the first one this phone has not
 * shown yet opens a pop-up over the screen in front. Each card is shown once per account: the ones
 * seen are kept in preferences, and on the first look every card already there counts as seen, so
 * installing or signing in does not celebrate old cards.
 */
internal object AnkiquestConquestPopup {
    private const val PREF_SHOWN = "ankiquestConquestsShown"

    /** The server lists conquests for longer, but only announces them this long; neither does this. */
    private const val FRESH_MS = 2 * 24 * 60 * 60 * 1000L

    /** A conquered card worth a pop-up: the answer [at] that conquered it, its [lapses], and whether it was a [leech]. */
    data class Fresh(
        val at: Long,
        val lapses: Int,
        val leech: Boolean,
    )

    /**
     * The newest of [conquests] not yet shown for [account] and conquered within [FRESH_MS] of [now],
     * or null. Remembers it and every other new one as shown, so nothing pops up twice, even when
     * several cards were conquered at once.
     */
    @VisibleForTesting
    internal fun take(
        prefs: SharedPreferences,
        account: String,
        conquests: JSONArray?,
        now: Long,
    ): Fresh? {
        val listed =
            (0 until (conquests?.length() ?: 0))
                .mapNotNull { conquests?.optJSONObject(it) }
                .filter { it.optLong("at") > 0 }
        val key = "$PREF_SHOWN:$account"
        val known = prefs.contains(key)
        val shown =
            prefs
                .getString(key, "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull() }
                .toSet()
        val fresh = if (known) listed.filter { it.getLong("at") !in shown && now - it.getLong("at") < FRESH_MS } else emptyList()
        if (known && fresh.isEmpty()) return null
        // Cards leave the server's short list only for newer ones, so the list is all there is to remember.
        prefs.edit { putString(key, listed.map { it.getLong("at") }.distinct().joinToString(",")) }
        return fresh.maxByOrNull { it.getLong("at") }?.let(::parse)
    }

    private fun parse(json: JSONObject) = Fresh(json.getLong("at"), json.optInt("lapses"), json.optBoolean("leech"))

    /** The pop-up over [host] for [card], with its front [text] and [deck] from this phone when known. */
    fun show(
        host: Activity,
        account: HomeAccount,
        card: Fresh,
        text: String?,
        deck: String?,
    ) {
        if (host.isFinishing || host.isDestroyed) return
        val message =
            listOfNotNull(
                host.resources.getQuantityString(R.plurals.aq_conquest_lead_mine, card.lapses, card.lapses),
                text?.let { host.getString(R.string.aq_conquest_popup_card, it) },
                deck?.let { host.getString(R.string.aq_conquest_deck, it) },
            ).joinToString("\n\n")
        AlertDialog
            .Builder(host)
            .setTitle(if (card.leech) R.string.aq_profile_leech else R.string.aq_profile_conquest)
            .setMessage(message)
            .setPositiveButton(R.string.aq_conquest_popup_open) { _, _ ->
                host.startActivity(AnkiquestConquestActivity.intent(host, account.user, card.at))
            }.setNegativeButton(R.string.aq_conquest_popup_later, null)
            .show()
    }
}
