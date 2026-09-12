package io.github.neurone00.adblock.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neurone00.adblock.R
import io.github.neurone00.adblock.data.AppNames
import io.github.neurone00.adblock.data.ListRepository
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.data.Stats
import io.github.neurone00.adblock.update.Updater
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
    val update by Updater.state.collectAsStateWithLifecycle()

    var blocked by remember { mutableLongStateOf(Stats.blocked.get()) }
    var queries by remember { mutableLongStateOf(Stats.queries.get()) }
    var log by remember { mutableStateOf(Stats.snapshot()) }
    var blockedOnly by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        launch { ListRepository.ensureLoaded(context) }
        launch { if (Prefs.autoUpdateApp) Updater.check(context, manual = false) }
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HeroCard(
                running = running,
                subtitle = when {
                    status.building -> "Unfolding the umbrella…"
                    status.loaded -> "${numbers.format(status.blockedDomains)} ad & tracker domains covered"
                    else -> "Loading blocklists"
                },
                onToggle = { toggle() },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), numbers.format(blocked), "kept off you")
                StatTile(Modifier.weight(1f), numbers.format(queries), "lookups")
                val pct = if (queries > 0) (blocked * 100 / queries) else 0
                StatTile(Modifier.weight(1f), "$pct%", "of traffic")
            }
        }
        val u = update
        if (u is Updater.State.Available || u is Updater.State.Downloading || u is Updater.State.Installing) {
            item { UpdateCard(u) { scope.launch { (u as? Updater.State.Available)?.let { Updater.downloadAndInstall(context, it.info) } } } }
        }
        if (status.updating) {
            item {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                    status.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("What the umbrella covers", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Every app's lookups pass under here, so banners, pop-ups, most in-app ads, " +
                            "trackers and telemetry never load. Only DNS is handled, so nothing gets slower.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "What still gets through: ads baked into the content stream itself, mainly YouTube " +
                            "video ads and Instagram/Facebook feed ads. For YouTube, NewPipe or ReVanced is the fix.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent drops", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = blockedOnly, onClick = { blockedOnly = !blockedOnly }, label = { Text("Blocked only") })
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (running) "Quiet so far. Open an app and watch the drops bounce off." else "Open the umbrella to see activity here.",
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
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun HeroCard(running: Boolean, subtitle: String, onToggle: () -> Unit) {
    val bob = rememberInfiniteTransition(label = "bob")
    val offset by bob.animateFloat(
        initialValue = 0f, targetValue = if (running) -6f else 0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "bobOffset",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(if (running) Brand.OpenGradient else Brand.ClosedGradient)
            .padding(24.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(148.dp)
                    .graphicsLayer { translationY = offset; rotationZ = if (running) 0f else -18f },
            )
            Text(
                if (running) "You're covered" else "Umbrella closed",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (running) "It's raining ads out there. Not on you." else "Ads are falling. Open the umbrella.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.92f),
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(18.dp))
            val buttonColor by animateColorAsState(if (running) Color.White else Brand.SkyDeep, label = "btn")
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = if (running) Brand.SkyDeep else Color.White,
                ),
            ) {
                Text(if (running) "Close umbrella" else "Open umbrella", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, value: String, label: String) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateCard(state: Updater.State, onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is Updater.State.Available -> {
                    Text("A newer Adbrella is out: ${state.info.versionName}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstall) { Text("Update now") }
                }
                is Updater.State.Downloading -> {
                    Text("Downloading ${state.info.versionName}… ${state.percent}%", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is Updater.State.Installing -> {
                    Text("Installing ${state.info.versionName}…", style = MaterialTheme.typography.titleMedium)
                    Text("If Android asks, tap Update.", style = MaterialTheme.typography.bodySmall)
                }
                else -> Unit
            }
        }
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
                listOfNotNull(appName, time, if (entry.blocked) "bounced" else "allowed").joinToString(" · "),
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
