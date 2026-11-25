# Milestone 5 — Local DNS Content Filter

## What was added

Screen Guard now offers an optional **DNS Content Filter** that blocks explicit sites at the DNS lookup stage, before a page loads.

The feature is implemented as a user-approved Android `VpnService`:

- The app asks Android for VPN consent through the standard system dialog.
- After approval, Screen Guard creates a local VPN interface **only for DNS**.
- DNS queries sent to the tunnel's DNS server are inspected on-device.
- Queries for blocked domains receive an immediate `NXDOMAIN` response.
- Allowed queries are forwarded to a family-safe upstream resolver chosen by the user.
- All other network traffic continues over the normal Wi-Fi/mobile connection and is never routed through, inspected by, or stored in Screen Guard.

The app cannot enable this silently. Android always shows the VPN consent dialog and an active VPN indicator. The filter can be stopped from the Screen Guard screen, its notification, or Android Settings → VPN.

## User controls

Open **Settings → DNS Content Filter** to:

- Approve Android VPN access, then start or stop the filter.
- Choose a preset resolver:
  - Cloudflare Family (`1.1.1.3`)
  - AdGuard Family (`94.140.14.15`)
  - CleanBrowsing Adult (`185.228.168.10`)
  - OpenDNS FamilyShield (`208.67.222.123`)
  - Custom IPv4 address or hostname
- Enable or disable the small built-in starter blocklist.
- Add custom domains. Subdomains match automatically (`example.com` blocks `www.example.com`).
- Remove custom domains by tapping their chips.
- View active/off status, current resolver, blocked/allowed/failed counters, and last runtime event.

Configuration is stored in app-private `SharedPreferences`. Screen Guard does **not** store the names of sites you visit, browsing history, or packet contents. Only aggregate counters and a coarse runtime event label (for example `QUERY_BLOCKED`) are kept in memory.

## Honest limitations

This is a helpful filter, not a guarantee.

- It filters ordinary **IPv4 UDP port 53** DNS queries routed to the tunnel DNS server.
- It does **not** inspect HTTPS content or route general internet traffic.
- Apps using their own encrypted DNS (DoH/DoT), hardcoded IP addresses, Android Private DNS, or a different VPN can bypass it.
- IPv6 DNS queries are not handled by this build.
- The selected upstream resolver provider can see DNS queries. Screen Guard itself sends them to that provider and uploads nothing else.
- A domain block is not the same as blocking an account, app, camera, or operating system feature.

The UI states these limitations plainly before and after activation. If this build is ever submitted to Google Play, the app's Data safety and permissions declarations must disclose the VPN use and the existing `QUERY_ALL_PACKAGES` access; no store policy bypass is used here.

## Android implementation

| File | Purpose |
| :--- | :--- |
| `dns/DnsFilterModels.kt` | Profiles, starter blocklist, config/status data classes |
| `dns/DnsFilterState.kt` | Private config persistence and runtime counters |
| `dns/DnsPacketProcessor.kt` | IPv4/UDP/DNS parsing, NXDOMAIN building, packet wrapping |
| `dns/DnsFilterVpnService.kt` | Foreground VPN service and DNS relay loop |
| `bridge/ScreenGuardModule.kt` | React Native bridge methods |
| `bridge/EventBridge.kt` | `DNS_FILTER_STATUS_CHANGED` event |
| `res/xml` / manifest | VPN service declaration, INTERNET permission |
| `src/screens/DnsFilterScreen.tsx` | User-facing configuration screen |

### Native bridge methods

- `getDnsFilterStatus()`
- `requestDnsFilterPermission()`
- `startDnsFilter()`
- `stopDnsFilter()`
- `updateDnsFilterConfig(configJson)`

### VPN routing safety

The VPN builder adds:

- `10.0.0.2/24` as the tunnel address
- a `/32` route for `10.0.0.2` only
- `10.0.0.2` as the DNS server

It deliberately does **not** add `0.0.0.0/0`, so non-DNS traffic is not captured. The upstream DNS socket is `protect()`ed so it is not routed back into the tunnel.

## Physical-device verification

The APK was rebuilt as version 1.2.0 (versionCode 3), copied to `ScreenGuard-debug.apk`, and installed successfully on the connected Xiaomi 24117RN76G (Android 16) with `adb install -r -d`. The installed package reports versionCode 3 / versionName 1.2.0, and launch logcat showed no fatal exception. The following interaction checks remain to be confirmed manually on-device:

1. Fresh install/update keeps existing preferences and accountability state.
2. Settings → DNS Content Filter opens without crash.
3. First start shows the Android VPN consent dialog.
4. Denying consent keeps the filter OFF and shows no VPN indicator.
5. Approving consent, then tapping **Start DNS filter**, shows the persistent VPN indicator and notification.
6. Adding `example.com` and visiting `https://example.com` in a standard browser fails to resolve; allowed sites still load.
7. Removing the domain restores resolution after refresh/reload.
8. Changing starter-list toggle while the filter is active takes effect on the next query.
9. Stopping from the notification removes the VPN indicator and sets status to OFF.
10. Reopening the app after stopping does not silently restart the filter.
11. Reboot does not silently re-enable an explicitly stopped filter.
12. The app does not request or require Accessibility/overlay permission for DNS filtering.

`npx tsc --noEmit` and `gradlew assembleDebug --no-daemon` must pass before installing any candidate build.
