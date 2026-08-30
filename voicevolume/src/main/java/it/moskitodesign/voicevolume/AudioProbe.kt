package it.moskitodesign.voicevolume

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager

/** Read-only probes: connection type (cable/wireless), active streams, devices. */
object AudioProbe {

    enum class Connection { CAVO, WIRELESS, ENTRAMBI, NON_DETERMINATO }

    data class State(
        val connection: Connection,
        val usb: Boolean,
        val btA2dp: Boolean,
        val btSco: Boolean,
        val outputs: List<String>,
        val activeUsages: List<String>,
    )

    fun snapshot(context: Context, audio: AudioManager): State {
        val outs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbDev = outs.any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val a2dp = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val sco = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

        val pluggedUsb = batteryPluggedUsb(context)
        val usb = usbDev || pluggedUsb

        val connection = when {
            usb && (a2dp || sco) -> Connection.ENTRAMBI
            usb -> Connection.CAVO
            a2dp || sco -> Connection.WIRELESS
            else -> Connection.NON_DETERMINATO
        }

        return State(
            connection = connection,
            usb = usb,
            btA2dp = a2dp,
            btSco = sco,
            outputs = outs.map { deviceTypeName(it.type) }.distinct(),
            activeUsages = activeUsages(audio),
        )
    }

    private fun batteryPluggedUsb(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_USB
    }

    fun activeUsages(audio: AudioManager): List<String> =
        audio.activePlaybackConfigurations.map { usageName(it.audioAttributes.usage) }.distinct()

    fun usageName(usage: Int): String = when (usage) {
        AudioAttributes.USAGE_MEDIA -> "Media"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "Voce chiamata"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "Guida navigazione"
        AudioAttributes.USAGE_ASSISTANT -> "Assistente"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "Accessibilità (TTS)"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "Suoni sistema"
        AudioAttributes.USAGE_NOTIFICATION -> "Notifica"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "Suoneria"
        AudioAttributes.USAGE_ALARM -> "Sveglia"
        AudioAttributes.USAGE_UNKNOWN -> "Sconosciuto"
        else -> "Usage $usage"
    }

    fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (media)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (HFP)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altoparlante interno"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Cuffie cablate"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset cablato"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Linea AUX"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Tipo $type"
    }
}
