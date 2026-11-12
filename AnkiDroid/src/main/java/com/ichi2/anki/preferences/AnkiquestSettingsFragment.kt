// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestAccount
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings
import com.ichi2.anki.ankiquest.AnkiquestAvatarEditor
import com.ichi2.anki.ankiquest.AnkiquestAvatars
import com.ichi2.anki.ankiquest.AnkiquestCommunity
import com.ichi2.anki.ankiquest.AnkiquestCommunity.Reminders
import com.ichi2.anki.ankiquest.AnkiquestCompanion
import com.ichi2.anki.ankiquest.AnkiquestDeckAdapter
import com.ichi2.anki.ankiquest.AnkiquestDeckSharing
import com.ichi2.anki.ankiquest.AnkiquestDeckTree
import com.ichi2.anki.ankiquest.AnkiquestDialogOwner
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestIcon
import com.ichi2.anki.ankiquest.AnkiquestNotificationHealth
import com.ichi2.anki.ankiquest.AnkiquestNotifier
import com.ichi2.anki.ankiquest.AnkiquestPoll
import com.ichi2.anki.ankiquest.AnkiquestSharing
import com.ichi2.anki.ankiquest.AnkiquestSubscriptions
import com.ichi2.anki.ankiquest.AnkiquestUndo
import com.ichi2.anki.ankiquest.AnkiquestUpdater
import com.ichi2.anki.ankiquest.AnkiquestWidget
import com.ichi2.anki.ankiquest.AnkiquestWidgetSettings
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.HomeAccountChanged
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.servicelayer.DebugInfoService
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.utils.Permissions.openAppSettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Date
import java.util.TimeZone

/**
 * AnkiQuest settings on one short screen: the account, what to be notified about, what friends hear
 * about, and the app itself. A new account needs none of them. Settings the server keeps are read
 * for the signed-in account each time the screen shows, and change only once the server confirms;
 * the rest are this phone's own.
 *
 * The other controls live where they are used: the daily goal on Today, streak protection, muting
 * and deck alerts on a profile, and telling friends about one deck in that deck's menu.
 */
class AnkiquestSettingsFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest
    override val analyticsScreenNameConstant = "prefs.ankiquest"

    private var running = false
    private var deckUndo: AnkiquestUndo? = null

    /** Every server write from this screen, one at a time, so a write never overwrites another's answer. */
    private val serverWrites = Mutex()
    private val refreshServerSettings = mutableListOf<() -> Unit>()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) AnkiquestPoll.refreshNow(requireContext())
            if (isAdded) showHealth()
        }

    // Deliberately memory-only: a restored picker result is discarded after process recreation.
    private var pictureAccount: AnkiquestAvatars.Account? = null
    private var pictureEditor: AlertDialog? = null
    private val profilePicture =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val account = pictureAccount
            pictureAccount = null
            if (uri == null || account == null) return@registerForActivityResult
            pictureAction {
                AnkiquestAvatars.requireCurrent(account)
                val bitmap = AnkiquestAvatars.prepare(requireContext(), uri)
                AnkiquestAvatars.requireCurrent(account)
                if (!isAdded || view == null) return@pictureAction
                pictureEditor?.dismiss()
                pictureEditor =
                    AnkiquestAvatarEditor.show(requireContext(), bitmap) { cropped ->
                        pictureAction {
                            AnkiquestAvatars.save(account, cropped)
                            AnkiquestPoll.refreshNow(requireContext())
                        }
                    }
            }
        }

    override fun initSubscreen() {
        refreshServerSettings.clear()
        val deckSettings = sharedRead { Ankiquest.notificationSwitches() }
        bindAccount()
        bindNotifications(deckSettings)
        bindSharing(deckSettings)
        bindApp()
        showAccount()
    }

    /** The signed-in account that server settings belong to, or null on a phone that is not signed in. */
    private fun signedIn(): HomeAccount? = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }

    private fun requireSignedIn(): HomeAccount = signedIn() ?: throw HomeAccountChanged()

    /**
     * Without an account, the screen offers signing in and the app's own settings; the rest only
     * appear once there is an account for them to belong to.
     */
    private fun showAccount() {
        val account = signedIn()
        requirePreference<Preference>(R.string.ankiquest_sign_in_key).apply {
            if (account == null) {
                setTitle(R.string.ankiquest_sign_in_title)
                setSummary(R.string.ankiquest_sign_in_summary)
            } else {
                title = getString(R.string.ankiquest_signed_in_as, account.user)
                summary = account.server.toUri().host ?: account.server
            }
            isSelectable = account == null
        }
        for (key in listOf(R.string.ankiquest_avatar_key, R.string.ankiquest_companion_key, R.string.ankiquest_sign_out_key)) {
            requirePreference<Preference>(key).isVisible = account != null
        }
        requirePreference<PreferenceCategory>(R.string.ankiquest_notify_group_key).isVisible = account != null
        requirePreference<PreferenceCategory>(R.string.ankiquest_sharing_group_key).isVisible = account != null
    }

    private fun bindAccount() {
        requirePreference<Preference>(R.string.ankiquest_sign_in_key).setOnPreferenceClickListener {
            if (signedIn() == null) showSignIn()
            true
        }
        bindCompanion()
        requirePreference<Preference>(R.string.ankiquest_avatar_key).setOnPreferenceClickListener {
            pictureAction {
                val account = checkNotNull(AnkiquestAvatars.account()) { getString(R.string.ankiquest_nudges_unavailable) }
                AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.ankiquest_avatar_title)
                    .setItems(
                        arrayOf(getString(R.string.ankiquest_avatar_choose), getString(R.string.ankiquest_avatar_remove)),
                    ) { _, option ->
                        if (option == 0) {
                            pictureAction {
                                AnkiquestAvatars.requireCurrent(account)
                                pictureAccount = account
                                try {
                                    profilePicture.launch("image/*")
                                } catch (e: Exception) {
                                    pictureAccount = null
                                    throw e
                                }
                            }
                        } else {
                            AlertDialog
                                .Builder(requireContext())
                                .setMessage(R.string.ankiquest_avatar_remove_confirm)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.ankiquest_avatar_remove) { _, _ ->
                                    pictureAction {
                                        AnkiquestAvatars.remove(account)
                                        AnkiquestPoll.refreshNow(requireContext())
                                    }
                                }.show()
                        }
                    }.show()
            }
            true
        }
        requirePreference<Preference>(R.string.ankiquest_sign_out_key).setOnPreferenceClickListener {
            val account = signedIn() ?: return@setOnPreferenceClickListener true
            AlertDialog
                .Builder(requireContext())
                .setMessage(R.string.ankiquest_sign_out_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ankiquest_device_sign_out) { _, _ ->
                    lifecycleScope.launch {
                        AnkiquestAccountSettings.signOutHere(account)
                        if (isAdded) showSnackbar(R.string.ankiquest_signed_out)
                    }
                }.show()
            true
        }
    }

    /** Asks for a username and password, and fills in the server, player and token from the answer. */
    private fun showSignIn() {
        val view = layoutInflater.inflate(R.layout.dialog_ankiquest_sign_in, null)
        val server = view.findViewById<EditText>(R.id.ankiquest_sign_in_server)
        val user = view.findViewById<EditText>(R.id.ankiquest_sign_in_user)
        val password = view.findViewById<EditText>(R.id.ankiquest_sign_in_password)
        val create = view.findViewById<CheckBox>(R.id.ankiquest_sign_in_create)
        val display = view.findViewById<EditText>(R.id.ankiquest_sign_in_display)
        val hint = view.findViewById<TextView>(R.id.ankiquest_sign_in_hint)
        val status = view.findViewById<TextView>(R.id.ankiquest_sign_in_status)
        val prefs = AnkiDroidApp.sharedPrefs()
        server.setText(prefs.getString(Ankiquest.PREF_URL, null)?.takeIf { it.isNotBlank() } ?: AnkiquestAccount.DEFAULT_SERVER)
        user.setText(AnkiquestAccountSettings.signInUsername(server.text.toString()))
        var usernameEdited = false
        var updatingSuggestion = false
        user.doAfterTextChanged { if (!updatingSuggestion) usernameEdited = true }
        server.doAfterTextChanged {
            if (!usernameEdited) {
                updatingSuggestion = true
                user.setText(AnkiquestAccountSettings.signInUsername(it.toString()))
                updatingSuggestion = false
            }
        }
        val dialog =
            AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.ankiquest_sign_in_title)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ankiquest_sign_in_submit, null)
                .show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        create.setOnCheckedChangeListener { _, creating ->
            display.visibility = if (creating) View.VISIBLE else View.GONE
            hint.visibility = if (creating) View.VISIBLE else View.GONE
            submit.setText(if (creating) R.string.ankiquest_sign_in_submit_create else R.string.ankiquest_sign_in_submit)
        }
        submit.setOnClickListener {
            val credentials =
                AnkiquestAccount.Credentials(
                    url =
                        server.text
                            .toString()
                            .trim()
                            .trimEnd('/'),
                    user =
                        user.text
                            .toString()
                            .trim()
                            .lowercase(),
                    password = password.text.toString(),
                    display = display.text.toString().trim(),
                    create = create.isChecked,
                )
            if (credentials.url.isEmpty() || credentials.user.isEmpty() || credentials.password.isEmpty()) {
                status.setText(R.string.ankiquest_sign_in_missing)
                return@setOnClickListener
            }
            submit.isEnabled = false
            status.setText(R.string.ankiquest_sign_in_connecting)
            lifecycleScope.launch {
                try {
                    val signed = AnkiquestAccount.submit(credentials)
                    if (!isAdded) return@launch
                    prefs.edit {
                        putString(Ankiquest.PREF_URL, signed.url)
                        putString(Ankiquest.PREF_USER, signed.user)
                        putString(Ankiquest.PREF_TOKEN, signed.token)
                    }
                    dialog.dismiss()
                    showSnackbar(getString(R.string.ankiquest_sign_in_done, signed.user))
                    AnkiquestPoll.refreshNow(requireContext())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isAdded) return@launch
                    submit.isEnabled = true
                    status.text = AnkiquestAccount.message(requireContext(), e, credentials.url)
                }
            }
        }
    }

    /**
     * Three switches and the reminder timing. Each switch stands for a few underlying settings, on
     * the server and on this phone, and shows on while any of them is on.
     */
    private fun bindNotifications(switches: suspend () -> JSONObject) {
        bindServerToggle(
            R.string.ankiquest_notify_friends_key,
            read = { AnkiquestSubscriptions.repository.load(requireSignedIn()).enabled },
            save = { AnkiquestSubscriptions.repository.setEnabled(requireSignedIn(), it).enabled },
            notify = true,
        )
        val reminders = sharedRead { AnkiquestCommunity.reminders(requireSignedIn()) }
        bindServerToggle(
            R.string.ankiquest_notify_reminders_key,
            read = { reminders().enabled.isNotEmpty() || AnkiquestNotifier.streakReminder() },
            save = { on ->
                val saved = saveReminders { it.copy(enabled = if (on) AnkiquestCommunity.Reminder.entries.toSet() else emptySet()) }
                AnkiquestNotifier.setStreakReminder(on)
                saved.enabled.isNotEmpty() || AnkiquestNotifier.streakReminder()
            },
            notify = true,
        )
        bindReminderTiming(reminders)
        bindServerToggle(
            R.string.ankiquest_notify_moments_key,
            read = {
                val read = switches()
                read.optBoolean(Ankiquest.NUDGES) || Ankiquest.celebrations(read) || AnkiquestNotifier.rankAlerts()
            },
            save = { on ->
                val saved =
                    serverWrites.withLock {
                        Ankiquest.setNotificationSwitch(Ankiquest.NUDGES, on)
                        Ankiquest.setNotificationSwitch(Ankiquest.CELEBRATIONS, on)
                    }
                AnkiquestNotifier.setRankAlerts(on)
                saved.optBoolean(Ankiquest.NUDGES) || Ankiquest.celebrations(saved) || AnkiquestNotifier.rankAlerts()
            },
            notify = true,
        )
        requirePreference<Preference>(R.string.ankiquest_health_key).setOnPreferenceClickListener {
            showNotificationHealth()
            true
        }
    }

    /** Saves the reminders [change] makes of the server's current ones, read again first so nothing else is overwritten. */
    private suspend fun saveReminders(change: (Reminders) -> Reminders): Reminders =
        serverWrites.withLock {
            val account = requireSignedIn()
            AnkiquestCommunity.saveReminders(account, change(AnkiquestCommunity.reminders(account)))
        }

    /** When the daily reminder comes, and the quiet hours that hold back every reminder. */
    private fun bindReminderTiming(read: suspend () -> Reminders) {
        val time = requirePreference<ListPreference>(R.string.ankiquest_reminder_time_key)
        val quiet = requirePreference<Preference>(R.string.ankiquest_quiet_hours_key)
        time.entries = Reminders.HOURS.map(::hourLabel).toTypedArray()
        time.entryValues = Reminders.HOURS.map { it.toString() }.toTypedArray()
        time.dialogTitle = time.title
        var loaded: Reminders? = null
        var generation = 0

        fun show(reminders: Reminders?) {
            loaded = reminders
            time.isEnabled = reminders != null
            quiet.isEnabled = reminders != null
            if (reminders == null) {
                time.setSummary(R.string.ankiquest_check_running)
                quiet.setSummary(R.string.ankiquest_check_running)
                return
            }
            time.value = reminders.hour.toString()
            time.summary = time.entry
            quiet.summary =
                if (reminders.quiet) {
                    getString(R.string.ankiquest_quiet_hours_range, hourLabel(reminders.quietStart), hourLabel(reminders.quietEnd))
                } else {
                    getString(R.string.ankiquest_quiet_hours_none)
                }
        }

        fun run(action: suspend () -> Reminders) {
            val request = ++generation
            val account = signedIn()
            show(null)
            lifecycleScope.launch {
                try {
                    val reminders = action()
                    if (request != generation || signedIn()?.scope != account?.scope || !isAdded) return@launch
                    show(reminders)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request != generation || !isAdded) return@launch
                    val message = getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                    time.summary = message
                    quiet.summary = message
                }
            }
        }
        refreshServerSettings.add { run(read) }
        time.setOnPreferenceChangeListener { _, value ->
            val hour = (value as? String)?.toIntOrNull()
            if (hour != null && loaded != null) run { saveReminders { it.copy(hour = hour) } }
            false
        }
        quiet.setOnPreferenceClickListener {
            val current = loaded ?: return@setOnPreferenceClickListener true
            chooseQuietHours(current) { start, end -> run { saveReminders { it.copy(quietStart = start, quietEnd = end) } } }
            true
        }
    }

    /** Asks for the hours reminders stay silent; the same hour for both ends turns quiet hours off. */
    private fun chooseQuietHours(
        current: Reminders,
        save: (start: Int, end: Int) -> Unit,
    ) {
        val context = requireContext()
        val hours = Reminders.HOURS.map(::hourLabel)

        fun picker(
            label: Int,
            hour: Int,
        ): Pair<TextView, Spinner> =
            TextView(context).apply { setText(label) } to
                Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, hours)
                    setSelection(hour)
                }
        val (fromLabel, from) = picker(R.string.ankiquest_quiet_hours_from, current.quietStart)
        val (untilLabel, until) = picker(R.string.ankiquest_quiet_hours_until, current.quietEnd)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                listOf(fromLabel, from, untilLabel, until).forEach(::addView)
            }
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankiquest_quiet_hours_title)
            .setView(content)
            .setPositiveButton(R.string.ankiquest_deck_notifications_save) { _, _ ->
                save(from.selectedItemPosition, until.selectedItemPosition)
            }.setNeutralButton(R.string.ankiquest_quiet_hours_turn_off) { _, _ ->
                save(current.quietStart, current.quietStart)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** An hour of the day as this device writes times, such as "20:00" or "8:00 PM". */
    private fun hourLabel(hour: Int): String =
        DateFormat
            .getTimeFormat(requireContext())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(hour * 3_600_000L))

    private fun bindSharing(switches: suspend () -> JSONObject) {
        bindServerToggle(
            R.string.ankiquest_share_moments_key,
            read = { AnkiquestSharing.settings(requireSignedIn()).shared },
            save = { on ->
                serverWrites.withLock {
                    val account = requireSignedIn()
                    AnkiquestSharing.saveSettings(account, AnkiquestSharing.settings(account).switched(on), dailyCap = null).shared
                }
            },
        )
        // Older servers omit the global listing field.
        val global = AnkiquestWidgetSettings.GLOBAL_SETTING
        bindServerToggle(
            R.string.ankiquest_show_global_key,
            read = { switches().opt(global) as? Boolean },
            save = { Ankiquest.setNotificationSwitch(global, it).getBoolean(global) },
        )
        bindAction(R.string.ankiquest_moments_decks_key) { account ->
            if (account == null || account.token.isEmpty()) {
                return@bindAction null
            }
            deckUndo?.clear()
            val owner = AnkiquestDialogOwner(requireActivity(), account, viewLifecycleOwner.lifecycle)
            try {
                if (!owner.isCurrent()) {
                    owner.close()
                    return@bindAction null
                }
                val settings = Ankiquest.deckNotificationSettings(account)
                if (!owner.isCurrent()) {
                    owner.close()
                    return@bindAction null
                }
                showDeckNotifications(account, owner, settings)
                null
            } catch (e: CancellationException) {
                owner.close()
                throw e
            } catch (e: Exception) {
                if (owner.isCurrent()) {
                    val failed =
                        AlertDialog
                            .Builder(requireContext())
                            .setTitle(requirePreference<Preference>(R.string.ankiquest_moments_decks_key).title)
                            .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                    owner.show(failed)
                } else {
                    owner.close()
                }
                null
            }
        }
    }

    private fun bindApp() {
        val moodIcon = requirePreference<SwitchPreferenceCompat>(R.string.ankiquest_mood_icon_key)
        moodIcon.isVisible = AnkiquestIcon.available(requireContext())
        moodIcon.setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true) {
                // Queue the last known mood before the poll refreshes it.
                AnkiquestIcon.showLastKnown(requireContext(), TimeManager.time.intTimeMS())
                AnkiquestPoll.refreshNow(requireContext())
            } else {
                AnkiquestIcon.show(requireContext(), AnkiquestIcon.Mood.DEFAULT)
            }
            true
        }
        requirePreference<Preference>(R.string.ankiquest_check_updates_key).summary =
            getString(
                R.string.ankiquest_check_updates_summary,
                AnkiquestUpdater.installed() ?: getString(R.string.ankiquest_local_build),
            )
        bindAction(R.string.ankiquest_check_updates_key) { AnkiquestUpdater.checkNow(requireActivity()) }
        requirePreference<Preference>(R.string.ankiquest_debug_info_key).setOnPreferenceClickListener {
            launchCatchingTask { DebugInfoService.copyReport(requireContext()) }
            true
        }
        requirePreference<Preference>(R.string.ankiquest_update_channel_key).setOnPreferenceChangeListener { _, _ ->
            AnkiquestUpdater.checkSoon()
            true
        }
        requirePreference<Preference>(R.string.aq_privacy_key).setOnPreferenceClickListener {
            openPrivacyNotice()
            true
        }
    }

    /**
     * The server's privacy notice opens in the browser: it is the operator's own legal text, filled
     * in with their details and only published as a web page, so the app shows exactly that page.
     */
    private fun openPrivacyNotice() {
        val url = Ankiquest.privacyUrl()
        if (url == null) {
            AlertDialog
                .Builder(requireContext())
                .setMessage(Ankiquest.unconfigured(requireContext()))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        (requireActivity() as AnkiActivity).openUrl(url.toUri())
    }

    private fun pictureAction(action: suspend () -> Unit) {
        val preference = requirePreference<Preference>(R.string.ankiquest_avatar_key)
        if (!preference.isEnabled) return
        preference.isEnabled = false
        lifecycleScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    AlertDialog
                        .Builder(requireContext())
                        .setTitle(R.string.ankiquest_avatar_title)
                        .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                preference.isEnabled = true
            }
        }
    }

    private fun bindCompanion() {
        val preference = requirePreference<ListPreference>(R.string.ankiquest_companion_key)
        var generation = 0
        var saving = false
        var refreshPending = false
        preference.isPersistent = false
        preference.isEnabled = false
        val refresh = refresh@{
            val request = ++generation
            preference.isEnabled = false
            val account = signedIn() ?: return@refresh
            preference.summary = getString(R.string.ankiquest_check_running)
            if (saving) {
                refreshPending = true
                return@refresh
            }
            lifecycleScope.launch {
                try {
                    val choice = AnkiquestHomeData.companion(account)
                    if (request != generation || AnkiquestHomeData.account()?.scope != account.scope || !isAdded) return@launch
                    AnkiquestCompanion.remember(account, choice)
                    preference.value = choice
                    preference.summary = preference.entry
                    preference.isEnabled = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request == generation && isAdded) {
                        preference.summary = getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        refreshServerSettings.add { refresh() }
        preference.setOnPreferenceChangeListener { _, value ->
            val account = AnkiquestHomeData.account() ?: return@setOnPreferenceChangeListener false
            val request = ++generation
            saving = true
            preference.isEnabled = false
            lifecycleScope.launch {
                try {
                    val choice = AnkiquestHomeData.saveCompanion(account, value.toString())
                    if (request != generation || AnkiquestHomeData.account()?.scope != account.scope || !isAdded) return@launch
                    AnkiquestCompanion.remember(account, choice)
                    preference.value = choice
                    preference.summary = preference.entry
                    preference.isEnabled = true
                    AnkiquestWidget.requestUpdate(requireContext())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request == generation && isAdded) {
                        AlertDialog
                            .Builder(requireContext())
                            .setTitle(preference.title)
                            .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } finally {
                    saving = false
                    if (refreshPending && isAdded) {
                        refreshPending = false
                        refresh()
                    } else if (request == generation && isAdded && !preference.isEnabled) {
                        refresh()
                    }
                }
            }
            false
        }
    }

    private fun openAlertSettings(nudge: Boolean) {
        try {
            startActivity(AnkiquestNotifier.alertSettingsIntent(requireContext(), nudge))
        } catch (_: ActivityNotFoundException) {
            openAppSettingsScreen()
        }
    }

    /** The warning at the top of the screen, there only while a signed-in player's alerts cannot reach them. */
    private fun showHealth() {
        requirePreference<Preference>(R.string.ankiquest_health_key).isVisible =
            signedIn() != null && !AnkiquestNotifier.deliverable(requireContext())
    }

    /** What stops alerts, and the way to each Android setting that can fix it. */
    @VisibleForTesting
    internal fun showNotificationHealth() {
        val context = requireContext()
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val state = { enabled: Boolean -> getString(if (enabled) R.string.ankiquest_health_allowed else R.string.ankiquest_health_blocked) }
        val lastSync =
            AnkiquestNotificationHealth.lastSuccessfulSync()?.let { at ->
                val date = Date(at)
                "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
            } ?: getString(R.string.ankiquest_health_never)
        val details =
            listOf(
                getString(R.string.ankiquest_health_permission, state(permissionGranted)),
                getString(R.string.ankiquest_health_messages, state(AnkiquestNotifier.alertsEnabled(context, false))),
                getString(R.string.ankiquest_health_nudges, state(AnkiquestNotifier.alertsEnabled(context, true))),
                getString(R.string.ankiquest_health_last_sync, lastSync),
            ).joinToString("\n")
        AlertDialog
            .Builder(context)
            .setTitle(R.string.ankiquest_health_title)
            .setMessage(details)
            .setPositiveButton(CommonString.settings) { _, _ ->
                AlertDialog
                    .Builder(context)
                    .setTitle(CommonString.settings)
                    .setItems(
                        arrayOf(
                            getString(R.string.ankiquest_health_permission_settings),
                            getString(R.string.ankiquest_message_alerts_title),
                            getString(R.string.ankiquest_nudge_alerts_title),
                        ),
                    ) { _, choice ->
                        when (choice) {
                            0 ->
                                if (!permissionGranted) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openAppSettingsScreen()
                                }
                            1 -> openAlertSettings(nudge = false)
                            2 -> openAlertSettings(nudge = true)
                        }
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lets settings refreshed together share one in-flight [read] instead of each asking the server. */
    private fun <T> sharedRead(read: suspend () -> T): suspend () -> T {
        var running: Pair<HomeAccount?, Deferred<T>>? = null
        return {
            val account = signedIn()
            val shared =
                running?.takeIf { it.first == account && it.second.isActive }?.second
                    ?: lifecycleScope.async { read() }.also { running = account to it }
            shared.await()
        }
    }

    /** Server switches are refreshed for the current account and change only after the server confirms. */
    private fun bindServerToggle(
        key: Int,
        read: suspend () -> Boolean?,
        save: suspend (Boolean) -> Boolean,
        notify: Boolean = false,
    ) {
        val preference = requirePreference<SwitchPreferenceCompat>(key)
        val summary = preference.summary
        var generation = 0
        var saving = false
        var refreshPending = false
        preference.isPersistent = false
        preference.isEnabled = false
        val refresh = refresh@{
            val request = ++generation
            preference.isEnabled = false
            preference.summary = getString(R.string.ankiquest_check_running)
            // Returning to settings during a write must read the state after that write completes.
            if (saving) {
                refreshPending = true
                return@refresh
            }
            val account = HomeAccount.current()
            lifecycleScope.launch {
                try {
                    val enabled = read()
                    if (request != generation || account != HomeAccount.current() || !isAdded) return@launch
                    preference.isVisible = enabled != null
                    preference.isChecked = enabled == true
                    preference.isEnabled = enabled != null
                    preference.summary = summary
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request != generation || account != HomeAccount.current() || !isAdded) return@launch
                    preference.summary = getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
                }
            }
        }
        refreshServerSettings.add { refresh() }
        preference.setOnPreferenceChangeListener { _, value ->
            val wanted = value == true
            val request = ++generation
            val account = HomeAccount.current()
            preference.isEnabled = false
            saving = true
            lifecycleScope.launch {
                try {
                    if (account != HomeAccount.current()) return@launch
                    val enabled = save(wanted)
                    if (request != generation || account != HomeAccount.current() || !isAdded) return@launch
                    preference.isChecked = enabled
                    preference.isEnabled = true
                    if (wanted && enabled && notify) {
                        askForNotifications()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (request != generation || account != HomeAccount.current() || !isAdded) return@launch
                    AlertDialog
                        .Builder(requireContext())
                        .setTitle(preference.title)
                        .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    refresh()
                } finally {
                    saving = false
                    if (refreshPending && isAdded) {
                        refreshPending = false
                        refresh()
                    }
                }
            }
            false
        }
    }

    /** Shows the account, then reads every server setting again for it; without an account there are none to read. */
    private fun refreshAll() {
        showAccount()
        showHealth()
        if (signedIn() != null) refreshServerSettings.forEach { it() }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key in setOf(Ankiquest.PREF_URL, Ankiquest.PREF_USER, Ankiquest.PREF_TOKEN)) {
            pictureAccount = null
            pictureEditor?.dismiss()
            pictureEditor = null
            refreshAll()
        }
    }

    private fun bindAction(
        key: Int,
        action: suspend (HomeAccount?) -> String?,
    ) {
        val preference = requirePreference<Preference>(key)
        val idleSummary = preference.summary
        preference.setOnPreferenceClickListener {
            if (running) return@setOnPreferenceClickListener true
            running = true
            preference.summary = getString(R.string.ankiquest_check_running)
            val context = requireContext()
            val account = AnkiquestHomeData.account()
            lifecycleScope.launch {
                val message =
                    try {
                        action(account)
                    } finally {
                        running = false
                        preference.summary = idleSummary
                    }
                if (message == null) return@launch
                AlertDialog
                    .Builder(context)
                    .setTitle(preference.title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            true
        }
    }

    private fun showDeckNotifications(
        account: HomeAccount,
        owner: AnkiquestDialogOwner,
        settings: JSONObject,
    ) {
        val context = requireContext()
        val rows = settings.getJSONArray("decks")
        if (rows.length() == 0) {
            val empty =
                AlertDialog
                    .Builder(context)
                    .setMessage(R.string.ankiquest_deck_notifications_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            owner.show(empty)
            return
        }
        val tree = AnkiquestDeckTree((0 until rows.length()).map { rows.getJSONObject(it) })
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        val adapter =
            AnkiquestDeckAdapter(
                tree,
                BooleanArray(tree.size) { tree.rows[it].deck.getBoolean("enabled") },
            ) {}
        list.adapter = adapter
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_deck_notifications_title)
                .setView(list)
                .setPositiveButton(R.string.ankiquest_deck_notifications_next, null)
                .setNeutralButton(R.string.ankiquest_deck_notifications_select_all, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                adapter.setAll(adapter.anyUnchecked())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!owner.take(dialog)) {
                    return@setOnClickListener
                }
                val shared = tree.rows.filterIndexed { index, _ -> adapter.checked[index] }.map { it.deck }
                val unshared = tree.rows.filterIndexed { index, _ -> !adapter.checked[index] }.map { it.deck }
                chooseRecipients(account, owner, settings, shared, unshared)
            }
        }
        owner.show(dialog)
    }

    /** Asks once who should hear about every newly shared deck. */
    private fun chooseRecipients(
        account: HomeAccount,
        owner: AnkiquestDialogOwner,
        settings: JSONObject,
        shared: List<JSONObject>,
        unshared: List<JSONObject>,
    ) {
        val context = requireContext()
        val ids = { decks: List<JSONObject> -> decks.map { it.getString("id") } }
        val previous = AnkiquestDeckSharing.snapshot(shared + unshared)
        if (shared.isEmpty()) {
            saveDeckSharing(account, owner, emptyList(), ids(unshared), emptyList(), previous)
            return
        }
        val rows = settings.getJSONArray("recipients")
        val people = (0 until rows.length()).map { rows.getJSONObject(it) }
        if (people.isEmpty()) {
            val empty =
                AlertDialog
                    .Builder(context)
                    .setMessage(R.string.ankiquest_deck_notifications_no_people)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            owner.show(empty)
            return
        }
        val chosen =
            shared
                .flatMap { deck ->
                    deck.getJSONArray("recipients").let { list -> (0 until list.length()).map { list.getString(it) } }
                }.toSet()
        val checked = people.map { it.getString("user") in chosen }.toBooleanArray()
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_deck_notifications_people_title)
                .setMultiChoiceItems(
                    people.map { "${it.getString("display")} (${it.getString("user")})" }.toTypedArray(),
                    checked,
                ) { _, index, value -> checked[index] = value }
                .setPositiveButton(R.string.ankiquest_deck_notifications_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!owner.isCurrent()) {
                    owner.close()
                    return@setOnClickListener
                }
                val recipients = people.filterIndexed { index, _ -> checked[index] }.map { it.getString("user") }
                if (recipients.isEmpty()) {
                    val validation =
                        AlertDialog
                            .Builder(context)
                            .setMessage(R.string.ankiquest_deck_notifications_choose_people)
                            .setPositiveButton(android.R.string.ok, null)
                            .create()
                    owner.explain(validation)
                    return@setOnClickListener
                }
                if (!owner.take(dialog)) {
                    return@setOnClickListener
                }
                saveDeckSharing(account, owner, ids(shared), ids(unshared), recipients, previous)
            }
        }
        owner.show(dialog)
    }

    private fun saveDeckSharing(
        account: HomeAccount,
        owner: AnkiquestDialogOwner,
        shared: List<String>,
        unshared: List<String>,
        recipients: List<String>,
        previous: List<AnkiquestDeckSharing.Setting>,
    ) {
        val activity = requireActivity() as AnkiActivity
        val undo = deckUndo ?: AnkiquestUndo(activity).also { deckUndo = it }
        val message = resources.getQuantityString(R.plurals.ankiquest_deck_notifications_saved, shared.size, shared.size)
        undo.apply(
            account,
            message,
            perform = { done ->
                deckWrite(owner, done) { Ankiquest.saveDeckNotificationSettings(shared, unshared, recipients, account) }
            },
            inverse = {
                val restoreOwner = AnkiquestDialogOwner(activity, account, viewLifecycleOwner.lifecycle)
                deckWrite(restoreOwner, { view?.showSnackbar(getString(R.string.ankiquest_deck_sharing_restored)) }) {
                    AnkiquestDeckSharing.restore(account, previous)
                }
            },
        )
    }

    /** Keep success, failure and Undo with the same foreground visit as the picker. */
    private fun deckWrite(
        owner: AnkiquestDialogOwner,
        saved: () -> Unit,
        write: suspend () -> Unit,
    ) {
        val context = requireContext()
        lifecycleScope.launch {
            try {
                write()
                if (owner.isCurrent()) {
                    saved()
                }
                owner.close()
            } catch (e: CancellationException) {
                owner.close()
                throw e
            } catch (e: Exception) {
                if (!owner.isCurrent()) {
                    owner.close()
                    return@launch
                }
                val failed =
                    AlertDialog
                        .Builder(context)
                        .setMessage(getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                owner.show(failed)
            }
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        // Deliver pending messages promptly after returning from Android alert settings.
        AnkiquestPoll.refreshNow(requireContext())
    }

    override fun onDestroyView() {
        deckUndo?.clear()
        deckUndo = null
        pictureEditor?.dismiss()
        pictureEditor = null
        super.onDestroyView()
    }

    override fun onStop() {
        deckUndo?.clear()
        super.onStop()
    }
}
