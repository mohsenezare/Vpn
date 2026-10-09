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
