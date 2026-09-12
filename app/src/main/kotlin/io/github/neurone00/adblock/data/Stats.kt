package io.github.neurone00.adblock.data

import java.util.concurrent.atomic.AtomicLong

/** Live counters and a short log of recent DNS decisions, shared by service and UI. */
object Stats {
    class Entry(val domain: String, val blocked: Boolean, val time: Long, val uid: Int)

    private const val MAX_LOG = 400

    val blocked = AtomicLong()
    val queries = AtomicLong()
    private val log = ArrayDeque<Entry>(MAX_LOG)

    fun record(domain: String, isBlocked: Boolean, uid: Int) {
        queries.incrementAndGet()
        if (isBlocked) blocked.incrementAndGet()
        synchronized(log) {
            if (log.size >= MAX_LOG) log.removeFirst()
            log.addLast(Entry(domain, isBlocked, System.currentTimeMillis(), uid))
        }
    }

    /** Newest first. */
    fun snapshot(): List<Entry> = synchronized(log) { log.toList().asReversed() }

    fun load() {
        blocked.set(Prefs.totalBlocked)
        queries.set(Prefs.totalQueries)
    }

    fun persist() {
        Prefs.totalBlocked = blocked.get()
        Prefs.totalQueries = queries.get()
    }

    fun reset() {
        blocked.set(0); queries.set(0)
        synchronized(log) { log.clear() }
        persist()
    }
}
