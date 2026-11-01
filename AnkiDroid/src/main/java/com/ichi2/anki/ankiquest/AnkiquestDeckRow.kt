// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.res.ColorStateList
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.dp
import com.ichi2.anki.databinding.ItemDeckBinding
import com.ichi2.anki.deckpicker.DisplayDeckNode

/** Shows a deck's daily AnkiQuest status in its deck list row. */
object AnkiquestDeckRow {
    fun bind(
        binding: ItemDeckBinding,
        node: DisplayDeckNode,
    ) {
        val dailyStatus = node.dailyState?.status
        binding.deckDailyStatus.isVisible = dailyStatus == DailyDeckStatus.LEARNING_LATER
        val finished = dailyStatus == DailyDeckStatus.DONE
        binding.deckName.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (finished) R.drawable.ic_done else 0, 0)
        TextViewCompat.setCompoundDrawableTintList(
            binding.deckName,
            if (finished) {
                ColorStateList.valueOf(binding.deckName.context.getColor(R.color.aq_deck_green))
            } else {
                null
            },
        )
        binding.deckName.compoundDrawablePadding = 4.dp.toPx(binding.deckName.context)
        node.dailyState?.let { daily ->
            val status = binding.deckDailyStatus
            status.text = daily.label(status.context)
            status.setTextColor(status.context.getColor(daily.color))
            status.setCompoundDrawablesRelativeWithIntrinsicBounds(daily.icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(
                status,
                ColorStateList.valueOf(status.context.getColor(daily.color)),
            )
            status.compoundDrawablePadding = 4.dp.toPx(status.context)
            if (daily.status == DailyDeckStatus.DONE && !node.isSelected) {
                binding.deckLayout.backgroundTintList =
                    ColorStateList.valueOf(status.context.getColor(R.color.aq_deck_done_background))
            }
        }
        if (node.dailyState?.status != DailyDeckStatus.DONE ||
            node.isSelected
        ) {
            binding.deckLayout.backgroundTintList = null
        }
    }
}
