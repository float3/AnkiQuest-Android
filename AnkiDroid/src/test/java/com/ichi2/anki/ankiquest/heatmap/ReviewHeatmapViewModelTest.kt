// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewHeatmapViewModelTest : RobolectricTest() {
    private val today = LocalDate.of(2026, 9, 22)
    private val initial = ReviewHeatmap.summarize(today, mapOf(today to 1), emptyMap())
    private val updated = ReviewHeatmap.summarize(today, mapOf(today to 2), emptyMap())

    @Test
    fun `a cancelled older load cannot replace a newer result`() =
        runTest {
            var pending: Continuation<ReviewHeatmapData>? = null
            var loads = 0
            val viewModel =
                ReviewHeatmapViewModel {
                    if (++loads == 1) {
                        // Simulate a backend read that does not stop immediately on cancellation.
                        suspendCoroutine { pending = it }
                    } else {
                        updated
                    }
                }

            val oldLoad = viewModel.refresh()
            runCurrent()
            val oldResult = assertNotNull(pending)
            viewModel.refresh().join()
            oldResult.resume(initial)
            oldLoad.join()

            assertTrue(oldLoad.isCancelled)
            assertEquals(ReviewHeatmapViewModel.State.Loaded(updated), viewModel.state.value)
        }

    @Test
    fun `a cancelled older load failure cannot hide a newer result`() =
        runTest {
            var pending: Continuation<ReviewHeatmapData>? = null
            var loads = 0
            val viewModel =
                ReviewHeatmapViewModel {
                    if (++loads == 1) {
                        suspendCoroutine { pending = it }
                    } else {
                        updated
                    }
                }

            val oldLoad = viewModel.refresh()
            runCurrent()
            val oldResult = assertNotNull(pending)
            viewModel.refresh().join()
            oldResult.resumeWithException(IOException("Old collection read failed"))
            oldLoad.join()

            assertTrue(oldLoad.isCancelled)
            assertEquals(ReviewHeatmapViewModel.State.Loaded(updated), viewModel.state.value)
        }

    @Test
    fun `failed load can be retried`() =
        runTest {
            var loads = 0
            val retry = CompletableDeferred<ReviewHeatmapData>()
            val viewModel =
                ReviewHeatmapViewModel {
                    if (++loads == 1) throw IOException("Collection temporarily unavailable")
                    retry.await()
                }

            viewModel.refresh().join()
            assertEquals(ReviewHeatmapViewModel.State.Error, viewModel.state.value)

            val retryJob = viewModel.refresh()
            runCurrent()
            assertEquals(ReviewHeatmapViewModel.State.Loading, viewModel.state.value)
            retry.complete(updated)
            retryJob.join()

            assertEquals(ReviewHeatmapViewModel.State.Loaded(updated), viewModel.state.value)
        }

    @Test
    fun `refresh keeps the previous heatmap visible until new history is ready`() =
        runTest {
            var loads = 0
            val next = CompletableDeferred<ReviewHeatmapData>()
            val viewModel = ReviewHeatmapViewModel { if (++loads == 1) initial else next.await() }

            viewModel.refresh().join()
            val refresh = viewModel.refresh()
            runCurrent()
            assertEquals(ReviewHeatmapViewModel.State.Loaded(initial), viewModel.state.value)

            next.complete(updated)
            refresh.join()
            assertEquals(ReviewHeatmapViewModel.State.Loaded(updated), viewModel.state.value)
        }
}
