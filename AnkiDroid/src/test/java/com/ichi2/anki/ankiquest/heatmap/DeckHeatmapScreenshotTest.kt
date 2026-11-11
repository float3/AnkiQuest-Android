// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest.heatmap

import com.ichi2.anki.ScreenshotTest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import java.time.LocalDate

/** Deck-list captures use the same study date as the recorded heatmap baseline. */
abstract class DeckHeatmapScreenshotTest : ScreenshotTest() {
    @get:Rule
    val fixedHeatmap: TestRule =
        object : ExternalResource() {
            override fun before() {
                // The native scheduler reads the host clock independently of MockTime.
                val data = ReviewHeatmap.summarize(LocalDate.parse("2026-11-09"), emptyMap(), emptyMap())
                mockkObject(ReviewHeatmap)
                every { ReviewHeatmap.load(any()) } returns data
            }

            override fun after() = unmockkObject(ReviewHeatmap)
        }
}
