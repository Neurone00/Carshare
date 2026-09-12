package io.github.neurone00.adblock.data

/** A downloadable blocklist the user can switch on or off. */
data class Source(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val defaultOn: Boolean,
    /** Copy shipped inside the APK so blocking works before the first download. */
    val bundledAsset: String? = null,
)

object BlocklistSources {
    private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"

    val all: List<Source> = listOf(
        Source(
            "hagezi-pro", "HaGeZi Multi PRO",
            "Ads, trackers, telemetry, scam and malware domains. Balanced: strong protection without breaking apps. Bundled with the app.",
            "${HAGEZI}pro-onlydomains.txt", defaultOn = true, bundledAsset = "blocklist_default.txt",
        ),
        Source(
            "adguard-dns", "AdGuard DNS filter",
            "AdGuard's list, tuned for ads inside mobile apps.",
            "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt", defaultOn = true,
        ),
        Source(
            "stevenblack", "StevenBlack unified hosts",
            "The classic hosts-file list (ads and malware).",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", defaultOn = true,
        ),
        Source(
            "hagezi-doh", "Encrypted-DNS bypass servers",
            "Stops apps side-stepping the filter with their own DNS-over-HTTPS/TLS servers, so they fall back to the filtered DNS.",
            "${HAGEZI}doh-onlydomains.txt", defaultOn = true,
        ),
        Source(
            "hagezi-samsung", "Samsung telemetry",
            "Samsung device tracking and telemetry (Galaxy Store ads, diagnostics).",
            "${HAGEZI}native.samsung-onlydomains.txt", defaultOn = true,
        ),
        Source(
            "hagezi-tiktok", "TikTok telemetry",
            "TikTok tracking endpoints (the app keeps working).",
            "${HAGEZI}native.tiktok-onlydomains.txt", defaultOn = false,
        ),
        Source(
            "hagezi-ultimate", "HaGeZi Multi ULTIMATE",
            "Aggressive. Blocks a lot more but can break some sites and apps. Use with the allow list.",
            "${HAGEZI}ultimate-onlydomains.txt", defaultOn = false,
        ),
        Source(
            "hagezi-light", "HaGeZi Multi LIGHT",
            "Minimal list that never gets in the way. Pick this instead of PRO if something breaks.",
            "${HAGEZI}light-onlydomains.txt", defaultOn = false,
        ),
        Source(
            "hagezi-tif", "Threat Intelligence Feeds",
            "Malware, phishing, scam and botnet domains. Large (40 MB download).",
            "${HAGEZI}tif-onlydomains.txt", defaultOn = false,
        ),
        Source(
            "oisd-big", "OISD big",
            "Community list aiming for zero breakage.",
            "https://big.oisd.nl/domainswild2", defaultOn = false,
        ),
    )

    val defaults: Set<String> = all.filter { it.defaultOn }.map { it.id }.toSet()

    fun byId(id: String): Source? = all.firstOrNull { it.id == id }
}

/** Rules that are always active, regardless of list state. */
object BuiltinRules {
    /** Popular public DoH/DoT endpoints: blocked so apps cannot bypass the filter. */
    val lines: List<String> = listOf(
        "dns.google", "dns64.dns.google",
        "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "doh.opendns.com", "dns.nextdns.io", "doh.dns.sb",
        "dns.alidns.com", "doh.pub", "dns.cleanbrowsing.org", "doh.cleanbrowsing.org",
        "dns.controld.com", "doh.libredns.gr", "dns.mullvad.net",
    )
}
