package it.moskitodesign.voicevolume

import android.content.Context
import android.media.AudioManager

/** Simple SharedPreferences-backed config. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("voice_volume", Context.MODE_PRIVATE)

    /** Which stream the guidance voice actually uses (see MainActivity picker). */
    var targetStream: Int
        get() = sp.getInt(KEY_STREAM, AudioManager.STREAM_VOICE_CALL)
        set(value) = sp.edit().putInt(KEY_STREAM, value).apply()

    /** Boost factor relative to media volume, in percent (100 = same fraction). */
    var multiplierPercent: Int
        get() = sp.getInt(KEY_MULT, 100)
        set(value) = sp.edit().putInt(KEY_MULT, value).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        private const val KEY_STREAM = "target_stream"
        private const val KEY_MULT = "multiplier_percent"
        private const val KEY_ENABLED = "enabled"
    }
}
