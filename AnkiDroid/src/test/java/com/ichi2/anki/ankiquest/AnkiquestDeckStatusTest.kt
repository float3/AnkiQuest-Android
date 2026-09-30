// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.ContextThemeWrapper
import android.view.View
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.awaitInitialDeckHolder
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.deckpicker.DeckFilters
import com.ichi2.anki.deckpicker.filterAndFlattenDisplay
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.widgets.DeckAdapter
import com.ichi2.anki.withDeckPicker
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestDeckStatusTest : RobolectricTest() {
    @Test
    fun `only learning due later gets a second line, and a finished deck gets a check`() {
        val done = addDeck("Done")
        val ready = addDeck("Ready")
        val later = addDeck("Later")
        val empty = addDeck("Empty")
        val states =
            mapOf(
                done to DailyDeckState(ready = 0, remaining = 0, reviewed = 3),
                ready to DailyDeckState(ready = 5, remaining = 5, reviewed = 0),
                later to DailyDeckState(ready = 0, remaining = 1, reviewed = 1, nextLearningAt = TimeManager.time.intTimeMS() + 3_600_000),
                empty to DailyDeckState(ready = 0, remaining = 0, reviewed = 0),
            )
        val rows = bind(states)

        assertTrue(
            rows
                .getValue(later)
                .binding.deckDailyStatus.isVisible,
            "learning later today is new information",
        )
        for (deck in listOf(done, ready, empty)) {
            assertFalse(
                rows
                    .getValue(deck)
                    .binding.deckDailyStatus.isVisible,
                "the counts already say this",
            )
        }
        assertNotNull(
            rows
                .getValue(done)
                .binding.deckName.compoundDrawablesRelative[2],
            "a finished deck shows a check",
        )
        for (deck in listOf(ready, later, empty)) {
            assertNull(
                rows
                    .getValue(deck)
                    .binding.deckName.compoundDrawablesRelative[2],
            )
        }
    }

    @Test
    fun `the deck list shows daily status only while the setting is on`() {
        val spanish = addDeck("Spanish")
        col.decks.select(spanish)
        for ((enabled, expected) in listOf(true to true, false to false)) {
            AnkiDroidApp.sharedPrefs().edit { putBoolean(AnkiquestDecks.DECK_STATUS_KEY, enabled) }
            withDeckPicker(deckCount = 0) { deckPicker ->
                deckPicker.awaitInitialDeckHolder(spanish)
                val adapter =
                    (deckPicker.deckPickerBinding.decks.adapter as ConcatAdapter)
                        .adapters
                        .filterIsInstance<DeckAdapter>()
                        .single()
                assertEquals(expected, adapter.currentList.any { it.dailyState != null }, "setting on: $enabled")
            }
        }
    }

    private fun bind(states: Map<DeckId, DailyDeckState>): Map<DeckId, DeckAdapter.ViewHolder> {
        val context = ContextThemeWrapper(targetContext, R.style.Theme_Light)
        val adapter =
            DeckAdapter(
                context,
                onDeckSelected = {},
                onDeckCountsSelected = {},
                onDeckChildrenToggled = {},
                onDeckContextRequested = {},
                onDeckRightClick = { _, _, _ -> },
            )
        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
            }
        val nodes =
            col.sched
                .deckDueTree()
                .filterAndFlattenDisplay(DeckFilters.create(""), 1)
                .map { it.withDailyState(states[it.did]) }
        var committed = false
        adapter.submit(nodes, hasSubDecks = false) { committed = true }
        advanceRobolectricLooperUntil { committed }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 480, 2000)
        return states.keys.associateWith { deck ->
            list.findViewHolderForAdapterPosition(adapter.currentList.indexOfFirst { it.did == deck }) as DeckAdapter.ViewHolder
        }
    }
}
