// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.deckpicker.heatmap

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.testutils.EmptyApplication
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import kotlin.test.assertTrue
import com.ichi2.anki.common.android.R as CommonR

/** Human-reviewed render artifacts, deliberately kept out of source-controlled golden images. */
@RunWith(RobolectricTestRunner::class)
@Config(application = EmptyApplication::class, qualifiers = "en-rUS-mdpi")
@Category(EmptyApplicationCategory::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewHeatmapRenderTest {
    @Test
    fun `render phone widths themes larger font and non loaded states`() {
        val today = LocalDate.of(2024, 9, 22)
        val reviews = (0L..600L).filter { it % 9 != 0L }.associate { today.minusDays(it) to (it % 140 + 1).toInt() }
        val scheduled = (1L..90L).associate { today.plusDays(it) to (it % 80 + 1).toInt() }
        val data = ReviewHeatmap.summarize(today, reviews, scheduled)
        render("light-320", 320) { setData(data) }
        render("light-390", 390) { setData(data) }
        render("dark-320", 320, dark = true) { setData(data) }
        render("dark-390", 390, dark = true) { setData(data) }
        render("large-font-320", 320, fontScale = 1.5f) { setData(data) }
        render("empty-320", 320) { setData(ReviewHeatmap.summarize(today, emptyMap(), emptyMap())) }
        render("loading-390", 390) { setLoading() }
        render("error-390", 390) { setError() }
    }

    private fun render(
        name: String,
        width: Int,
        dark: Boolean = false,
        fontScale: Float = 1f,
        configure: ReviewHeatmapView.() -> Unit,
    ) {
        val app: Context = ApplicationProvider.getApplicationContext()
        val config =
            Configuration(app.resources.configuration).apply {
                this.fontScale = fontScale
                uiMode =
                    (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
        val context = ContextThemeWrapper(app.createConfigurationContext(config), if (dark) CommonR.style.Theme_Dark else CommonR.style.Theme_Light)
        val view = ReviewHeatmapView(context).apply(configure)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
        // An unattached view does not execute View.post; position the same recent part of the year explicitly.
        view.findViewById<HorizontalScrollView>(R.id.review_heatmap_scroll).scrollTo(
            view.findViewById<ReviewHeatmapGrid>(R.id.review_heatmap_grid).xForDate(LocalDate.of(2024, 9, 22)) - width / 2,
            0,
        )
        val bitmap = createBitmap(view.measuredWidth, view.measuredHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(MaterialColors.getColor(context, android.R.attr.colorBackground, 0))
        view.draw(canvas)
        val output = File("build/reports/heatmap/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
