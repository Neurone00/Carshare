# ☂ Adbrella — keeps the ads off you

A system-wide ad blocker for Android (built for a Samsung Galaxy S23). A small, open Android app that blocks ads and trackers in **every app on the
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

1. On the phone, open the **Adbrella (latest build)** release:
   https://github.com/Neurone00/Carshare/releases/tag/adblock-latest and
   download `adbrella.apk`.
2. Open it, allow installing from this source, install.
3. Open the app, tap **Open umbrella**, accept the VPN connection request.
4. In *Settings* inside the app, follow the three "Make it stick" buttons:
   - Battery optimisation → *Not optimised* (One UI otherwise kills it).
   - Always-on VPN → tap the gear next to *Adbrella* and enable
     **Always-on VPN**. Leave *Block connections without VPN* off.
   - Private DNS → **Off** (Connections › More connection settings). If it is
     set to a provider, Android encrypts lookups straight to that provider and
     the filter never sees them.
5. Optional: add the **Adbrella** tile to the quick-settings panel.

The bundled HaGeZi PRO list is active immediately. Tap **Update** in the
*Lists* tab once to download the latest lists (they refresh daily afterwards).

## Self-updating

Every push builds a new APK, signs it with the committed `app/adbrella.jks`
key, and publishes it together with `update.json` on the rolling
`adblock-latest` release. The app checks that manifest on launch (at most every
6 hours) and once a day in the background, downloads the APK, verifies its
SHA-256, and installs it through PackageInstaller. Android asks you to confirm
the first self-update; from then on Adbrella is its own installer of record and
updates apply silently on Android 12+. Turn it off under *Settings › Updates*.

The build number is the GitHub Actions run number (`versionName 1.<run>`), so
newer builds always have a higher versionCode.

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

CI builds and publishes an APK on every push. All builds are signed with the
committed key `app/adbrella.jks` (password `adbrella`), which is what makes
in-place updates possible. That key only protects a sideloaded personal app;
do not reuse it for anything published to a store.

## Project layout

```
core/   Kotlin/JVM: DNS wire format, IPv4/IPv6+UDP/TCP packet builders, rule parser, compact domain filter
app/    Android: VpnService + packet loop, blocklist repository, WorkManager updater, self-updater, Compose UI, QS tile
```
