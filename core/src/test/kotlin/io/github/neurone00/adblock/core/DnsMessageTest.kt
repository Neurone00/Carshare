package io.github.neurone00.adblock.core

import io.github.neurone00.adblock.core.dns.DnsMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsMessageTest {
    @Test
    fun parsesQuestionName() {
        val q = DnsMessage.buildQuery(0x1234, "Ads.Example.COM", DnsMessage.TYPE_A)
        val parsed = assertNotNull(DnsMessage.parseQuestion(q))
        assertEquals("ads.example.com", parsed.name)
        assertEquals(DnsMessage.TYPE_A, parsed.type)
        assertEquals(DnsMessage.CLASS_IN, parsed.clazz)
        assertEquals(q.size, parsed.questionEnd)
        assertEquals(0x1234, DnsMessage.transactionId(q))
    }

    @Test
    fun parsesWithOffset() {
        val q = DnsMessage.buildQuery(7, "a.b", DnsMessage.TYPE_AAAA)
        val buf = ByteArray(10) + q + ByteArray(5)
        val parsed = assertNotNull(DnsMessage.parseQuestion(buf, 10, q.size))
        assertEquals("a.b", parsed.name)
        assertEquals(q.size, parsed.questionEnd)
    }

    @Test
    fun rejectsTruncated() {
        val q = DnsMessage.buildQuery(1, "ads.example.com", DnsMessage.TYPE_A)
        assertNull(DnsMessage.parseQuestion(q, 0, q.size - 3))
        assertNull(DnsMessage.parseQuestion(ByteArray(5)))
    }

    @Test
    fun rejectsPointerLoop() {
        val q = ByteArray(16)
        q[5] = 1 // QDCOUNT = 1
        q[12] = 0xC0.toByte(); q[13] = 12 // pointer to itself
        assertNull(DnsMessage.parseQuestion(q))
    }

    @Test
    fun blockedResponseForA() {
        val q = DnsMessage.buildQuery(0xBEEF, "ads.example.com", DnsMessage.TYPE_A)
        val parsed = assertNotNull(DnsMessage.parseQuestion(q))
        val r = DnsMessage.buildBlockedResponse(q, 0, parsed)
        assertTrue(DnsMessage.isResponse(r))
        assertEquals(0xBEEF, DnsMessage.transactionId(r))
        assertEquals(0x81, r[2].toInt() and 0xFF) // QR + RD
        assertEquals(0x80, r[3].toInt() and 0xFF) // RA, NOERROR
        assertEquals(1, r[7].toInt())              // ANCOUNT
        assertEquals(q.size + 16, r.size)
        val rd = r.copyOfRange(r.size - 4, r.size)
        assertTrue(rd.all { it == 0.toByte() })
        // Answer name is a pointer to offset 12.
        assertEquals(0xC0, r[q.size].toInt() and 0xFF)
        assertEquals(12, r[q.size + 1].toInt())
        // Response is itself parseable.
        assertEquals("ads.example.com", DnsMessage.parseQuestion(r)?.name)
    }

    @Test
    fun blockedResponseForAaaaAndOthers() {
        val q6 = DnsMessage.buildQuery(1, "x.y", DnsMessage.TYPE_AAAA)
        val r6 = DnsMessage.buildBlockedResponse(q6, 0, DnsMessage.parseQuestion(q6)!!)
        assertEquals(q6.size + 12 + 16, r6.size)
        val qh = DnsMessage.buildQuery(1, "x.y", DnsMessage.TYPE_HTTPS)
        val rh = DnsMessage.buildBlockedResponse(qh, 0, DnsMessage.parseQuestion(qh)!!)
        assertEquals(qh.size, rh.size)
        assertEquals(0, rh[7].toInt())
    }
}
