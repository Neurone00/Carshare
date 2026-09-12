package io.github.neurone00.adblock.core.filter

/**
 * 64-bit FNV-1a computed from the *end* of the name backwards, so that the
 * running hash after consuming k characters is exactly the hash of the last k
 * characters. One pass over "a.b.example.com" therefore yields the hashes of
 * "example.com", "b.example.com" and "a.b.example.com" for free.
 */
object DomainHash {
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    fun of(domain: String): Long {
        var h = FNV_OFFSET
        for (i in domain.length - 1 downTo 0) h = (h xor lower(domain[i]).code.toLong()) * FNV_PRIME
        return h
    }

    /**
     * Walks every registrable suffix of [domain] (each label boundary, longest
     * last), calling [visit] with the suffix hash. Returns true as soon as [visit]
     * returns true. Single-label names ("localhost") are never visited.
     */
    inline fun anySuffix(domain: String, visit: (Long) -> Boolean): Boolean {
        var h = FNV_OFFSET_PUBLIC
        var i = domain.length - 1
        while (i >= 0) {
            val c = domain[i]
            if (c == '.') {
                // Hash so far covers the suffix after this dot.
                if (i < domain.length - 1 && visit(h)) return true
            }
            h = (h xor lowerPublic(c).code.toLong()) * FNV_PRIME_PUBLIC
            i--
        }
        return visit(h)
    }

    @PublishedApi internal const val FNV_OFFSET_PUBLIC = FNV_OFFSET
    @PublishedApi internal const val FNV_PRIME_PUBLIC = FNV_PRIME

    @PublishedApi internal fun lowerPublic(c: Char): Char = lower(c)

    private fun lower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c
}
