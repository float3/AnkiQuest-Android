// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.IdRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R

/**
 * The bottom navigation of the four AnkiQuest tabs: Today, Friends, Progress and Decks.
 *
 * Every tab sets its bar up here so the bars look and behave the same: the bar reaches the bottom
 * edge of the screen, behind the gesture handle or navigation buttons, and pads its items above them.
 * Switching tabs replaces the open tab instead of stacking another one on it, so Back from any tab
 * returns to the deck list.
 */
object AnkiquestTabBar {
    /**
     * Adds the tab bar to the bottom of the vertical [root] of a tab other than Decks, with [selected] chosen.
     * [root] keeps clear of the status bar, cutouts and side navigation; the bar handles the bottom.
     */
    fun attach(
        activity: Activity,
        root: LinearLayout,
        @IdRes selected: Int,
    ): BottomNavigationView {
        val bar = LayoutInflater.from(activity).inflate(R.layout.ankiquest_tab_bar, root, false) as BottomNavigationView
        root.addView(bar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = 0)
            insets
        }
        setup(activity, bar, selected)
        return bar
    }

    /** Turns [bar] into the tab bar of [activity] with [selected] chosen; the deck list calls this for its own bar. */
    fun setup(
        activity: Activity,
        bar: BottomNavigationView,
        @IdRes selected: Int,
    ) {
        if (bar.menu.findItem(R.id.ankiquest_nav_today) == null) {
            bar.menu.clear()
            bar.inflateMenu(R.menu.ankiquest_navigation)
        }
        bar.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        bar.selectedItemId = selected
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        bar.setOnItemSelectedListener { item ->
            if (item.itemId != selected) open(activity, item.itemId)
            item.itemId == selected
        }
    }

    /** Opens tab [item] in place of the tab [activity] shows; the deck list stays underneath every other tab. */
    private fun open(
        activity: Activity,
        @IdRes item: Int,
    ) {
        val target = AnkiquestNavigation.destination(activity, item) ?: AnkiquestNavigation.decks(activity)
        activity.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        if (activity !is DeckPicker) activity.finish()
    }
}
