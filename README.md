# Aegis VPN 0.5.7

Based on the existing 0.5.5 Android UI. Glass cards, connection slider, iOS spinner, traffic statistics, notifications and Quick Settings remain.

Only the five user-selected V2Ray subscriptions in SourceHub.java are fetched. OpenVPN, its relay mirror, external-app integration and older subscriptions have been removed. The fifth GitHub URL uses its equivalent raw-content URL to avoid redirects. Existing old selections are cleared on migration.

## Update and test

Tap **Update · test · select best**. The app downloads all five lists, decodes plaintext/Base64 subscriptions and deduplicates links. It tests each supported configuration with one isolated worker process. Each test creates an isolated sing-box outbound and sends an HTTPS HEAD request through that outbound to https://www.gstatic.com/generate_204. A verified TLS response with status 204 is required. Delay includes proxy connection, destination TLS and response headers; it is not an ICMP ping or a bare TCP test. Timeout is six seconds per HTTPS request. There is no direct fallback.

After all tests finish, the fastest successful configuration is selected and stored encrypted. If none succeeds, no failed configuration is automatically selected. Cancelling or a network change prevents automatic selection. Manual selection is available in the server library. Tests can take several minutes for large subscriptions. Results are in-memory observations, not availability guarantees. Successful caches from these five sources may be retained for up to 72 hours if refresh fails; source status displays timestamps/errors.

## Build

GitHub Actions builds pinned sing-box 1.12.22 commit f63091d14d8984d53dc9a5563cb72af978b9779e, adds app/native/aegis_latency.go, generates the arm64 libbox AAR, runs parser/traffic tests and assembles the APK. Android 8+ / arm64. Version code 19.

The VPN engine is GPLv3; retain upstream license and source availability when distributing derived binaries. Connectivity on a particular user's ISP must be tested on that device.

Native tests now run in a private :probe service process. Binder death and deadlines are handled without terminating the activity or VPN process. Three consecutive worker failures stop the scan visibly. CI exercises actual Android worker creation, invalid-config handling, worker termination and rebind using an x86_64 emulator; the delivered APK remains arm64.
