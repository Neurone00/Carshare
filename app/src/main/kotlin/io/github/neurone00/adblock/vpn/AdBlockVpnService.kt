package io.github.neurone00.adblock.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.neurone00.adblock.MainActivity
import io.github.neurone00.adblock.R
import io.github.neurone00.adblock.core.net.UdpDatagram
import io.github.neurone00.adblock.data.ListRepository
import io.github.neurone00.adblock.data.Prefs
import io.github.neurone00.adblock.data.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.NumberFormat

/**
 * Local VPN that captures only DNS traffic. Every app's lookups are answered by
 * [TunLoop]: ad/tracker domains get a null address, everything else is
 * forwarded to a real resolver. No other traffic is tunnelled.
 */
class AdBlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var loop: TunLoop? = null
    @Volatile private var upstreams: List<InetSocketAddress> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val cm: ConnectivityManager? by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.enabled = false
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                startAsForeground()
                shutdown(keepForeground = true)
                if (!establish()) { stopSelf(); return START_NOT_STICKY }
                return START_STICKY
            }
            else -> { // ACTION_START, always-on VPN, or a sticky restart
                startAsForeground()
                if (tun == null && !establish()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        // Another VPN took over, or the user disconnected from system settings.
        Prefs.enabled = false
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun establish(): Boolean {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS, 24)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        // Also capture apps that hard-code a public resolver instead of using the system one.
        for (ip in HARDCODED_RESOLVERS) builder.addRoute(ip, 32)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        // Our own downloads never need to go through the tunnel.
        try { builder.addDisallowedApplication(packageName) } catch (_: PackageManager.NameNotFoundException) {}
        for (pkg in Prefs.bypassApps) {
            try { builder.addDisallowedApplication(pkg) } catch (_: PackageManager.NameNotFoundException) {}
        }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() threw", e)
            null
        }
        if (pfd == null) {
            Log.w(TAG, "VPN not established (permission missing or revoked)")
            return false
        }
        tun = pfd
        refreshUpstreams()
        loop = TunLoop(this, pfd, { upstreams }, { ListRepository.filter }, ::ownerUid).also { it.start() }
        running.value = true
        registerNetworkCallback()

        jobs = scope.launch {
            launch { ListRepository.ensureLoaded(applicationContext) }
            launch { Prefs.changes.collect { refreshUpstreams() } }
            launch {
                var lastShown = -1L
                var lastPersist = System.currentTimeMillis()
                while (isActive) {
                    delay(15_000)
                    val blocked = Stats.blocked.get()
                    if (blocked != lastShown) {
                        lastShown = blocked
                        getSystemService(android.app.NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, buildNotification())
                    }
                    if (System.currentTimeMillis() - lastPersist > 60_000) {
                        Stats.persist(); lastPersist = System.currentTimeMillis()
                    }
                }
            }
        }
        Log.i(TAG, "VPN established; upstreams=$upstreams")
        return true
    }

    private fun shutdown(keepForeground: Boolean = false) {
        jobs?.cancel(); jobs = null
        unregisterNetworkCallback()
        loop?.stop(); loop = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        running.value = false
        Stats.persist()
        if (!keepForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun refreshUpstreams() {
        upstreams = UpstreamDns.resolve(this)
    }

    private fun registerNetworkCallback() {
        val cm = cm ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshUpstreams()
            override fun onLost(network: Network) = refreshUpstreams()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshUpstreams()
        }
        try {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback registration failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    /** Which app sent this DNS query (Android 10+, VPN apps only). -1 when unknown. */
    private fun ownerUid(d: UdpDatagram): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val cm = cm ?: return -1
        return try {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(d.srcIp), d.srcPort),
                InetSocketAddress(InetAddress.getByAddress(d.dstIp), d.dstPort),
            )
        } catch (_: Exception) {
            -1
        }
    }

    // ---- foreground notification ---------------------------------------

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val count = NumberFormat.getIntegerInstance().format(Stats.blocked.get())
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, count))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val TAG = "AdBlockVpn"
        const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "io.github.neurone00.adblock.START"
        const val ACTION_STOP = "io.github.neurone00.adblock.STOP"
        const val ACTION_RESTART = "io.github.neurone00.adblock.RESTART"

        const val TUN_PREFIX = "10.111.222."
        const val TUN_ADDRESS = "10.111.222.2"
        const val DNS_ADDRESS = "10.111.222.1"
        private const val MTU = 8192

        /** Public resolvers some apps talk to directly; routed into the tunnel so they get filtered too. */
        private val HARDCODED_RESOLVERS = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15", "76.76.2.0", "76.76.10.0",
        )

        /** True while the tunnel is up. */
        val running = MutableStateFlow(false)

        fun start(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Re-creates the tunnel (e.g. after the bypass-apps list changed). No-op when not running. */
        fun restartIfRunning(context: Context) {
            if (!running.value) return
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
