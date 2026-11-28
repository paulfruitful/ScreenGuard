# Screen Guard v1.6.6 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 28, 2026 | Platform: Android 11–16 (32-bit & 64-bit ARM)*

---

## Overview

Screen Guard v1.6.6 fixes intermittent local DNS dropouts and adds **Whitelisted Sites** so trusted domains always get through, prevents Bluetooth/Earphones system popups from bypassing excluded apps, makes the Protective Shield scrollable in landscape mode, and tightens visual false-positive guards on normal video and movie scenes.

---

## What's New in v1.6.6

### Reliable DNS Filter + Whitelisted Sites
- **No More DNS Blackouts:** Replaced the single blocking DNS socket with a 16-worker concurrent forwarder, fresh protected sockets per query, transaction-ID verification, fast-fail `SERVFAIL`, and a 45-second in-memory TTL cache.
- **Automatic Failover:** Every preset now fails over automatically across primary, secondary, and tertiary family-safe resolvers (e.g., `1.1.1.3 -> 1.0.0.3 -> 94.140.14.15`).
- **Whitelisted Sites (Always Allowed):** Add domains or URLs under **DNS Content Filter -> Whitelisted Sites** so legitimate sites always resolve cleanly (even if an upstream family DNS blocks them). Known adult domains remain protected when Uninstall Protection is locked.

### Excluded-App & Landscape Shield Fixes
- **Stops Earphones / Bluetooth Popup Hijacking:** Ignored Xiaomi/Android Bluetooth and audio accessory overlays (`com.xiaomi.bluetooth`, `com.miui.misound`, `com.miui.audiomonitor`, `com.android.bluetooth`) so connecting or adjusting earphones while using an excluded app (like Chatflix) never triggers a false scan over it.
- **Scrollable Landscape Protective Shield:** Wrapped the Protective Shield card in a `ScrollView` so the Bible verse, **Return to Safe Browsing**, and **Cool Off** buttons stay reachable in landscape orientation.

### Fewer False Positives on Normal Content
- **Stronger Stage-2 Corroboration & Everyday-Scene Guards:** Prevented single-model `SUGGESTIVE` spikes on normal shirts, warm lighting, and movie frames from overriding safe Stage-2 scores without corroboration, expanded the everyday-scene verifier (suits, microphones, guitars/pianos, laptops/monitors/TVs, sports), and tightened pose/multi-frame confirmation gates.

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual safety detection, everyday-content verification, blacklist/whitelist matching, Cool-Off voice reading, and local DNS routing stay on your device.
- **Zero External Uploads:** No screenshots, audio, browsing history, or custom lists are ever uploaded.
