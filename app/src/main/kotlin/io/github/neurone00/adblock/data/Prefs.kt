package io.github.neurone00.adblock.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tiny SharedPreferences facade. Every write bumps [changes] so Compose
 * screens can re-read values without a full DataStore setup.
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("adblock", Context.MODE_PRIVATE)
    }

    private fun bump() { changes.value = changes.value + 1 }
    private fun put(block: SharedPreferences.Editor.() -> Unit) { sp.edit().apply(block).apply(); bump() }

    /** User wants blocking on (drives boot auto-start and the quick-settings tile). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = put { putBoolean("enabled", v) }

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = put { putBoolean("auto_start", v) }

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = put { putBoolean("auto_update", v) }

    /** Upstream provider id (see UpstreamDns.PROVIDERS) or a literal IP address. */
    var upstream: String
        get() = sp.getString("upstream", "auto") ?: "auto"
        set(v) = put { putString("upstream", v) }

    var enabledSources: Set<String>
        get() = sp.getStringSet("sources", null)?.toSet() ?: BlocklistSources.defaults
        set(v) = put { putStringSet("sources", v.toSet()) }

    var customUrls: Set<String>
        get() = sp.getStringSet("custom_urls", null)?.toSet() ?: emptySet()
        set(v) = put { putStringSet("custom_urls", v.toSet()) }

    var customBlock: String
        get() = sp.getString("custom_block", "") ?: ""
        set(v) = put { putString("custom_block", v) }

    var customAllow: String
        get() = sp.getString("custom_allow", "") ?: ""
        set(v) = put { putString("custom_allow", v) }

    var bypassApps: Set<String>
        get() = sp.getStringSet("bypass_apps", null)?.toSet() ?: emptySet()
        set(v) = put { putStringSet("bypass_apps", v.toSet()) }

    var totalBlocked: Long
        get() = sp.getLong("total_blocked", 0)
        set(v) = put { putLong("total_blocked", v) }

    var totalQueries: Long
        get() = sp.getLong("total_queries", 0)
        set(v) = put { putLong("total_queries", v) }

    var lastUpdate: Long
        get() = sp.getLong("last_update", 0)
        set(v) = put { putLong("last_update", v) }

    fun sourceUpdatedAt(key: String): Long = sp.getLong("src_time_$key", 0)
    fun sourceRules(key: String): Int = sp.getInt("src_rules_$key", 0)
    fun setSourceResult(key: String, time: Long, rules: Int) = put {
        putLong("src_time_$key", time); putInt("src_rules_$key", rules)
    }

    fun addAllow(domain: String) {
        val d = domain.trim().lowercase()
        if (d.isEmpty()) return
        val lines = customAllow.lines().map { it.trim() }
        if (d in lines) return
        customAllow = (lines.filter { it.isNotEmpty() } + d).joinToString("\n")
    }
}
