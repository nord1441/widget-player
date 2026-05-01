package com.example.shuffleplayer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shuffleplayer.R
import com.example.shuffleplayer.data.ErrorLogDb
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { ErrorLogDb.get(context).errorLogDao() }
    val entries by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val timeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.settings_back))
                }
                Text(
                    text = stringResource(R.string.errors_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedButton(onClick = {
                    scope.launch { dao.clear() }
                }) { Text(stringResource(R.string.errors_clear)) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.errors_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn {
                    items(entries, key = { it.id }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.errorCategory,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorFor(entry.errorCategory),
                                )
                                Text(
                                    text = timeFmt.format(Date(entry.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = entry.trackLabel ?: entry.uri,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = entry.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
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
