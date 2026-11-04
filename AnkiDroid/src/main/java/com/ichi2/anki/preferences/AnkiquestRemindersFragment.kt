// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestCommunity
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminders
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestHttpException
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.HomeAccountChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date
import java.util.TimeZone

/**
 * The player's community reminders, natively, as the website's `/community#reminders` sets them:
 * which of the server's reminders to send, when, quiet hours and a daily limit. Every change is saved
 * at once and shown only once the server has confirmed it.
 */
class AnkiquestRemindersFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_reminders
    override val analyticsScreenNameConstant = "prefs.ankiquest.reminders"

    private var settings: Reminders? = null
    private var account: HomeAccount? = null
    private var job: Job? = null
    private var generation = 0
    private var busy = false
    private var message: String? = null
    private var canRetry = false

    override fun initSubscreen() {
        val kinds = requirePreference<PreferenceCategory>(KINDS_KEY)
        for (reminder in AnkiquestCommunity.Reminder.entries) {
            kinds.addPreference(
                SwitchPreferenceCompat(requireContext()).apply {
                    key = reminderKey(reminder)
                    title = getString(reminder.title)
                    summary = getString(reminder.summary)
                    isPersistent = false
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, value ->
                        change { current ->
                            current.copy(enabled = if (value == true) current.enabled + reminder else current.enabled - reminder)
                        }
                        false
                    }
                },
            )
        }
        val timing = requirePreference<PreferenceCategory>(TIMING_KEY)
        val hours = Reminders.HOURS.map(::hourLabel).toTypedArray()
        val hourValues = Reminders.HOURS.map { it.toString() }.toTypedArray()
        timing.addPreference(choice(HOUR_KEY, R.string.aq_reminders_hour, hours, hourValues) { current, hour -> current.copy(hour = hour) })
        timing.addPreference(
            choice(
                QUIET_START_KEY,
                R.string.aq_reminders_quiet_start,
                hours,
                hourValues,
            ) { current, hour -> current.copy(quietStart = hour) },
        )
        timing.addPreference(
            choice(QUIET_END_KEY, R.string.aq_reminders_quiet_end, hours, hourValues) { current, hour -> current.copy(quietEnd = hour) },
        )
        timing.addPreference(
            choice(
                LIMIT_KEY,
                R.string.aq_reminders_limit,
                Reminders.LIMITS.map { resources.getQuantityString(R.plurals.aq_reminders_limit_value, it, it) }.toTypedArray(),
                Reminders.LIMITS.map { it.toString() }.toTypedArray(),
            ) { current, limit -> current.copy(dailyLimit = limit) },
        )
        requirePreference<Preference>(RETRY_KEY).setOnPreferenceClickListener {
            refresh()
            true
        }
        render()
    }

    private fun choice(
        key: String,
        title: Int,
        entries: Array<String>,
        values: Array<String>,
        update: (Reminders, Int) -> Reminders,
    ): ListPreference =
        ListPreference(requireContext()).apply {
            this.key = key
            this.title = getString(title)
            dialogTitle = getString(title)
            this.entries = entries
            entryValues = values
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, value ->
                (value as? String)?.toIntOrNull()?.let { picked -> change { update(it, picked) } }
                false
            }
        }

    /** An hour of the day as this device writes times, such as "20:00" or "8:00 PM". */
    private fun hourLabel(hour: Int): String =
        DateFormat
            .getTimeFormat(requireContext())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(hour * 3_600_000L))

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onStop() {
        generation++
        job?.cancel()
        settings = null
        account = null
        super.onStop()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key in setOf(Ankiquest.PREF_URL, Ankiquest.PREF_USER, Ankiquest.PREF_TOKEN, "username")) refresh()
    }

    private fun refresh() {
        settings = null
        request(saving = false) { AnkiquestCommunity.reminders(it) }
    }

    /** Saves the settings [update] makes of the confirmed ones; nothing changes on screen until the server answers. */
    private fun change(update: (Reminders) -> Reminders) {
        val current = settings ?: return
        if (busy) return
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            refresh()
            return
        }
        val wanted = update(current)
        if (wanted == current) return
        request(saving = true) { AnkiquestCommunity.saveReminders(it, wanted) }
    }

    private fun request(
        saving: Boolean,
        action: suspend (HomeAccount) -> Reminders,
    ) {
        val request = ++generation
        job?.cancel()
        val captured = AnkiquestHomeData.account()
        account = captured
        canRetry = false
        if (captured == null || captured.token.isEmpty()) {
            settings = null
            busy = false
            message = getString(R.string.aq_reminders_configure)
            render()
            return
        }
        busy = true
        message = getString(if (saving) R.string.aq_reminders_saving else R.string.aq_reminders_loading)
        render()
        job =
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = action(captured)
                    if (!isCurrent(request, captured)) return@launch
                    settings = result
                    message = if (saving) getString(R.string.aq_reminders_saved) else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isCurrent(request, captured)) return@launch
                    // A failed save may have reached the server: reload before another change.
                    settings = null
                    canRetry = e !is HomeAccountChanged
                    message =
                        getString(
                            when {
                                e is AnkiquestHttpException && e.code in setOf(401, 403) -> R.string.aq_reminders_auth
                                e is AnkiquestHttpException && e.code == 404 -> R.string.aq_reminders_unsupported
                                e is HomeAccountChanged -> R.string.aq_reminders_configure
                                else -> R.string.aq_reminders_error
                            },
                        )
                } finally {
                    if (isCurrent(request, captured)) {
                        busy = false
                        render()
                    }
                }
            }
    }

    private fun isCurrent(
        request: Int,
        captured: HomeAccount,
    ): Boolean =
        request == generation && view != null && account?.scope == captured.scope && AnkiquestHomeData.account()?.scope == captured.scope

    private fun render() {
        val current = settings
        val editable = current != null && !busy
        requirePreference<Preference>(STATUS_KEY).apply {
            summary = message ?: getString(R.string.aq_reminders_hint)
        }
        requirePreference<Preference>(RETRY_KEY).isVisible = canRetry && !busy
        requirePreference<PreferenceCategory>(KINDS_KEY).isVisible = current != null
        requirePreference<PreferenceCategory>(TIMING_KEY).isVisible = current != null
        for (reminder in AnkiquestCommunity.Reminder.entries) {
            requirePreference<SwitchPreferenceCompat>(reminderKey(reminder)).apply {
                isChecked = current != null && reminder in current.enabled
                isEnabled = editable
            }
        }

        fun show(
            key: String,
            value: Int?,
        ) = requirePreference<ListPreference>(key).apply {
            this.value = value?.toString()
            summary = entry
            isEnabled = editable
        }
        show(HOUR_KEY, current?.hour)
        show(QUIET_START_KEY, current?.quietStart)
        show(QUIET_END_KEY, current?.quietEnd)
        show(LIMIT_KEY, current?.dailyLimit)
        if (current != null && !current.quiet) {
            requirePreference<ListPreference>(QUIET_END_KEY).summary = getString(R.string.aq_reminders_quiet_off)
        }
    }

    internal companion object {
        const val STATUS_KEY = "ankiquestRemindersStatus"
        const val RETRY_KEY = "ankiquestRemindersRetry"
        const val KINDS_KEY = "ankiquestRemindersKinds"
        const val TIMING_KEY = "ankiquestRemindersTiming"
        const val HOUR_KEY = "ankiquestReminderHour"
        const val QUIET_START_KEY = "ankiquestReminderQuietStart"
        const val QUIET_END_KEY = "ankiquestReminderQuietEnd"
        const val LIMIT_KEY = "ankiquestReminderLimit"

        fun reminderKey(reminder: AnkiquestCommunity.Reminder): String = "ankiquestReminder:${reminder.key}"
    }
}
