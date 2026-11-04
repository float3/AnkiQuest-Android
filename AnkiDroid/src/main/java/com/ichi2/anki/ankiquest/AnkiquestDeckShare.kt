// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Right after a deck is finished for the day, asks whether to tell friends and which ones. Decks
 * already announced to chosen people every day are left to the server; players can turn the
 * question off in the sharing settings. Nobody is ticked to begin with, and the deck's name only
 * leaves the phone for the friends the player picks.
 */
internal object AnkiquestDeckShare {
    const val PREF_ASK = "ankiquestAskToShareDecks"
    private const val PREF_ASKED = "ankiquestDeckShareAsked"

    data class Recipient(
        val user: String,
        val display: String,
    )

    /** The question for one finished deck: its [name] and the people the player may tell. */
    data class Prompt(
        val deck: Long,
        val name: String,
        val recipients: List<Recipient>,
    )

    /** Whether the question is on; it is unless the player turned it off. */
    fun enabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_ASK, true)

    /**
     * The question to ask about [deck] from the server's deck settings (`GET /api/decks`), or null
     * when the deck already tells its chosen people by itself or there is nobody to tell.
     */
    fun prompt(
        deck: Long,
        name: String,
        settings: JSONObject,
    ): Prompt? {
        val automatic =
            settings.optJSONArray("decks")?.objects().orEmpty().any {
                it.optString("id") == deck.toString() &&
                    it.optBoolean("enabled") &&
                    (it.optJSONArray("recipients")?.length() ?: 0) > 0
            }
        if (automatic) return null
        val recipients =
            settings.optJSONArray("recipients")?.objects().orEmpty().mapNotNull { person ->
                val user = person.optString("user").ifEmpty { return@mapNotNull null }
                Recipient(user, person.optString("display").ifEmpty { user })
            }
        return if (recipients.isEmpty()) null else Prompt(deck, name, recipients)
    }

    /** True the first time it is asked for [account], [deck] and Anki [day]; it keeps only the current day's answers. */
    fun firstAsk(
        prefs: SharedPreferences,
        account: String,
        deck: Long,
        day: Long,
    ): Boolean {
        val asked = prefs.getStringSet(PREF_ASKED, emptySet()).orEmpty()
        val entry = "$account:$day:$deck"
        if (entry in asked) return false
        prefs.edit { putStringSet(PREF_ASKED, asked.filter { it.contains(":$day:") }.toSet() + entry) }
        return true
    }

    /**
     * Asks about the selected deck when it was just finished for the day, once per deck and day.
     * Every failure only means no question: the study session itself is already done.
     */
    suspend fun offer(context: Context) {
        try {
            val prefs = AnkiDroidApp.sharedPrefs()
            if (!enabled(prefs)) return
            val account = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() } ?: return
            val finished =
                withCol {
                    val now = TimeManager.time.intTimeMS()
                    val deck = decks.selected()
                    val state = AnkiquestDecks.dailyStates(this, now)[deck]
                    if (state?.status != DailyDeckStatus.DONE) {
                        null
                    } else {
                        Triple(deck, decks.name(deck), AnkiquestDecks.studyDay(this, now))
                    }
                } ?: return
            val (deck, name, day) = finished
            if (!firstAsk(prefs, account.scope, deck, day)) return
            val settings =
                withContext(Dispatchers.IO) {
                    AnkiquestApi.json(account, AnkiquestApi.request(account, "api/decks/${account.encodedUser}").build())
                }
            val prompt = prompt(deck, name, settings) ?: return
            withContext(Dispatchers.Main) { show(context, account, prompt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest finished deck question skipped")
        }
    }

    private fun show(
        context: Context,
        account: HomeAccount,
        prompt: Prompt,
    ) {
        val language = AnkiquestLanguage.context(context)
        val picked = BooleanArray(prompt.recipients.size)
        AlertDialog
            .Builder(context)
            .setTitle(language.getString(R.string.ankiquest_deck_share_title, prompt.name))
            .setMultiChoiceItems(prompt.recipients.map { it.display }.toTypedArray(), picked) { _, which, checked ->
                picked[which] = checked
            }.setPositiveButton(language.getString(R.string.ankiquest_deck_share_send)) { _, _ ->
                val recipients = prompt.recipients.filterIndexed { index, _ -> picked[index] }.map { it.user }
                if (recipients.isNotEmpty()) send(context.applicationContext, account, prompt, recipients)
            }.setNegativeButton(language.getString(R.string.ankiquest_deck_share_not_now), null)
            .setNeutralButton(language.getString(R.string.ankiquest_deck_share_never)) { _, _ ->
                AnkiDroidApp.sharedPrefs().edit { putBoolean(PREF_ASK, false) }
                showThemedToast(context, language.getString(R.string.ankiquest_deck_share_never_done), shortLength = false)
            }.show()
    }

    private fun send(
        context: Context,
        account: HomeAccount,
        prompt: Prompt,
        recipients: List<String>,
    ) {
        applicationScope.launch {
            val language = AnkiquestLanguage.context(context)
            val message =
                try {
                    val told = AnkiquestSharing.shareFinishedDeck(account, prompt.deck, prompt.name, recipients)
                    if (told == 0) {
                        language.getString(R.string.ankiquest_deck_share_nobody)
                    } else {
                        language.resources.getQuantityString(R.plurals.ankiquest_share_sent, told, told)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AnkiquestHttpException) {
                    Timber.w(e, "ankiquest finished deck share refused")
                    language.getString(
                        if (e.code == 404 || e.code == 405) R.string.ankiquest_deck_share_unsupported else R.string.ankiquest_share_failed,
                    )
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest finished deck share failed")
                    language.getString(R.string.ankiquest_share_failed)
                }
            launch(Dispatchers.Main) { showThemedToast(context, message, shortLength = false) }
        }
    }
}
