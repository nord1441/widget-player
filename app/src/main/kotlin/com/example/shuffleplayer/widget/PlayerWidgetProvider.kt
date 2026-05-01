package com.example.shuffleplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.shuffleplayer.R
import com.example.shuffleplayer.playback.PlaybackService

class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_2x1)
            views.setOnClickPendingIntent(R.id.btn_prev, command(context, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.btn_play_pause, command(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.btn_next, command(context, ACTION_NEXT))
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV, ACTION_PLAY_PAUSE, ACTION_NEXT -> {
                val svc = Intent(context, PlaybackService::class.java).apply {
                    action = intent.action
                }
                context.startForegroundService(svc)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun command(context: Context, action: String): PendingIntent {
        val intent = Intent(context, PlayerWidgetProvider::class.java).apply {
            this.action = action
            // ComponentName constraint so the broadcast is delivered to us.
            component = ComponentName(context, PlayerWidgetProvider::class.java)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_PREV = "com.example.shuffleplayer.action.PREV"
        const val ACTION_PLAY_PAUSE = "com.example.shuffleplayer.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.shuffleplayer.action.NEXT"
    }
}
