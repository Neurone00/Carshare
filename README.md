# AdBlock DNS — system-wide ad blocker for Android (Samsung Galaxy S23)

A small, open Android app that blocks ads and trackers in **every app on the
phone** without root. It runs a local VPN that captures only DNS lookups,
answers ad/tracker domains with a null address, and forwards everything else
to a real resolver. No traffic other than DNS is tunnelled, so there is no
speed penalty and negligible battery cost.

## What it blocks — and what it can't

| Ad type | Blocked? | Why |
| --- | --- | --- |
| Banner and interstitial ads in apps and games | **Yes** | Served by ad networks (AdMob, Unity, AppLovin, …) on their own domains |
| Web page ads in any browser | **Yes** | Ad servers are separate domains |
| Trackers, analytics, telemetry (incl. Samsung's) | **Yes** | Own domains |
| Ads in Samsung apps (Galaxy Store, Weather, …) | **Mostly** | Own domains |
| YouTube in-stream / pre-roll video ads | **No** | Served from the same `googlevideo.com` servers as the video itself. No DNS or network filter can separate them. Use NewPipe or a ReVanced-patched YouTube instead. |
| Instagram / Facebook / TikTok feed ads | **No** | Same reason: the ad is part of the feed response from the app's own API. |
| Twitch / Spotify audio ads | **No** | Same reason. |

This is a hard limit of any DNS or network-level blocker (AdGuard, Blokada,
Pi-hole, NextDNS have exactly the same limit). Blocking those requires
patching the specific app, which is what ReVanced does for YouTube.

## Installing on the S23

1. Download `adblock-dns-debug.apk` (or `-release.apk`) from the latest
   **Build** run under *Actions* in this repository (or from *Releases*).
2. Open it on the phone, allow installing from this source, install.
3. Open the app, tap **Turn on**, accept the VPN connection request.
4. In *Settings* inside the app, follow the three "Make it stick" buttons:
   - Battery optimisation → *Not optimised* (One UI otherwise kills it).
   - Always-on VPN → tap the gear next to *AdBlock DNS* and enable
     **Always-on VPN**. Leave *Block connections without VPN* off.
   - Private DNS → **Off** (Connections › More connection settings). If it is
     set to a provider, Android encrypts lookups straight to that provider and
     the filter never sees them.
5. Optional: add the **AdBlock DNS** tile to the quick-settings panel.

The bundled HaGeZi PRO list is active immediately. Tap **Update** in the
*Lists* tab once to download the latest lists (they refresh daily afterwards).

## Features

- Local VPN DNS filter, works for all apps and all browsers.
- 288k+ blocked domains out of the box, ~9 MB of RAM, sub-microsecond lookups.
- Curated list presets (HaGeZi PRO/LIGHT/ULTIMATE, AdGuard DNS filter,
  StevenBlack, OISD, threat intel, Samsung/TikTok telemetry) plus any custom URL.
  Understands hosts files, plain domain lists and Adblock `||domain^` syntax.
- Your own always-block / always-allow rules; one-tap **Allow** from the log.
- Per-app bypass for apps that refuse to run behind a VPN.
- Shows which app made each blocked request.
- Catches apps that hard-code public resolvers (8.8.8.8, 1.1.1.1, …) and
  resets DNS-over-TLS/HTTPS attempts so nothing sneaks past the filter.
- Auto-start on boot, always-on VPN support, quick-settings tile, daily
  background list updates on Wi-Fi.
- No accounts, no analytics, no network access except list downloads.

## How it works

```
app ──DNS query──▶ tun0 (10.111.222.1) ──▶ TunLoop
                                             ├─ blocked?  → reply 0.0.0.0 / ::   (app fails fast, nothing loads)
                                             └─ allowed?  → forward via a VPN-protected UDP socket
                                                            to your network's resolver, relay the answer
```

The VPN interface only routes the fake resolver address (and a handful of
public resolver IPs), so all other packets leave the phone normally.

## Building

Requires JDK 17 and the Android SDK (platform 34). `./gradlew :app:assembleDebug`
produces `app/build/outputs/apk/debug/app-debug.apk`. The DNS/packet/filter
engine lives in the pure-JVM `core` module and is unit-tested with
`./gradlew :core:test`.

CI builds an APK on every push. To get a stable release signature (so updates
install over the previous version), add repository secrets
`ADBLOCK_KEYSTORE_B64` (base64 of a `.jks`), `ADBLOCK_KEYSTORE_PASSWORD`,
`ADBLOCK_KEY_ALIAS`, `ADBLOCK_KEY_PASSWORD`. Without them the release build is
signed with the runner's debug key, which changes between runs: uninstall
before installing a newer build.

## Project layout

```
core/   Kotlin/JVM: DNS wire format, IPv4/IPv6+UDP/TCP packet builders, rule parser, compact domain filter
app/    Android: VpnService + packet loop, blocklist repository, WorkManager updater, Compose UI, QS tile
```
