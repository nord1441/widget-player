package com.example.shuffleplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.example.shuffleplayer.R
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.playback.PlaybackCommands
import com.example.shuffleplayer.playback.PlaybackService

class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            renderForId(context, appWidgetManager, id, appWidgetManager.getAppWidgetOptions(id))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        renderForId(context, appWidgetManager, appWidgetId, newOptions ?: Bundle.EMPTY)
    }

    private fun renderForId(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val layout = pickLayout(minWidth, minHeight)
        val views = buildViews(context, layout)
        manager.updateAppWidget(id, views)
    }

    private fun pickLayout(minWidthDp: Int, minHeightDp: Int): Int = when {
        minHeightDp >= 110 && minWidthDp >= 250 -> R.layout.widget_4x2
        minWidthDp >= 250 -> R.layout.widget_4x1
        minWidthDp >= 110 -> R.layout.widget_2x1
        else -> R.layout.widget_1x1
    }

    private fun buildViews(context: Context, layout: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val prefs = Prefs.get(context)

        val playPauseIcon = if (prefs.isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        // play/pause is present in every layout
        views.setImageViewResource(R.id.btn_play_pause, playPauseIcon)
        views.setOnClickPendingIntent(
            R.id.btn_play_pause,
            command(context, PlaybackCommands.ACTION_PLAY_PAUSE),
        )

        when (layout) {
            R.layout.widget_2x1, R.layout.widget_4x1, R.layout.widget_4x2 -> {
                views.setOnClickPendingIntent(
                    R.id.btn_prev,
                    command(context, PlaybackCommands.ACTION_PREV),
                )
                views.setOnClickPendingIntent(
                    R.id.btn_next,
                    command(context, PlaybackCommands.ACTION_NEXT),
                )
            }
        }

        if (layout == R.layout.widget_4x1 || layout == R.layout.widget_4x2) {
            val title = prefs.lastTitle ?: context.getString(R.string.widget_no_track)
            val artist = prefs.lastArtist
            views.setTextViewText(R.id.txt_title, title)
            if (artist.isNullOrBlank()) {
                views.setViewVisibility(R.id.txt_artist, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.txt_artist, android.view.View.VISIBLE)
                views.setTextViewText(R.id.txt_artist, artist)
            }
        }

        return views
    }

    private fun command(context: Context, action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getForegroundService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, PlayerWidgetProvider::class.java)
    }
}
