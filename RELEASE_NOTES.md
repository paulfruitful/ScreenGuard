# Screen Guard v1.6.2 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 27, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.6.2 fixes **Cool Off** and **Take a Break** shield actions on Android 14–16 and Xiaomi HyperOS so tapping either button cleanly closes the underlying app and reliably guides you into a Cool-Off session or custom break interval.

---

## What's New in v1.6.2

### Clean App Exit on Cool Off & Take a Break
- **Exits Underlying App Immediately:** Replaces `GLOBAL_ACTION_BACK` with `GLOBAL_ACTION_HOME` when **Cool Off**, **Take a Break**, or **Return to Safe Browsing** is tapped so TikTok, Instagram, Facebook, X, or browsers exit immediately and stop video/audio playback.
- **Preserves Active Break & Cool-Off Flows:** Guards app-switch cleanup so exiting to the launcher never auto-dismisses the Break chooser or Cool-Off session.

### Unbreakable Cool-Off Launch on Xiaomi HyperOS / Android 14–16
- **Multi-Layer Activity Launch:** Launches `CoolOffActivity` using Android 14+ `PendingIntent` + `ActivityOptions` from the active `AccessibilityService` context while the trusted overlay is still attached.
- **Automatic In-Overlay Cool-Off Room Fallback:** If an OEM ROM (such as Xiaomi HyperOS `MIUIOP 10021`) blocks background Activity launches, seamlessly runs the full interactive Cool-Off session directly inside the Accessibility Overlay (live countdown timer, exercise instructions, interactive prompts, offline scripture, calming instrumental audio with mute toggle, and full completion state reset).

### Custom Duration "Take a Break" Flow
- **Any Interval of Your Choosing (1–60 min):** Tapping **Take a Break** on a Doomscroll reminder now closes the social app and opens **"Good call taking a break."** with quick presets (`1m`, `3m`, `5m`, `10m`, `15m`) plus `− / +` buttons to start a Cool-Off session for any custom duration from 1 to 60 minutes, or dismiss cleanly to your Home screen.

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual safety detection, everyday-content verification, Doomscroll tracking, Cool-Off exercises, and DNS filtering run locally on your device.
- **Zero External Uploads:** No screenshots, video contents, messages, or behavioural histories ever leave your phone.
