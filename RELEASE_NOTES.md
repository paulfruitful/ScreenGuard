# Screen Guard v1.6.0 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 26, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.6.0 introduces the optional **Behavioural Protection (Doomscroll Detection)** engine for short-form video feeds, adds a concise block-reason indicator directly on the Protective Shield, and eliminates false positives on lying-down close-up faces and fitness training videos.

---

## What's New in v1.6.0

### Optional Doomscroll Protection (Behavioural Protection Engine)
- **Opt-In in Settings:** Disabled by default (`OFF`); enable anytime under **Settings -> Doomscroll Protection (Optional)** with zero overhead when off.
- **Modular Per-App Feed Detection:** Recognizes short-form video feeds across **TikTok**, **Instagram Reels**, **Facebook Reels**, and **X Video**, while excluding DMs, comments, search, profiles, ordinary timelines, and deliberate long-form videos.
- **Multi-Signal Behavioural Scoring (0–100):** Tracks confirmed active feed duration, distinct video transitions (with duplicate-event suppression and lightweight perceptual-hash fallback), inter-video dwell times, scroll intensity, repeated reopening frequency, meaningful breaks (`>= 60s`), and intended session time (`5 / 10 / 15 / 30 min`, with an optional Strict Session Limit).
- **3-Level Unified Shield & Cool-Off Integration:**
  - **Level 1 (Gentle Reminder):** *Take a Break* or *Continue* (5-minute cooldown).
  - **Level 2 (Strong Intervention):** *Take a Cool-Off Session* or *Return to Safe Home* (10-minute cooldown).
  - **Level 3 (Critical Cool-Off):** Launches Screen Guard's existing Cool-Off Room and resets active doomscroll risk upon completion.
  - Visual content protection always retains strict top priority without competing overlays.

### Protective Shield Block Reason Indicator
- **Clear Block Context:** Displays a concise reason directly on the Protective Shield pill (for example: `High porn / explicit`, `High nudity`, `Explicit animated / hentai`, `Suggestive / thirst trap`, or `Blocked adult account / link`) alongside the active app name.

### False-Positive Hardening (Lying-Down Portraits & Fitness Vlogs)
- **Rotated Face Detection (`90°` / `270°`):** Extends ML Kit talking-head portrait verification to recognize horizontal/lying-down close-up faces (such as neck workouts or bed vlogs) so skin-tone pillows or bedsheets do not trigger false blocks.
- **Fitness & Training Caption Context:** Expands on-device NLP fitness/training vocabulary and allows benign fitness captions to suppress Stage-1-only false alarms when Stage 2 strongly confirms zero nudity.

---

## Privacy Guarantee

- **100% On-Device Processing:** All behavioural tracking, visual inference, OCR, face verification, and DNS filtering run locally on your device.
- **Zero External Uploads:** No video contents, screenshots, private messages, or behavioural histories are ever uploaded.
