package it.moskitodesign.voicevolume

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * On-demand audio tests: a tone on a chosen stream, or a spoken TTS phrase
 * routed to the usage matching that stream. Used to identify routing/volume
 * per stream ("riproduci voce di sistema", ecc.).
 */
class StreamTester(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Play a ~600ms beep on the given AudioManager stream at full test level. */
    fun tone(streamType: Int) {
        val tg = runCatching { ToneGenerator(streamType, ToneGenerator.MAX_VOLUME) }.getOrNull()
            ?: return
        tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        main.postDelayed({ runCatching { tg.release() } }, 800)
    }

    /** Speak [text] routed to the usage that matches [streamType]. */
    fun speak(streamType: Int, text: String, onDone: (() -> Unit)? = null) {
        val attrs = AudioAttributes.Builder()
            .setUsage(usageForStream(streamType))
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.setAudioAttributes(attrs)
                    doSpeak(text, onDone)
                }
            }
        } else if (ttsReady) {
            tts?.setAudioAttributes(attrs)
            doSpeak(text, onDone)
        }
    }

    private fun doSpeak(text: String, onDone: (() -> Unit)?) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
        onDone?.let { main.postDelayed(it, 1500) }
    }

    fun release() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    private fun usageForStream(streamType: Int): Int = when (streamType) {
        AudioManager.STREAM_VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioManager.STREAM_MUSIC -> AudioAttributes.USAGE_MEDIA
        AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
        AudioManager.STREAM_SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        AudioManager.STREAM_ALARM -> AudioAttributes.USAGE_ALARM
        AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        AudioManager.STREAM_ACCESSIBILITY -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        else -> AudioAttributes.USAGE_MEDIA
    }
}
