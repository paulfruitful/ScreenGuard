# Screen Guard v1.3.0 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 25, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.3.0 introduces a **multi-modal on-device verification engine** powered by Google ML Kit Face Detection, Pose Detection, On-Device OCR, and a contextual NLP classifier — alongside a **2–3 frame accumulative confirmation gate**, **mandatory dual-model agreement**, and **in-app GitHub Releases OTA updates**. Non-explicit videos and talking-head clips play cleanly without false alarms while explicit content and clothed suggestive videos stay firmly blocked.

---

## What's New in v1.3.0

### Multi-Modal On-Device Verification (Zero False-Positive Focus)
- **ML Kit Face Detection (Talking-Head Veto):** Detects close-up talking-head, vlog, and video-call portraits so ordinary faces, shoulders, camisoles, and dimly lit rooms are not mislabeled as nudity.
- **ML Kit Pose Detection (Clothed Suggestive / Twerking Guard):** Tracks full-body posture and hip prominence across consecutive frames regardless of clothing, catching sustained sexually suggestive dancing while allowing workouts and sports.
- **ML Kit On-Device OCR & Contextual NLP:** Reads burned-in captions inside TikTok, Reels, X, and WhatsApp videos and classifies non-sexual context (tailoring, business, gym, MMA, medical, faith) versus suggestive promos and obfuscated adult text.

### 2–3 Frame Accumulative Confirmation & Dual-Model Consensus
- **Fixed Policy Threshold Floors:** Replaced overly aggressive single-frame floors (`24%`–`35%`) with calibrated precision-first thresholds (`62%`–`72%`).
- **Mandatory Dual-Model Agreement:** Stage 2 (`OpenNSFW ResNet-50`) independently verifies every suspicious Stage 1 frame and vetoes single-model spikes.
- **2–3 Frame Accumulative Gate:**
  - Explicit adult domains block instantaneously (**0 s**, deterministic).
  - Visual explicit/nudity content confirms across **2 consecutive frames (~1.2 s)**.
  - Suggestive and clothed provocative clips confirm across **3 consecutive frames (~2.2 s)**.

### In-App GitHub Releases OTA Updater
- Automatically checks GitHub Releases on app launch and surfaces a clean **Update Available** banner on the Home screen and in **Settings → App Updates**.
- Downloads release APKs directly in-app with a live progress bar and hands them off to Android's official Package Installer.

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual, face, pose, OCR, and NLP verification runs locally on your phone with zero cloud uploads.
- **Zero Disk Retention:** Screen frames are inspected in temporary memory and discarded immediately.
- **DNS Privacy:** Local DNS filtering never logs or persists browsing history.
