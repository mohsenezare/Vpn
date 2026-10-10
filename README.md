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

## 0.5.2 minimal change

- Adds V2Ray Smart/OpenVPN mode selection on the Home screen immediately above the existing connection slider. Mode is strictly obeyed; missing servers do not cause a silent protocol switch.
- Removes Telegram proxies and NapsternetV from the app, parser, source fetcher and mirrored feeds. Keeps free and paid OpenVPN support plus V2Ray.
- Leaves the 0.5 Home slider, layout, glass cards, animations, stats and remaining features intact.
