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

/** Private "Download picture" destination in Android's share sheet. */
class AnkiquestGalleryActivity : AnkiActivity() {
    private var started = false
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                savePicture()
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean(STARTED) == true
        if (started) {
            finish()
            return
        }
        val account = AnkiquestHomeData.account()
        if (intent.data == null || account == null || intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE) != account.scope) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            savePicture()
        } else if (savedInstanceState == null) {
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STARTED, started)
        super.onSaveInstanceState(outState)
    }

    private fun savePicture() {
        if (started) {
            return
        }
        started = true
        val account = AnkiquestHomeData.account()
        val source = intent.data
        if (source == null || account == null || intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE) != account.scope) {
            finish()
            return
        }
        // A requested local save may finish after this short-lived chooser destination closes.
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

    companion object {
        private const val STARTED = "ankiquest.picture_started"
    }
}
