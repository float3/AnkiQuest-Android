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
import java.time.LocalDate

/**
 * Keeps review history out of the deck-list loading path and survives activity recreation.
 *
 * The collection's history is shown as soon as it is read. When AnkiQuest is set up, the
 * days its streak was protected on follow from the server, and the streaks are counted
 * again with them; without a connection the heatmap stays as the collection alone says.
 */
class ReviewHeatmapViewModel(
    private val protectedDays: suspend () -> Set<LocalDate> = ::ankiquestProtectedDays,
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
                    val kept =
                        try {
                            protectedDays()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.i(error, "AnkiQuest streak days unavailable")
                            emptySet()
                        }
                    ensureActive()
                    if (kept.isNotEmpty()) {
                        mutableState.value = State.Loaded(ReviewHeatmap.withProtectedDays(data, kept))
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

private suspend fun ankiquestProtectedDays(): Set<LocalDate> =
    if (Ankiquest.isConfigured()) ReviewHeatmap.protectedDays(Ankiquest.profile()) else emptySet()
