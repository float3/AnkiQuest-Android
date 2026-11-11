// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.AbstractFlashcardViewer
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BottomNavController.NavigationItem
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.CommonString
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.android.input.Shortcut
import com.ichi2.anki.android.input.shortcut
import com.ichi2.anki.ankiquest.heatmap.ReviewHeatmapFooter
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.widgets.DeckAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/** AnkiQuest behaviour of [DeckPicker], kept here so the upstream activity only carries one-line calls. */
object AnkiquestDeckPickerHooks {
    /** The deck list's adapter: [deckAdapter]'s rows, then the review heatmap. */
    fun deckListAdapter(
        activity: DeckPicker,
        deckAdapter: DeckAdapter,
    ): RecyclerView.Adapter<*> = ReviewHeatmapFooter.attach(activity, deckAdapter)

    /** Replaces the AnkiQuest menu entry with Browse when the bottom navigation already leads to AnkiQuest. */
    fun decorateMenu(
        activity: DeckPicker,
        menu: Menu,
    ) {
        if (AnkiquestNavigation.enabled() && !activity.fragmented) {
            menu.findItem(R.id.action_ankiquest)?.apply {
                setTitle(CommonString.browse_cards)
                setIcon(R.drawable.ic_flashcard_black)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
    }

    /** @return true if [item] was the AnkiQuest menu entry. */
    fun onOptionsItemSelected(
        activity: DeckPicker,
        item: MenuItem,
        openCardBrowser: () -> Unit,
    ): Boolean {
        if (item.itemId != R.id.action_ankiquest) return false
        if (AnkiquestNavigation.enabled() && !activity.fragmented) {
            openCardBrowser()
        } else {
            activity.startActivity(AnkiquestHomeActivity.intent(activity))
        }
        return true
    }

    /**
     * Shows the study session summary after a review session, then asks whether to tell friends
     * about a deck finished for the day.
     *
     * @return true if AnkiQuest consumed the result, in which case the caller skips its own handling.
     */
    fun onReviewResult(
        activity: DeckPicker,
        resultCode: Int,
        openReviewer: () -> Unit,
    ): Boolean {
        val ended = resultCode == AbstractFlashcardViewer.RESULT_DEFAULT || resultCode == AbstractFlashcardViewer.RESULT_NO_MORE_CARDS
        if (!AnkiquestNavigation.enabled() || !ended) {
            if (ended) activity.launchCatchingTask { AnkiquestDeckShare.offer(activity) }
            return false
        }
        activity.launchCatchingTask {
            val prefs = activity.sharedPrefs()
            val owner = AnkiquestNavigation.accountFingerprint(prefs.all)
            val summary = AnkiquestStudySession.finish()
            // Keep the result with this host while paused; discard it after an account change.
            activity.lifecycle.withResumed {
                if (owner != AnkiquestNavigation.accountFingerprint(prefs.all)) {
                    return@withResumed
                }
                val session =
                    AnkiquestMomentCard.Session(
                        summary = summary?.takeIf { prefs.getBoolean(AnkiquestNavigation.PREF_SESSION_SUMMARY, true) },
                        onContinue = openReviewer,
                        onClose = { activity.launchCatchingTask { AnkiquestDeckShare.offer(activity) } },
                    )
                if (!AnkiquestMoments.present(activity, prefs, session = session)) {
                    activity.launchCatchingTask { AnkiquestDeckShare.offer(activity) }
                }
                activity.fragment?.refreshInterface()
            }
        }
        return true
    }

    /**
     * Shows today's goal in one tappable line above the deck list, which opens Today, or hides it
     * when no account is set, the goal is off or the collection cannot be read.
     */
    fun refreshGoal(activity: DeckPicker) {
        AnkiquestMoments.showBadge(activity.binding.bottomNavigation, AnkiDroidApp.sharedPrefs())
        val parent = activity.binding.deckPickerPane.deckpickerView
        if (AnkiquestHomeData.account() == null) {
            showGoal(parent, null)
            return
        }
        activity.lifecycleScope.launch {
            val loaded =
                try {
                    withCol {
                        AnkiquestDailyGoal.status(AnkiquestDailyGoal.load(this), AnkiDroidApp.sharedPrefs()) to
                            AnkiquestGoalPlan.secondsPerCard(this)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AnkiQuest goal could not be loaded for the deck list")
                    null
                }
            showGoal(parent, loaded?.first, loaded?.second ?: AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD) {
                activity.startActivity(AnkiquestHomeActivity.intent(activity))
            }
        }
    }

    /** Puts the one-line goal for [status] at the top of [parent], or hides it without a goal; returns the line shown. */
    internal fun showGoal(
        parent: LinearLayout,
        status: AnkiquestDailyGoal.Status?,
        secondsPerCard: Double = AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD,
        open: () -> Unit = {},
    ): AnkiquestCompactGoal? {
        val existing = parent.children.filterIsInstance<AnkiquestCompactGoal>().firstOrNull()
        val display = status?.let { AnkiquestGoalBar.display(parent.context, it, secondsPerCard) }
        if (status == null || display == null) {
            existing?.isVisible = false
            return null
        }
        val line =
            existing ?: AnkiquestCompactGoal(parent.context).also {
                val margin = (12 * parent.resources.displayMetrics.density).toInt()
                parent.addView(
                    it,
                    0,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(margin, margin / 2, margin, margin / 3)
                    },
                )
            }
        line.isVisible = true
        line.show(display, status, open)
        return line
    }

    /** Recreates [activity] when AnkiQuest navigation was switched on or off while it was in the background. */
    fun recreateIfNavigationChanged(
        activity: DeckPicker,
        navigationMode: Boolean?,
    ): Boolean {
        if (navigationMode?.let { it != AnkiquestNavigation.enabled() } != true) return false
        activity.intent.putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
        activity.recreate()
        return true
    }

    /**
     * Turns the bottom navigation into the AnkiQuest shell, with the deck library as its fourth destination.
     * Card browsing and all existing library tools remain native.
     *
     * @return true if AnkiQuest set up the bottom navigation, in which case the caller skips its own setup.
     */
    fun setupBottomNavigation(
        deckPicker: DeckPicker,
        bottomNav: BottomNavigationView,
        fragmentContainer: View,
        contentWrapper: View,
    ): Boolean {
        if (!AnkiquestNavigation.enabled()) return false
        fragmentContainer.isVisible = false
        deckPicker.supportFragmentManager.commit {
            deckPicker.supportFragmentManager.fragments.forEach { fragment ->
                if (fragment.id == R.id.bottom_nav_fragment_container) hide(fragment)
            }
        }
        contentWrapper.isVisible = true
        AnkiquestTabBar.setup(deckPicker, bottomNav, R.id.ankiquest_nav_decks)
        return true
    }

    /** @return true if AnkiQuest restored the Decks tab, in which case the caller skips its own restore. */
    fun showRestoredBottomNavTab(deckPicker: DeckPicker): Boolean {
        if (!AnkiquestNavigation.enabled() || deckPicker.fragmented) return false
        deckPicker.binding.bottomNavigation?.selectedItemId = R.id.ankiquest_nav_decks
        return true
    }

    /** Maps Alt+1..4 to the AnkiQuest bottom navigation items. */
    fun dispatchNavigationKey(
        activity: DeckPicker,
        event: KeyEvent,
    ): Boolean {
        if (!AnkiquestNavigation.enabled() ||
            (!activity.bottomNavigationEnabled && activity.fragmented) ||
            event.action != KeyEvent.ACTION_DOWN ||
            !event.isAltPressed
        ) {
            return false
        }
        val bottomNavigation = activity.binding.bottomNavigation ?: return false
        val target =
            when (event.keyCode) {
                KeyEvent.KEYCODE_1 -> R.id.ankiquest_nav_today
                KeyEvent.KEYCODE_2 -> R.id.ankiquest_nav_friends
                KeyEvent.KEYCODE_3 -> R.id.ankiquest_nav_progress
                KeyEvent.KEYCODE_4 -> R.id.ankiquest_nav_decks
                else -> return false
            }
        bottomNavigation.selectedItemId = target
        return true
    }

    /** The AnkiQuest label for a bottom navigation shortcut, or null when upstream's shortcut applies. */
    fun navigationShortcut(
        activity: DeckPicker,
        keys: String,
        destination: NavigationItem,
    ): Shortcut? {
        if (!AnkiquestNavigation.enabled() || (!activity.bottomNavigationEnabled && activity.fragmented)) return null
        val label =
            when (destination) {
                NavigationItem.HOME -> R.string.ankiquest_nav_today
                NavigationItem.BROWSER -> R.string.ankiquest_nav_friends
                NavigationItem.STATS -> R.string.ankiquest_nav_progress
                NavigationItem.MORE -> R.string.ankiquest_nav_decks
            }
        return activity.shortcut(keys, label)
    }

    /**
     * Opens the AnkiQuest destination requested by the launch intent.
     *
     * The local destination opens before any optional network work,
     * and an explicit Study action does not start a competing sync.
     *
     * @return true if AnkiQuest handled startup, in which case the caller skips its own sync.
     */
    suspend fun onFinishedStartup(
        activity: DeckPicker,
        openReviewer: () -> Unit,
        backgroundSync: suspend () -> Unit,
    ): Boolean {
        val studyRequested = activity.intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK)
        if (!handleEntry(activity, openReviewer)) return false
        if (!studyRequested) backgroundSync()
        return true
    }

    /** Handles a Study action delivered to an already running [activity]. */
    fun onNewIntent(
        activity: DeckPicker,
        navigationMode: Boolean?,
        openReviewer: () -> Unit,
    ) {
        if (activity.intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK) &&
            navigationMode == AnkiquestNavigation.enabled()
        ) {
            activity.launchCatchingTask { handleEntry(activity, openReviewer) }
        }
    }

    /** Consume a home action once, after collection setup, without depending on deck-list rendering. */
    private suspend fun handleEntry(
        activity: DeckPicker,
        openReviewer: () -> Unit,
    ): Boolean {
        val intent = activity.intent
        if (intent.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK)) {
            val deckId = intent.getLongExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, 0)
            intent.removeExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK)
            intent.putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
            val available =
                withCol {
                    if (decks.get(deckId) == null) {
                        null
                    } else {
                        decks.select(deckId)
                        sched.deckDueTree().find(deckId)?.hasCardsReadyToStudy() == true
                    }
                }
            when (available) {
                true -> openReviewer()
                false -> activity.showSnackbar(R.string.ankiquest_deck_ready_later)
                null -> activity.showSnackbar(R.string.ankiquest_missing_deck)
            }
            return true
        }
        if (!activity.fragmented && AnkiquestNavigation.opensToday(intent)) {
            intent.putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
            activity.startActivity(AnkiquestHomeActivity.intent(activity))
            return true
        }
        return false
    }
}
