// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.common.destinations.BrowserDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.utils.ext.launchCollectionInLifecycleScope
import com.ichi2.anki.widgets.DeckAdapter

/** Shows the review heatmap as one footer after the DeckPicker's deck rows. */
object ReviewHeatmapFooter {
    /** @return the adapter for the deck list: [deckAdapter]'s rows, then the heatmap. */
    fun attach(
        activity: DeckPicker,
        deckAdapter: DeckAdapter,
    ): RecyclerView.Adapter<*> {
        val heatmapViewModel = ViewModelProvider(activity)[ReviewHeatmapViewModel::class.java]
        val heatmapAdapter =
            ReviewHeatmapAdapter(
                onDaySelected = { _, date ->
                    activity.launchCatchingTask {
                        // Recalculate the relative search day in case midnight/rollover passed
                        // while the deck list remained on screen.
                        val query = withCol { ReviewHeatmap.searchForDay(this, date) }
                        if (query != null) {
                            with(activity) { navigate(BrowserDestination.Search(query, allDecks = true)) }
                        }
                    }
                },
                onRetry = { heatmapViewModel.refresh() },
            )
        with(activity) {
            activity.viewModel.flowOfDecksReloaded.launchCollectionInLifecycleScope { heatmapViewModel.refresh() }
            heatmapViewModel.state.launchCollectionInLifecycleScope { state ->
                when (state) {
                    ReviewHeatmapViewModel.State.Loading -> heatmapAdapter.setLoading()
                    is ReviewHeatmapViewModel.State.Loaded -> heatmapAdapter.setData(state.data)
                    ReviewHeatmapViewModel.State.Error -> heatmapAdapter.setError()
                }
            }
        }
        return concat(deckAdapter, heatmapAdapter)
    }

    /** Joins the deck rows and the heatmap, which follows the committed deck rows. */
    fun concat(
        deckAdapter: DeckAdapter,
        heatmapAdapter: ReviewHeatmapAdapter,
    ): ConcatAdapter {
        // Registered before the ConcatAdapter's own observer: RecyclerView notifies observers
        // last-registered first, so the footer moves after the ConcatAdapter has counted the new rows.
        deckAdapter.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                private fun update() = heatmapAdapter.onDeckListCommitted(deckAdapter.itemCount > 0)

                override fun onChanged() = update()

                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) = update()

                override fun onItemRangeRemoved(
                    positionStart: Int,
                    itemCount: Int,
                ) = update()
            },
        )
        return ConcatAdapter(deckAdapter, heatmapAdapter)
    }
}
