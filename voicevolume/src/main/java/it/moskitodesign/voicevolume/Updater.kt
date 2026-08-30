package it.moskitodesign.voicevolume

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal self-updater for the sideloaded APK.
 *
 * Reads a JSON manifest from [Prefs.updateUrl] of the form:
 *   { "versionCode": 2, "versionName": "0.2.0",
 *     "url": "https://host/voicevolume.apk", "notes": "..." }
 * If versionCode is newer than the installed build, downloads the APK and
 * launches the system package installer (requires REQUEST_INSTALL_PACKAGES).
 */
object Updater {

    data class Result(
        val hasUpdate: Boolean,
        val versionName: String? = null,
        val notes: String? = null,
        val apkUrl: String? = null,
        val error: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())

    fun currentVersionCode(context: Context): Long {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun check(context: Context, onResult: (Result) -> Unit) {
        val url = Prefs(context).updateUrl.trim()
        if (url.isEmpty()) {
            onResult(Result(false, error = "Nessun URL di aggiornamento impostato"))
            return
        }
        Thread {
            val result = runCatching {
                val json = httpGet(url)
                val obj = JSONObject(json)
                val remoteCode = obj.getLong("versionCode")
                val newer = remoteCode > currentVersionCode(context)
                Result(
                    hasUpdate = newer,
                    versionName = obj.optString("versionName"),
                    notes = obj.optString("notes"),
                    apkUrl = obj.optString("url"),
                )
            }.getOrElse { Result(false, error = it.message ?: "Errore rete") }
            main.post { onResult(result) }
        }.start()
    }

    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: (String) -> Unit,
    ) {
        Thread {
            val outcome = runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "voicevolume-update.apk")
                downloadTo(apkUrl, apk) { pct -> main.post { onProgress("Download… $pct%") } }
                main.post { onProgress("Avvio installazione…"); install(context, apk) }
                "ok"
            }
            outcome.exceptionOrNull()?.let { e ->
                main.post { onProgress("Errore: ${e.message}") }
            }
        }.start()
    }

    private fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun httpGet(spec: String): String {
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadTo(spec: String, dest: File, onPct: (Int) -> Unit) {
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLength.toLong()
            var read = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var n = input.read(buf)
                    var lastPct = -1
                    while (n >= 0) {
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) { onPct(pct); lastPct = pct }
                        }
                        n = input.read(buf)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
