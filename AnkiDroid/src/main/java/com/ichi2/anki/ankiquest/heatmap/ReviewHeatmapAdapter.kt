// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate

/** One footer after the deck rows. Its year and selection survive recycling. */
class ReviewHeatmapAdapter(
    private val onDaySelected: (ReviewHeatmapData, LocalDate) -> Unit,
    private val onRetry: () -> Unit,
) : RecyclerView.Adapter<ReviewHeatmapAdapter.Holder>() {
    private var data: ReviewHeatmapData? = null
    private var failed = false
    private var shown = false
    private val viewState = ReviewHeatmapView.State()

    init {
        // An early footer must not consume the deck list's initial or restored scroll position.
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    class Holder(
        val heatmap: ReviewHeatmapView,
    ) : RecyclerView.ViewHolder(heatmap)

    override fun getItemCount(): Int = if (shown) 1 else 0

    /** Keep the footer behind committed deck rows, including when a filter is cleared. */
    fun onDeckListCommitted(hasDecks: Boolean) {
        if (shown == hasDecks) return
        shown = hasDecks
        if (shown) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder =
        Holder(
            ReviewHeatmapView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                onDaySelected = this@ReviewHeatmapAdapter.onDaySelected
                onRetry = this@ReviewHeatmapAdapter.onRetry
            },
        )

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val currentData = data
        when {
            currentData != null -> holder.heatmap.setData(currentData, viewState)
            failed -> holder.heatmap.setError()
            else -> holder.heatmap.setLoading()
        }
    }

    fun setData(data: ReviewHeatmapData) {
        this.data = data
        failed = false
        if (shown) notifyItemChanged(0)
    }

    fun setLoading() {
        data = null
        failed = false
        if (shown) notifyItemChanged(0)
    }

    fun setError() {
        data = null
        failed = true
        if (shown) notifyItemChanged(0)
    }
}
