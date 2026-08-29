package it.moskitodesign.voicevolume

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    // (label, stream constant)
    private val streams = listOf(
        "Voce chiamata / HFP (VOICE_CALL)" to AudioManager.STREAM_VOICE_CALL,
        "Media / Musica (MUSIC)" to AudioManager.STREAM_MUSIC,
        "Notifiche (NOTIFICATION)" to AudioManager.STREAM_NOTIFICATION,
        "Sistema (SYSTEM)" to AudioManager.STREAM_SYSTEM,
        "Sveglia (ALARM)" to AudioManager.STREAM_ALARM,
        "Suoneria (RING)" to AudioManager.STREAM_RING,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = Prefs(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Aggancia il volume della voce a quello media"
            textSize = 20f
        })

        // --- Target stream picker ---
        root.addView(spacer())
        root.addView(TextView(this).apply { text = "Stream della voce da regolare:" })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                streams.map { it.first }
            )
            setSelection(streams.indexOfFirst { it.second == prefs.targetStream }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.targetStream = streams[pos].second
                    refresh()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(spinner)

        // --- Multiplier ---
        root.addView(spacer())
        val multLabel = TextView(this)
        root.addView(multLabel)
        val seek = SeekBar(this).apply {
            max = 150            // maps to 50..200 %
            progress = prefs.multiplierPercent - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.multiplierPercent = p + 50
                    multLabel.text = "Fattore volume voce: ${prefs.multiplierPercent}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        multLabel.text = "Fattore volume voce: ${prefs.multiplierPercent}%"
        root.addView(seek)

        // --- Enable switch ---
        root.addView(spacer())
        val sw = Switch(this).apply {
            text = "Sincronizzazione attiva (servizio)"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, checked ->
                prefs.enabled = checked
                if (checked) {
                    ensureNotificationPermission()
                    VolumeSyncService.start(this@MainActivity)
                } else {
                    VolumeSyncService.stop(this@MainActivity)
                }
                refresh()
            }
        }
        root.addView(sw)

        // --- Test now ---
        root.addView(spacer())
        root.addView(Button(this).apply {
            text = "Applica ora (una volta)"
            setOnClickListener { applyOnce(); refresh() }
        })

        // --- Status ---
        root.addView(spacer())
        status = TextView(this).apply { textSize = 13f }
        root.addView(status)

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun applyOnce() {
        val target = prefs.targetStream
        val mult = prefs.multiplierPercent / 100f
        val mediaCur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetMax = audio.getStreamMaxVolume(target).coerceAtLeast(1)
        val desired = ((mediaCur.toFloat() / mediaMax) * mult * targetMax)
            .toInt().coerceIn(0, targetMax)
        runCatching { audio.setStreamVolume(target, desired, 0) }
    }

    private fun refresh() {
        val target = prefs.targetStream
        val label = streams.firstOrNull { it.second == target }?.first ?: "?"
        status.text = buildString {
            append("Media: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/")
            append("${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}\n")
            append("Target ($label): ")
            append("${audio.getStreamVolume(target)}/${audio.getStreamMaxVolume(target)}\n")
            append("Servizio: ${if (prefs.enabled) "attivo" else "spento"}")
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (12 * resources.displayMetrics.density).toInt()
        )
    }
}
