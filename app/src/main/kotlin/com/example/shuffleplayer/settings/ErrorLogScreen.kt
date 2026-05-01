package com.example.shuffleplayer.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.shuffleplayer.data.ErrorLogDb
import com.example.shuffleplayer.data.PlaybackErrorEntity

@Composable
fun ErrorLogScreen() {
    val context = LocalContext.current
    val dao = remember { ErrorLogDb.get(context).errorLogDao() }
    val entries by dao.observeAll().collectAsState(initial = emptyList())

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.padding(16.dp)) {
            items(entries, key = { it.id }) { entry ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = entry.errorCategory,
                        style = MaterialTheme.typography.labelMedium,
                        color = colorFor(entry.errorCategory),
                    )
                    Text(
                        text = entry.trackLabel ?: entry.uri,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = entry.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun colorFor(category: String): Color = when (category) {
    "IO" -> Color(0xFFE57373)
    "DECODER" -> Color(0xFFFFB74D)
    "PARSING" -> Color(0xFFBA68C8)
    else -> Color(0xFF90A4AE)
}

@Suppress("unused")
private fun PlaybackErrorEntity.summary(): String = "$errorCategory: $errorMessage"
