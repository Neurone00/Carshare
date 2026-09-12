package io.github.neurone00.adblock.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.data.Stats
import io.github.neurone00.adblock.update.Updater
import io.github.neurone00.adblock.vpn.UpstreamDns
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick by Prefs.changes.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val upstream = remember(tick) { Prefs.upstream }
    val autoUpdateApp = remember(tick) { Prefs.autoUpdateApp }
    val autoStart = remember(tick) { Prefs.autoStart }
    val autoUpdate = remember(tick) { Prefs.autoUpdate }
    val isCustom = UpstreamDns.PROVIDERS.none { it.id == upstream }
    var customIp by rememberSaveable { mutableStateOf(if (isCustom) upstream else "") }

    fun open(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section("Updates")
        ListItem(
            headlineContent = { Text("Update Adbrella automatically") },
            supportingContent = { Text("Checks the release page daily and installs new builds. Version ${Updater.currentVersionName(context)} installed.") },
            trailingContent = { Switch(checked = autoUpdateApp, onCheckedChange = { Prefs.autoUpdateApp = it }) },
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = update
            Button(
                onClick = {
                    scope.launch {
                        val info = Updater.check(context, manual = true)
                        if (info != null) Updater.downloadAndInstall(context, info)
                    }
                },
                enabled = u !is Updater.State.Checking && u !is Updater.State.Downloading && u !is Updater.State.Installing,
            ) { Text("Check for updates") }
            Text(
                when (u) {
                    is Updater.State.Idle -> ""
                    is Updater.State.Checking -> "Checking…"
                    is Updater.State.UpToDate -> "You're on the latest build."
                    is Updater.State.Available -> "Update ${u.info.versionName} available"
                    is Updater.State.Downloading -> "Downloading ${u.percent}%"
                    is Updater.State.Installing -> "Installing…"
                    is Updater.State.Error -> u.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (u is Updater.State.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Upstream DNS")
        Text(
            "Where allowed lookups are sent. Automatic uses whatever your Wi-Fi or mobile network provides.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        UpstreamDns.PROVIDERS.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = upstream == p.id, onClick = { Prefs.upstream = p.id })
                Column {
                    Text(p.name)
                    if (p.addresses.isNotEmpty()) Text(p.addresses.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = isCustom, onClick = { if (customIp.isNotBlank()) Prefs.upstream = customIp.trim() })
            OutlinedTextField(
                value = customIp,
                onValueChange = { customIp = it },
                label = { Text("Custom resolver IP") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (customIp.isNotBlank()) Prefs.upstream = customIp.trim() }, enabled = customIp.isNotBlank()) { Text("Use") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Behaviour")
        ListItem(
            headlineContent = { Text("Start on boot") },
            supportingContent = { Text("Restore blocking after a restart. For a guarantee, also enable Always-on VPN below.") },
            trailingContent = { Switch(checked = autoStart, onCheckedChange = { Prefs.autoStart = it }) },
        )
        ListItem(
            headlineContent = { Text("Update lists daily") },
            supportingContent = { Text("On Wi-Fi, when the battery is not low.") },
            trailingContent = { Switch(checked = autoUpdate, onCheckedChange = { Prefs.autoUpdate = it }) },
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Make it stick (Samsung)")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "One UI is aggressive about killing background apps. These three settings keep the blocker alive:",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("1. Exempt from battery optimisation") }
            OutlinedButton(
                onClick = { open(Intent(Settings.ACTION_VPN_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("2. Always-on VPN (gear icon next to Adbrella)") }
            OutlinedButton(
                onClick = { open(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("3. Private DNS → Off") }
            Text(
                "Private DNS lives under Connections › More connection settings. If it is set to a provider, " +
                    "Android encrypts lookups straight to that provider and skips this filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Statistics")
        TextButton(onClick = { Stats.reset() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Reset counters") }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
