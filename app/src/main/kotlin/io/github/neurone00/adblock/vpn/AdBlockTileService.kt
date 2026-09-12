package io.github.neurone00.adblock.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.neurone00.adblock.MainActivity
import io.github.neurone00.adblock.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick-settings tile: one tap toggles blocking from the notification shade. */
class AdBlockTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        job = s.launch { AdBlockVpnService.running.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel(); job = null
        scope?.cancel(); scope = null
    }

    override fun onClick() {
        if (AdBlockVpnService.running.value) {
            AdBlockVpnService.stop(this)
            render(false)
            return
        }
        if (VpnService.prepare(this) != null) {
            // Consent dialog needed: open the app.
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        Prefs.enabled = true
        AdBlockVpnService.start(this)
        render(true)
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (active) "Blocking" else "Off"
        tile.updateTile()
    }
}
