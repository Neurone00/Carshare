package io.github.neurone00.adblock.core.filter

/**
 * Compact open-addressing set of 64-bit keys. Hundreds of thousands of blocked
 * domains fit in a few megabytes, instead of the tens of megabytes a
 * HashSet<String> would need on a phone.
 */
class LongHashSet(expectedSize: Int = 1024) {
    private var keys: LongArray
    private var mask: Int
    var size: Int = 0
        private set

    init {
        var cap = 16
        while (cap < expectedSize * 2) cap = cap shl 1
        keys = LongArray(cap)
        mask = cap - 1
    }

    fun add(key: Long): Boolean {
        val k = if (key == 0L) 1L else key // 0 marks an empty slot
        if ((size + 1) * 2 > keys.size) grow()
        var i = mix(k) and mask
        while (true) {
            val cur = keys[i]
            if (cur == 0L) { keys[i] = k; size++; return true }
            if (cur == k) return false
            i = (i + 1) and mask
        }
    }

    operator fun contains(key: Long): Boolean {
        val k = if (key == 0L) 1L else key
        var i = mix(k) and mask
        while (true) {
            val cur = keys[i]
            if (cur == 0L) return false
            if (cur == k) return true
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size shl 1)
        mask = keys.size - 1
        size = 0
        for (k in old) if (k != 0L) add(k)
    }

    private fun mix(k: Long): Int {
        var h = k * -0x61c8864680b583ebL // golden-ratio multiplier
        h = h xor (h ushr 32)
        return h.toInt()
    }
}
