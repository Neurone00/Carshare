package io.github.neurone00.adblock.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.neurone00.adblock.data.Prefs
import java.net.InetAddress
import java.net.InetSocketAddress

/** Picks the real resolvers that filtered queries are forwarded to. */
object UpstreamDns {
    class Provider(val id: String, val name: String, val addresses: List<String>)

    val PROVIDERS = listOf(
        Provider("auto", "Automatic (your network's own DNS)", emptyList()),
        Provider("cloudflare", "Cloudflare 1.1.1.1", listOf("1.1.1.1", "1.0.0.1")),
        Provider("quad9", "Quad9 (adds malware blocking)", listOf("9.9.9.9", "149.112.112.112")),
        Provider("adguard", "AdGuard DNS (adds a second ad filter)", listOf("94.140.14.14", "94.140.15.15")),
        Provider("google", "Google 8.8.8.8", listOf("8.8.8.8", "8.8.4.4")),
    )

    private val FALLBACK = listOf("1.1.1.1", "9.9.9.9")

    /** Ordered list of candidate resolvers: the chosen provider first, then the network's, then fallbacks. */
    fun resolve(context: Context): List<InetSocketAddress> {
        val result = LinkedHashSet<InetSocketAddress>()
        val choice = Prefs.upstream
        val provider = PROVIDERS.firstOrNull { it.id == choice }
        val chosen = provider?.addresses ?: listOf(choice) // literal IP typed by the user
        chosen.forEach { addr(it)?.let(result::add) }
        systemDnsServers(context).forEach { result.add(InetSocketAddress(it, 53)) }
        FALLBACK.forEach { addr(it)?.let(result::add) }
        return result.toList()
    }

    private fun addr(ip: String): InetSocketAddress? = try {
        InetSocketAddress(InetAddress.getByName(ip.trim()), 53)
    } catch (_: Exception) {
        null
    }

    /** DNS servers of every connected non-VPN network with internet access. */
    fun systemDnsServers(context: Context): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val out = LinkedHashSet<InetAddress>()
        val networks = try { cm.allNetworks } catch (_: Exception) { emptyArray() }
        for (n in networks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            for (dns in lp.dnsServers) {
                val s = dns.hostAddress ?: continue
                if (s.startsWith(AdBlockVpnService.TUN_PREFIX)) continue // never loop back into ourselves
                out.add(dns)
            }
        }
        return out.toList()
    }
}
