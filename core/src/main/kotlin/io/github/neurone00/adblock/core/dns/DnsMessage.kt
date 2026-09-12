package io.github.neurone00.adblock.core.dns

/**
 * The first question of a DNS message.
 *
 * @property name        Lower-cased query name without the trailing dot (e.g. "ads.example.com").
 * @property type        QTYPE (1 = A, 28 = AAAA, 65 = HTTPS ...).
 * @property clazz       QCLASS (1 = IN).
 * @property questionEnd Offset (relative to the message start) of the first byte after the question section.
 */
class DnsQuestion(val name: String, val type: Int, val clazz: Int, val questionEnd: Int)

/**
 * Minimal, allocation-light DNS wire-format helpers. Only what a filtering
 * forwarder needs: read the question of a query and synthesise a "blocked"
 * answer that points the client at the null address (0.0.0.0 / ::).
 */
object DnsMessage {
    const val HEADER_LEN = 12
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    /** Default TTL of synthesised block answers (seconds). Short so an un-block takes effect quickly. */
    const val BLOCK_TTL = 60

    fun transactionId(msg: ByteArray, off: Int = 0): Int = u16(msg, off)

    fun setTransactionId(msg: ByteArray, off: Int, id: Int) {
        msg[off] = (id ushr 8).toByte()
        msg[off + 1] = id.toByte()
    }

    fun isResponse(msg: ByteArray, off: Int = 0, len: Int = msg.size - off): Boolean =
        len >= HEADER_LEN && (msg[off + 2].toInt() and 0x80) != 0

    /**
     * Parses the first question of the message at [off]..[off]+[len].
     * Returns null when the message is truncated, malformed, or has no question.
     */
    fun parseQuestion(msg: ByteArray, off: Int = 0, len: Int = msg.size - off): DnsQuestion? {
        if (len < HEADER_LEN) return null
        val end = off + len
        if (u16(msg, off + 4) < 1) return null // QDCOUNT

        val sb = StringBuilder(64)
        var pos = off + HEADER_LEN
        var afterName = -1
        var jumps = 0
        while (true) {
            if (pos >= end) return null
            val label = msg[pos].toInt() and 0xFF
            when {
                label == 0 -> {
                    if (afterName < 0) afterName = pos + 1
                    break
                }
                label and 0xC0 == 0xC0 -> { // compression pointer
                    if (pos + 1 >= end) return null
                    val ptr = ((label and 0x3F) shl 8) or (msg[pos + 1].toInt() and 0xFF)
                    if (afterName < 0) afterName = pos + 2
                    if (++jumps > 16) return null
                    pos = off + ptr
                }
                label > 63 -> return null
                else -> {
                    if (pos + 1 + label > end) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..label) {
                        val c = (msg[pos + i].toInt() and 0xFF).toChar()
                        sb.append(if (c in 'A'..'Z') c + 32 else c)
                    }
                    pos += 1 + label
                }
            }
        }
        if (afterName + 4 > end) return null
        return DnsQuestion(sb.toString(), u16(msg, afterName), u16(msg, afterName + 2), afterName + 4 - off)
    }

    /**
     * Builds a NOERROR response to [query] whose answer, for A / AAAA questions,
     * is the null address (0.0.0.0 / ::). Other question types get an empty
     * answer section. Clients fail fast instead of waiting for an ad server.
     */
    fun buildBlockedResponse(
        query: ByteArray,
        off: Int,
        q: DnsQuestion,
        ttl: Int = BLOCK_TTL,
    ): ByteArray {
        val rdLen = when (q.type) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> 0
        }
        val answerCount = if (rdLen > 0) 1 else 0
        val answerLen = if (rdLen > 0) 2 + 2 + 2 + 4 + 2 + rdLen else 0
        val out = ByteArray(q.questionEnd + answerLen)
        System.arraycopy(query, off, out, 0, q.questionEnd)

        // Flags: QR=1, opcode copied, AA=0, TC=0, RD copied; RA=1, RCODE=NOERROR.
        out[2] = (0x80 or (query[off + 2].toInt() and 0x79)).toByte()
        out[3] = 0x80.toByte()
        putU16(out, 4, 1)           // QDCOUNT
        putU16(out, 6, answerCount) // ANCOUNT
        putU16(out, 8, 0)           // NSCOUNT
        putU16(out, 10, 0)          // ARCOUNT

        if (rdLen > 0) {
            var p = q.questionEnd
            out[p++] = 0xC0.toByte(); out[p++] = HEADER_LEN.toByte() // pointer to the question name
            putU16(out, p, q.type); p += 2
            putU16(out, p, CLASS_IN); p += 2
            out[p++] = (ttl ushr 24).toByte(); out[p++] = (ttl ushr 16).toByte()
            out[p++] = (ttl ushr 8).toByte(); out[p++] = ttl.toByte()
            putU16(out, p, rdLen) // RDATA is already all zeros
        }
        return out
    }

    /** Encodes a plain query for [name] / [type] (used by tests and the connectivity self-check). */
    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val nameLen = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(HEADER_LEN + nameLen + 4)
        putU16(out, 0, id)
        out[2] = 0x01 // RD
        putU16(out, 4, 1)
        var p = HEADER_LEN
        for (l in labels) {
            out[p++] = l.length.toByte()
            for (c in l) out[p++] = c.code.toByte()
        }
        out[p++] = 0
        putU16(out, p, type); p += 2
        putU16(out, p, CLASS_IN)
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }
}
