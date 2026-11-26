# Screen Guard v1.4.1 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 26, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.4.1 is a focused stability and reliability update that eliminates an Android 16 native crash in ML Kit Pose Detection and adds one-tap Background Autostart guidance for Xiaomi HyperOS / MIUI and OEM devices so the Accessibility Service stays connected.

---

## What's Fixed in v1.4.1

### Accessibility Service Stability & Crash Fix
- **Eliminated Android 16 Native JNI Crash (`SIGABRT`):** Removed the legacy `com.google.mlkit:pose-detection:18.0.0-beta5` (`mediapipe-internal:17.0.0-beta10`) dependency that triggered `NoSuchFieldError` native aborts and inflated process RAM on Android 16 devices.
- **Lower Background Memory Footprint:** Retaining lightweight ML Kit Face Detection (talking-head portrait veto), ML Kit On-Device OCR + Contextual NLP, LiteRT dual-model visual verification, and the `< 60 ms` split-second X/social media account and keyword blocker without MediaPipe pose overhead.

### Xiaomi HyperOS / OEM Keep-Alive Controls
- **One-Tap Background Autostart Settings:** Added a direct shortcut on the Home recovery prompt and under **Settings -> Permissions & Privacy -> Background Autostart (Prevent Phone Kill)** so Xiaomi, Redmi, POCO, Oppo, Realme, Vivo, Huawei, and OnePlus users can prevent Recents Clear-All (`OneKeyClean`) from terminating the Accessibility Service.
- **Clearer Accessibility Reconnect Instructions:** When Android places the service in a stopped state after a system kill, the Home screen now clearly guides users to toggle Screen Guard OFF and back ON in Accessibility Settings and enable Background Autostart.

---

## Privacy Guarantee

- **100% On-Device Processing:** All fast-path text inspection, ML verification, and DNS filtering happen locally on your device.
- **Zero Disk Retention:** Screen frames and inline text are evaluated in temporary memory and never stored or uploaded.
