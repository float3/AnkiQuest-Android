// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R

/** Account-scoped artwork cache. The server owns the choice; offline UI uses its last answer. */
internal object AnkiquestCompanion {
    private const val KEY_PREFIX = "ankiquestCompanion:"
    private val choices = setOf("aki", "ankilope", "none")

    fun normalize(value: String?) = value?.takeIf { it in choices } ?: "aki"

    fun selected(): String {
        val prefs = AnkiDroidApp.sharedPrefsOrNull() ?: return "aki"
        val settings = prefs.all
        val account = HomeAccount.from(settings, (settings["username"] as? String).orEmpty()) ?: return "aki"
        return normalize(prefs.getString(KEY_PREFIX + account.scope, "aki"))
    }

    fun remember(
        account: HomeAccount,
        value: String,
    ): Boolean {
        if (AnkiquestHomeData.account()?.scope != account.scope) return false
        AnkiDroidApp.sharedPrefs().edit { putString(KEY_PREFIX + account.scope, normalize(value)) }
        return true
    }

    fun visible() = selected() != "none"

    @DrawableRes
    fun resource(
        @DrawableRes aki: Int,
    ): Int =
        if (selected() != "ankilope") {
            aki
        } else {
            when (aki) {
                R.drawable.aki_face -> R.drawable.ankilope_face
                R.drawable.aki_welcome -> R.drawable.ankilope_welcome
                R.drawable.aki_challenge -> R.drawable.ankilope_welcome
                R.drawable.aki_celebrate, R.drawable.aki_winner, R.drawable.aki_achievement, R.drawable.aki_challenge_won,
                R.drawable.aki_place_1, R.drawable.aki_place_2, R.drawable.aki_place_3,
                -> R.drawable.ankilope_celebrate
                R.drawable.aki_review, R.drawable.aki_streak, R.drawable.aki_freeze, R.drawable.aki_streak_milestone,
                -> R.drawable.ankilope_study
                else -> aki
            }
        }

    fun show(
        view: ImageView,
        @DrawableRes aki: Int,
    ) {
        view.isVisible = visible()
        if (view.isVisible) view.setImageResource(resource(aki))
    }
}
