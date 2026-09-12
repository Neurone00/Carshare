package io.github.neurone00.adblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neurone00.adblock.data.BlocklistSources
import io.github.neurone00.adblock.data.ListRepository
import io.github.neurone00.adblock.data.Prefs
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun ListsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ListRepository.status.collectAsStateWithLifecycle()
    val tick by Prefs.changes.collectAsStateWithLifecycle()
    val enabled = remember(tick) { Prefs.enabledSources }
    val customUrls = remember(tick) { Prefs.customUrls.toList().sorted() }
    val lastUpdate = remember(tick) { Prefs.lastUpdate }
    val ruleCounts = remember(tick, status.updating) {
        (BlocklistSources.all.map { it.id } + Prefs.customUrls).associateWith { Prefs.sourceRules(it) }
    }
    var newUrl by rememberSaveable { mutableStateOf("") }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    fun refresh(force: Boolean) = scope.launch { ListRepository.update(context, force) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Blocklists", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (lastUpdate > 0) "Last updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastUpdate))
                            else "Using the bundled list; tap Update to fetch the latest versions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { refresh(true) }, enabled = !status.updating) { Text("Update") }
                }
                if (status.updating) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    status.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                status.lastError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Problem: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        items(BlocklistSources.all, key = { it.id }) { src ->
            val on = src.id in enabled
            val rules = ruleCounts[src.id] ?: 0
            val downloaded = remember(src.id, rules) { ListRepository.hasFile(context, src.id) }
            ListItem(
                headlineContent = { Text(src.name) },
                supportingContent = {
                    Column {
                        Text(src.description)
                        val state = when {
                            downloaded && rules > 0 -> "${numbers.format(rules)} rules"
                            src.bundledAsset != null -> "bundled copy"
                            on -> "not downloaded yet"
                            else -> ""
                        }
                        if (state.isNotEmpty()) Text(state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                trailingContent = {
                    Switch(checked = on, onCheckedChange = { checked ->
                        Prefs.enabledSources = if (checked) enabled + src.id else enabled - src.id
                        refresh(false)
                    })
                },
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Custom list URLs", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Any hosts file, domain list or Adblock-style list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(customUrls, key = { it }) { url ->
            ListItem(
                headlineContent = { Text(url, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = {
                    val rules = ruleCounts[url] ?: 0
                    Text(if (rules > 0) "${numbers.format(rules)} rules" else "not downloaded yet", style = MaterialTheme.typography.labelSmall)
                },
                trailingContent = {
                    IconButton(onClick = {
                        ListRepository.removeCustomUrl(context, url)
                        scope.launch { ListRepository.rebuild(context) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val u = newUrl.trim()
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            Prefs.customUrls = Prefs.customUrls + u
                            newUrl = ""
                            refresh(false)
                        }
                    },
                    enabled = newUrl.trim().startsWith("http"),
                ) { Text("Add") }
            }
        }
    }
}
