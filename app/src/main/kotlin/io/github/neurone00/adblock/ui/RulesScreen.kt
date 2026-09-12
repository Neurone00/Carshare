package io.github.neurone00.adblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.neurone00.adblock.data.ListRepository
import io.github.neurone00.adblock.data.Prefs
import kotlinx.coroutines.launch

@Composable
fun RulesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var block by rememberSaveable { mutableStateOf(Prefs.customBlock) }
    var allow by rememberSaveable { mutableStateOf(Prefs.customAllow) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val dirty = block != Prefs.customBlock || allow != Prefs.customAllow

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your rules", style = MaterialTheme.typography.titleLarge)
        Text(
            "One domain per line. A domain also covers all of its subdomains. " +
                "Allow rules win over every blocklist, so use them to fix an app that stopped working.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = block,
            onValueChange = { block = it; saved = false },
            label = { Text("Always block") },
            placeholder = { Text("ads.example.com\ntelemetry.example.net") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = allow,
            onValueChange = { allow = it; saved = false },
            label = { Text("Always allow") },
            placeholder = { Text("cdn.example.com") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                Prefs.customBlock = block
                Prefs.customAllow = allow
                saved = true
                scope.launch { ListRepository.rebuild(context) }
            },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved && !dirty) "Saved" else "Save and apply") }
    }
}
