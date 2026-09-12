package io.github.neurone00.adblock.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.vpn.AdBlockVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppRow(val label: String, val pkg: String, val icon: ImageBitmap?)

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val tick by Prefs.changes.collectAsStateWithLifecycle()
    val bypass = remember(tick) { Prefs.bypassApps }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .mapNotNull { pkg ->
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        val icon = try { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
                        AppRow(pm.getApplicationLabel(info).toString(), pkg, icon)
                    } catch (_: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("Bypass apps", style = MaterialTheme.typography.titleLarge)
            Text(
                "Ticked apps use the normal, unfiltered DNS. Use this only for an app that " +
                    "refuses to work with blocking on (some banking apps check for VPNs).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val list = apps
        if (list == null) {
            LinearProgressIndicator(Modifier.padding(horizontal = 16.dp))
            return@Column
        }
        LazyColumn {
            items(list, key = { it.pkg }) { app ->
                val checked = app.pkg in bypass
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.pkg, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = {
                        app.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp)) }
                    },
                    trailingContent = {
                        Checkbox(checked = checked, onCheckedChange = { on ->
                            Prefs.bypassApps = if (on) bypass + app.pkg else bypass - app.pkg
                            AdBlockVpnService.restartIfRunning(context)
                        })
                    },
                )
            }
        }
    }
}
