/*
 *  Copyright (c) 2026 Vedant Kakade <vedantkakade05@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.widget

import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.awaitDeckHolder
import com.ichi2.anki.awaitInitialDeckHolder
import com.ichi2.anki.deckpicker.DeckFilters
import com.ichi2.anki.deckpicker.DisplayDeckNode
import com.ichi2.anki.deckpicker.filterAndFlattenDisplay
import com.ichi2.anki.deckpicker.heatmap.ReviewHeatmap
import com.ichi2.anki.deckpicker.heatmap.ReviewHeatmapAdapter
import com.ichi2.anki.widgets.DeckAdapter
import com.ichi2.anki.withDeckPicker
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DeckAdapterTest : RobolectricTest() {
    @Test
    fun `first deck stays visible when the heatmap loads before the deck list`() {
        val deckId = addDeck("First deck")
        val deckList = col.sched.deckDueTree().filterAndFlattenDisplay(DeckFilters.create(""), deckId)
        val fixture = HeatmapDeckListFixture()

        // Deck data arrives asynchronously. Exercise a frame where the footer is ready first.
        assertEquals(0, fixture.deckAdapter.itemCount)
        fixture.layout()

        fixture.submit(deckList)
        fixture.layout()

        fixture.assertFirstDeckVisible(deckList.first())
    }

    @Test
    fun `clearing an empty deck filter returns to the first deck instead of the heatmap`() {
        val deckId = addDeck("First deck")
        val tree = col.sched.deckDueTree()
        val deckList = tree.filterAndFlattenDisplay(DeckFilters.create(""), deckId)
        val filteredList = tree.filterAndFlattenDisplay(DeckFilters.create("No deck matches this filter"), deckId)
        assertTrue(filteredList.isEmpty())
        val fixture = HeatmapDeckListFixture()
        fixture.submit(deckList)
        fixture.layout()
        fixture.assertFirstDeckVisible(deckList.first())

        fixture.submit(filteredList)
        fixture.layout()
        assertEquals(0, checkNotNull(fixture.decks.adapter).itemCount)

        fixture.submit(deckList)
        fixture.layout()
        fixture.assertFirstDeckVisible(deckList.first())
    }

    @Test
    fun `saved deck scroll survives a layout before the first deck commit`() {
        val deckIds = (0 until 30).map { addDeck("Deck ${it.toString().padStart(2, '0')}") }
        val deckList = col.sched.deckDueTree().filterAndFlattenDisplay(DeckFilters.create(""), deckIds.first())
        val original = HeatmapDeckListFixture()
        original.submit(deckList)
        original.layout()
        // Arrange a real saved viewport; the restoring fixture never requests a scroll.
        original.layoutManager.scrollToPositionWithOffset(18, -9)
        original.layout()
        assertEquals(18, original.layoutManager.findFirstVisibleItemPosition())
        val originalTop = original.layoutManager.getDecoratedTop(assertNotNull(original.layoutManager.findViewByPosition(18)))
        val state = SparseArray<Parcelable>()
        original.decks.saveHierarchyState(state)

        val restored = HeatmapDeckListFixture()
        restored.decks.restoreHierarchyState(state)
        assertEquals(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY, restored.heatmapAdapter.stateRestorationPolicy)
        assertEquals(RecyclerView.Adapter.StateRestorationPolicy.PREVENT, checkNotNull(restored.decks.adapter).stateRestorationPolicy)
        restored.layout()
        assertEquals(0, restored.deckAdapter.itemCount)
        restored.submit(deckList)
        restored.layout()

        assertEquals(1, restored.heatmapAdapter.itemCount)
        assertEquals(RecyclerView.Adapter.StateRestorationPolicy.ALLOW, checkNotNull(restored.decks.adapter).stateRestorationPolicy)
        assertEquals(18, restored.layoutManager.findFirstVisibleItemPosition())
        val restoredRow = assertNotNull(restored.layoutManager.findViewByPosition(18))
        assertEquals(originalTop, restored.layoutManager.getDecoratedTop(restoredRow))
        val holder = assertIs<DeckAdapter.ViewHolder>(restored.decks.getChildViewHolder(restoredRow))
        assertEquals(deckList[18].did, restored.deckAdapter.currentList[holder.bindingAdapterPosition].did)
    }

    @Test
    fun ensureDeckSelectionUpdatesCorrectly() {
        val deck1Id = addDeck("Deck 1")
        val deck2Id = addDeck("Deck 2")
        val deck3Id = addDeck("Deck 3")

        val node =
            col.sched.deckDueTree().filterAndFlattenDisplay(
                DeckFilters.create(""),
                deck1Id,
            )

        assertTrue(node.first { it.did == deck1Id }.isSelected)

        val afterDeck2 = node.map { it.withUpdatedDeckId(deck2Id) }
        assertFalse(actual = afterDeck2.first { it.did == deck1Id }.isSelected)
        assertTrue(actual = afterDeck2.first { it.did == deck2Id }.isSelected)

        val afterDeck3 = afterDeck2.map { it.withUpdatedDeckId(deck3Id) }
        assertFalse(actual = afterDeck3.first { it.did == deck2Id }.isSelected)
        assertTrue(actual = afterDeck3.first { it.did == deck3Id }.isSelected)
    }

    @Test
    fun `selecting a pressed deck does not start a new ripple`() {
        val initiallySelected = addDeck("Initially selected")
        val pressedDeck = addDeck("Pressed")
        col.decks.select(initiallySelected)

        withDeckPicker(deckCount = 0) { deckPicker ->
            val adapter = (deckPicker.deckPickerBinding.decks.adapter as ConcatAdapter).adapters.filterIsInstance<DeckAdapter>().single()
            val pressedRow = deckPicker.awaitInitialDeckHolder(pressedDeck).itemView
            pressedRow.isPressed = true
            val originalRipple = pressedRow.background

            adapter.updateSelectedDeck(pressedDeck)
            advanceRobolectricLooperUntil { adapter.currentList.single { it.did == pressedDeck }.isSelected }

            val selectedBackground = deckPicker.awaitDeckHolder(pressedDeck).itemView.background
            // A replacement drawable must not inherit the press and start a second ripple.
            assertTrue(
                selectedBackground === originalRipple || android.R.attr.state_pressed !in selectedBackground.state,
                "Selecting a pressed deck restarted its ripple",
            )
        }
    }

    @Test
    fun `toggling subdecks preserves the arrow view and updates its icon`() {
        val parentDeck = addDeck("Parent")
        addDeck("Parent::Child")
        col.decks.select(parentDeck)

        withDeckPicker(deckCount = 0) { deckPicker ->
            val adapter = (deckPicker.deckPickerBinding.decks.adapter as ConcatAdapter).adapters.filterIsInstance<DeckAdapter>().single()
            val parent = deckPicker.awaitInitialDeckHolder(parentDeck)
            val changePayloads = mutableListOf<Any?>()
            adapter.observeItemRangeChanges { _, _, payload -> changePayloads.add(payload) }

            repeat(2) {
                val wasCollapsed = adapter.currentList.single { it.did == parentDeck }.collapsed
                parent.binding.deckExpander.performClick()
                advanceRobolectricLooperUntil {
                    adapter.currentList.single { it.did == parentDeck }.collapsed != wasCollapsed
                }

                assertTrue(changePayloads.isNotEmpty())
                assertTrue(changePayloads.all { it != null }, "A full row update interrupts the arrow ripple")
                assertSame(parent, deckPicker.awaitDeckHolder(parentDeck))
                assertEquals(
                    deckPicker.getString(if (wasCollapsed) CommonString.collapse else CommonString.expand),
                    parent.binding.deckExpander.contentDescription,
                )
                changePayloads.clear()
            }
        }
    }

    private inner class HeatmapDeckListFixture {
        private val context = ContextThemeWrapper(targetContext, R.style.Theme_Light)
        val deckAdapter =
            DeckAdapter(
                context,
                onDeckSelected = {},
                onDeckCountsSelected = {},
                onDeckChildrenToggled = {},
                onDeckContextRequested = {},
                onDeckRightClick = { _, _, _ -> },
            )
        val heatmapAdapter =
            ReviewHeatmapAdapter(onDaySelected = { _, _ -> }, onRetry = {}).apply {
                val today = LocalDate.of(2024, 9, 22)
                setData(ReviewHeatmap.summarize(today, mapOf(today to 10), emptyMap()))
            }
        val layoutManager = LinearLayoutManager(context)
        val decks =
            RecyclerView(context).apply {
                id = R.id.decks
                layoutManager = this@HeatmapDeckListFixture.layoutManager
                adapter = ConcatAdapter(deckAdapter, heatmapAdapter)
            }

        fun submit(deckList: List<DisplayDeckNode>) {
            var committed = false
            deckAdapter.submit(deckList, hasSubDecks = false) {
                heatmapAdapter.onDeckListCommitted(deckAdapter.itemCount > 0)
                committed = true
            }
            advanceRobolectricLooperUntil { committed }
        }

        fun layout() {
            decks.forceLayout()
            decks.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(406, View.MeasureSpec.EXACTLY),
            )
            decks.layout(0, 0, 320, 406)
        }

        fun assertFirstDeckVisible(expected: DisplayDeckNode) {
            assertEquals(1, heatmapAdapter.itemCount)
            assertEquals(deckAdapter.itemCount + 1, checkNotNull(decks.adapter).itemCount)
            assertEquals(0, layoutManager.findFirstVisibleItemPosition(), "The footer must not become the deck list's scroll anchor")
            val firstDeck = assertIs<DeckAdapter.ViewHolder>(decks.findViewHolderForAdapterPosition(0))
            assertSame(deckAdapter, firstDeck.bindingAdapter)
            assertEquals(expected.did, deckAdapter.currentList[firstDeck.bindingAdapterPosition].did)
        }
    }

    private fun RecyclerView.Adapter<*>.observeItemRangeChanges(listener: (Int, Int, Any?) -> Unit) {
        registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                    payload: Any?,
                ) {
                    listener(positionStart, itemCount, payload)
                }
            },
        )
    }
}
