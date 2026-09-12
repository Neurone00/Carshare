package io.github.neurone00.adblock.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.neurone00.adblock.core.dns.DnsMessage
import io.github.neurone00.adblock.core.filter.DomainFilter
import io.github.neurone00.adblock.core.net.IpPackets
import io.github.neurone00.adblock.core.net.UdpDatagram
import io.github.neurone00.adblock.data.Stats
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The packet pump. Reads raw IP packets from the TUN device, answers blocked
 * DNS queries locally, forwards the rest to a real resolver through a socket
 * that bypasses the VPN, and writes the replies back into the tunnel.
 *
 * Only DNS ever enters the tunnel (the VPN routes just the fake resolver
 * addresses), so this stays cheap on battery: no app traffic is proxied.
 */
class TunLoop(
    private val service: VpnService,
    tun: ParcelFileDescriptor,
    private val upstreams: () -> List<InetSocketAddress>,
    private val filter: () -> DomainFilter,
    private val ownerUid: (UdpDatagram) -> Int,
) {
    private class Pending(
        val origId: Int,
        val clientIp: ByteArray,
        val clientPort: Int,
        val dnsIp: ByteArray,
        val dnsPort: Int,
        val sentAt: Long,
    )

    @Volatile private var running = true
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Any()
    private val socket = DatagramSocket().also {
        service.protect(it)
        it.soTimeout = 500
    }
    private val pending = ConcurrentHashMap<Int, Pending>()
    private var nextId = Random.nextInt(1, 0xFFFF)
    @Volatile private var upstreamIndex = 0
    @Volatile private var consecutiveTimeouts = 0

    private val reader = Thread(::readLoop, "adblock-tun").apply { isDaemon = true }
    private val receiver = Thread(::receiveLoop, "adblock-dns").apply { isDaemon = true }

    fun start() {
        reader.start()
        receiver.start()
    }

    fun stop() {
        running = false
        try { socket.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        reader.interrupt()
        receiver.interrupt()
    }

    // ---- TUN -> us -------------------------------------------------------

    private fun readLoop() {
        val buf = ByteArray(65536)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (running) Log.w(TAG, "tun read failed", e)
                break
            }
            if (n < 0) break
            if (n == 0) { try { Thread.sleep(5) } catch (_: InterruptedException) { break }; continue }
            try {
                handlePacket(buf, n)
            } catch (e: Exception) {
                Log.w(TAG, "packet handling failed", e)
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        when (IpPackets.protocol(buf, len)) {
            IpPackets.PROTO_UDP -> {
                val d = IpPackets.parseUdp(buf, len) ?: return
                if (d.dstPort == 53) handleDns(d)
            }
            IpPackets.PROTO_TCP -> {
                // DNS-over-TLS probes (853) and TCP DNS: reset immediately so the
                // system falls back to plain UDP instead of waiting for a timeout.
                val seg = IpPackets.parseTcp(buf, len) ?: return
                IpPackets.buildTcpReset(seg)?.let(::writeTun)
            }
            else -> Unit
        }
    }

    private fun handleDns(d: UdpDatagram) {
        val q = DnsMessage.parseQuestion(d.buf, d.payloadOffset, d.payloadLength)
        if (q != null && q.clazz == DnsMessage.CLASS_IN) {
            val blocked = filter().isBlocked(q.name)
            Stats.record(q.name, blocked, ownerUid(d))
            if (blocked) {
                val response = DnsMessage.buildBlockedResponse(d.buf, d.payloadOffset, q)
                writeTun(IpPackets.buildUdp(d.dstIp, d.srcIp, d.dstPort, d.srcPort, response))
                return
            }
        }
        forward(d)
    }

    private fun forward(d: UdpDatagram) {
        val servers = upstreams()
        if (servers.isEmpty()) return
        val target = servers[upstreamIndex % servers.size]
        val payload = d.buf.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLength)
        if (payload.size < DnsMessage.HEADER_LEN) return
        val origId = DnsMessage.transactionId(payload)
        val id = allocateId()
        DnsMessage.setTransactionId(payload, 0, id)
        pending[id] = Pending(origId, d.srcIp, d.srcPort, d.dstIp, d.dstPort, SystemClock.elapsedRealtime())
        try {
            socket.send(DatagramPacket(payload, payload.size, target))
        } catch (e: IOException) {
            pending.remove(id)
            if (running) {
                Log.w(TAG, "send to $target failed: ${e.message}")
                rotateUpstream()
            }
        }
    }

    @Synchronized
    private fun allocateId(): Int {
        do {
            nextId = (nextId + 1) and 0xFFFF
        } while (nextId == 0 || pending.containsKey(nextId))
        return nextId
    }

    // ---- upstream -> us -> TUN -----------------------------------------

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        var lastSweep = SystemClock.elapsedRealtime()
        while (running) {
            try {
                pkt.length = buf.size
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                sweep()
                continue
            } catch (e: IOException) {
                if (running) Log.w(TAG, "upstream receive failed", e)
                break
            }
            val len = pkt.length
            if (len < DnsMessage.HEADER_LEN) continue
            val id = DnsMessage.transactionId(buf)
            val p = pending.remove(id) ?: continue
            consecutiveTimeouts = 0
            DnsMessage.setTransactionId(buf, 0, p.origId)
            writeTun(IpPackets.buildUdp(p.dnsIp, p.clientIp, p.dnsPort, p.clientPort, buf, 0, len))
            val now = SystemClock.elapsedRealtime()
            if (now - lastSweep > 1000) { sweep(); lastSweep = now }
        }
    }

    private fun sweep() {
        val cutoff = SystemClock.elapsedRealtime() - QUERY_TIMEOUT_MS
        var expired = 0
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.sentAt < cutoff) { it.remove(); expired++ }
        }
        if (expired > 0) {
            consecutiveTimeouts += expired
            if (consecutiveTimeouts >= 3) rotateUpstream()
        }
    }

    private fun rotateUpstream() {
        consecutiveTimeouts = 0
        upstreamIndex++
        Log.i(TAG, "switching upstream resolver (index $upstreamIndex)")
    }

    private fun writeTun(packet: ByteArray) {
        try {
            synchronized(writeLock) { output.write(packet) }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "tun write failed", e)
        }
    }

    companion object {
        private const val TAG = "TunLoop"
        private const val QUERY_TIMEOUT_MS = 3000L
    }
}
