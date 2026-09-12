package io.github.neurone00.adblock.core.filter

/**
 * Immutable decision engine: is a query name blocked?
 *
 * A name is blocked when it, or any parent domain, is in the block set, unless
 * it (or any parent) is in the allow set. Allow always wins so a user can
 * rescue a single host from an aggressive list.
 */
class DomainFilter(
    private val blocked: LongHashSet,
    private val allowed: LongHashSet,
) {
    val blockedCount: Int get() = blocked.size
    val allowedCount: Int get() = allowed.size

    fun isAllowed(name: String): Boolean =
        allowed.size > 0 && DomainHash.anySuffix(name) { it in allowed }

    fun isBlocked(name: String): Boolean {
        if (name.isEmpty() || name.indexOf('.') < 0) return false
        if (isAllowed(name)) return false
        return DomainHash.anySuffix(name) { it in blocked }
    }

    companion object {
        val EMPTY = DomainFilter(LongHashSet(1), LongHashSet(1))
    }
}

/** Accumulates rules from any number of lists into a [DomainFilter]. */
class FilterBuilder(expectedBlocked: Int = 200_000) {
    private val blocked = LongHashSet(expectedBlocked)
    private val allowed = LongHashSet(64)
    var lines: Long = 0
        private set

    fun add(rule: Rule) {
        if (rule.allow) allowed.add(DomainHash.of(rule.domain)) else blocked.add(DomainHash.of(rule.domain))
    }

    fun addLine(line: String) {
        lines++
        RuleParser.parseLine(line)?.let(::add)
    }

    fun addAll(text: Sequence<String>) = text.forEach(::addLine)

    fun build(): DomainFilter = DomainFilter(blocked, allowed)
}
