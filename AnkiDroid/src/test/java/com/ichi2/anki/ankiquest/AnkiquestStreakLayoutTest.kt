// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestStreakWidget.Size
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
// Native font metrics start at API 26; API 24 RemoteViews are covered by the tier tests.
@Config(sdk = [27, 33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnkiquestStreakLayoutTest : RobolectricTest() {
    @Test
    fun `streak digits fit at each minimum widget size`() {
        val sizes =
            listOf(Triple(Size.SMALL, 57, 57), Triple(Size.MEDIUM, 100, 90), Triple(Size.LARGE, 200, 220), Triple(Size.HUGE, 300, 380))
        for ((size, width, height) in sizes) {
            for (streak in listOf(1, 7, 30, 100, 365, 1000)) {
                val board =
                    JSONArray().put(
                        JSONObject()
                            .put("user", "hill")
                            .put("streak", streak)
                            .put("streak_state", "studied")
                            .put("today_reviews", 20)
                            .put("today_minutes", 12)
                            .put("streak_minutes", 10)
                            .put("day_ends_at", TimeManager.time.intTimeMS() + 3_600_000),
                    )
                val view = AnkiquestStreakWidget.views(targetContext, board, "hill", size).apply(targetContext, FrameLayout(targetContext))
                // A launcher resolves inherited layout direction before displaying RemoteViews.
                view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                val density = view.resources.displayMetrics.density
                val widthPx = (width * density).toInt()
                val heightPx = (height * density).toInt()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, widthPx, heightPx)
                val number = view.findViewById<TextView>(R.id.ankiquest_streak_number)
                val flame = number.compoundDrawablesRelative[2]
                assertTrue(flame != null && flame.bounds.width() > 0 && flame.bounds.height() > 0, "$size at $streak has no visible flame")
                assertTrue(number.compoundDrawables[2] != null, "$size at $streak has no resolved flame")
                val required = number.paint.measureText(number.text.toString()) + number.compoundPaddingLeft + number.compoundPaddingRight
                assertTrue(required <= number.width + 1, "$size at $streak needs $required px but has ${number.width}")
                assertTrue(
                    number.height >= number.layout.height,
                    "$size at $streak clips ${number.layout.height}px into ${number.height}px, font ${number.textSize}/${number.paint.fontSpacing}",
                )
            }
        }
    }
}
