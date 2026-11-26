# Screen Guard v1.5.0 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 26, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.5.0 delivers three major upgrades: an enhanced on-device visual AI detection engine for clothed suggestive videos and erotic illustrations/animated content, the full Cool-Off Room behavioral self-control system integrated into the Protective Shield, and stronger background Accessibility persistence for Xiaomi HyperOS / MIUI and Android 14–16.

---

## What's New in v1.5.0

### Upgraded On-Device Visual AI Detection (~1.1–2.1s)
- **Clothed Thirst Traps & Twerking Challenges:** Calibrated clean full-media ROI cropping (`0–100% W`, `15–80% H`) and multi-category scoring so clothed sexually suggestive posing and dancing are caught without requiring bare-skin nudity, while keeping ordinary fashion, gym, beach, medical, and portrait content safe.
- **Explicit Animated Porn, Hentai & Erotic Line Art:** Removed bare-skin Stage 2 vetoes and drawing bypasses on high-confidence hentai and erotic illustrations so explicit anime/cartoon content and illicit drawings are classified accurately while ordinary anime remains allowed.
- **Faster Temporal Multi-Frame Confirmation:** High-confidence suggestive frames confirm in 2 frames (~1.1s), and moderate suggestive or erotic-drawing frames confirm in 3 frames (~2.1s).
- **Expanded Multimodal Slang & Caption Fusion:** Added on-device NLP terms for thirst-trap/twerking slang and erotic-art keywords with multimodal confidence fusion.

### Cool-Off Room (Behavioral Self-Control)
- **Protective Shield Integration:** Normal shield activations offer both **Return to Safe Browsing** and **Cool Off**; repeated activations within a rolling time window (default: 3 activations in 3 minutes) automatically escalate to a Cool-Off-required shield until a session is completed.
- **6 Guided Psychological Exercises:** Box Breathing, Urge Surfing, 5-4-3-2-1 Grounding, Cognitive Reframe, Future-Self Anchor, and Physical Reset — supporting 1-minute, 3-minute (default), and 5-minute sessions.
- **Offline Scripture & Calming Instrumental Audio:** Built-in KJV scripture meditation with local audio synthesis and playback controls, fully offline and styled in Screen Guard's native design system.
- **Configurable Settings:** Customize session duration, escalation threshold, detection window, audio defaults, and scripture preferences in Settings.

### Unbreakable Connection Hardening & Setup Flow
- **Recents Clear-All Protection (`excludeFromRecents`):** Prevents Clear All (`OneKeyClean`) from terminating the background Accessibility process.
- **Co-Resident VPN Watchdog:** Detects within seconds if Android stops the Accessibility Service and triggers a protective-shield urgent disconnect alert with 3-step reconnect instructions (auto-clearing the moment Accessibility reconnects).
- **One-Tap Home Setup Checklist & HyperOS Fix:** Guides users through Accessibility Protection, Urgent Shield Alerts, Battery Saver (No Restrictions), and OEM Background Autostart with accurate Xiaomi HyperOS (`MIUIOP 10008`) status detection.

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual classification, OCR, face verification, text inspection, Cool-Off exercises, and DNS filtering happen locally on your device.
- **Zero Disk Retention:** Screen frames are evaluated in temporary memory and never stored or uploaded.
