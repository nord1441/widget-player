package com.example.shuffleplayer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.shuffleplayer.data.Prefs

@Composable
fun SettingsScreen(
    onPickM3u: () -> Unit,
    onPickFolder: () -> Unit,
    onClearSource: () -> Unit,
) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(Prefs.get(context).sourceUri) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Shuffle Player", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Source: ${source ?: "(none)"}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(onClick = onPickM3u) { Text("Choose .m3u file") }
            Button(onClick = onPickFolder) { Text("Choose folder") }
            Button(
                onClick = {
                    onClearSource()
                    source = null
                },
            ) { Text("Clear source") }
        }
    }
}
