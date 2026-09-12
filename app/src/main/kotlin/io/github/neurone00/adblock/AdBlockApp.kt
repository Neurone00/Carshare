package io.github.neurone00.adblock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.data.Stats
import io.github.neurone00.adblock.vpn.AdBlockVpnService
import io.github.neurone00.adblock.vpn.ListUpdateWorker

class AdBlockApp : Application() {
    companion object {
        const val CHANNEL_UPDATES = "updates"
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Stats.load()
        getSystemService(NotificationManager::class.java)?.let { nm ->
            nm.createNotificationChannel(
                NotificationChannel(
                    AdBlockVpnService.CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATES,
                    getString(R.string.notification_channel_updates),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        ListUpdateWorker.schedule(this)
    }
}
