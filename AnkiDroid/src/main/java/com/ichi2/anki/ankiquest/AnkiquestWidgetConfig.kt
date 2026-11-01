// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R

/** Asks which leaderboard period a placed widget should show. */
class AnkiquestWidgetConfig : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val widgetId =
            intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val language = AnkiquestLanguage.context(this)
        val labels = AnkiquestWidget.PERIODS.map { language.getString(it.label) }.toTypedArray()
        val chosen = AnkiquestWidget.PERIODS.indexOfFirst { it.name == AnkiquestWidget.periodOf(widgetId) }
        AlertDialog
            .Builder(this)
            .setTitle(language.getString(R.string.ankiquest_widget_period_title))
            .setSingleChoiceItems(labels, chosen) { dialog, which ->
                AnkiquestWidget.setPeriod(this, widgetId, AnkiquestWidget.PERIODS[which].name)
                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                dialog.dismiss()
            }.setOnDismissListener { finish() }
            .show()
    }
}
