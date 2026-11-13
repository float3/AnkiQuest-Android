// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestGalleryActivityTest : RobolectricTest() {
    private val account = HomeAccount("https://quest.example.test", "hill", "synthetic-token")
    private val saves = AtomicInteger()
    private val source get() = "content://${targetContext.packageName}.apkgfileprovider/cache/card.png".toUri()

    @Before
    fun prepare() {
        mockkObject(AnkiquestHomeData, AnkiquestGallery)
        every { AnkiquestHomeData.account(any()) } returns account
        every { AnkiquestGallery.save(any(), source, account) } answers {
            saves.incrementAndGet()
            "content://media/external/images/media/42".toUri()
        }
        shadowOf(targetContext as Application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @After
    fun cleanup() {
        advanceRobolectricLooper()
        unmockkObject(AnkiquestHomeData, AnkiquestGallery)
    }

    private fun intent() =
        Intent(targetContext, AnkiquestGalleryActivity::class.java)
            .setDataAndType(source, "image/png")
            .putExtra(AnkiquestShare.EXTRA_SCOPE, account.scope)

    private fun build(request: Intent = intent()) =
        Robolectric.buildActivity(AnkiquestGalleryActivity::class.java, request).also { saveControllerForCleanup(it) }

    @Test
    fun `modern Android saves without asking for storage access and recreation does not duplicate it`() {
        val controller = build().create()
        val activity = controller.get()
        advanceRobolectricLooperUntil { saves.get() == 1 }
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertTrue(activity.isFinishing)
        val state = Bundle()
        controller.saveInstanceState(state)
        val restored = build().create(state).get()
        advanceRobolectricLooper()
        assertTrue(restored.isFinishing)
        assertEquals(1, saves.get())
    }

    @Test
    @Config(sdk = [24])
    fun `older Android saves only after permission is granted`() {
        val activity = build().setup().get()
        val request = assertNotNull(shadowOf(activity).lastRequestedPermission)
        assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions.toList())
        assertEquals(0, saves.get())
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        advanceRobolectricLooperUntil { saves.get() == 1 }
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(sdk = [24])
    fun `denied storage permission leaves no gallery picture`() {
        val activity = build().setup().get()
        val request = assertNotNull(shadowOf(activity).lastRequestedPermission)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        advanceRobolectricLooper()
        assertTrue(activity.isFinishing)
        verify(exactly = 0) { AnkiquestGallery.save(any(), any(), any()) }
    }

    @Test
    @Config(sdk = [24])
    fun `an old account destination never requests storage permission`() {
        val activity =
            build(intent().putExtra(AnkiquestShare.EXTRA_SCOPE, "old-account"))
                .create()
                .get()
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertTrue(activity.isFinishing)
        verify(exactly = 0) { AnkiquestGallery.save(any(), any(), any()) }
    }

    @Test
    @Config(sdk = [24])
    fun `changing account during the permission prompt cancels saving`() {
        val activity = build().setup().get()
        val request = assertNotNull(shadowOf(activity).lastRequestedPermission)
        every { AnkiquestHomeData.account(any()) } returns account.copy(user = "other")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        advanceRobolectricLooper()
        assertTrue(activity.isFinishing)
        verify(exactly = 0) { AnkiquestGallery.save(any(), any(), any()) }
    }
}
