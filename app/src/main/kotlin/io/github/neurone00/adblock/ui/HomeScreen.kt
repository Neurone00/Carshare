package io.github.neurone00.adblock.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neurone00.adblock.data.AppNames
import io.github.neurone00.adblock.data.ListRepository
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.data.Stats
import io.github.neurone00.adblock.vpn.AdBlockVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by AdBlockVpnService.running.collectAsStateWithLifecycle()
    val status by ListRepository.status.collectAsStateWithLifecycle()

    var blocked by remember { mutableLongStateOf(Stats.blocked.get()) }
    var queries by remember { mutableLongStateOf(Stats.queries.get()) }
    var log by remember { mutableStateOf(Stats.snapshot()) }
    var blockedOnly by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        ListRepository.ensureLoaded(context)
        while (true) {
            blocked = Stats.blocked.get()
            queries = Stats.queries.get()
            log = Stats.snapshot()
            delay(750)
        }
    }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            Prefs.enabled = true
            AdBlockVpnService.start(context)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun toggle() {
        if (running) {
            AdBlockVpnService.stop(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val consent = VpnService.prepare(context)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            Prefs.enabled = true
            AdBlockVpnService.start(context)
        }
    }

    val numbers = remember { NumberFormat.getIntegerInstance() }
    val visible = if (blockedOnly) log.filter { it.blocked } else log

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (running) "Ads are being blocked" else "Blocking is off",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            status.building -> "Loading blocklists…"
                            status.loaded -> "${numbers.format(status.blockedDomains)} blocked domains" +
                                (if (status.allowedDomains > 0) " · ${status.allowedDomains} allowed" else "")
                            else -> "Blocklists not loaded yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { toggle() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (running) "Turn off" else "Turn on")
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat(numbers.format(blocked), "blocked")
                        Stat(numbers.format(queries), "lookups")
                        val pct = if (queries > 0) (blocked * 100 / queries) else 0
                        Stat("$pct%", "of traffic")
                    }
                }
            }
        }
        if (status.updating) {
            item {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    status.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("What this blocks", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Every app's DNS lookups go through this filter, so banner, interstitial and " +
                            "most in-app ads, plus trackers and telemetry, never load. Nothing else is " +
                            "tunnelled, so there is no speed or battery cost worth noticing.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Not blockable this way: ads served from the same servers as the content, " +
                            "mainly YouTube in-stream ads and Instagram/Facebook feed ads. For YouTube " +
                            "use a client like NewPipe or a ReVanced-patched app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = blockedOnly, onClick = { blockedOnly = !blockedOnly }, label = { Text("Blocked only") })
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (running) "Waiting for DNS traffic…" else "Turn blocking on to see activity here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visible, key = { "${it.time}-${it.domain}-${it.uid}" }) { entry ->
            LogRow(entry, appName = AppNames.label(context, entry.uid)) {
                Prefs.addAllow(entry.domain)
                scope.launch { ListRepository.rebuild(context) }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LogRow(entry: Stats.Entry, appName: String?, onAllow: () -> Unit) {
    val time = remember(entry.time) { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.time)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.domain,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(appName, time, if (entry.blocked) "blocked" else "allowed").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.blocked) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}
