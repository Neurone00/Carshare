package it.moskitodesign.voicevolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Keeps a chosen "voice" audio stream aligned to the media (STREAM_MUSIC)
 * volume, so the Android Auto guidance voice tracks the media knob.
 *
 * Non-root: uses AudioManager.setStreamVolume. Which stream to target
 * (VOICE_CALL for HFP routing, MUSIC for projection routing, etc.) is
 * chosen by the user and read from Prefs.
 */
class VolumeSyncService : Service() {

    private lateinit var audio: AudioManager
    private var enhancer: LoudnessEnhancer? = null

    /** Broadcast fired by the system whenever any stream volume changes. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VOLUME_CHANGED_ACTION) sync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startForeground(NOTIF_ID, buildNotification())
        ContextCompat.registerReceiver(
            this, volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        sync() // align immediately on start
        applyBoost()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sync()
        applyBoost()
        return START_STICKY
    }

    /** Apply the LoudnessEnhancer gain (global output mix) from prefs. */
    private fun applyBoost() {
        val gain = Prefs(this).boostGainMb
        runCatching {
            if (enhancer == null) enhancer = LoudnessEnhancer(0) // 0 = global output mix
            enhancer?.setTargetGain(gain)
            enhancer?.enabled = gain > 0
        }
    }

    /** Set the target stream proportionally to the current media volume. */
    private fun sync() {
        val prefs = Prefs(this)
        val targetStream = prefs.targetStream
        val multiplier = prefs.multiplierPercent / 100f

        val mediaCur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetMax = audio.getStreamMaxVolume(targetStream).coerceAtLeast(1)

        // Safety ceiling: never exceed this fraction of the stream max.
        val cap = (targetMax * prefs.safetyCapPercent / 100f).roundToInt().coerceIn(0, targetMax)

        val fraction = (mediaCur.toFloat() / mediaMax) * multiplier
        val desired = (fraction * targetMax).roundToInt().coerceIn(0, cap)

        if (audio.getStreamVolume(targetStream) != desired) {
            runCatching { audio.setStreamVolume(targetStream, desired, 0) }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(volumeReceiver) }
        runCatching { enhancer?.release() }
        enhancer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "voice_volume_sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
    }

    companion object {
        // Hidden but broadcast system action; reliable across vendors.
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, VolumeSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VolumeSyncService::class.java))
        }
    }
}
