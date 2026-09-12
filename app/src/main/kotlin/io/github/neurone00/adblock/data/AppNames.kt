package io.github.neurone00.adblock.data

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/** Resolves Linux UIDs of DNS clients to human-readable app names, with a cache. */
object AppNames {
    private val cache = ConcurrentHashMap<Int, String>()

    fun label(context: Context, uid: Int): String? {
        if (uid < 0) return null
        cache[uid]?.let { return it }
        val pm = context.packageManager
        val name = try {
            when (uid) {
                0 -> "System (root)"
                1000 -> "Android system"
                else -> pm.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
                    try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        pkg
                    }
                } ?: "uid $uid"
            }
        } catch (_: Exception) {
            "uid $uid"
        }
        cache[uid] = name
        return name
    }
}
