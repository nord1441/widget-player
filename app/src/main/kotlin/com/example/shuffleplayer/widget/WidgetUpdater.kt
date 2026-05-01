package com.example.shuffleplayer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent

object WidgetUpdater {

    fun refreshAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(PlayerWidgetProvider.componentName(context))
        if (ids.isEmpty()) return
        val intent = Intent(context, PlayerWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }
}
