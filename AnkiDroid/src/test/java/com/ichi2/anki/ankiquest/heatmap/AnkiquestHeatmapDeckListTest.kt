// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.deckPicker
import com.ichi2.anki.deckpicker.DeckFilters
import com.ichi2.anki.deckpicker.DisplayDeckNode
import com.ichi2.anki.deckpicker.filterAndFlattenDisplay
import com.ichi2.anki.widgets.DeckAdapter
import com.ichi2.anki.withDeckPicker
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.ichi2.anki.common.android.R as CommonR

/** The review heatmap footer of the DeckPicker deck list. */
@RunWith(AndroidJUnit4::class)
class AnkiquestHeatmapDeckListTest : RobolectricTest() {
    @Test
    fun `review heatmap is a single footer after the deck list`() =
        deckPicker {
            val adapter = deckPickerBinding.decks.adapter as ConcatAdapter
            val deckAdapter = adapter.adapters.filterIsInstance<DeckAdapter>().single()
            val heatmapAdapter = adapter.adapters.filterIsInstance<ReviewHeatmapAdapter>().single()

            advanceRobolectricLooperUntil { deckAdapter.itemCount > 0 && heatmapAdapter.itemCount == 1 }
            assertEquals(listOf(deckAdapter, heatmapAdapter), adapter.adapters)
            assertEquals(1, heatmapAdapter.itemCount)
            assertEquals(deckAdapter.itemCount + 1, adapter.itemCount)
        }

    @Test
    fun `reloading decks refreshes the review heatmap after studying`() =
        withDeckPicker(deckCount = 1) { deckPicker ->
            val heatmap = ViewModelProvider(deckPicker)[ReviewHeatmapViewModel::class.java]
            // Lifecycle flow callbacks are posted onto the paused Android looper in Robolectric.
            advanceRobolectricLooperUntil(lazyMessage = { "Initial heatmap did not load: ${heatmap.state.value}" }) {
                heatmap.state.value is ReviewHeatmapViewModel.State.Loaded
            }
            val initial = assertNotNull(heatmap.state.value as? ReviewHeatmapViewModel.State.Loaded).data
            val cardId = addBasicNote().firstCard().id
            val reviewTime = col.backend.schedTimingToday().nextDayAt * 1000L - 1
            col.db.execute(
                "insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?, ?, -1, 3, 1, 1, 2500, 1000, 1)",
                reviewTime,
                cardId,
            )

            deckPicker.updateDeckList()

            advanceRobolectricLooperUntil(lazyMessage = { "Deck reload did not refresh the heatmap: ${heatmap.state.value}" }) {
                (heatmap.state.value as? ReviewHeatmapViewModel.State.Loaded)?.data?.totalReviews == initial.totalReviews + 1
            }
            val refreshed = assertNotNull(heatmap.state.value as? ReviewHeatmapViewModel.State.Loaded).data
            assertEquals(1, refreshed.reviews[refreshed.today])
            assertEquals(1, refreshed.currentStreak)

            val decks = deckPicker.deckPickerBinding.decks
            val footerPosition = checkNotNull(decks.adapter).itemCount - 1
            val expectedTotal = deckPicker.getString(R.string.review_heatmap_total, "1")
            decks.scrollToPosition(footerPosition)
            advanceRobolectricLooperUntil(lazyMessage = { "The heatmap footer did not display the new review total" }) {
                decks
                    .findViewHolderForAdapterPosition(footerPosition)
                    ?.itemView
                    ?.findViewById<TextView>(R.id.review_heatmap_total)
                    ?.text
                    ?.toString() == expectedTotal
            }
        }

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

    private inner class HeatmapDeckListFixture {
        private val context = ContextThemeWrapper(targetContext, CommonR.style.Theme_Light)
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
                adapter = ReviewHeatmapFooter.concat(deckAdapter, heatmapAdapter)
            }

        fun submit(deckList: List<DisplayDeckNode>) {
            deckAdapter.submit(deckList, hasSubDecks = false)
            advanceRobolectricLooperUntil { deckAdapter.currentList == deckList }
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
}
