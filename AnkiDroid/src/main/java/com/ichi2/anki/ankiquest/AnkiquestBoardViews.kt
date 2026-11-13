// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ichi2.anki.R
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.text.NumberFormat

/** The cards, text, chips, bars and avatars the leaderboard and profile screens are built from, in the Today screen's colours. */
internal class AnkiquestBoardViews(
    private val context: Context,
) {
    private val numbers = NumberFormat.getIntegerInstance(AnkiquestLanguage.locale())

    fun number(value: Long): String = numbers.format(value)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun card(
        parent: LinearLayout,
        tinted: Boolean = false,
        horizontal: Boolean = false,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (horizontal) gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = shape(if (tinted) R.color.aq_home_tint else R.color.aq_home_surface)
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
        }

    /** A column that takes the width left in a horizontal [parent]. */
    fun column(parent: LinearLayout): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            parent.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }

    fun text(
        parent: LinearLayout,
        value: String,
        heading: Boolean = false,
        small: Boolean = false,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize =
                when {
                    heading -> 17f
                    small -> 14f
                    else -> 16f
                }
            setTextColor(context.getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
            if (heading) {
                typeface = Typeface.DEFAULT_BOLD
                ViewCompat.setAccessibilityHeading(this, true)
            }
            setPadding(0, dp(3), 0, dp(3))
            val width =
                if (parent.orientation ==
                    LinearLayout.HORIZONTAL
                ) {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    ViewGroup.LayoutParams.MATCH_PARENT
                }
            parent.addView(this, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** A section heading between cards. */
    fun section(
        parent: LinearLayout,
        value: String,
    ): TextView = text(parent, value, heading = true).apply { setPadding(0, dp(16), 0, 0) }

    fun button(
        parent: LinearLayout,
        label: String,
        primary: Boolean = false,
        click: () -> Unit,
    ): MaterialButton =
        MaterialButton(context).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            minimumHeight = dp(48)
            cornerRadius = dp(12)
            setTextColor(context.getColor(if (primary) R.color.aq_home_primary_text else R.color.aq_home_text))
            backgroundTintList = ColorStateList.valueOf(context.getColor(if (primary) R.color.aq_home_primary else R.color.aq_home_surface))
            if (!primary) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(context.getColor(R.color.aq_home_outline))
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
            ChipGroup(context).apply {
                isSingleLine = true
                isSingleSelection = true
            }
        for ((value, label) in options) group.addView(chip(label, checked = value == selected, enabled = enabled) { pick(value) })
        parent.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(group)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )
    }

    /**
     * A chip that switches on and off, for a [ChipGroup] where several can be on. Screen readers say
     * whether it is on; [click] re-renders with the new state.
     */
    fun toggle(
        label: String,
        checked: Boolean,
        enabled: Boolean,
        click: () -> Unit,
    ): Chip =
        chip(label, checked, enabled, click).apply {
            isCheckable = true
            isChecked = checked
        }

    private fun chip(
        label: String,
        checked: Boolean,
        enabled: Boolean,
        click: () -> Unit,
    ): Chip =
        Chip(context).apply {
            text = label
            textSize = 15f
            // Grows with the text size, so large text is never clipped.
            chipMinHeight = maxOf(dp(32).toFloat(), paint.fontMetrics.run { descent - ascent } + dp(14))
            isCheckable = checked
            isChecked = checked
            isCheckedIconVisible = false
            isEnabled = enabled
            setTextColor(context.getColor(if (checked) R.color.aq_home_primary_text else R.color.aq_home_text))
            chipBackgroundColor =
                ColorStateList.valueOf(context.getColor(if (checked) R.color.aq_home_primary else R.color.aq_home_surface))
            chipStrokeWidth = if (checked) 0f else dp(1).toFloat()
            chipStrokeColor = ColorStateList.valueOf(context.getColor(R.color.aq_home_outline))
            // The state is the selection drawn above; clicking always asks [click], which re-renders.
            setOnClickListener { click() }
        }

    /** A progress bar at [percent] of its width, 0 to 100. */
    fun bar(
        parent: LinearLayout,
        percent: Int,
    ): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = percent.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.aq_home_primary))
            progressBackgroundTintList = ColorStateList.valueOf(context.getColor(R.color.aq_home_outline))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                },
            )
        }

    /** [user]'s profile picture, or the first letter of [display] in a circle while there is none. */
    fun avatar(
        parent: LinearLayout,
        user: String,
        display: String,
        size: Int,
    ): View {
        val edge = dp(size)
        val photo = AnkiquestAvatars.bitmap(user)
        val view =
            if (photo != null) {
                ImageView(context).apply {
                    setImageBitmap(photo)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            } else {
                val name = display.trim().ifEmpty { user }
                val initial = name.take(name.offsetByCodePoints(0, minOf(1, name.codePointCount(0, name.length)))).uppercase()
                FrameLayout(context).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(context.getColor(R.color.aq_home_tint))
                        }
                    addView(
                        TextView(context).apply {
                            text = initial
                            textSize = size * 0.4f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(context.getColor(R.color.aq_home_avatar_text))
                            gravity = Gravity.CENTER
                        },
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                }
            }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        parent.addView(view, LinearLayout.LayoutParams(edge, edge).apply { marginStart = dp(8) })
        return view
    }

    /** Makes [view] open something on a tap, with the ripple of a list item. */
    fun clickable(
        view: View,
        click: () -> Unit,
    ) {
        val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        view.foreground = AppCompatResources.getDrawable(context, ripple.resourceId)
        view.setOnClickListener { click() }
    }

    fun shape(
        @ColorRes color: Int,
        radius: Int = 18,
    ): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(context.getColor(color))
        }
}

/**
 * Runs [work] and answers the message for its failure, or null when it worked; a changed account
 * answers [R.string.aq_feed_account_changed], for the screen to start over.
 */
internal suspend fun boardAttempt(work: suspend () -> Unit): Int? =
    try {
        work()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: HomeAccountChanged) {
        R.string.aq_feed_account_changed
    } catch (_: AnkiquestBoards.NotVisible) {
        R.string.aq_profile_hidden
    } catch (e: AnkiquestHttpException) {
        Timber.w(e, "AnkiQuest board request refused")
        if (e.code == 401 || e.code == 403) R.string.aq_home_auth else R.string.aq_board_server_error
    } catch (e: IOException) {
        Timber.w(e, "AnkiQuest board unreachable")
        R.string.aq_board_offline
    } catch (e: JSONException) {
        Timber.w(e, "AnkiQuest board unreadable")
        R.string.aq_board_server_error
    }
