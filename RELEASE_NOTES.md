# Screen Guard v1.6.3 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 27, 2026 | Platform: Android 11–16 (32-bit & 64-bit ARM)*

---

## Overview

Screen Guard v1.6.3 fixes APK installation compatibility across 32-bit and 64-bit Android phones — resolving *"App not installed as app isn't compatible with your phone"* on devices like Samsung Galaxy A13 and *"App not installed as package appears to be invalid"* on Android 14–15 devices like Vivo Y19s.

---

## What's New in v1.6.3

### Universal Device Compatibility (`armeabi-v7a` + `arm64-v8a`)
- **Added Full 32-Bit ARM (`armeabi-v7a`) Support:** Bundles native libraries for both 32-bit (`armeabi-v7a`) and 64-bit (`arm64-v8a`) Android devices, enabling installation on Samsung Galaxy A13 / A04 / A05, Vivo Y19s / Y17s, Tecno, Infinix, and Redmi A-series phones alongside 64-bit flagships.
- **Full APK Signature Schemes (v1 + v2 + v3):** Explicitly enables JAR (v1), APK Signature Scheme v2, and APK Signature Scheme v3 signing for broad OEM Package Installer compatibility (Samsung OneUI, Vivo FuntouchOS 15, Xiaomi HyperOS, Oppo ColorOS) while preserving seamless upgrades for existing users.
- **Removed Emulator-Only `x86_64` Binaries:** Keeps the universal phone APK lean by dropping desktop-emulator-only `x86_64` libraries.

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual safety detection, everyday-content verification, Doomscroll tracking, Cool-Off exercises, and DNS filtering run locally on your device.
- **Zero External Uploads:** No screenshots, video contents, messages, or behavioural histories ever leave your phone.
