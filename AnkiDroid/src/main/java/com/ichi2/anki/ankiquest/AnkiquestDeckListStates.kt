// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.DeckNode

/** Daily deck states shown in the deck list. */
internal object AnkiquestDeckListStates {
    /** @return the states of the decks in [dueTree], or an empty map when the deck list does not show them. */
    suspend fun load(dueTree: DeckNode): Map<DeckId, DailyDeckState> =
        if (AnkiquestDecks.statusInDeckList()) {
            withCol { AnkiquestDecks.dailyStates(this, TimeManager.time.intTimeMS(), dueTree) }
        } else {
            emptyMap()
        }
}
