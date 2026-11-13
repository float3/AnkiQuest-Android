// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Looper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.allViews
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import com.ichi2.testutils.EmptyAnkiActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** The share sheet with friends and a group to pick, one picked and a note, at normal and 200% text. */
class AnkiquestShareSheetScreenshotTest : ScreenshotTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "screenshot-token")
    private val members = listOf(AnkiquestSocial.Person("hill", "Hill"), AnkiquestSocial.Person("ana", "Ana"))
    private val overview =
        AnkiquestSocial.Overview(
            friends = listOf(AnkiquestSocial.Person("ana", "Ana"), AnkiquestSocial.Person("cerro", "Cerro")),
            incoming = emptyList(),
            outgoing = emptyList(),
            muted = emptyList(),
            groups = listOf(AnkiquestSocial.Group(3, "Spanish club", owner = false, invite = null, members = members)),
            public = true,
        )

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepareSheet() {
        mockkObject(AnkiquestHomeData, AnkiquestSocial)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestSocial.overview(identity) } returns overview
    }

    @After
    fun cleanupSheet() {
        ShadowDialog.getLatestDialog()?.dismiss()
        unmockkObject(AnkiquestHomeData, AnkiquestSocial)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun sheet() = capture("share_sheet")

    @Test
    fun sheetLargeText() {
        RuntimeEnvironment.setFontScale(2f)
        capture("share_sheet_200percent")
    }

    private fun capture(name: String) {
        val screen = startRegularActivity<EmptyAnkiActivity>(Intent(targetContext, EmptyAnkiActivity::class.java))
        screen.setContentView(FrameLayout(screen))
        val sheet =
            AnkiquestShareSheet(
                screen,
                identity,
                AnkiquestSharing.MomentRef.Win(17),
                AnkiquestShare.Card("Level 54", "I reached level 54 with 90,000 XP.", R.drawable.aki_level_up),
                elsewhere = {},
            )
        sheet.show()
        advanceRobolectricLooperUntil { sheet.state is AnkiquestShareSheet.State.Picking }
        val dialog = ShadowDialog.getLatestDialog()
        dialog.window!!
            .decorView.allViews
            .filterIsInstance<TextView>()
            .first { it.text.toString() == "Cerro" }
            .performClick()
        dialog.findViewById<EditText>(R.id.aq_share_note).setText("Took me a month!")

        // Let the preview picture arrive and the sheet settle.
        advanceRobolectricLooperUntil {
            dialog.window!!
                .decorView.allViews
                .any { it is ImageView && it.drawable != null }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        captureScreen(name)
    }
}
