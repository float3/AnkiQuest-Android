// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.ActivityManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.worker.SyncWorker
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.P, Build.VERSION_CODES.Q])
class AnkiquestLauncherTest : RobolectricTest() {
    @Before
    fun watchSyncCancellation() {
        mockkObject(SyncWorker.Companion)
        justRun { SyncWorker.cancel(any()) }
    }

    @After
    fun stopWatchingSync() {
        unmockkObject(SyncWorker.Companion)
    }

    @Test
    fun `a cold launcher open cancels background sync before opening the collection`() {
        openLauncher()

        verify(exactly = 1) { SyncWorker.cancel(any()) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `returning to an existing task leaves its sync running`() {
        val info = ActivityManager.RecentTaskInfo()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.taskId = 42
            info.isRunning = true
        } else {
            info.id = 42
        }
        val task = mockk<ActivityManager.AppTask>()
        every { task.taskInfo } returns info
        val manager = targetContext.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setAppTasks(listOf(task))

        openLauncher()

        verify(exactly = 0) { SyncWorker.cancel(any()) }
    }

    private fun openLauncher() {
        val controller = Robolectric.buildActivity(AnkiquestLauncher::class.java).create()
        saveControllerForCleanup(controller)
    }
}
