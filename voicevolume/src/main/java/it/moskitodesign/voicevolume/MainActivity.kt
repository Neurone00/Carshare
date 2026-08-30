package it.moskitodesign.voicevolume

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.moskitodesign.voicevolume.databinding.ActivityMainBinding
import it.moskitodesign.voicevolume.databinding.RowStreamBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private lateinit var tester: StreamTester
    private val handler = Handler(Looper.getMainLooper())
    private val rows = mutableListOf<Pair<Int, RowStreamBinding>>()

    private val streams = listOf(
        "Voce chiamata / HFP" to AudioManager.STREAM_VOICE_CALL,
        "Media / Musica" to AudioManager.STREAM_MUSIC,
        "Accessibilità (lettura TTS)" to AudioManager.STREAM_ACCESSIBILITY,
        "Notifiche" to AudioManager.STREAM_NOTIFICATION,
        "Sistema" to AudioManager.STREAM_SYSTEM,
        "Sveglia" to AudioManager.STREAM_ALARM,
        "Suoneria" to AudioManager.STREAM_RING,
    )

    private val playbackCb = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
            runOnUiThread { updateActive() }
        }
    }

    /** Live feedback: which stream index changed (e.g. when turning the car knob). */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VolumeSyncService.VOLUME_CHANGED_ACTION) return
            val type = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val value = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val label = streams.firstOrNull { it.second == type }?.first ?: "stream $type"
            b.tvLastChange.text = "Ultimo cambio: $label → $value"
            refreshAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = Prefs(this)
        tester = StreamTester(this)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupMaster()
        buildStreamRows()
        setupMultiplier()
        setupTestButtons()
        setupUpdater()

        b.btnRefreshConn.setOnClickListener { refreshConnection() }
        b.btnRefreshDiag.setOnClickListener { refreshDiag() }
        b.btnDnd.setOnClickListener {
            if (hasDndAccess()) toast("Controllo volumi sistema già abilitato")
            else requestDndAccess()
        }

        refreshAll()
        animateIn()
        autoCheckOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        audio.registerAudioPlaybackCallback(playbackCb, handler)
        ContextCompat.registerReceiver(
            this, volumeReceiver,
            IntentFilter(VolumeSyncService.VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshAll()
    }

    override fun onPause() {
        audio.unregisterAudioPlaybackCallback(playbackCb)
        runCatching { unregisterReceiver(volumeReceiver) }
        super.onPause()
    }

    override fun onDestroy() {
        tester.release()
        super.onDestroy()
    }

    // ---------- output rows ----------

    private fun buildStreamRows() {
        val inflater = LayoutInflater.from(this)
        for ((label, s) in streams) {
            val row = RowStreamBinding.inflate(inflater, b.streamsContainer, false)
            row.tvStreamLabel.text = label
            val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
            row.sliderVol.valueFrom = 0f
            row.sliderVol.valueTo = max.toFloat()
            row.sliderVol.value = audio.getStreamVolume(s).coerceIn(0, max).toFloat()
            row.tvStreamValue.text = "${audio.getStreamVolume(s)}/$max"
            row.sliderVol.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    setStreamChecked(s, value.toInt(), label)
                    row.tvStreamValue.text = "${audio.getStreamVolume(s)}/$max"
                }
            }
            row.btnTest.setOnClickListener { tester.tone(s) }
            rows.add(s to row)
            b.streamsContainer.addView(row.root)
        }
    }

    // ---------- system-volume permission & checked writes ----------

    private val dndGatedStreams = setOf(
        AudioManager.STREAM_SYSTEM, AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM
    )
    private var dndPrompted = false

    /** Set a stream volume and surface what actually happened (success / blocked). */
    private fun setStreamChecked(stream: Int, target: Int, label: String) {
        val res = runCatching { audio.setStreamVolume(stream, target, 0) }
        val actual = audio.getStreamVolume(stream)
        when {
            res.isFailure -> {
                if (stream in dndGatedStreams && !hasDndAccess()) promptDnd(label)
                else toast("$label: modifica non permessa")
            }
            actual != target -> {
                if (stream in dndGatedStreams && !hasDndAccess()) promptDnd(label)
                else toast("$label: bloccato a $actual (stream fisso/aliased)")
            }
        }
    }

    private fun hasDndAccess(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun promptDnd(label: String) {
        if (dndPrompted) return
        dndPrompted = true
        toast("Per alzare \"$label\" serve l'accesso Non disturbare")
        requestDndAccess()
    }

    private fun requestDndAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- master media ----------

    private fun setupMaster() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        b.sliderMaster.valueFrom = 0f
        b.sliderMaster.valueTo = max.toFloat()
        b.sliderMaster.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max).toFloat()
        b.tvMaster.text = "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/$max"
        b.sliderMaster.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val cap = (max * prefs.safetyCapPercent / 100f).toInt().coerceIn(0, max)
            val v = value.toInt().coerceIn(0, cap)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
            b.tvMaster.text = "$v/$max"
            if (v.toFloat() != value) b.sliderMaster.value = v.toFloat() // enforce cap
        }
    }

    // ---------- multiplier ----------

    private fun setupMultiplier() {
        b.spinnerVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, streams.map { it.first }
        )
        b.spinnerVoice.setSelection(
            streams.indexOfFirst { it.second == prefs.targetStream }.coerceAtLeast(0)
        )
        b.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.targetStream = streams[pos].second
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val start = ((prefs.multiplierPercent / 5) * 5).coerceIn(50, 200)
        b.sliderMult.value = start.toFloat()
        b.tvMult.text = "Fattore: $start%"
        b.sliderMult.addOnChangeListener { _, value, _ ->
            prefs.multiplierPercent = value.toInt()
            b.tvMult.text = "Fattore: ${value.toInt()}%"
        }

        val cap = prefs.safetyCapPercent.coerceIn(10, 100)
        b.sliderCap.value = ((cap / 5) * 5).coerceIn(10, 100).toFloat()
        b.tvCap.text = "Limite di sicurezza: $cap%"
        b.sliderCap.addOnChangeListener { _, value, _ ->
            prefs.safetyCapPercent = value.toInt()
            b.tvCap.text = "Limite di sicurezza: ${value.toInt()}%"
        }

        b.swSync.isChecked = prefs.enabled
        b.swSync.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            if (checked) { ensureNotificationPermission(); VolumeSyncService.start(this) }
            else VolumeSyncService.stop(this)
        }

        b.btnApplyOnce.setOnClickListener { applyMultiplierOnce(); refreshAll() }
        b.btnSpeakTest.setOnClickListener {
            tester.speak(prefs.targetStream, "Prova voce di sistema Carshare")
        }
    }

    private fun applyMultiplierOnce() {
        val target = prefs.targetStream
        val mult = prefs.multiplierPercent / 100f
        val mediaCur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetMax = audio.getStreamMaxVolume(target).coerceAtLeast(1)
        val cap = (targetMax * prefs.safetyCapPercent / 100f).toInt().coerceIn(0, targetMax)
        val desired = ((mediaCur.toFloat() / mediaMax) * mult * targetMax)
            .toInt().coerceIn(0, cap)
        val label = streams.firstOrNull { it.second == target }?.first ?: "voce"
        setStreamChecked(target, desired, label)
    }

    // ---------- test protocol ----------

    private fun setupTestButtons() {
        b.btnTestCavo.setOnClickListener { runProtocol(TestProtocol.Mode.CAVO) }
        b.btnTestWireless.setOnClickListener { runProtocol(TestProtocol.Mode.WIRELESS) }
    }

    private fun runProtocol(mode: TestProtocol.Mode) {
        val steps = TestProtocol.steps(mode, prefs.targetStream)
        val results = mutableListOf<TestProtocol.StepResult>()

        fun finish() {
            val detected = AudioProbe.snapshot(this, audio).connection
            b.tvTestReport.text = TestProtocol.buildReport(mode, detected, results)
        }

        fun show(index: Int) {
            if (index >= steps.size) { finish(); return }
            val step = steps[index]

            // Perform the step's action.
            if (step.stream != null) {
                if (step.tts) tester.speak(step.stream, "Prova voce di guida Carshare")
                else tester.tone(step.stream)
            }
            val evidence = when {
                index == 0 -> AudioProbe.snapshot(this, audio).connection.toString()
                step.stream != null -> AudioProbe.activeUsages(audio).joinToString().ifEmpty { "—" }
                else -> ""
            }

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(step.title)
                .setMessage(step.instruction)
                .setCancelable(false)

            if (step.stream != null && !step.tts) {
                dialog.setNeutralButton("Ripeti") { _, _ -> show(index) }
            }
            if (step.stream != null || index == steps.size - 1) {
                dialog.setPositiveButton("Sentito ✓") { _, _ ->
                    results.add(TestProtocol.StepResult(step, true, evidence)); show(index + 1)
                }
                dialog.setNegativeButton("Non sentito ✗") { _, _ ->
                    results.add(TestProtocol.StepResult(step, false, evidence)); show(index + 1)
                }
            } else {
                dialog.setPositiveButton("Prosegui") { _, _ ->
                    results.add(TestProtocol.StepResult(step, null, evidence)); show(index + 1)
                }
            }
            dialog.show()
        }

        show(0)
    }

    // ---------- updater ----------

    private fun setupUpdater() {
        b.etUrl.setText(prefs.updateUrl)
        val pkg = packageManager.getPackageInfo(packageName, 0)
        b.tvUpdate.text = "Versione installata: ${pkg.versionName} (${Updater.currentVersionCode(this)})"
        b.swAuto.isChecked = prefs.autoUpdate
        b.swAuto.setOnCheckedChangeListener { _, checked -> prefs.autoUpdate = checked }
        b.btnCheckUpdate.setOnClickListener {
            prefs.updateUrl = b.etUrl.text?.toString()?.trim() ?: ""
            b.tvUpdate.text = "Controllo…"
            Updater.check(this) { r -> onUpdateResult(r, auto = false) }
        }
    }

    /** Silent check on launch: if enabled and an update exists, install it. */
    private fun autoCheckOnLaunch() {
        if (!prefs.autoUpdate || prefs.updateUrl.isBlank()) return
        Updater.check(this) { r -> onUpdateResult(r, auto = true) }
    }

    private fun onUpdateResult(r: Updater.Result, auto: Boolean) {
        when {
            r.error != null -> { if (!auto) b.tvUpdate.text = "Errore: ${r.error}"; return }
            !r.hasUpdate -> { if (!auto) b.tvUpdate.text = "Sei aggiornato (${r.versionName ?: "?"})"; return }
        }
        val apk = r.apkUrl
        if (auto && apk != null) {
            b.tvUpdate.text = "Aggiornamento ${r.versionName}: scarico…"
            Updater.downloadAndInstall(this, apk) { msg -> b.tvUpdate.text = msg }
            return
        }
        b.tvUpdate.text = "Disponibile ${r.versionName}\n${r.notes ?: ""}"
        if (apk == null) return
        val btn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Scarica e installa ${r.versionName}"
            setOnClickListener {
                Updater.downloadAndInstall(this@MainActivity, apk) { msg -> b.tvUpdate.text = msg }
            }
        }
        b.updateInner.addView(btn)
    }

    // ---------- refresh & diagnostics ----------

    private fun refreshAll() {
        refreshConnection()
        refreshDiag()
        val mmax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        b.sliderMaster.valueTo = mmax.toFloat()
        b.sliderMaster.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, mmax).toFloat()
        b.tvMaster.text = "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/$mmax"
        for ((s, row) in rows) {
            val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
            row.sliderVol.valueTo = max.toFloat()
            row.sliderVol.value = audio.getStreamVolume(s).coerceIn(0, max).toFloat()
            row.tvStreamValue.text = "${audio.getStreamVolume(s)}/$max"
        }
        updateActive()
    }

    private fun refreshConnection() {
        val st = AudioProbe.snapshot(this, audio)
        b.tvConnection.text = buildString {
            append("Rilevata: ${st.connection}\n")
            append("USB: ${yn(st.usb)}   BT A2DP: ${yn(st.btA2dp)}   BT SCO: ${yn(st.btSco)}\n")
            append("Uscite: ${st.outputs.joinToString().ifEmpty { "—" }}")
        }
    }

    private fun refreshDiag() {
        b.tvDiag.text = buildString {
            append("Mode audio: ${audio.mode}\n")
            @Suppress("DEPRECATION")
            append("Musica attiva: ${audio.isMusicActive}\n\n")
            append("Volumi stream:\n")
            for ((label, s) in streams) {
                append("  $label: ${audio.getStreamVolume(s)}/${audio.getStreamMaxVolume(s)}\n")
            }
        }
    }

    private fun updateActive() {
        val usages = AudioProbe.activeUsages(audio)
        b.tvActive.text = "In riproduzione: ${usages.joinToString().ifEmpty { "—" }}"
    }

    private fun yn(v: Boolean) = if (v) "sì" else "no"

    // ---------- misc ----------

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun animateIn() {
        val group = b.content
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.alpha = 0f
            child.translationY = 24f
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L * i)
                .setDuration(280L)
                .start()
        }
    }
}
