// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.sched.DeckNode

/** Daily deck states shown in the deck list. */
internal object AnkiquestDeckListStates {
    /** @return the states of the decks in [dueTree]; the deck list always shows them. */
    fun load(
        col: Collection,
        dueTree: DeckNode,
    ): Map<DeckId, DailyDeckState> = AnkiquestDecks.dailyStates(col, TimeManager.time.intTimeMS(), dueTree)
}
