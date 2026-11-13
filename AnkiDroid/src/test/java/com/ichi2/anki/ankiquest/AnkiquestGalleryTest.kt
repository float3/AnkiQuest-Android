// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.SharedPreferencesTestHook
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.ichi2.utils.openInputStreamSafe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Config(application = EmptyApplication::class, sdk = [24, 33])
@RunWith(AndroidJUnit4::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestGalleryTest : RobolectricTest() {
    private val resolver = mockk<ContentResolver>()
    private val inserted = slot<ContentValues>()
    private val published = slot<ContentValues>()
    private val output = ByteArrayOutputStream()
    private val bytes = "the prepared PNG bytes".toByteArray()
    private val saved = "content://media/external/images/media/42".toUri()
    private lateinit var source: Uri
    private lateinit var context: ContextWrapper
    private lateinit var account: HomeAccount

    @Before
    fun prepare() {
        SharedPreferencesTestHook.override = targetContext.sharedPrefs()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.PREF_URL, "https://example.test")
            putString(Ankiquest.PREF_USER, "hill")
            putString(Ankiquest.PREF_TOKEN, "test-token")
        }
        account = AnkiquestHomeData.account()!!
        source = "content://${targetContext.packageName}.apkgfileprovider/cache/picture.png".toUri()
        context =
            object : ContextWrapper(targetContext) {
                override fun getContentResolver() = resolver
            }
        every { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, capture(inserted)) } returns saved
        every { resolver.openInputStreamSafe(source) } answers { bytes.inputStream() }
        every { resolver.openOutputStream(saved) } returns output
        every { resolver.update(saved, capture(published), null, null) } returns 1
        every { resolver.delete(saved, null, null) } returns 1
    }

    @After
    fun resetPreferences() {
        SharedPreferencesTestHook.override = null
    }

    @Test
    fun `save copies the exact shared bytes and publishes only the complete image`() {
        assertEquals(saved, AnkiquestGallery.save(context, source, account))
        assertContentEquals(bytes, output.toByteArray())
        assertEquals("image/png", inserted.captured.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertTrue(inserted.captured.getAsString(MediaStore.Images.Media.DISPLAY_NAME).endsWith(".png"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals("Pictures/AnkiQuest", inserted.captured.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals(1, inserted.captured.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertEquals(0, published.captured.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        } else {
            assertFalse(inserted.captured.containsKey("is_pending"))
            verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
        }
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `a failed write removes only its own incomplete gallery row`() {
        every { resolver.openOutputStream(saved) } throws IOException("storage full")
        assertFailsWith<IOException> { AnkiquestGallery.save(context, source, account) }
        verify(exactly = 1) { resolver.delete(saved, null, null) }
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `a missing source leaves no empty picture in the gallery`() {
        every { resolver.openInputStreamSafe(source) } returns null
        assertFailsWith<IOException> { AnkiquestGallery.save(context, source, account) }
        verify(exactly = 1) { resolver.delete(saved, null, null) }
    }

    @Test
    fun `an account change while copying discards the new image`() {
        every { resolver.openOutputStream(saved) } returns
            object : ByteArrayOutputStream() {
                override fun close() {
                    AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.PREF_TOKEN, "another-account") }
                    super.close()
                }
            }
        assertFailsWith<HomeAccountChanged> { AnkiquestGallery.save(context, source, account) }
        verify(exactly = 1) { resolver.delete(saved, null, null) }
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `an old account or foreign content URI never creates a gallery row`() {
        assertFailsWith<IllegalArgumentException> { AnkiquestGallery.save(context, "content://other/private".toUri(), account) }
        AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.PREF_TOKEN) }
        assertFailsWith<HomeAccountChanged> { AnkiquestGallery.save(context, source, account) }
        verify(exactly = 0) { resolver.insert(any(), any()) }
    }
}
