package io.github.neurone00.adblock.data

import android.content.Context
import android.util.Log
import io.github.neurone00.adblock.core.filter.DomainFilter
import io.github.neurone00.adblock.core.filter.FilterBuilder
import io.github.neurone00.adblock.core.filter.RuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Downloads blocklists, keeps them on disk and compiles them (plus the user's
 * own rules) into the [DomainFilter] the VPN service consults for every query.
 */
object ListRepository {
    private const val TAG = "ListRepository"
    private const val FRESH_MS = 6 * 60 * 60 * 1000L

    data class Status(
        val updating: Boolean = false,
        val building: Boolean = false,
        val progress: String? = null,
        val lastError: String? = null,
        val blockedDomains: Int = 0,
        val allowedDomains: Int = 0,
        val loaded: Boolean = false,
    )

    val status = MutableStateFlow(Status())

    /** The filter currently in force. Read on every DNS query, so keep it a plain volatile. */
    @Volatile
    var filter: DomainFilter = DomainFilter.EMPTY
        private set

    private val mutex = Mutex()

    private fun listsDir(context: Context): File = File(context.filesDir, "lists").apply { mkdirs() }

    fun fileFor(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(listsDir(context), digest.joinToString("") { "%02x".format(it) } + ".txt")
    }

    fun hasFile(context: Context, key: String): Boolean = fileFor(context, key).exists()

    suspend fun ensureLoaded(context: Context): DomainFilter {
        if (status.value.loaded) return filter
        return rebuild(context)
    }

    /** Recompiles the filter from whatever is on disk (or bundled) plus custom rules. */
    suspend fun rebuild(context: Context): DomainFilter = withContext(Dispatchers.Default) {
        mutex.withLock {
            status.value = status.value.copy(building = true)
            val ctx = context.applicationContext
            val builder = FilterBuilder()
            val enabled = Prefs.enabledSources
            for (src in BlocklistSources.all) {
                if (src.id !in enabled) continue
                val file = fileFor(ctx, src.id)
                try {
                    if (file.exists()) {
                        file.bufferedReader().useLines { builder.addAll(it) }
                    } else if (src.bundledAsset != null) {
                        GZIPInputStream(ctx.assets.open(src.bundledAsset)).bufferedReader().useLines { builder.addAll(it) }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed reading ${src.id}", e)
                }
            }
            for (url in Prefs.customUrls) {
                val file = fileFor(ctx, url)
                if (file.exists()) try {
                    file.bufferedReader().useLines { builder.addAll(it) }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed reading $url", e)
                }
            }
            BuiltinRules.lines.forEach(builder::addLine)
            Prefs.customBlock.lineSequence().forEach(builder::addLine)
            Prefs.customAllow.lineSequence().forEach { raw ->
                val t = raw.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) builder.addLine(if (t.startsWith("@@")) t else "@@$t")
            }
            val built = builder.build()
            filter = built
            status.value = status.value.copy(
                building = false, loaded = true,
                blockedDomains = built.blockedCount, allowedDomains = built.allowedCount,
            )
            Log.i(TAG, "Filter rebuilt: ${built.blockedCount} blocked, ${built.allowedCount} allowed")
            built
        }
    }

    /**
     * Downloads every enabled list that is missing or (when [force]) stale, then
     * rebuilds. Returns true when at least one list was refreshed.
     */
    suspend fun update(context: Context, force: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (status.value.updating) return@withContext false
        val ctx = context.applicationContext
        status.value = status.value.copy(updating = true, lastError = null)
        var refreshed = 0
        var failures = 0
        var lastError: String? = null
        try {
            val targets = BlocklistSources.all.filter { it.id in Prefs.enabledSources }.map { it.id to it.url } +
                Prefs.customUrls.map { it to it }
            for ((key, url) in targets) {
                val file = fileFor(ctx, key)
                val fresh = file.exists() && System.currentTimeMillis() - Prefs.sourceUpdatedAt(key) < FRESH_MS
                if (fresh && !force) continue
                val name = BlocklistSources.byId(key)?.name ?: url
                status.value = status.value.copy(progress = "Downloading $name…")
                try {
                    val rules = download(url, file)
                    Prefs.setSourceResult(key, System.currentTimeMillis(), rules)
                    refreshed++
                } catch (e: Exception) {
                    failures++
                    lastError = "$name: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "Download failed for $url", e)
                }
            }
            if (refreshed > 0) Prefs.lastUpdate = System.currentTimeMillis()
        } finally {
            status.value = status.value.copy(updating = false, progress = null, lastError = lastError)
        }
        rebuild(ctx)
        refreshed > 0
    }

    /** Downloads [url] into [dest] atomically and returns the number of usable rules. */
    private fun download(url: String, dest: File): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "AdBlockDNS/1.0 (Android)")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val tmp = File(dest.path + ".tmp")
            var rules = 0
            conn.inputStream.bufferedReader().use { reader ->
                tmp.bufferedWriter().use { writer ->
                    reader.lineSequence().forEach { line ->
                        if (RuleParser.parseLine(line) != null) rules++
                        writer.write(line); writer.newLine()
                    }
                }
            }
            if (rules == 0) { tmp.delete(); throw IOException("no usable rules in response") }
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw IOException("could not save list") }
            return rules
        } finally {
            conn.disconnect()
        }
    }

    fun removeCustomUrl(context: Context, url: String) {
        Prefs.customUrls = Prefs.customUrls - url
        fileFor(context, url).delete()
    }
}
