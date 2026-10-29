// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.deckpicker.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankiquest.Ankiquest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps review history out of the deck-list loading path and survives activity recreation.
 *
 * The collection's history is shown as soon as it is read. When AnkiQuest is set up, its
 * numbering of the days, which follows the clock each review was made under, and the days
 * its streak was protected on follow from the server, and the heatmap is drawn again with
 * them; without a connection it stays as the collection alone says.
 */
class ReviewHeatmapViewModel(
    private val serverDays: suspend () -> ReviewHeatmap.ServerDays? = ::ankiquestDays,
    private val load: suspend () -> ReviewHeatmapData = { withCol { ReviewHeatmap.load(this) } },
) : ViewModel() {
    sealed interface State {
        data object Loading : State

        data class Loaded(
            val data: ReviewHeatmapData,
        ) : State

        data object Error : State
    }

    private val mutableState = MutableStateFlow<State>(State.Loading)
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh(): Job {
        refreshJob?.cancel()
        return viewModelScope
            .launch {
                if (mutableState.value !is State.Loaded) mutableState.value = State.Loading
                try {
                    val data = load()
                    ensureActive()
                    mutableState.value = State.Loaded(data)
                    val server =
                        try {
                            serverDays()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.i(error, "AnkiQuest days unavailable")
                            null
                        }
                    ensureActive()
                    if (server != null) {
                        mutableState.value = State.Loaded(ReviewHeatmap.withServerDays(data, server))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    Timber.w(error, "Unable to load review heatmap")
                    mutableState.value = State.Error
                }
            }.also { refreshJob = it }
    }
}

private suspend fun ankiquestDays(): ReviewHeatmap.ServerDays? =
    if (Ankiquest.isConfigured()) ReviewHeatmap.serverDays(Ankiquest.profile()) else null
