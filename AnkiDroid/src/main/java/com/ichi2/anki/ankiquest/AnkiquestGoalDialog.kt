// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Choice
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.Mode
import com.ichi2.anki.ankiquest.AnkiquestDailyGoal.ServerGoal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Chooses the daily goal, showing what each choice means for this collection: the cards and time it
 * asks for today and when the backlog would be gone. Saving stores the goal on the server.
 */
internal object AnkiquestGoalDialog {
    private const val PREF_SETUP_SHOWN = "ankiquestGoalSetupShown"
    private val offering = AtomicBoolean(false)

    /** Opens the dialog for [account], starting from [initial]; [onSaved] gets the goal the server keeps. */
    fun show(
        activity: FragmentActivity,
        account: HomeAccount,
        initial: Choice,
        @StringRes title: Int = R.string.ankiquest_goal_title,
        onSaved: (ServerGoal) -> Unit = {},
    ): AlertDialog {
        val view = activity.layoutInflater.inflate(R.layout.dialog_ankiquest_goal, null)
        val modes = view.findViewById<RadioGroup>(R.id.ankiquest_goal_mode)
        val presets = view.findViewById<ChipGroup>(R.id.ankiquest_goal_presets)
        val custom = view.findViewById<Chip>(R.id.ankiquest_goal_preset_custom)
        val field = view.findViewById<EditText>(R.id.ankiquest_goal_value)
        val estimate = view.findViewById<TextView>(R.id.ankiquest_goal_estimate)
        val hint = view.findViewById<TextView>(R.id.ankiquest_goal_hint)
        val status = view.findViewById<TextView>(R.id.ankiquest_goal_status)
        val presetChips =
            AnkiquestGoalPlan.PRESET_DAYS.zip(
                listOf(
                    R.id.ankiquest_goal_preset_7,
                    R.id.ankiquest_goal_preset_14,
                    R.id.ankiquest_goal_preset_30,
                    R.id.ankiquest_goal_preset_60,
                ),
            )
        presetChips.forEach { (days, id) ->
            view.findViewById<Chip>(id).text = activity.resources.getQuantityString(R.plurals.ankiquest_goal_days, days, days)
        }
        val modeButtons =
            mapOf(
                Mode.BACKLOG to R.id.ankiquest_goal_mode_backlog,
                Mode.CARDS to R.id.ankiquest_goal_mode_cards,
                Mode.MINUTES to R.id.ankiquest_goal_mode_minutes,
                Mode.OFF to R.id.ankiquest_goal_mode_off,
            )
        val values =
            Mode.entries
                .associateWith { if (it == initial.mode) initial.value else Choice.defaultValue(it) }
                .toMutableMap()
        var mode = initial.mode
        var forecast: AnkiquestGoalPlan.Forecast? = null
        var forecastFailed = false
        var saving = false
        var filling = false

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(CommonString.save, null)
                .show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        fun customDays(): Boolean = mode == Mode.BACKLOG && presets.checkedChipId == custom.id

        fun current(): Choice? {
            val value =
                when {
                    mode == Mode.OFF -> initial.value
                    mode == Mode.BACKLOG && !customDays() ->
                        presetChips.firstOrNull { it.second == presets.checkedChipId }?.first
                    else -> field.text.toString().toIntOrNull()
                } ?: return null
            return Choice(mode, value).takeIf { it.valid }
        }

        fun update() {
            val choice = current()
            val known = forecast
            estimate.text =
                when {
                    choice == null ->
                        Choice.range(mode)?.let { activity.getString(R.string.ankiquest_goal_invalid, it.first, it.last) }
                    known == null ->
                        activity.getString(
                            if (forecastFailed) R.string.ankiquest_goal_estimate_failed else R.string.ankiquest_goal_estimating,
                        )
                    else ->
                        AnkiquestGoalPlan
                            .project(choice, known)
                            ?.let { estimates(activity, it, known) }
                            ?: activity.getString(R.string.ankiquest_goal_estimate_off)
                }
            hint.isVisible = known != null
            if (known != null) {
                hint.text =
                    if (known.backlog > 0) {
                        val cards = AnkiquestGoalPlan.shrinkingCards(known.dailyDue)
                        activity.resources.getQuantityString(R.plurals.ankiquest_goal_shrink, cards, cards)
                    } else {
                        val cards = AnkiquestGoalPlan.keepingUpCards(known.dailyDue)
                        activity.resources.getQuantityString(R.plurals.ankiquest_goal_nothing_overdue, cards, cards)
                    }
            }
            save.isEnabled = choice != null && !saving
        }

        fun showMode() {
            filling = true
            presets.isVisible = mode == Mode.BACKLOG
            if (mode == Mode.BACKLOG) {
                val days = values.getValue(Mode.BACKLOG)
                presets.check(presetChips.firstOrNull { it.first == days }?.second ?: custom.id)
            }
            field.isVisible = mode == Mode.CARDS || mode == Mode.MINUTES || customDays()
            Choice.range(mode)?.let { field.hint = activity.getString(R.string.ankiquest_goal_value_hint, it.first, it.last) }
            field.setText(values.getValue(mode).toString())
            filling = false
            update()
        }

        modes.check(modeButtons.getValue(mode))
        modes.setOnCheckedChangeListener { _, id ->
            mode = modeButtons.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedChangeListener
            showMode()
        }
        presets.setOnCheckedStateChangeListener { _, _ ->
            if (filling) return@setOnCheckedStateChangeListener
            presetChips.firstOrNull { it.second == presets.checkedChipId }?.let { values[Mode.BACKLOG] = it.first }
            field.isVisible = customDays()
            if (customDays()) field.setText(values.getValue(Mode.BACKLOG).toString())
            update()
        }
        field.doAfterTextChanged { text ->
            if (filling) return@doAfterTextChanged
            text?.toString()?.toIntOrNull()?.let { values[mode] = it }
            update()
        }
        showMode()

        activity.lifecycleScope.launch {
            try {
                forecast = CollectionManager.withCol { AnkiquestGoalPlan.forecast(this) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest goal estimates could not be worked out")
                forecastFailed = true
            }
            if (dialog.isShowing) update()
        }

        save.setOnClickListener {
            val choice = current() ?: return@setOnClickListener
            saving = true
            update()
            status.setText(R.string.ankiquest_goal_saving)
            activity.lifecycleScope.launch {
                try {
                    val saved = AnkiquestDailyGoal.save(account, choice, restart = choice.mode == Mode.BACKLOG)
                    dialog.dismiss()
                    onSaved(saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status.text =
                        (e as? AnkiquestSharing.Refusal)?.text
                            ?: activity.getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                } finally {
                    saving = false
                    if (dialog.isShowing) update()
                }
            }
        }
        return dialog
    }

    /** The estimate lines for [projection]: cards and time today, and when the backlog is gone. */
    private fun estimates(
        context: Context,
        projection: AnkiquestGoalPlan.Projection,
        forecast: AnkiquestGoalPlan.Forecast,
    ): String {
        val resources = context.resources
        val minutes = (projection.seconds / 60.0).roundToInt()
        val time =
            if (minutes == 0) {
                context.getString(R.string.ankiquest_summary_under_minute)
            } else {
                resources.getQuantityString(R.plurals.ankiquest_summary_minutes, minutes, minutes)
            }
        val lines =
            mutableListOf(
                context.getString(R.string.ankiquest_goal_estimate_cards, projection.cards),
                context.getString(R.string.ankiquest_goal_estimate_time, time),
            )
        when (val days = projection.clearedInDays) {
            0 -> Unit
            null -> {
                val due = AnkiquestGoalPlan.keepingUpCards(forecast.dailyDue)
                lines += resources.getQuantityString(R.plurals.ankiquest_goal_estimate_never, due, due)
            }
            else -> lines += resources.getQuantityString(R.plurals.ankiquest_goal_estimate_cleared, days, days)
        }
        return lines.joinToString("\n")
    }

    /**
     * Opens the dialog once per player while the server says no goal was ever chosen: after signing in,
     * or on the next start for players who were already signed in. Closing it without saving still
     * counts, so it never asks again. [onSaved] gets the goal the server keeps once one is saved.
     */
    fun offerSetup(
        activity: FragmentActivity,
        onSaved: (ServerGoal) -> Unit = {},
    ) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val account = AnkiquestHomeData.account(prefs)?.takeIf { it.token.isNotEmpty() } ?: return
        val key = scopedKey(PREF_SETUP_SHOWN, sha256(account.server, account.user))
        if (prefs.getBoolean(key, false) || !offering.compareAndSet(false, true)) return
        activity.lifecycleScope.launch {
            try {
                val goal = AnkiquestDailyGoal.fetch(account, prefs)
                if (goal.chosen != false) {
                    prefs.edit { putBoolean(key, true) }
                    return@launch
                }
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (AnkiquestHomeData.account(prefs)?.scope != account.scope) return@launch
                prefs.edit { putBoolean(key, true) }
                show(activity, account, goal.choice, R.string.ankiquest_goal_setup_title, onSaved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest goal setup could not ask the server")
            } finally {
                offering.set(false)
            }
        }
    }
}
