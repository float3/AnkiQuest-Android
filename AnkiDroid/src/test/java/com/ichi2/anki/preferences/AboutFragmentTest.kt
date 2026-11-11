// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import android.os.Looper
import android.text.Spanned
import android.text.style.URLSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertContains

@RunWith(AndroidJUnit4::class)
class AboutFragmentTest : RobolectricTest() {
    @Test
    fun `About links to the source of this AnkiQuest build`() {
        val intent = PreferencesActivity.getIntent(targetContext, AboutFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        shadowOf(Looper.getMainLooper()).idle()
        val parent = controller.get().fragment as PreferencesFragment
        val about = parent.childFragmentManager.findFragmentById(R.id.settings_container) as AboutFragment

        val license = about.binding.licenseDescription.text as Spanned
        val urls = license.getSpans(0, license.length, URLSpan::class.java).map(URLSpan::getURL)
        assertContains(urls, "https://github.com/float3/AnkiQuest-Android/tree/${BuildConfig.GIT_COMMIT_HASH}")
    }
}
