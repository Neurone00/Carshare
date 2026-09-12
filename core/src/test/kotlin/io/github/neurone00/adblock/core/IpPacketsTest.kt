package io.github.neurone00.adblock.core

import io.github.neurone00.adblock.core.net.Checksum
import io.github.neurone00.adblock.core.net.IpPackets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IpPacketsTest {
    private val v4a = byteArrayOf(10, 111, (222).toByte(), 2)
    private val v4b = byteArrayOf(10, 111, (222).toByte(), 1)
    private val v6a = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
    private val v6b = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }

    @Test
    fun udpRoundTripV4() {
        val payload = "hello".toByteArray()
        val pkt = IpPackets.buildUdp(v4a, v4b, 40000, 53, payload)
        assertEquals(20 + 8 + 5, pkt.size)
        assertEquals(IpPackets.PROTO_UDP, IpPackets.protocol(pkt, pkt.size))
        // IPv4 header checksum verifies to zero.
        assertEquals(0, Checksum().add(pkt, 0, 20).finish())
        val d = assertNotNull(IpPackets.parseUdp(pkt, pkt.size))
        assertEquals(4, d.ipVersion)
        assertContentEquals(v4a, d.srcIp)
        assertContentEquals(v4b, d.dstIp)
        assertEquals(40000, d.srcPort)
        assertEquals(53, d.dstPort)
        assertContentEquals(payload, pkt.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLength))
        // UDP checksum (with pseudo header) verifies to zero.
        val cs = Checksum().add(v4a, 0, 4).add(v4b, 0, 4).addU16(17).addU16(13).add(pkt, 20, 13).finish()
        assertEquals(0, cs)
    }

    @Test
    fun udpRoundTripV6() {
        val payload = ByteArray(33) { it.toByte() }
        val pkt = IpPackets.buildUdp(v6a, v6b, 1234, 53, payload)
        assertEquals(40 + 8 + 33, pkt.size)
        val d = assertNotNull(IpPackets.parseUdp(pkt, pkt.size))
        assertEquals(6, d.ipVersion)
        assertContentEquals(v6b, d.dstIp)
        assertEquals(33, d.payloadLength)
        val cs = Checksum().add(v6a, 0, 16).add(v6b, 0, 16).addU32(41).addU16(0).addU16(17).add(pkt, 40, 41).finish()
        assertEquals(0, cs)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(IpPackets.parseUdp(ByteArray(3), 3))
        assertNull(IpPackets.parseUdp(ByteArray(60) { 0x45 }, 60)) // proto 0x45 is not UDP
        val pkt = IpPackets.buildUdp(v4a, v4b, 1, 2, ByteArray(10))
        assertNull(IpPackets.parseUdp(pkt, pkt.size - 4)) // truncated
    }

    @Test
    fun tcpSynGetsRstAck() {
        // Hand-build a SYN to port 853 (DNS-over-TLS probe).
        val syn = ByteArray(40)
        syn[0] = 0x45; syn[2] = 0; syn[3] = 40; syn[8] = 64; syn[9] = 6
        System.arraycopy(v4a, 0, syn, 12, 4); System.arraycopy(v4b, 0, syn, 16, 4)
        syn[20] = 0xC3.toByte(); syn[21] = 0x50 // src port 50000
        syn[22] = 0x03; syn[23] = 0x55           // dst port 853
        syn[24] = 0x00; syn[25] = 0x00; syn[26] = 0x10; syn[27] = 0x00 // seq 4096
        syn[32] = (5 shl 4).toByte(); syn[33] = IpPackets.TCP_SYN.toByte()
        val seg = assertNotNull(IpPackets.parseTcp(syn, syn.size))
        assertEquals(853, seg.dstPort)
        val rst = assertNotNull(IpPackets.buildTcpReset(seg))
        val r = assertNotNull(IpPackets.parseTcp(rst, rst.size))
        assertContentEquals(v4b, r.srcIp)
        assertContentEquals(v4a, r.dstIp)
        assertEquals(853, r.srcPort)
        assertEquals(50000, r.dstPort)
        assertEquals(IpPackets.TCP_RST or IpPackets.TCP_ACK, r.flags)
        assertEquals(4097, r.ack)
        val cs = Checksum().add(v4b, 0, 4).add(v4a, 0, 4).addU16(6).addU16(20).add(rst, 20, 20).finish()
        assertEquals(0, cs)
    }
}
