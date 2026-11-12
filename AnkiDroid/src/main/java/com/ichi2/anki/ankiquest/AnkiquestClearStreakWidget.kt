// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** A separate transparent picker entry uses the same refresh and resize lifecycle. */
class AnkiquestClearStreakWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) = AnkiquestStreakWidget().onUpdate(context, appWidgetManager, appWidgetIds)

    override fun onEnabled(context: Context) = AnkiquestStreakWidget().onEnabled(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = AnkiquestStreakWidget().onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
}
