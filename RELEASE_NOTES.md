# Screen Guard v1.6.4 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 27, 2026 | Platform: Android 11–16 (32-bit & 64-bit ARM)*

---

## Overview

Screen Guard v1.6.4 upgrades shield exit actions to cleanly shut down offending apps without triggering floating Picture-in-Picture mini-players, makes the unbypassable Protective Guard overlay Cool-Off Room the default everywhere, guarantees immediate re-blocking when sliding back to blacklisted pages from Recents, and adds custom user-managed Blocked Pages & Accounts in Settings.

---

## What's New in v1.6.4

### Clean App Shutdown (No Picture-in-Picture)
- **Prevents Floating Video Mini-Players:** Tapping **Return to Safe Browsing**, **Cool Off**, or **Take a Break** now executes `BACK -> BACK -> HOME -> killBackgroundProcesses` while the opaque shield stays on screen — closing fullscreen video players and offending profiles before leaving the app so X, TikTok, Instagram, and browsers cannot minimize into a floating PiP player.

### Unbypassable Protective Guard Cool-Off Room (Default)
- **Default Everywhere:** The full-screen in-overlay **Protective Guard Cool-Off Room** (`TYPE_ACCESSIBILITY_OVERLAY`) is now the primary Cool-Off experience for Shield **Cool Off**, **Take a Break** custom durations (`1–60 min`), and Settings **Open Cool-Off Room**.
- **Cannot Be Boycotted:** Remains locked across app switches, Recents, and screen lock/unlock until the countdown timer and reflection steps genuinely finish.

### Instant Blacklist Blocking on Resume + Custom Blocked Pages
- **Immediate Block on Resume from Recents:** Scans visible active-window text immediately whenever you switch or slide back into an app so blacklisted adult/OnlyFans profiles block right away every time.
- **Custom Blocked Pages & Accounts in Settings:** Add your own `@handles`, creator names, or profile URLs (`x.com/...`, `instagram.com/...`, `tiktok.com/...`) under **Settings -> Blocked Pages & Accounts** for instant cross-app blocking (protected from impulsive removal when Uninstall Protection is locked).

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual safety detection, everyday-content verification, blacklist matching, Doomscroll tracking, Cool-Off exercises, and DNS filtering run locally on your device.
- **Zero External Uploads:** No screenshots, video contents, messages, or custom blocklists ever leave your phone.
