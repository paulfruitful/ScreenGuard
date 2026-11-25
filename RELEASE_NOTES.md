# Screen Guard v1.1.0 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 25, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.1.0 adds built-in **Local DNS Content Filtering**, **PIN-Locked Uninstall Protection with Android Device Admin**, a searchable **Trusted Apps Picker**, and **smarter false-positive reduction** — keeping Strict mode uncompromising on explicit content while dramatically reducing accidental shields on non-sexual scenes.

---

## What's New in v1.1.0

### Local DNS Content Filter (Active from Launch)
- Blocks adult and explicit domains at the network level before pages or media even load.
- Prompts for Android's official VPN consent on first launch and starts automatically on app startup, accessibility connect, and device reboot once approved.
- Routes **only DNS queries** through a local on-device tunnel — all other app traffic continues over your normal Wi-Fi or cellular connection.
- Includes curated family-safe DNS presets (**Cloudflare Family**, **AdGuard Family**, **CleanBrowsing Adult**, **OpenDNS FamilyShield**), custom resolver support, and a personal custom domain blocklist.

### Uninstall Protection & Relapse Lock
- Set a **4–8 digit Lock PIN** (or have a trusted friend or accountability partner set it for you) in the **Protect** tab.
- When locked, **Pause Protection** (Home & Settings) and **Stop DNS Filter** (DNS screen & notification) are blocked natively until unlocked with the PIN.
- One-tap **Android Device Admin Guard** blocks quick uninstallation from the home screen, Play Store, and App Info.

### Smarter False-Positive Reduction (Without Loosening Strictness)
- **Class-Specific Confirmation:** Explicit `PORN`, `NUDITY`, and `SEXUAL_ACTIVITY` remain immediate **1-frame** blocks, while broad `SUGGESTIVE` frames require 2 consecutive frames to eliminate single-frame scroll spikes.
- **Benign Non-Sexual Context Veto:** Gym/fitness, MMA/UFC/combat sports, swimming, medical/dermatology, and parenting contexts veto borderline suggestive false alarms when explicit scores are low — while never overriding explicit porn or adult domains.
- Backed by an automated hard-negative & strictness unit test suite.

### Searchable Trusted Apps Picker
- Easily pick trusted apps to exclude from protection via a searchable list of installed apps — no manual package names required.

---

## Privacy Guarantee

- **100% On-Device Processing:** Zero cloud uploads and zero external tracking servers.
- **Zero Disk Retention:** Screen frames are inspected in temporary memory and discarded immediately.
- **DNS Privacy:** No browsing history is logged or persisted.
