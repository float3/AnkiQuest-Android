// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** "Download picture": saves a picture from the share cache to the gallery, asking for storage access before Android 10. */
class AnkiquestGalleryActivity : AnkiActivity() {
    /** Where saving stands. Kept across recreation, so a save never runs twice. */
    private enum class State { CHECKING, AWAITING_PERMISSION, SAVING }

    private var state = State.CHECKING
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                savePicture()
                return@registerForActivityResult
            }
            toast(R.string.ankiquest_picture_failed)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        state = savedInstanceState?.getString(STATE_PROGRESS)?.let(State::valueOf) ?: State.CHECKING
        when (state) {
            // The save already started before recreation.
            State.SAVING -> finish()
            // The permission answer is delivered to the recreated activity.
            State.AWAITING_PERMISSION -> Unit
            State.CHECKING -> check()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROGRESS, state.name)
        super.onSaveInstanceState(outState)
    }

    /** Saves at once where no permission is needed, otherwise asks for it first. */
    private fun check() {
        if (account() == null) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            savePicture()
            return
        }
        state = State.AWAITING_PERMISSION
        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** The signed-in account, when this destination has a picture and still belongs to it. */
    private fun account(): HomeAccount? {
        val account = AnkiquestHomeData.account() ?: return null
        if (intent.data == null || intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE) != account.scope) {
            return null
        }
        return account
    }

    private fun savePicture() {
        if (state == State.SAVING) {
            return
        }
        state = State.SAVING
        val account = account()
        val source = intent.data
        if (account == null || source == null) {
            finish()
            return
        }

        // A requested local save may finish after this short-lived destination closes.
        val context = applicationContext
        applicationScope.launch(Dispatchers.IO) {
            val message =
                try {
                    AnkiquestGallery.save(context, source, account)
                    R.string.ankiquest_picture_saved
                } catch (error: Exception) {
                    Timber.w(error, "AnkiQuest gallery picture could not be saved")
                    R.string.ankiquest_picture_failed
                }
            withContext(Dispatchers.Main) {
                showThemedToast(context, AnkiquestLanguage.context(context).getString(message), shortLength = false)
            }
        }
        finish()
    }

    private fun toast(message: Int) = showThemedToast(this, AnkiquestLanguage.context(this).getString(message), shortLength = false)

    companion object {
        private const val STATE_PROGRESS = "ankiquest.picture_state"
    }
}
