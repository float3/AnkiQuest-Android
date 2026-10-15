// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.content.Intent
import android.view.View
import androidx.core.content.edit
import androidx.core.view.children
import androidx.recyclerview.widget.ConcatAdapter
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooper
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooperUntil
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.deckpicker.DeckPickerViewModel
import com.ichi2.anki.deckpicker.DeckPickerViewModel.StartupResponse
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.widgets.DeckAdapter
import com.ichi2.testutils.BackupManagerTestUtilities

// TODO: move to testFixtures once RobolectricTest is moved

context(test: RobolectricTest)
fun withDeckPicker(
    deckCount: Int,
    withCards: Boolean = false,
    block: (DeckPicker) -> Unit,
) {
    // startup code occurs here so all users of this method are correctly setup
    test.ensureCollectionLoadIsSynchronous()
    test.setIntroductionSlidesShown(true)
    BackupManagerTestUtilities.setupSpaceForBackup(test.targetContext)
    // suppress the periodic 'backup your collection' prompt so the screenshot is just the deck list
    test.targetContext.sharedPrefs().edit { putBoolean("backupPromptDisabled", true) }
    if (withCards) test.ensureNonEmptyCollection()
    for (i in 0 until deckCount) {
        // 'Deck' is before 'Default' alphabetically
        test.addDeck("Test Deck $i")
    }
    val deckPicker =
        test.startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent()).also {
            advanceRobolectricLooper() // may be a fix for flaky tests
        }
    block(deckPicker)
}

/**
 * Completes the initial real RecyclerView layout before a test captures a row's identity.
 * A subdeck-list refresh and heatmap-footer update can request another layout during Robolectric's
 * initial traversal. Driving that traversal at the existing bounds makes setup deterministic.
 * Never use this after capturing a row whose identity or pressed drawable the test must preserve.
 */
fun DeckPicker.awaitInitialDeckHolder(deckId: DeckId): DeckAdapter.ViewHolder = awaitDeckHolder(deckId, layoutInitialRow = true)

/** Waits for a requested deck's real row without scrolling, measuring, laying out or rebinding it. */
fun DeckPicker.awaitDeckHolder(deckId: DeckId): DeckAdapter.ViewHolder = awaitDeckHolder(deckId, layoutInitialRow = false)

private fun DeckPicker.awaitDeckHolder(
    deckId: DeckId,
    layoutInitialRow: Boolean,
): DeckAdapter.ViewHolder {
    val decks = deckPickerBinding.decks
    val adapter = (decks.adapter as ConcatAdapter).adapters.filterIsInstance<DeckAdapter>().single()
    var holder: DeckAdapter.ViewHolder? = null
    // Draining the main looper once can finish before the background list diff posts its layout.
    advanceRobolectricLooperUntil(lazyMessage = {
        "Deck $deckId was not laid out; initial=$layoutInitialRow, " +
            "deckIds=${adapter.currentList.map { it.did }}, " +
            "pending=${decks.hasPendingAdapterUpdates()}, " +
            "bounds=${decks.left},${decks.top}-${decks.right},${decks.bottom}, " +
            "shown=${decks.isShown}, laidOut=${decks.isLaidOut}, " +
            "requested=${decks.isLayoutRequested}, suppressed=${decks.isLayoutSuppressed}, " +
            "computing=${decks.isComputingLayout}, " +
            "children=${decks.children.map { decks.getChildViewHolder(it) }.toList()}"
    }) {
        val position = adapter.currentList.indexOfFirst { it.did == deckId }
        if (layoutInitialRow && position >= 0 && decks.width > 0 && decks.height > 0 && !decks.isComputingLayout) {
            decks.forceLayout()
            decks.measure(
                View.MeasureSpec.makeMeasureSpec(decks.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(decks.height, View.MeasureSpec.EXACTLY),
            )
            decks.layout(decks.left, decks.top, decks.right, decks.bottom)
        }
        holder =
            if (position >= 0 && !decks.hasPendingAdapterUpdates()) {
                (decks.findViewHolderForAdapterPosition(position) as? DeckAdapter.ViewHolder)?.takeIf {
                    it.bindingAdapter === adapter &&
                        adapter.currentList.getOrNull(it.bindingAdapterPosition)?.did == deckId
                }
            } else {
                null
            }
        holder != null
    }
    return checkNotNull(holder)
}

fun DeckPickerViewModel.awaitStartup() =
    advanceRobolectricLooperUntil {
        startupJob?.isActive != true && flowOfStartupResponse.value !is StartupResponse.Success
    }
