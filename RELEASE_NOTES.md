# Screen Guard v1.4.0 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 25, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.4.0 introduces a **split-second (< 60 ms) inline fast-path** for X (Twitter) and social media feeds, blocking major adult accounts, explicit hashtags, and high-priority NSFW keywords the instant they enter the screen — without waiting for a screenshot or neural network cycle.

---

## What's New in v1.4.0

### Split-Second X / Social Media Fast-Path (< 60 ms)
- **Zero-Wait Inline Shield:** Inspects fresh incoming Accessibility text parcels in `< 0.1 ms` on X, TikTok, Instagram, Facebook, Snapchat, and Reddit and triggers the protective overlay immediately when a blocked adult account or priority keyword appears.
- **Major X Porn Accounts & Performers Added:** Expanded the on-device directory to **470+ adult accounts, aggregators, and studio names**, including `ShmoodNasty`, `TheLionsDens28`, `goodgoodgoons`, `EbonyCct`, `EbonyLeakz`, `BaddiesHub`, `VidVaultX`, `ShadyTaboo`, `CaptionTV`, `slvttyvisions`, `freaksstown`, `nuttyvidspage`, `Ricky's Room`, `Willow Rider`, `Princess Emily`, and hundreds more.
- **High-Priority Root & Keyword Rules:** Instant detection for `shmood*`, `goon`/`goons`, `slut`/`slvt`, `freak`/`freaks`, `ebony`, `bbc`, `pawg`, `18+`, `chudai`, `wataa`, `pussy`, `cock`, `dick`, `tits`, `ass`, `phat ass`, `moan`, `horny`, `doggy`, `throat goat`, `heads and backshots`, `nut` + adult emoji, and `#nsfw` hashtags.
- **Smart False-Positive Exemptions:** Strict word boundaries and context guards ensure innocent terms like `BBC News`, `class`/`pass`/`glass`, `peanut`/`minute`, `Greek mythology Medusa`, and Bible/church content are never mistakenly blocked.

### Multi-Modal Verification & OTA Updates
- Continues running ML Kit Face Detection (talking-head veto), Pose Detection (clothed twerking guard), On-Device OCR + Contextual NLP, Dual-Model Agreement, 2–3 frame accumulative confirmation, Local DNS Content Filtering, and PIN-locked Uninstall Protection.

---

## Privacy Guarantee

- **100% On-Device Processing:** All fast-path text inspection, ML verification, and DNS filtering happen locally on your device.
- **Zero Disk Retention:** Screen frames and inline text are evaluated in temporary memory and never stored or uploaded.
