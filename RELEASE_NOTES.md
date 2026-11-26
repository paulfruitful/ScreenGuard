# Screen Guard v1.4.2 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 26, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.4.2 hardens Accessibility Service persistence on Xiaomi HyperOS / MIUI and Android 14–16, adds a protective-shield urgent disconnect alert whenever the phone stops the background service, and puts all keep-alive permissions into a one-tap Home setup checklist.

---

## What's New in v1.4.2

### Unbreakable Connection Hardening
- **Recents Clear-All Protection (`excludeFromRecents`):** Excludes Screen Guard's main window from the Recents task-clear sweep so tapping Clear All (`OneKeyClean`) no longer kills the background Accessibility process.
- **Co-Resident VPN Watchdog:** Adds a lightweight background watchdog inside the `START_STICKY` DNS Filter VPN service that detects within seconds if Android places the Accessibility Service into a stopped/crashed state.
- **Strict Event Crash Boundary:** Wraps `onAccessibilityEvent` in a top-level safety guard so unexpected accessibility parcel/node errors can never crash the service.

### Urgent Protective-Shield Disconnect Alert
- **Full-Screen Shield Alert:** When protection is enabled and the phone disconnects the Accessibility Service, Screen Guard renders an urgent full-screen alert styled like the protective shield (via system overlay when Display Over Other Apps is granted, plus a high-priority urgent notification and full-screen alert screen) with clear 3-step instructions to toggle Accessibility OFF and back ON.
- **Instant Auto-Clear:** All urgent alerts and overlays dismiss automatically the moment Accessibility reconnects.

### One-Tap Protection Setup Flow
- **Home Setup Checklist:** Guides new and returning users through enabling Accessibility Protection, Urgent Shield Alerts (Display Over Other Apps), Battery Saver (No Restrictions), and Xiaomi/OEM Background Autostart in one tap each.

---

## Privacy Guarantee

- **100% On-Device Processing:** All detection, fast-path text inspection, and DNS filtering happen locally on your device.
- **Zero Disk Retention:** Screen frames are evaluated in temporary memory and never stored or uploaded.
