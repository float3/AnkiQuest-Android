// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Device
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Methods
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Provider
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Purpose
import com.ichi2.anki.ankiquest.AnkiquestAccountSettings.Summary
import com.ichi2.anki.ankiquest.AnkiquestCapabilities
import com.ichi2.anki.ankiquest.AnkiquestHomeData
import com.ichi2.anki.ankiquest.AnkiquestHttpException
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestSignInFragmentTest : RobolectricTest() {
    private val withPassword =
        Methods(
            account = true,
            password = true,
            providers = listOf(Provider("google", "Google", false), Provider("github", "GitHub", true)),
        )
    private val withoutPassword = withPassword.copy(password = false)
    private val devices =
        listOf(
            Device("this", "app", "Pixel 9", NOW - DAY, NOW, current = true),
            Device("old", "app", "Pixel 8", NOW - 9 * DAY, null, current = false),
            Device("site", "web", null, NOW - DAY, NOW - DAY, current = false),
        )
    private val ticket = "https://quest.example/auth/oauth/github?ticket=abc"

    @Before
    fun prepare() {
        TimeManager.resetWith(MockTime(NOW))
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://quest.example")
            putString(Ankiquest.PREF_USER, "ana")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        mockkObject(AnkiquestAccountSettings, AnkiquestCapabilities)
        coEvery { AnkiquestAccountSettings.methods(any()) } returns withPassword
        coEvery { AnkiquestAccountSettings.devices(any()) } returns devices
        coEvery { AnkiquestCapabilities.current(any()) } returns setOf(AnkiquestAccountSettings.APP_SIGN_IN)
        coEvery { AnkiquestAccountSettings.browser(any(), any(), any()) } returns ticket
    }

    @After
    fun restore() {
        unmockkObject(AnkiquestAccountSettings, AnkiquestCapabilities)
        TimeManager.reset()
    }

    @Test
    fun `sign-ins and devices are shown as the server keeps them`() {
        val fragment = open().second
        assertFalse(fragment.status().isVisible)
        assertEquals(targetContext.getString(R.string.ankiquest_password_change), fragment.password().summary)
        val providers = fragment.providers()
        assertEquals(listOf("Google", "GitHub"), (0 until providers.preferenceCount).map { providers.getPreference(it).title })
        assertEquals(targetContext.getString(R.string.ankiquest_provider_linked), fragment.provider("github").summary)
        assertEquals(targetContext.getString(R.string.ankiquest_device_this, "Pixel 9"), fragment.device("this").title)
        assertFalse(fragment.device("this").isSelectable, "this device is signed out by signing out")
        assertEquals(targetContext.getString(R.string.ankiquest_device_website), fragment.device("site").title)
        assertTrue(fragment.signOutOthers().isEnabled)
        assertTrue(fragment.delete().isVisible)
    }

    @Test
    fun `a player from the server configuration has no password or account to manage`() {
        coEvery { AnkiquestAccountSettings.methods(any()) } returns Methods(account = false, password = false, providers = emptyList())
        val fragment = open().second
        assertFalse(fragment.password().isVisible)
        assertFalse(fragment.providers().isVisible)
        assertFalse(fragment.delete().isVisible)
    }

    @Test
    fun `a failed load says why and tries again when tapped`() {
        coEvery { AnkiquestAccountSettings.methods(any()) } throws AnkiquestHttpException(500, "The server broke.")
        val fragment = open().second
        assertTrue(fragment.status().isVisible)
        assertEquals(targetContext.getString(R.string.ankiquest_account_failed, "The server broke."), fragment.status().summary)
        coEvery { AnkiquestAccountSettings.methods(any()) } returns withPassword
        fragment.status().performClick()
        idle()
        assertFalse(fragment.status().isVisible)
        assertTrue(fragment.password().isVisible)
    }

    @Test
    fun `without an account it asks to sign in first`() {
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "") }
        val fragment = open().second
        assertEquals(targetContext.getString(R.string.ankiquest_account_configure), fragment.status().summary)
        coVerify(exactly = 0) { AnkiquestAccountSettings.methods(any()) }
    }

    @Test
    fun `unlinking a provider asks first and shows what the server answers`() {
        coEvery { AnkiquestAccountSettings.unlink(any(), "github") } returns
            withPassword.copy(providers = withPassword.providers.map { it.copy(linked = false) })
        val fragment = open().second
        fragment.provider("github").performClick()
        idle()
        coVerify(exactly = 0) { AnkiquestAccountSettings.unlink(any(), any()) }
        positive(latest())
        assertEquals(targetContext.getString(R.string.ankiquest_provider_unlinked), fragment.provider("github").summary)
    }

    @Test
    fun `refusing to unlink the last sign-in is explained`() {
        coEvery { AnkiquestAccountSettings.unlink(any(), any()) } throws
            AnkiquestHttpException(409, "Link another sign-in first, or this account cannot be signed into.")
        val fragment = open().second
        fragment.provider("github").performClick()
        idle()
        positive(latest())
        assertEquals("Link another sign-in first, or this account cannot be signed into.", message(latest()))
    }

    @Test
    fun `linking a provider opens the server's sign-in in the browser`() {
        val (controller, fragment) = open()
        fragment.provider("google").performClick()
        idle()
        coVerify { AnkiquestAccountSettings.browser(any(), "google", Purpose.LINK) }
        val opened = assertNotNull(shadowOf(controller.get()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(ticket.toUri(), opened.data)
    }

    @Test
    fun `a server without app sign-ins is not sent to the browser`() {
        coEvery { AnkiquestCapabilities.current(any()) } returns emptySet()
        val (controller, fragment) = open()
        fragment.provider("google").performClick()
        idle()
        assertEquals(targetContext.getString(R.string.ankiquest_provider_unsupported), message(latest()))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        coVerify(exactly = 0) { AnkiquestAccountSettings.browser(any(), any(), any()) }
    }

    @Test
    fun `linking that needs a fresh sign-in signs in again and then links`() {
        coEvery { AnkiquestAccountSettings.browser(any(), "google", Purpose.LINK) } throws
            AnkiquestHttpException(403, "Sign in with your provider again first.", reauth = true) andThen ticket
        coEvery { AnkiquestAccountSettings.summary(any()) } returns Summary("ana", password = false, confirmedUntil = NOW + 60_000)
        val (controller, fragment) = open()
        fragment.provider("google").performClick()
        idle()
        val choose = latest()
        assertEquals(1, choose.listView.adapter.count, "only GitHub is linked")
        choose.listView.performItemClick(null, 0, 0)
        idle()
        coVerify { AnkiquestAccountSettings.browser(any(), "github", Purpose.REAUTH) }
        assertNotNull(fragment.afterReauth)
        shadowOf(controller.get()).nextStartedActivity

        controller.pause().stop()
        controller.start().resume()
        idle()
        coVerify(exactly = 2) { AnkiquestAccountSettings.browser(any(), "google", Purpose.LINK) }
        assertNull(fragment.afterReauth)
    }

    @Test
    fun `coming back without signing in again carries on with nothing`() {
        coEvery { AnkiquestAccountSettings.summary(any()) } returns Summary("ana", password = false, confirmedUntil = null)
        val (controller, fragment) = open()
        fragment.afterReauth = { error("must not run") }
        controller.pause().stop()
        controller.start().resume()
        idle()
        assertEquals(targetContext.getString(R.string.ankiquest_reauth_pending), message(latest()))
    }

    @Test
    fun `changing the password sends the current and the new one`() {
        coEvery { AnkiquestAccountSettings.setPassword(any(), any(), any(), any()) } returns withPassword
        val fragment = open().second
        fragment.password().performClick()
        idle()
        val dialog = latest()
        val (current, new) = dialog.edits()
        current.setText("old password")
        new.setText("short")
        positive(dialog)
        assertEquals(
            targetContext.getString(R.string.ankiquest_password_length),
            dialog
                .texts()
                .last()
                .text
                .toString(),
        )
        coVerify(exactly = 0) { AnkiquestAccountSettings.setPassword(any(), any(), any(), any()) }
        new.setText("a new long password")
        dialog.views<CheckBox>().single().isChecked = true
        positive(dialog)
        coVerify { AnkiquestAccountSettings.setPassword(any(), "old password", "a new long password", true) }
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `a wrong current password keeps the dialog open with the server's words`() {
        coEvery { AnkiquestAccountSettings.setPassword(any(), any(), any(), any()) } throws
            AnkiquestHttpException(403, "That password is not right.")
        val fragment = open().second
        fragment.password().performClick()
        idle()
        val dialog = latest()
        dialog.edits().forEach { it.setText("long enough password") }
        positive(dialog)
        assertTrue(dialog.isShowing)
        assertEquals(
            "That password is not right.",
            dialog
                .texts()
                .last()
                .text
                .toString(),
        )
    }

    @Test
    fun `a first password needs a fresh sign-in and is then saved`() {
        coEvery { AnkiquestAccountSettings.methods(any()) } returns withoutPassword
        coEvery { AnkiquestAccountSettings.setPassword(any(), null, "a new long password", false) } throws
            AnkiquestHttpException(403, "Sign in with your provider again first.", reauth = true) andThen withPassword
        coEvery { AnkiquestAccountSettings.summary(any()) } returns Summary("ana", password = false, confirmedUntil = NOW + 60_000)
        val (controller, fragment) = open()
        assertEquals(targetContext.getString(R.string.ankiquest_password_set), fragment.password().summary)
        fragment.password().performClick()
        idle()
        val dialog = latest()
        val edits = dialog.edits()
        assertEquals(View.GONE, edits[0].visibility, "there is no current password")
        edits[1].setText("a new long password")
        positive(dialog)
        latest().listView.performItemClick(null, 0, 0)
        idle()
        controller.pause().stop()
        controller.start().resume()
        idle()
        coVerify(exactly = 2) { AnkiquestAccountSettings.setPassword(any(), null, "a new long password", false) }
    }

    @Test
    fun `signing out a device asks first and shows the remaining ones`() {
        coEvery { AnkiquestAccountSettings.signOutDevice(any(), "old") } returns devices.filter { it.id != "old" }
        val fragment = open().second
        fragment.device("old").performClick()
        idle()
        positive(latest())
        coVerify { AnkiquestAccountSettings.signOutDevice(any(), "old") }
        assertNull(fragment.findPreference<Preference>("${AnkiquestSignInFragment.DYNAMIC_PREFIX}device:old"))
    }

    @Test
    fun `signing out every other device leaves only this one`() {
        coEvery { AnkiquestAccountSettings.signOutOthers(any()) } returns devices.take(1)
        val fragment = open().second
        fragment.signOutOthers().performClick()
        idle()
        positive(latest())
        coVerify { AnkiquestAccountSettings.signOutOthers(any()) }
        assertFalse(fragment.signOutOthers().isEnabled)
    }

    @Test
    fun `the data download is written to the chosen file`() {
        val uri = "content://documents/ankiquest-ana.json".toUri()
        val written = ByteArrayOutputStream()
        shadowOf(targetContext.contentResolver).registerOutputStream(uri, written)
        coEvery { AnkiquestAccountSettings.export(any(), any()) } coAnswers {
            secondArg<java.io.OutputStream>().write("{\"profile\":[]}".toByteArray())
            14L
        }
        val fragment = open().second
        fragment.saveExport(assertNotNull(AnkiquestHomeData.account()), uri)
        idle()
        assertEquals("{\"profile\":[]}", written.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `a failed download says so`() {
        val uri = "content://documents/ankiquest-ana.json".toUri()
        shadowOf(targetContext.contentResolver).registerOutputStream(uri, ByteArrayOutputStream())
        coEvery { AnkiquestAccountSettings.export(any(), any()) } throws AnkiquestHttpException(401, "Sign in first.")
        val fragment = open().second
        fragment.saveExport(assertNotNull(AnkiquestHomeData.account()), uri)
        idle()
        assertEquals("Sign in first.", message(latest()))
    }

    @Test
    fun `deleting an account with a password asks for it and signs out`() {
        coEvery { AnkiquestAccountSettings.summary(any()) } returns Summary("ana", password = true, confirmedUntil = null)
        coEvery { AnkiquestAccountSettings.delete(any(), "wrong") } throws AnkiquestHttpException(403, "That password is not right.")
        coEvery { AnkiquestAccountSettings.delete(any(), "long enough pw") } coAnswers {
            AnkiquestAccountSettings.signOut(firstArg())
        }
        val fragment = open().second
        fragment.delete().performClick()
        idle()
        val dialog = latest()
        dialog.edits().single().setText("wrong")
        positive(dialog)
        assertTrue(dialog.isShowing)
        assertEquals(
            "That password is not right.",
            dialog
                .texts()
                .last()
                .text
                .toString(),
        )
        dialog.edits().single().setText("long enough pw")
        positive(dialog)
        assertFalse(dialog.isShowing)
        assertEquals(targetContext.getString(R.string.ankiquest_delete_done), message(latest()))
        assertNull(AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }, "this device is signed out")
        assertEquals(targetContext.getString(R.string.ankiquest_account_configure), fragment.status().summary)
    }

    @Test
    fun `deleting an account without a password signs in again first and then asks for the username`() {
        coEvery { AnkiquestAccountSettings.methods(any()) } returns withoutPassword
        coEvery { AnkiquestAccountSettings.summary(any()) } returns
            Summary("ana", password = false, confirmedUntil = null) andThen
            Summary("ana", password = false, confirmedUntil = NOW + 60_000)
        coEvery { AnkiquestAccountSettings.delete(any(), null) } returns Unit
        val (controller, fragment) = open()
        fragment.delete().performClick()
        idle()
        latest().listView.performItemClick(null, 0, 0)
        idle()
        coVerify { AnkiquestAccountSettings.browser(any(), "github", Purpose.REAUTH) }
        controller.pause().stop()
        controller.start().resume()
        idle()
        val dialog = latest()
        dialog.edits().single().setText("someone")
        positive(dialog)
        assertEquals(
            targetContext.getString(R.string.ankiquest_delete_type_username, "ana"),
            dialog
                .texts()
                .last()
                .text
                .toString(),
        )
        dialog.edits().single().setText("ana")
        positive(dialog)
        coVerify { AnkiquestAccountSettings.delete(any(), null) }
    }

    @Test
    fun `a deletion the server wants confirmed again goes back to signing in`() {
        coEvery { AnkiquestAccountSettings.summary(any()) } returns Summary("ana", password = false, confirmedUntil = NOW + 60_000)
        coEvery { AnkiquestAccountSettings.delete(any(), null) } throws
            AnkiquestHttpException(403, "Sign in with your provider again first.", reauth = true)
        val fragment = open().second
        fragment.delete().performClick()
        idle()
        val dialog = latest()
        dialog.edits().single().setText("ana")
        positive(dialog)
        assertFalse(dialog.isShowing)
        assertEquals(1, latest().listView.adapter.count)
    }

    private fun open(): Pair<ActivityController<PreferencesActivity>, AnkiquestSignInFragment> {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestSignInFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        idle()
        val fragment = (controller.get().fragment as PreferencesFragment).childFragmentManager.findFragmentById(R.id.settings_container)
        return controller to fragment as AnkiquestSignInFragment
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latest(): AlertDialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)

    private fun positive(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun message(dialog: AlertDialog): String = assertNotNull(dialog.findViewById<TextView>(android.R.id.message)).text.toString()

    private inline fun <reified T : View> AlertDialog.views(): List<T> = allViews(window!!.decorView).filterIsInstance<T>()

    private fun allViews(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()

    private fun AlertDialog.edits(): List<EditText> = views()

    /** The plain text views the fragment added, after the dialog's own title and message. */
    private fun AlertDialog.texts(): List<TextView> = views<TextView>().filter { it !is EditText && it !is CheckBox && it.id == View.NO_ID }

    private fun AnkiquestSignInFragment.status() = findPreference<Preference>(getString(R.string.ankiquest_account_status_key))!!

    private fun AnkiquestSignInFragment.password() = findPreference<Preference>(getString(R.string.ankiquest_password_key))!!

    private fun AnkiquestSignInFragment.providers() = findPreference<PreferenceCategory>(getString(R.string.ankiquest_providers_key))!!

    private fun AnkiquestSignInFragment.provider(key: String) =
        findPreference<Preference>("${AnkiquestSignInFragment.DYNAMIC_PREFIX}$key")!!

    private fun AnkiquestSignInFragment.device(id: String) =
        findPreference<Preference>("${AnkiquestSignInFragment.DYNAMIC_PREFIX}device:$id")!!

    private fun AnkiquestSignInFragment.signOutOthers() = findPreference<Preference>(getString(R.string.ankiquest_sign_out_others_key))!!

    private fun AnkiquestSignInFragment.delete() = findPreference<Preference>(getString(R.string.ankiquest_delete_account_key))!!

    private companion object {
        const val NOW = 1_793_183_400_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
