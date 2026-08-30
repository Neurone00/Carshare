package it.moskitodesign.voicevolume

import android.media.AudioManager

/**
 * Guided validation protocols for the two connection modes (cable / wireless).
 * Each protocol steps through the relevant streams; the user confirms whether
 * each test was audible, and a report with a verdict is produced.
 */
object TestProtocol {

    enum class Mode(val label: String, val expected: AudioProbe.Connection) {
        CAVO("Cavo (USB)", AudioProbe.Connection.CAVO),
        WIRELESS("Wireless (BT/Wi-Fi)", AudioProbe.Connection.WIRELESS),
    }

    /** stream == null → observation-only step; tts == true → spoken test. */
    data class Step(
        val title: String,
        val instruction: String,
        val stream: Int?,
        val tts: Boolean = false,
    )

    data class StepResult(val step: Step, val heard: Boolean?, val evidence: String)

    fun steps(mode: Mode, voiceStream: Int): List<Step> {
        val link = when (mode) {
            Mode.CAVO -> "Collega il telefono all'auto con il CAVO USB e avvia Android Auto."
            Mode.WIRELESS -> "Collega il telefono in WIRELESS (Bluetooth + Wi-Fi) e avvia Android Auto."
        }
        return listOf(
            Step("1. Connessione", "$link\nQuando pronto, prosegui: verifico il tipo di connessione rilevato.", null),
            Step("2. Media", "Riproduco un tono sullo stream MEDIA. Dovresti sentirlo dagli altoparlanti auto.", AudioManager.STREAM_MUSIC),
            Step("3. Voce chiamata / HFP", "Riproduco un tono sullo stream VOICE_CALL (canale telefonata/HFP).", AudioManager.STREAM_VOICE_CALL),
            Step("4. Voce guida (TTS)", "Riproduco una frase vocale sullo stream della voce selezionato.", voiceStream, tts = true),
            Step("5. Notifica", "Riproduco un tono sullo stream NOTIFICA.", AudioManager.STREAM_NOTIFICATION),
            Step("6. Moltiplicatore", "Alza ora il volume MEDIA dai comandi auto: la voce deve salire in proporzione. Confermi?", null),
        )
    }

    fun buildReport(
        mode: Mode,
        detected: AudioProbe.Connection,
        results: List<StepResult>,
    ): String = buildString {
        append("═══ PROTOCOLLO ${mode.label} ═══\n\n")

        val connOk = detected == mode.expected || detected == AudioProbe.Connection.ENTRAMBI
        append("Connessione attesa: ${mode.expected}\n")
        append("Connessione rilevata: $detected  ${if (connOk) "✓" else "✗"}\n\n")

        var audibleFails = 0
        for (r in results) {
            val mark = when (r.heard) {
                true -> "✓"
                false -> "✗".also { audibleFails++ }
                null -> "•"
            }
            append("$mark ${r.step.title}")
            if (r.evidence.isNotEmpty()) append("  [${r.evidence}]")
            append("\n")
        }

        append("\n─── ESITO ───\n")
        val pass = connOk && audibleFails == 0
        append(if (pass) "PASS ✓ — modalità ${mode.label} validata."
        else "DA VERIFICARE ✗ — ${if (!connOk) "connessione non corrispondente; " else ""}$audibleFails test non uditi.")
    }
}
