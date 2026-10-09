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
