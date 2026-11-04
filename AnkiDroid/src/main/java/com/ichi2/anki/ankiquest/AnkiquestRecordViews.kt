// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ichi2.anki.R
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.text.NumberFormat

/**
 * The cards, text, chips, tiles and bars the records and study history screens are built from, in
 * the Today screen's colours, and the frame both share ([R.layout.activity_ankiquest_records]).
 */
internal class AnkiquestRecordViews(
    private val activity: ComponentActivity,
) {
    private val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())

    val content: LinearLayout get() = activity.findViewById(R.id.aq_records_content)

    fun number(value: Long): String = numbers.format(value)

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** Sets up the frame: [title], back, refresh described by [refreshLabel], and the system bars. */
    fun frame(
        @StringRes title: Int,
        @StringRes refreshLabel: Int,
        refresh: () -> Unit,
    ) {
        activity.findViewById<TextView>(R.id.aq_records_title).apply {
            setText(title)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        activity.findViewById<View>(R.id.aq_records_back).setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        activity.findViewById<View>(R.id.aq_records_refresh).apply {
            contentDescription = activity.getString(refreshLabel)
            setOnClickListener { refresh() }
        }
        val root = activity.findViewById<View>(R.id.aq_records_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    fun card(
        parent: LinearLayout,
        tinted: Boolean = false,
    ): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = shape(if (tinted) R.color.aq_home_tint else R.color.aq_home_surface)
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
        }

    /** A horizontal row whose children share its width through [cell]. */
    fun row(parent: LinearLayout): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    fun text(
        parent: LinearLayout,
        value: String,
        heading: Boolean = false,
        small: Boolean = false,
    ): TextView =
        TextView(activity).apply {
            text = value
            textSize =
                when {
                    heading -> 17f
                    small -> 14f
                    else -> 16f
                }
            setTextColor(activity.getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
            if (heading) {
                typeface = Typeface.DEFAULT_BOLD
                ViewCompat.setAccessibilityHeading(this, true)
            }
            setPadding(0, dp(3), 0, dp(3))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** A cell of a [row]: text taking [weight] of the width, or only what it needs at 0. */
    fun cell(
        row: LinearLayout,
        value: String,
        weight: Float,
        bold: Boolean = false,
        small: Boolean = false,
        end: Boolean = false,
    ): TextView =
        TextView(activity).apply {
            text = value
            textSize = if (small) 14f else 16f
            setTextColor(activity.getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = if (end) Gravity.END else Gravity.START
            setPadding(dp(4), dp(3), dp(4), dp(3))
            row.addView(
                this,
                if (weight > 0) {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )
        }

    /** Tiles of a value over its label, side by side, wrapping into rows of [perRow]. */
    fun tiles(
        parent: LinearLayout,
        values: List<Pair<String, String>>,
        perRow: Int = 3,
    ) {
        for (chunk in values.chunked(perRow)) {
            val line = row(parent)
            for ((value, label) in chunk) {
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = shape(R.color.aq_home_surface, 14)
                    contentDescription = "$value $label"
                    addView(
                        TextView(activity).apply {
                            text = value
                            textSize = 20f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(activity.getColor(R.color.aq_home_text))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                    )
                    addView(
                        TextView(activity).apply {
                            text = label
                            textSize = 13f
                            setTextColor(activity.getColor(R.color.aq_home_muted))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                    )
                    line.addView(
                        this,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            topMargin = dp(8)
                            marginEnd = dp(8)
                        },
                    )
                }
            }
            repeat(perRow - chunk.size) { line.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f)) }
        }
    }

    fun button(
        parent: LinearLayout,
        label: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): MaterialButton =
        MaterialButton(activity).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            minimumHeight = dp(48)
            cornerRadius = dp(12)
            isEnabled = enabled
            setTextColor(activity.getColor(if (primary) R.color.aq_home_primary_text else R.color.aq_home_text))
            backgroundTintList =
                ColorStateList.valueOf(activity.getColor(if (primary) R.color.aq_home_primary else R.color.aq_home_surface))
            if (!primary) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(activity.getColor(R.color.aq_home_outline))
            }
            setOnClickListener { click() }
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(8)
                },
            )
        }

    /** A single-choice row of chips, scrolling sideways when it does not fit. */
    fun <T> choices(
        parent: LinearLayout,
        options: List<Pair<T, String>>,
        selected: T,
        enabled: Boolean,
        pick: (T) -> Unit,
    ) {
        val group =
            ChipGroup(activity).apply {
                isSingleLine = true
                isSingleSelection = true
            }
        for ((value, label) in options) group.addView(chip(label, checked = value == selected, enabled = enabled) { pick(value) })
        parent.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(group)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
    }

    private fun chip(
        label: String,
        checked: Boolean,
        enabled: Boolean,
        click: () -> Unit,
    ): Chip =
        Chip(activity).apply {
            text = label
            textSize = 15f
            // Grows with the text size, so large text is never clipped.
            chipMinHeight = maxOf(dp(32).toFloat(), paint.fontMetrics.run { descent - ascent } + dp(14))
            isCheckable = checked
            isChecked = checked
            isCheckedIconVisible = false
            isEnabled = enabled
            setTextColor(activity.getColor(if (checked) R.color.aq_home_primary_text else R.color.aq_home_text))
            chipBackgroundColor =
                ColorStateList.valueOf(activity.getColor(if (checked) R.color.aq_home_primary else R.color.aq_home_surface))
            chipStrokeWidth = if (checked) 0f else dp(1).toFloat()
            chipStrokeColor = ColorStateList.valueOf(activity.getColor(R.color.aq_home_outline))
            // The state is the selection drawn above; clicking always asks [click], which re-renders.
            setOnClickListener { click() }
        }

    /** A progress bar at [percent] of its width, 0 to 100. */
    fun bar(
        parent: LinearLayout,
        percent: Int,
    ): ProgressBar =
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = percent.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(activity.getColor(R.color.aq_home_primary))
            progressBackgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.aq_home_outline))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(6)
                },
            )
        }

    fun shape(
        @ColorRes color: Int,
        radius: Int = 18,
    ): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(activity.getColor(color))
        }

    companion object {
        /**
         * Runs [work] and answers the message for its failure, or null when it worked: a refused
         * token [R.string.aq_home_auth], a changed account [R.string.aq_feed_account_changed] for
         * the screen to start over, [offline] and [unreadable].
         */
        suspend fun attempt(
            @StringRes offline: Int,
            @StringRes unreadable: Int,
            work: suspend () -> Unit,
        ): Int? =
            try {
                work()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: HomeAccountChanged) {
                R.string.aq_feed_account_changed
            } catch (e: AnkiquestHttpException) {
                Timber.w(e, "AnkiQuest records request refused")
                if (e.code == 401 || e.code == 403) R.string.aq_home_auth else unreadable
            } catch (e: IOException) {
                Timber.w(e, "AnkiQuest records unreachable")
                offline
            } catch (e: JSONException) {
                Timber.w(e, "AnkiQuest records unreadable")
                unreadable
            }
    }
}
