package it.moskitodesign.voicevolume

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
 * launches the system package installer (requires REQUEST_INSTALL_PACKAGES
 * and the per-app "install unknown apps" permission on Android 8+).
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

    /** Whether the app may launch an APK install (per-app "unknown sources"). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Send the user to grant "install unknown apps" for this app. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
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
                main.post {
                    if (!canInstall(context)) {
                        onProgress("Consenti \"Installa app sconosciute\", poi riapri per completare")
                        requestInstallPermission(context)
                    } else {
                        onProgress("Avvio installazione…")
                        install(context, apk)
                    }
                }
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

    /** Open [spec], manually following up to 5 redirects (GitHub → S3, any protocol). */
    private fun open(spec: String, readTimeout: Int): HttpURLConnection {
        var url = URL(spec)
        var redirects = 0
        while (true) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                this.readTimeout = readTimeout
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CarshareAudio")
                setRequestProperty("Accept", "*/*")
            }
            val code = c.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = c.getHeaderField("Location") ?: error("Redirect senza Location")
                c.disconnect()
                url = URL(url, loc)
                redirects++
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                error("HTTP $code")
            }
            return c
        }
    }

    private fun httpGet(spec: String): String {
        val conn = open(spec, 15000)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadTo(spec: String, dest: File, onPct: (Int) -> Unit) {
        val conn = open(spec, 30000)
        try {
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
