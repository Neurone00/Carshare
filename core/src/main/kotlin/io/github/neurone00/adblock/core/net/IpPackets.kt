package io.github.neurone00.adblock.core.net

/** A parsed UDP datagram (IPv4 or IPv6) that lives inside [buf]. */
class UdpDatagram(
    val buf: ByteArray,
    val ipVersion: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/** A parsed TCP segment header, enough to answer a SYN with a RST. */
class TcpSegment(
    val ipVersion: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Int,
    val ack: Int,
    val flags: Int,
    val payloadLength: Int,
)

/**
 * Raw IPv4 / IPv6 packet parsing and construction for the TUN device.
 * Only UDP (for DNS) and enough TCP (to reset DNS-over-TLS probes) are handled.
 */
object IpPackets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_ACK = 0x10

    private var ipId = (System.nanoTime() and 0xFFFF).toInt()

    fun ipVersion(buf: ByteArray, len: Int): Int = if (len < 1) 0 else (buf[0].toInt() and 0xFF) ushr 4

    /** Returns the transport protocol number of the packet, or -1 if unparseable. */
    fun protocol(buf: ByteArray, len: Int): Int = when (ipVersion(buf, len)) {
        4 -> if (len >= 20) buf[9].toInt() and 0xFF else -1
        6 -> if (len >= 40) buf[6].toInt() and 0xFF else -1
        else -> -1
    }

    fun parseUdp(buf: ByteArray, len: Int): UdpDatagram? {
        val hdr = transportHeader(buf, len, PROTO_UDP) ?: return null
        val (version, off) = hdr
        if (off + 8 > len) return null
        val udpLen = u16(buf, off + 4)
        if (udpLen < 8 || off + udpLen > len) return null
        return UdpDatagram(
            buf = buf,
            ipVersion = version,
            srcIp = srcIp(buf, version),
            dstIp = dstIp(buf, version),
            srcPort = u16(buf, off),
            dstPort = u16(buf, off + 2),
            payloadOffset = off + 8,
            payloadLength = udpLen - 8,
        )
    }

    fun parseTcp(buf: ByteArray, len: Int): TcpSegment? {
        val hdr = transportHeader(buf, len, PROTO_TCP) ?: return null
        val (version, off) = hdr
        if (off + 20 > len) return null
        val dataOff = ((buf[off + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOff < 20 || off + dataOff > len) return null
        return TcpSegment(
            ipVersion = version,
            srcIp = srcIp(buf, version),
            dstIp = dstIp(buf, version),
            srcPort = u16(buf, off),
            dstPort = u16(buf, off + 2),
            seq = u32(buf, off + 4),
            ack = u32(buf, off + 8),
            flags = buf[off + 13].toInt() and 0xFF,
            payloadLength = len - off - dataOff,
        )
    }

    /** Builds a complete IP+UDP packet carrying [payload]. */
    fun buildUdp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOff: Int = 0,
        payloadLen: Int = payload.size,
    ): ByteArray {
        val v6 = srcIp.size == 16
        val ipLen = if (v6) 40 else 20
        val udpLen = 8 + payloadLen
        val out = ByteArray(ipLen + udpLen)
        writeIpHeader(out, v6, srcIp, dstIp, PROTO_UDP, udpLen)
        val u = ipLen
        putU16(out, u, srcPort)
        putU16(out, u + 2, dstPort)
        putU16(out, u + 4, udpLen)
        System.arraycopy(payload, payloadOff, out, u + 8, payloadLen)
        val cs = pseudoHeader(srcIp, dstIp, PROTO_UDP, udpLen).add(out, u, udpLen).finish()
        putU16(out, u + 6, if (cs == 0) 0xFFFF else cs)
        return out
    }

    /**
     * Builds the RST that answers an unwanted [seg] (RFC 793 §3.4). Returns null
     * if the segment is itself a RST.
     */
    fun buildTcpReset(seg: TcpSegment): ByteArray? {
        if (seg.flags and TCP_RST != 0) return null
        val v6 = seg.ipVersion == 6
        val ipLen = if (v6) 40 else 20
        val out = ByteArray(ipLen + 20)
        writeIpHeader(out, v6, seg.dstIp, seg.srcIp, PROTO_TCP, 20)
        val t = ipLen
        putU16(out, t, seg.dstPort)
        putU16(out, t + 2, seg.srcPort)
        val hasAck = seg.flags and TCP_ACK != 0
        val seqNo: Int
        val ackNo: Int
        val flags: Int
        if (hasAck) {
            seqNo = seg.ack; ackNo = 0; flags = TCP_RST
        } else {
            var segLen = seg.payloadLength
            if (seg.flags and TCP_SYN != 0) segLen++
            if (seg.flags and TCP_FIN != 0) segLen++
            seqNo = 0; ackNo = seg.seq + segLen; flags = TCP_RST or TCP_ACK
        }
        putU32(out, t + 4, seqNo)
        putU32(out, t + 8, ackNo)
        out[t + 12] = (5 shl 4).toByte()
        out[t + 13] = flags.toByte()
        putU16(out, t + 14, 0)
        val cs = pseudoHeader(seg.dstIp, seg.srcIp, PROTO_TCP, 20).add(out, t, 20).finish()
        putU16(out, t + 16, cs)
        return out
    }

    // ---- internals -------------------------------------------------------

    private fun transportHeader(buf: ByteArray, len: Int, wantProto: Int): Pair<Int, Int>? {
        return when (ipVersion(buf, len)) {
            4 -> {
                if (len < 20) return null
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (ihl < 20 || ihl > len) return null
                if (buf[9].toInt() and 0xFF != wantProto) return null
                val fragField = u16(buf, 6)
                if (fragField and 0x3FFF != 0) return null // fragmented: not supported
                val total = u16(buf, 2)
                if (total > len || total < ihl) return null
                4 to ihl
            }
            6 -> {
                if (len < 40) return null
                if (buf[6].toInt() and 0xFF != wantProto) return null // extension headers: not supported
                val payload = u16(buf, 4)
                if (40 + payload > len) return null
                6 to 40
            }
            else -> null
        }
    }

    private fun writeIpHeader(out: ByteArray, v6: Boolean, src: ByteArray, dst: ByteArray, proto: Int, payloadLen: Int) {
        if (v6) {
            out[0] = 0x60
            putU16(out, 4, payloadLen)
            out[6] = proto.toByte()
            out[7] = 64
            System.arraycopy(src, 0, out, 8, 16)
            System.arraycopy(dst, 0, out, 24, 16)
        } else {
            out[0] = 0x45
            putU16(out, 2, 20 + payloadLen)
            putU16(out, 4, nextIpId())
            putU16(out, 6, 0x4000) // DF
            out[8] = 64
            out[9] = proto.toByte()
            System.arraycopy(src, 0, out, 12, 4)
            System.arraycopy(dst, 0, out, 16, 4)
            putU16(out, 10, Checksum().add(out, 0, 20).finish())
        }
    }

    private fun pseudoHeader(src: ByteArray, dst: ByteArray, proto: Int, upperLen: Int): Checksum {
        val cs = Checksum()
        cs.add(src, 0, src.size).add(dst, 0, dst.size)
        if (src.size == 16) {
            cs.addU32(upperLen).addU16(0).addU16(proto)
        } else {
            cs.addU16(proto).addU16(upperLen)
        }
        return cs
    }

    @Synchronized
    private fun nextIpId(): Int { ipId = (ipId + 1) and 0xFFFF; return ipId }

    private fun srcIp(buf: ByteArray, version: Int) = if (version == 6) buf.copyOfRange(8, 24) else buf.copyOfRange(12, 16)
    private fun dstIp(buf: ByteArray, version: Int) = if (version == 6) buf.copyOfRange(24, 40) else buf.copyOfRange(16, 20)

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun putU16(b: ByteArray, off: Int, v: Int) { b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte() }
    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }
}
