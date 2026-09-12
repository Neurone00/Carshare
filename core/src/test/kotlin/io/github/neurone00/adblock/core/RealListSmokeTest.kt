package io.github.neurone00.adblock.core

import io.github.neurone00.adblock.core.filter.FilterBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealListSmokeTest {
    @Test
    fun realLists() {
        val files = listOf("hagezi-pro.txt", "stevenblack-hosts.txt").map { File(System.getenv("ADBLOCK_LISTS_DIR") ?: "/nonexistent", it) }
        if (files.any { !it.exists() }) return
        val rt = Runtime.getRuntime(); System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val b = FilterBuilder()
        for (f in files) f.bufferedReader().useLines { b.addAll(it) }
        val filter = b.build()
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        println("SMOKE lines=${b.lines} blocked=${filter.blockedCount} allowed=${filter.allowedCount} buildMs=$ms heapDeltaMB=${(after - before) / 1_000_000}")
        val t1 = System.nanoTime()
        var hits = 0
        repeat(200_000) { if (filter.isBlocked("cdn$it.static.example.org")) hits++ }
        println("SMOKE 200k lookups in ${(System.nanoTime() - t1) / 1_000_000}ms, false hits=$hits")
        for (d in listOf("doubleclick.net", "googleadservices.com", "pagead2.googlesyndication.com", "ads.mopub.com", "app-measurement.com", "graph.facebook.com.ads.example.net")) {
            println("SMOKE $d blocked=${filter.isBlocked(d)}")
        }
        assertTrue(filter.isBlocked("doubleclick.net"))
        assertTrue(filter.isBlocked("pagead2.googlesyndication.com"))
        assertFalse(filter.isBlocked("google.com"))
        assertFalse(filter.isBlocked("www.youtube.com"))
        assertFalse(filter.isBlocked("github.com"))
        assertTrue(hits == 0)
        assertTrue(filter.blockedCount > 250_000, "blocked=${filter.blockedCount}")
    }
}
