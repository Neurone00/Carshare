package io.github.neurone00.adblock.core.net

/** Internet (RFC 1071) ones-complement checksum accumulator. */
class Checksum {
    private var sum = 0L

    fun add(buf: ByteArray, off: Int, len: Int): Checksum {
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        return this
    }

    fun addU16(v: Int): Checksum { sum += v and 0xFFFF; return this }

    fun addU32(v: Int): Checksum { addU16(v ushr 16); addU16(v); return this }

    /** Folds the accumulator and returns the 16-bit ones-complement checksum. */
    fun finish(): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }
}
