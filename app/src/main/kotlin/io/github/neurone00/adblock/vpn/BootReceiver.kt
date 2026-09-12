package io.github.neurone00.adblock.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import io.github.neurone00.adblock.data.Prefs

/** Brings blocking back after a reboot or an app update, if the user left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.enabled || !Prefs.autoStart) return
        if (VpnService.prepare(context) != null) return // consent was revoked; the UI will ask again
        AdBlockVpnService.start(context)
    }
}
