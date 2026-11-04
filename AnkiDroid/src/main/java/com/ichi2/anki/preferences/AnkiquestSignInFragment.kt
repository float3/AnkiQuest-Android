// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Device
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Methods
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Purpose
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Summary
import com.ichi2.anki.ankiquest.AnkiquestCapabilities
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestHttpException
import com.ichi2.anki.ankiquest.HomeAccount
import com.ichi2.anki.ankiquest.HomeAccountChanged
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.snackbar.showSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Date

/**
 * The player's password, linked Google/GitHub sign-ins, signed-in devices, data download and account
 * deletion, all read from and saved to the server. A provider is linked, or signed in with again, in
 * the browser, since the provider's own page has to be shown; everything else stays in the app.
 */
class AnkiquestSignInFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_sign_in
    override val analyticsScreenNameConstant = "prefs.ankiquest.signin"

    private var methods: Methods? = null
    private var devices: List<Device>? = null
    private var status: String? = null
    private var generation = 0
    private var busy = false

    /** What to carry on with once the player has signed in again in the browser. Memory only. */
    @VisibleForTesting
    internal var afterReauth: (() -> Unit)? = null

    private var exporting: HomeAccount? = null
    private val exportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val account = exporting
            exporting = null
            if (uri != null && account != null) saveExport(account, uri)
        }

    override fun initSubscreen() {
        requirePreference<Preference>(R.string.ankiquest_account_status_key).setOnPreferenceClickListener {
            refresh()
            true
        }
        requirePreference<Preference>(R.string.ankiquest_password_key).setOnPreferenceClickListener {
            methods?.let(::showPassword)
            true
        }
        requirePreference<Preference>(R.string.ankiquest_sign_out_others_key).setOnPreferenceClickListener {
            confirm(getString(R.string.ankiquest_sign_out_others_confirm), getString(R.string.ankiquest_device_sign_out)) {
                act { account ->
                    devices = AnkiquestAccountSettings.signOutOthers(account)
                    showSnackbar(R.string.ankiquest_signed_out_others)
                }
            }
            true
        }
        requirePreference<Preference>(R.string.ankiquest_export_key).setOnPreferenceClickListener {
            val account = signedIn() ?: return@setOnPreferenceClickListener true
            exporting = account
            try {
                exportFile.launch("ankiquest-${account.user}.json")
            } catch (e: ActivityNotFoundException) {
                exporting = null
                alert(failure(e))
            }
            true
        }
        requirePreference<Preference>(R.string.ankiquest_delete_account_key).setOnPreferenceClickListener {
            startDeletion()
            true
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        val then = afterReauth ?: return
        afterReauth = null
        val account = current() ?: return
        lifecycleScope.launch {
            val confirmed =
                try {
                    val until = AnkiquestAccountSettings.summary(account).confirmedUntil
                    until != null && until > TimeManager.time.intTimeMS()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isAdded) alert(failure(e))
                    return@launch
                }
            if (!isAdded || current()?.scope != account.scope) return@launch
            if (confirmed) then() else alert(getString(R.string.ankiquest_reauth_pending))
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?,
    ) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key in setOf(Ankiquest.PREF_URL, Ankiquest.PREF_USER, Ankiquest.PREF_TOKEN)) {
            afterReauth = null
            refresh()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean =
        // Device ids and provider rows are not settings; keep them out of analytics.
        if (preference.key?.startsWith(DYNAMIC_PREFIX) == true) false else super.onPreferenceTreeClick(preference)

    private fun current(): HomeAccount? = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }

    /** The signed-in account, or null after saying that there is none. */
    private fun signedIn(): HomeAccount? = current() ?: null.also { alert(getString(R.string.ankiquest_account_configure)) }

    private fun refresh() {
        val request = ++generation
        val account = current()
        methods = null
        devices = null
        if (account == null) {
            status = getString(R.string.ankiquest_account_configure)
            render()
            return
        }
        status = getString(R.string.ankiquest_check_running)
        render()
        lifecycleScope.launch {
            try {
                val (loaded, signedIn) =
                    coroutineScope {
                        val methods = async { AnkiquestAccountSettings.methods(account) }
                        val devices = async { AnkiquestAccountSettings.devices(account) }
                        methods.await() to devices.await()
                    }
                if (request != generation || !isAdded) return@launch
                methods = loaded
                devices = signedIn
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request != generation || !isAdded) return@launch
                status = getString(R.string.ankiquest_account_failed, failure(e))
            }
            render()
        }
    }

    /**
     * Runs [action] for the signed-in account, one at a time. When the server wants a fresh provider
     * sign-in first, asks for one and then runs [retry].
     */
    private fun act(
        retry: (() -> Unit)? = null,
        action: suspend (HomeAccount) -> Unit,
    ) {
        if (busy) return
        val account = signedIn() ?: return
        busy = true
        render()
        lifecycleScope.launch {
            try {
                action(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAdded) return@launch
                if (e is AnkiquestHttpException && e.reauth && retry != null) reauthenticate(retry) else alert(failure(e))
            } finally {
                busy = false
                if (isAdded) render()
            }
        }
    }

    private fun render() {
        if (!isAdded) return
        val methods = methods
        val devices = devices
        requirePreference<Preference>(R.string.ankiquest_account_status_key).apply {
            isVisible = status != null
            summary = status
        }
        requirePreference<Preference>(R.string.ankiquest_password_key).apply {
            isVisible = methods?.account == true
            isEnabled = !busy
            summary = getString(if (methods?.password == true) R.string.ankiquest_password_change else R.string.ankiquest_password_set)
        }
        requirePreference<PreferenceCategory>(R.string.ankiquest_providers_key).apply {
            removeAll()
            methods?.providers.orEmpty().forEach { provider ->
                addPreference(
                    Preference(requireContext()).apply {
                        key = "$DYNAMIC_PREFIX${provider.key}"
                        title = provider.name
                        summary =
                            getString(if (provider.linked) R.string.ankiquest_provider_linked else R.string.ankiquest_provider_unlinked)
                        isPersistent = false
                        isEnabled = !busy
                        setOnPreferenceClickListener {
                            if (provider.linked) unlink(provider) else link(provider.key)
                            true
                        }
                    },
                )
            }
            isVisible = preferenceCount > 0
        }
        requirePreference<PreferenceCategory>(R.string.ankiquest_devices_key).apply {
            val others = requirePreference<Preference>(R.string.ankiquest_sign_out_others_key)
            for (index in preferenceCount - 1 downTo 0) {
                val row = getPreference(index)
                if (row !== others) removePreference(row)
            }
            devices.orEmpty().forEachIndexed { index, device ->
                addPreference(
                    Preference(requireContext()).apply {
                        key = "${DYNAMIC_PREFIX}device:${device.id}"
                        order = index
                        title = describe(device)
                        summary = signedInAt(device)
                        isPersistent = false
                        isSelectable = !device.current
                        isEnabled = !busy || device.current
                        setOnPreferenceClickListener {
                            confirm(
                                getString(R.string.ankiquest_device_sign_out_confirm, describe(device)),
                                getString(R.string.ankiquest_device_sign_out),
                            ) {
                                act { account ->
                                    this@AnkiquestSignInFragment.devices =
                                        AnkiquestAccountSettings.signOutDevice(account, device.id)
                                }
                            }
                            true
                        }
                    },
                )
            }
            others.order = devices.orEmpty().size
            others.isEnabled = !busy && devices.orEmpty().any { !it.current }
            isVisible = devices != null
        }
        requirePreference<Preference>(R.string.ankiquest_export_key).isEnabled = !busy
        requirePreference<Preference>(R.string.ankiquest_delete_account_key).apply {
            isVisible = methods?.account != false
            isEnabled = !busy
        }
    }

    private fun describe(device: Device): String {
        val name =
            when {
                device.kind == "web" -> getString(R.string.ankiquest_device_website)
                else -> device.name ?: getString(R.string.ankiquest_device_app)
            }
        return if (device.current) getString(R.string.ankiquest_device_this, name) else name
    }

    private fun signedInAt(device: Device): String {
        val date = { at: Long -> DateFormat.getDateFormat(requireContext()).format(Date(at)) }
        val lastUsed = device.lastUsedAt ?: return getString(R.string.ankiquest_device_signed_in, date(device.createdAt))
        return getString(R.string.ankiquest_device_used, date(device.createdAt), date(lastUsed))
    }

    private fun unlink(provider: AnkiquestAccountSettings.Provider) {
        confirm(getString(R.string.ankiquest_provider_unlink_confirm, provider.name), getString(R.string.ankiquest_provider_unlink)) {
            act { account -> methods = AnkiquestAccountSettings.unlink(account, provider.key) }
        }
    }

    private fun link(provider: String) {
        act(retry = { link(provider) }) { account -> openBrowser(account, provider, Purpose.LINK) }
    }

    /** Has the player sign in with a provider they linked again, then carries on with [then]. */
    private fun reauthenticate(then: () -> Unit) {
        val linked = methods?.providers.orEmpty().filter { it.linked }
        if (linked.isEmpty()) {
            alert(getString(R.string.ankiquest_reauth_none))
            return
        }
        AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.ankiquest_reauth_title)
            .setItems(linked.map { getString(R.string.ankiquest_reauth_with, it.name) }.toTypedArray()) { _, index ->
                act { account ->
                    if (openBrowser(account, linked[index].key, Purpose.REAUTH)) afterReauth = then
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opens the server's sign-in with [provider] in the browser; false when the server cannot do that for an app. */
    private suspend fun openBrowser(
        account: HomeAccount,
        provider: String,
        purpose: Purpose,
    ): Boolean {
        if (AnkiquestAccountSettings.APP_SIGN_IN !in AnkiquestCapabilities.current(account)) {
            alert(getString(R.string.ankiquest_provider_unsupported))
            return false
        }
        val url = AnkiquestAccountSettings.browser(account, provider, purpose).toUri()
        try {
            CustomTabsIntent.Builder().build().launchUrl(requireContext(), url)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        }
        showThemedToast(requireContext(), getString(R.string.ankiquest_browser_opened), shortLength = false)
        return true
    }

    private fun showPassword(methods: Methods) {
        val context = requireContext()
        val current =
            EditText(context).apply {
                setHint(R.string.ankiquest_password_current)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                visibility = if (methods.password) View.VISIBLE else View.GONE
            }
        val password =
            EditText(context).apply {
                setHint(R.string.ankiquest_password_new)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        val others = CheckBox(context).apply { setText(R.string.ankiquest_password_sign_out_others) }
        val message = TextView(context)
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_password_title)
                .setView(column(current, password, others, message))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(CommonString.save, null)
                .show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            val typed = password.text.toString()
            if (typed.length !in PASSWORD_LENGTH) {
                message.setText(R.string.ankiquest_password_length)
                return@setOnClickListener
            }
            val account = signedIn() ?: return@setOnClickListener
            val old = current.text.toString().takeIf { methods.password }
            val signOutOthers = others.isChecked
            val save: suspend (HomeAccount) -> Unit = {
                this.methods = AnkiquestAccountSettings.setPassword(it, old, typed, signOutOthers)
                if (signOutOthers) devices = AnkiquestAccountSettings.devices(it)
                showSnackbar(R.string.ankiquest_password_saved)
            }
            submit.isEnabled = false
            message.setText(R.string.ankiquest_check_running)
            lifecycleScope.launch {
                try {
                    save(account)
                    dialog.dismiss()
                    render()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isAdded) return@launch
                    if (e is AnkiquestHttpException && e.reauth) {
                        dialog.dismiss()
                        reauthenticate { act(action = save) }
                    } else {
                        submit.isEnabled = true
                        message.text = failure(e)
                    }
                }
            }
        }
    }

    private fun startDeletion() {
        act { account ->
            val summary = AnkiquestAccountSettings.summary(account)
            when {
                summary.password -> showDeletion(account, summary)
                (summary.confirmedUntil ?: 0) <= TimeManager.time.intTimeMS() -> reauthenticate(::startDeletion)
                else -> showDeletion(account, summary)
            }
        }
    }

    /** Asks for the password, or for an account without one, for its username, before deleting it. */
    private fun showDeletion(
        account: HomeAccount,
        summary: Summary,
    ) {
        val context = requireContext()
        val typed =
            EditText(context).apply {
                if (summary.password) {
                    setHint(R.string.ankiquest_password_title)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    hint = summary.user
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
            }
        val message = TextView(context)
        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.ankiquest_delete_title)
                .setMessage(
                    if (summary.password) {
                        getString(R.string.ankiquest_delete_password_message)
                    } else {
                        getString(R.string.ankiquest_delete_username_message, summary.user)
                    },
                ).setView(column(typed, message))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ankiquest_delete_button, null)
                .show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            val text = typed.text.toString()
            if (!summary.password && text.trim() != summary.user) {
                message.text = getString(R.string.ankiquest_delete_type_username, summary.user)
                return@setOnClickListener
            }
            if (summary.password && text.isEmpty()) return@setOnClickListener
            submit.isEnabled = false
            message.setText(R.string.ankiquest_check_running)
            lifecycleScope.launch {
                try {
                    AnkiquestAccountSettings.delete(account, text.takeIf { summary.password })
                    dialog.dismiss()
                    if (isAdded) alert(getString(R.string.ankiquest_delete_done))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isAdded) return@launch
                    if (e is AnkiquestHttpException && e.reauth) {
                        dialog.dismiss()
                        reauthenticate(::startDeletion)
                    } else {
                        submit.isEnabled = true
                        message.text = failure(e)
                    }
                }
            }
        }
    }

    /** Writes [account]'s data download to the document the player chose. */
    @VisibleForTesting
    internal fun saveExport(
        account: HomeAccount,
        uri: Uri,
    ) {
        act { _ ->
            val resolver = requireContext().contentResolver
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("The file could not be opened")
                out.use { AnkiquestAccountSettings.export(account, it) }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
            showSnackbar(R.string.ankiquest_export_saved)
        }
    }

    private fun column(vararg views: View): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            views.forEach(::addView)
        }

    private fun confirm(
        message: String,
        action: String,
        then: () -> Unit,
    ) {
        AlertDialog
            .Builder(requireContext())
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> then() }
            .show()
    }

    private fun alert(message: String) {
        if (!isAdded) return
        AlertDialog
            .Builder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** What went wrong, in the server's words when it gave some. */
    private fun failure(e: Exception): String =
        when (e) {
            is AnkiquestHttpException -> e.explanation ?: getString(R.string.ankiquest_check_http, e.code)
            is HomeAccountChanged -> getString(R.string.ankiquest_account_changed)
            is AnkiquestAccountSettings.ForeignAddress -> getString(R.string.ankiquest_browser_foreign)
            else -> getString(R.string.ankiquest_check_failed, e.message ?: e.javaClass.simpleName)
        }

    companion object {
        @VisibleForTesting
        internal const val DYNAMIC_PREFIX = "ankiquestSignIn:"
        private val PASSWORD_LENGTH = 10..256
    }
}
