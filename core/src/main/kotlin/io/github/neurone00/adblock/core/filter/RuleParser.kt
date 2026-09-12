package io.github.neurone00.adblock.core.filter

/** One parsed list entry. */
data class Rule(val domain: String, val allow: Boolean = false)

/**
 * Understands the three formats blocklists come in:
 *
 *  * hosts files:      `0.0.0.0 ads.example.com`
 *  * plain domains:    `ads.example.com`  (also `*.ads.example.com`)
 *  * Adblock syntax:   `||ads.example.com^`  and exceptions `@@||cdn.example.com^`
 *
 * Anything that cannot be expressed as "this domain and its subdomains" (paths,
 * wildcards in the middle, cosmetic rules, modifiers) is ignored.
 */
object RuleParser {
    private val LOCAL_NAMES = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )

    fun parseLine(raw: String): Rule? {
        var line = raw.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return null
        // Cosmetic / scriptlet rules (element hiding) are not DNS rules.
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#%#")) return null
        // Trailing "# comment" (hosts files).
        val hash = line.indexOf(" #").let { if (it < 0) line.indexOf("\t#") else it }
        if (hash >= 0) line = line.substring(0, hash).trim()

        var allow = false
        if (line.startsWith("@@")) { allow = true; line = line.substring(2) }

        // Adblock-style network rule.
        if (line.startsWith("||")) {
            line = line.substring(2)
            val dollar = line.indexOf('$')
            if (dollar >= 0) {
                val mods = line.substring(dollar + 1)
                if (mods.isNotEmpty() && mods != "important" && mods != "all") return null
                line = line.substring(0, dollar)
            }
            if (line.endsWith("^")) line = line.dropLast(1)
            if (line.endsWith("^|")) line = line.dropLast(2)
            return domain(line, allow)
        }
        if (line.startsWith("|")) return null

        // hosts-file line: "<ip> <host> [<host> ...]" — only the first host is used
        // by consumers we care about; blocklists put one host per line.
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size >= 2 && looksLikeIp(parts[0])) return domain(parts[1], allow)
        if (parts.size == 1) return domain(parts[0], allow)
        return null
    }

    private fun looksLikeIp(s: String): Boolean = s.all { it.isDigit() || it == '.' || it == ':' } && (s.contains('.') || s.contains(':'))

    private fun domain(input: String, allow: Boolean): Rule? {
        var d = input.trim().lowercase()
        if (d.startsWith("*.")) d = d.substring(2)
        d = d.trimEnd('.')
        if (d.isEmpty() || d in LOCAL_NAMES) return null
        if (d.indexOf('.') < 0) return null            // never block a whole TLD or a bare host
        if (d.length > 253) return null
        if (looksLikeIp(d)) return null
        var labelLen = 0
        for (c in d) {
            when {
                c == '.' -> { if (labelLen == 0) return null; labelLen = 0 }
                c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> labelLen++
                else -> return null
            }
            if (labelLen > 63) return null
        }
        return Rule(d, allow)
    }
}
