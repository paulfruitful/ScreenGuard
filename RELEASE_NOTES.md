# Screen Guard v1.6.1 — Release Notes

**Browse Safe. Live Free.**  
*Release Date: November 26, 2026 | Platform: Android 11–16*

---

## Overview

Screen Guard v1.6.1 adds an on-device **1,000-class everyday-content verifier (`efficientnet_lite0.tflite`)** to eliminate non-sexual visual false positives (pets/animals, food, vehicles, landscapes, and UI screens) and upgrades live TikTok Doomscroll feed tracking accuracy.

---

## What's New in v1.6.1

### On-Device Normal-Content False-Positive Gate (`NonSexualSceneVerifier`)
- **1,000-Class General Content Verifier:** Bundles Google's official 5.4 MB INT8 `efficientnet_lite0.tflite` model on LiteRT XNNPACK, invoked **only on suspicious non-hentai frames** (0 ms overhead on normal safe screens, ~20 ms on flagged frames).
- **Stops Animal, Food, Vehicle & Scenery False Blocks:** Recognizes 398 animal species (including 118 dog breeds such as pitbulls and all cat breeds), food/cooking dishes, vehicles, landscapes, and UI/article screens — suppressing texture-based NSFW CNN misfires while strictly excluding clothing/swimwear/bedroom classes so real explicit content and thirst traps remain blocked.
- **Expanded Pet & Animal NLP Context:** Adds pet and wildlife vocabulary (`dog`, `pitbull`, `puppy`, `cat`, `kitten`, `pet`, `wildlife`) as a corroborating on-device text signal.

### Live Doomscroll Protection Fixes (TikTok & Short-Form Feeds)
- **Active-Feed Persistence Across Animation Ticks:** Prevents throttled empty `TYPE_WINDOW_CONTENT_CHANGED` events (video timer ticks and UI animations) from prematurely pausing an active confirmed short-form feed.
- **Mid-Swipe Hysteresis:** Requires 2 consecutive full-node `UNKNOWN` scans before pausing active feed duration (non-feed screens such as Comments, DMs, Profile, and Search still pause immediately).
- **Real-World TikTok UI Tree Support:** Expands bounded background node inspection to 180 nodes (`35 ms` cap) and matches production TikTok feed containers (`viewpager`, `viewpager_container`, `view_pager_layout_wrapper`, `long_press_layout`, and `tv_reply_comment_title`).

---

## Privacy Guarantee

- **100% On-Device Processing:** All visual classification, everyday-content verification, OCR, face detection, Doomscroll tracking, and DNS filtering run locally on your device.
- **Zero External Uploads:** No frames, screenshots, messages, or behavioural histories ever leave your phone.
