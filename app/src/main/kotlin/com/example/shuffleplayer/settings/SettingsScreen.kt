package com.example.shuffleplayer.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shuffleplayer.R
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.playback.PlaybackCommands
import com.example.shuffleplayer.playback.PlaybackService

@Composable
fun SettingsScreen(
    sourceUri: String?,
    onPickM3u: () -> Unit,
    onPickFolder: () -> Unit,
    onClearSource: () -> Unit,
    onPinWidget: () -> Unit,
    onOpenErrorLog: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var shuffle by remember { mutableStateOf(prefs.shuffleEnabled) }
    var repeat by remember { mutableIntStateOf(prefs.repeatMode) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            Text(
                text = stringResource(R.string.settings_source),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = sourceUri ?: stringResource(R.string.settings_source_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickM3u) {
                    Text(stringResource(R.string.settings_pick_m3u))
                }
                OutlinedButton(onClick = onPickFolder) {
                    Text(stringResource(R.string.settings_pick_folder))
                }
            }
            TextButton(onClick = onClearSource) {
                Text(stringResource(R.string.settings_clear))
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { send(context) { it.action = PlaybackCommands.ACTION_PREV } },
                ) { Text(stringResource(R.string.settings_prev)) }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { send(context) { it.action = PlaybackCommands.ACTION_PLAY_PAUSE } },
                ) { Text(stringResource(R.string.settings_play)) }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { send(context) { it.action = PlaybackCommands.ACTION_NEXT } },
                ) { Text(stringResource(R.string.settings_next)) }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !shuffle
                        shuffle = next
                        prefs.shuffleEnabled = next
                        broadcast(context) {
                            it.action = PlaybackCommands.ACTION_SET_SHUFFLE
                            it.putExtra(PlaybackCommands.EXTRA_BOOL, next)
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_shuffle))
                Switch(checked = shuffle, onCheckedChange = null)
            }

            Text(
                text = stringResource(R.string.settings_repeat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Prefs.REPEAT_OFF to R.string.settings_repeat_off,
                    Prefs.REPEAT_ONE to R.string.settings_repeat_one,
                    Prefs.REPEAT_ALL to R.string.settings_repeat_all,
                ).forEach { (mode, label) ->
                    val onClick: () -> Unit = {
                        repeat = mode
                        prefs.repeatMode = mode
                        broadcast(context) {
                            it.action = PlaybackCommands.ACTION_SET_REPEAT
                            it.putExtra(PlaybackCommands.EXTRA_INT, mode)
                        }
                    }
                    if (repeat == mode) {
                        Button(onClick = onClick) { Text(stringResource(label)) }
                    } else {
                        OutlinedButton(onClick = onClick) { Text(stringResource(label)) }
                    }
                }
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = onOpenErrorLog,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_view_errors)) }

            OutlinedButton(
                onClick = onPinWidget,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_pin_widget)) }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private inline fun send(context: Context, configure: (Intent) -> Unit) {
    val intent = Intent(context, PlaybackService::class.java)
    configure(intent)
    context.startForegroundService(intent)
}

private inline fun broadcast(context: Context, configure: (Intent) -> Unit) {
    val intent = Intent().apply { setPackage(context.packageName) }
    configure(intent)
    context.sendBroadcast(intent)
}
