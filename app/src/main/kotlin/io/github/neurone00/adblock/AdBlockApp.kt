package io.github.neurone00.adblock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.data.Stats
import io.github.neurone00.adblock.vpn.AdBlockVpnService
import io.github.neurone00.adblock.vpn.ListUpdateWorker

class AdBlockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Stats.load()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                AdBlockVpnService.CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        ListUpdateWorker.schedule(this)
    }
}
