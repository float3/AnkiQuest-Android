// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
class AnkiquestAvatarContrastTest : RobolectricTest(loadBackend = false) {
    @Before
    fun preferences() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
    }

    @After
    fun cleanup() {
        SharedPreferencesTestHook.override = null
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `light avatar initials remain readable`() = assertReadableInitials()

    @Test
    @Config(qualifiers = "night")
    fun `dark avatar initials remain readable`() = assertReadableInitials()

    private fun assertReadableInitials() {
        // Check the resolved colours at both sizes, including Today's smaller initial.
        for (size in listOf(TODAY_AVATAR_SIZE, PROFILE_AVATAR_SIZE)) {
            val parent = LinearLayout(targetContext)
            val avatar = AnkiquestBoardViews(targetContext).avatar(parent, "contrast", "Contrast", size) as FrameLayout
            val initial = avatar.getChildAt(0) as TextView
            val background = assertNotNull((avatar.background as GradientDrawable).color).defaultColor
            val contrast = ColorUtils.calculateContrast(initial.currentTextColor, background)

            assertTrue(contrast >= MIN_TEXT_CONTRAST, "${size}dp avatar contrast is $contrast:1")
        }
    }

    private companion object {
        const val TODAY_AVATAR_SIZE = 32
        const val PROFILE_AVATAR_SIZE = 56
        const val MIN_TEXT_CONTRAST = 4.5
    }
}
