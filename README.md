# Aegis VPN Android

Native Android app with the approved orange shield and white/orange/green iOS-inspired UI.
It supports FREE VPN Gate OpenVPN servers and paid .ovpn profiles with optional username/password; remote OpenVPN connection uses the separate free GPL client [OpenVPN for Android](https://github.com/schwabe/ics-openvpn).
The network tunnel is created by that app; Aegis is a remote controller and directory. This is not a standalone tunneling engine.
It never shows Connected before the OpenVPN client reports the CONNECTED state.

## How to install

Download `AegisVPN-Android-debug` from GitHub Actions build artifacts.
Also install **OpenVPN for Android** (package `de.blinkt.openvpn`).
On first use tap Refresh Free Servers, select an OpenVPN relay, then swipe the switch. Approve the OpenVPN authorizations.
For paid subscriptions, tap Settings > Import paid .ovpn, choose a self-contained OpenVPN configuration and optionally enter credentials.
Credentials and imported configuration are stored with AndroidKeyStore AES-GCM on-device and are never uploaded to GitHub.

## Warnings

Volunteer VPN Gate relays are untrusted and often stop responding. Directory auto-update is an approximately six-hour background schedule plus on-launch refresh, not a promise of uptime.
Other protocols (VLESS / REALITY / Hysteria 2 / AmneziaWG) are NOT included as tunnel engines in this version.
The free relays' reported pings are directory-supplied, not fresh network measurements.
GitHub Actions creates a debug APK; this is not a production signed release.

Credits: OpenVPN external API AIDL layout based on the Apache-2.0 licensed [ics-openvpn remoteExample](https://github.com/schwabe/ics-openvpn/tree/master/remoteExample). The separate OpenVPN client is GPLv2.

## 0.4 update

- Replaced corrupt 2 KB JPEG resource with the original full PNG shield.
- Moving translucent background ribbons, draggable slider, connecting arc, animated rounded dialogs. Android 12+ provides system window blur when supported/enabled; older devices retain translucent rounded surfaces.
- OpenVPN callback registration now occurs after API authorization. VPN Gate rows with an unavailable ping are retained instead of discarded.
- Connection library: 13 user-specified Telegram public channels plus the V2RayAggregator filtered GitHub feed. Bounded concurrent fetches, duplicate removal, per-source success/error timestamps, last-good cache retained on failure for up to 72 hours. Six-hour OS scheduled updates plus one-hour stale refresh on app launch; Android can delay background jobs.
- V2Ray entries can be copied/imported into separately installed v2rayNG. Telegram proxy links open Telegram. NapsternetV entries open the source file post in Telegram (public previews do not expose the file bytes). These paths DO NOT connect an in-app VPN or imply tested connectivity.
- The library is a config directory, not a claim that any free node works on the user's ISP. Source unavailability and empty previews are exposed in Source status.
- Pure-Java parser regression tests run before the APK build.

No embedded V2Ray or NapsternetV engine is included in 0.4. OpenVPN still uses OpenVPN for Android. APK/debug signing may differ between CI runners; uninstalling an older debug build can be required and deletes imported profiles. Export your original .ovpn first.
- Optional endpoint testing measures TCP reachability for up to 40 configs/proxies on the phone and sorts measured results first. It is NOT a protocol handshake, speed test or proof of a working VPN; VMess and encoded Shadowsocks remain untested by this probe. OpenVPN connections time out visibly after 60 seconds.

Public Telegram links/file-post references are also mirrored by GitHub Actions every four hours (best effort). The app falls back to this GitHub mirror when Telegram previews are blocked, retains original update times and rejects mirrors older than 72 hours. The collector never logs private account profiles or credentials, and failure retains earlier files. Workflow schedules may be delayed or disabled by GitHub for inactive repositories.

## 0.4.1 smart refresh and layout fix

- The home screen now provides an accessible **Smart update · select best** action above the bottom navigation instead of hiding the connection library below it.
- One action refreshes both the VPN Gate OpenVPN directory and the V2Ray / Telegram / NapsternetV public configuration feeds; its completion screen reports counts and failures.
- After a successful free-directory refresh, free mode selects the server with the lowest **VPN Gate reported** ping. This is not a measured OpenVPN handshake, actual throughput, or proof that the server will work on the user's network. Existing purchased OpenVPN profiles are never replaced by this selection.
- Requests made while a source refresh is already running now receive a completion callback instead of silently being ignored.
- **Not yet supported:** in-app Telegram account login, independent V2Ray / NapsternetV VPN tunnels, automatic VPN-protocol failover, guaranteed connectivity. Install the separate supported clients where indicated.
- GitHub Actions builds a **debug APK**. CI and physical-device tests must pass before treating this release as verified.

## 0.5 experimental native sing-box tunnel (arm64)

- Adds an Android `VpnService` implementation with sing-box `libbox` bundled as an **arm64-v8a native library**. Built in GitHub Actions from verified upstream commit `f63091d14d8984d53dc9a5563cb72af978b9779e` (sing-box tag v1.12.22) using the SagerNet gomobile fork. This is not a library downloaded from an unknown config channel.
- Supports link-to-configuration translation for VLESS (TLS/Reality), VMess, Trojan, Shadowsocks and Hysteria 2. Not every protocol variant, transport or free-share format is supported, and imported configurations are not trusted.
- The slide-to-connect control can initiate an in-app TUN session, while paid OpenVPN profiles still use the separate **OpenVPN for Android** client. Telegram proxy links open Telegram and NapsternetV file posts still use an external client.
- VPN Gate's official directory may be DNS-blocked by networks. It now falls back to an off-network GitHub mirror which is refreshed every four hours (best effort); an upstream public snapshot is checked against a commit-pinned SHA-256 before use. On all failures, cached profiles are preserved and a real error is reported.
- `Tunnel active` only means that Android granted the local TUN interface and libbox started. It **does not** guarantee that the selected public upstream node can carry traffic, or that it will remain stable. TCP probing is preliminary only.
- No Telegram account login, session scraping, untrusted executable profile installation or secret upload is performed. Public/free server links can be dangerous; never assume their operators are trustworthy.
- The APK is not yet production verified: **CI compilation and real-device network tests are separate requirements**. Until an Android device has successfully connected through the tunnel, this remains an experimental build.
- Only `arm64-v8a` devices are supported by the new native artifact; other ABIs need dedicated builds.
- GPL-3.0 notice: sing-box is copyrighted by its upstream authors and licensed under GPL-3.0-or-later; the source tree and build process are available publicly for review. See the upstream [sing-box LICENSE](https://github.com/SagerNet/sing-box/blob/v1.12.22/LICENSE).

## 0.6 · visual and manual-control update

- Home layout now follows the approved reference more closely: a three-line hero, luminous orange/green connect control, soft glass location/traffic cards and fixed bottom navigation. The slider interpolates with a spring; breathing rings and responsive tab fades are real animations.
- Connection library opens as bottom sheets with swipeable lists, colored VLESS/VMess/Trojan/Shadowsocks/Hysteria2 markers, colored **TCP latency** pills, and separate actions to pin a server, connect, test, copy or delete.
- Manual native selection is encrypted in Android Keystore and **not overridden** by Smart refresh. Smart mode is still available and creates a sing-box urltest group of up to twelve candidates.
- Locations has dedicated OpenVPN, native V2Ray, Telegram MTProto, and NapsternetV/source categories, with vertical scrolling. VPN Gate GitHub source cache can hold up to 80 volunteer profiles when upstream provides them; actual country coverage depends on available servers.
- Telegram MTProto can be typed manually or selected from public sources, with an explicit "Add to Telegram" link; the user approves the proxy in Telegram, no Telegram account permissions needed.
- Config import accepts readable share links from clipboard, plaintext file, paste, or manually provided HTTPS subscription. Support for proprietary encrypted `.npv`/`.npv4` **is not implemented**. NapsternetV public post URLs are *references* and not usable tunnel profiles; unsupported file posts are not mislabeled as VPN connections.
- Further public V2Ray and Hysteria2 feeds have been added, but their endpoints are untrusted and may be unavailable. A TCP test only proves the port's preliminary reachability, not VPN authentication or working traffic.
- Displayed download/upload speeds remain blank rather than fabricated until real tunnel telemetry is implemented. A tun interface is not a guarantee of end-to-end protection.
- This build is Android arm64 (API 26+) debug/pre-release. GitHub CI proves compile and artifact generation; real-device visual performance, connectivity and Telegram intents still require device testing.

## 0.7 · responsiveness, country feeds, MTProto, NPV and bounded recovery

- Android Canvas now uses the GPU instead of a full-screen software layer. Gradients are cached, full-screen shadows and idle redraws are removed, 30-fps ambient updates only run when connected/connecting, and long location lists render only visible rows. The server picker shows 40 nodes per page.
- The Home and Stats download/upload numbers use Android `TrafficStats` sampled for **this app UID** at ~1.4-second intervals. This reflects approximate process network traffic and may include config updates; it is **not** proof of VPN payload throughput. On unsupported platforms and before tunnel activation it shows a dash, not a fake value.
- Smart native mode uses the sing-box `urltest` policy at a **20-second** interval. The watchdog checks HTTPS access through an Android VPN network every ~15 seconds, using two endpoints, requiring three consecutive failures and a previously confirmed success before attempting a bounded core restart (max 4, minimum 90 seconds between attempts). A blocked or inaccessible health-check endpoint cannot trigger an unbounded restart storm. This is a **best-effort in-process** recovery mechanism; external OpenVPN, Android process termination, deliberate disconnects and network-level outages remain outside this guarantee.
- MTProto updates no longer depend on parsing Telegram channel previews. One-tap "Refresh MTProto only" uses `SoliSpirit/mtproto` public subscription; users choose any proxy and approve it in the Telegram app. Aegis never requests Telegram credentials.
- Public NapsternetV source `npv_iran` returned no entries in observed GitHub feed jobs; **`mitivpn` was accessible**. The mirror and in-app category now display only the three newest observed NPV attachment post references from `mitivpn`. A link to a Telegram post **is not** the file contents. For posts with readable standard protocol links, Aegis tries to parse the public text; encrypted `.npv` still cannot be executed or decrypted by Aegis.
- Additional country-labeled V2Ray feeds: US, Canada, France, Switzerland, Germany, UK, Netherlands, Japan, Singapore and Australia, sourced from `Mokafela/Config-Finder`. The country selector filters by **source label**, which may not represent the true exit country. Actual availability changes continuously.
- Top-right settings icon was removed as requested. Settings are now accessible from the connection library; local manual server selection is unchanged by background updates.
- The experimental debug artifact is only built for **arm64**. CI compilation is not equivalent to real-world network or device animation testing.

## 0.8 · hardening after device feedback

- Fixed missing `tls.utls` for VLESS+REALITY shares. Every REALITY outbound now explicitly enables a supported uTLS fingerprint, defaulting to chrome when the source omits `fp`; invalid public keys, short IDs, and unsupported transports are rejected before selection.
- Native core initialization attempts to skip an incompatible auto-group node instead of failing all members of the group with `initialize outbound[N]`; no direct/bypass fallback is added.
- Smart selection prefers diverse endpoints using the phone's own bounded TCP probes, followed by sing-box HTTP URLTest. TCP reachability **does not** guarantee a working proxy, and results from another geography/ISP cannot determine what is fastest on a user's network. No external publisher's speed or "alive" figures are presented as independently confirmed.
- Independent MTProto sources added: `tgmtproxy/telegram-mtproto-proxy-list` and `shablin/mtproto-proxy`, with `SoliSpirit/mtproto` fallback. Public proxy availability changes and Telegram always confirms before enabling.
- NPV screen explicitly says **three public post references, not three tunnel profiles**; only readable standard links in a post are importable. Encrypted `.npv`/`.npv4` remains unsupported without a compatible decoder.
- The square application-icon bitmap is circularly clipped in the slider; the glow gradient is cached, idle ambient animation does not continuously run, and touch/slide easing is smoothed. Visual results and actual VPN connectivity still need Android hardware testing.
- Do not put trust in public volunteer proxies for confidential traffic. A list being downloaded, a TUN being established, or a remote TCP socket being open is not equivalent to an end-to-end verified protected path.
