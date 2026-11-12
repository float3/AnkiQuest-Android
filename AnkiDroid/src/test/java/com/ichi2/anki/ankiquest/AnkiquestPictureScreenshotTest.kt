// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertTrue

/** The exported PNG must remain readable in both themes and at twice the usual font scale. */
class AnkiquestPictureScreenshotTest : ScreenshotTest() {
    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun conquest() = capture("conquest", conquestCard())

    @Test
    fun conquestLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        capture("conquest_large_font", conquestCard())
    }

    @Test
    fun milestone() =
        capture(
            "milestone",
            AnkiquestShare.Card("30 days of study", "I kept learning, one day at a time.", R.drawable.aki_streak_milestone),
        )

    @Test
    fun goal() = capture("goal", AnkiquestShare.Card("Daily goal reached", "I reviewed 40 cards today.", R.drawable.aki_goal))

    @Test
    fun weeklyPodium() =
        capture("weekly_podium", AnkiquestShare.Card("My week in AnkiQuest", "I finished second this week!", R.drawable.aki_place_2))

    private fun conquestCard() =
        AnkiquestShare.Card(
            "Card conquered!",
            "I learned a card I had forgotten 4 times.",
            R.drawable.aki_deck_conquered,
            listOf("不客氣 · You're welcome", "Mandarin modern 1", "11 answers · 10 days"),
        )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        card: AnkiquestShare.Card,
    ) {
        val bitmap = AnkiquestShare.picture(targetContext, card)
        assertTrue(bitmap.width > 0 && bitmap.height >= bitmap.width)
        val directory = "build/outputs/roborazzi/${javaClass.simpleName}"
        val options = provideRoborazziContext().options
        try {
            bitmap.captureRoboImage(
                filePath = "$directory/$fileNamePrefix$name.png",
                roborazziOptions = options.copy(compareOptions = options.compareOptions.copy(outputDirectoryPath = "$directory/diffs")),
            )
        } finally {
            bitmap.recycle()
        }
    }
}
