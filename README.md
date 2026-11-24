# Screen Guard — Accessibility-Based Android Content Observation

Screen Guard is a native Android digital-wellbeing and content-safety application built with **React Native (TypeScript)** for the user interface and **Kotlin** for Android system-level functionality.

Instead of relying on `MediaProjection` (which requires persistent screen-casting prompts and high power consumption), Screen Guard implements an event-driven visual observation architecture using Android's `AccessibilityService` screenshot APIs (`AccessibilityService.takeScreenshotOfWindow` on Android 14+ and `AccessibilityService.takeScreenshot` on Android 11+).

---

## 1. Architecture & Processing Pipeline

```text
Accessibility Event (Window State / Content / Windows Changed / View Scrolled)
  │
  ▼
Privacy & Package Filter (Skips password inputs, keyboards, & user-excluded packages)
  │
  ▼
Event Debouncer (Coalesces burst events + enforces configurable & adaptive cooldowns)
  │
  ▼
Active Window Resolver (Identifies foreground packageName, appLabel, & active windowId)
  │
  ▼
Accessibility Screenshot Capturer
  ├─ Android 14+ (API 34+): AccessibilityService.takeScreenshotOfWindow(windowId, executor, callback)
  └─ Android 11+ (API 30+): AccessibilityService.takeScreenshot(DEFAULT_DISPLAY, executor, callback)
  │
  ▼
Screenshot Processor & Memory Guard
  ├─ Wraps HardwareBuffer -> Downsamples to 224×224 ARGB_8888 Bitmap
  └─ Unconditionally closes HardwareBuffer & recycles full-res Bitmap in finally {}
  │
  ▼
Detection Engine & Two-Stage LiteRT Classifier (Milestones 2 & 2.1)
  ├─ PerceptualFrameHasher: 64-bit dHash duplicate-frame cache (< 0.15ms, skips static frames)
  ├─ Backpressure Guard: MAX_PENDING_INFERENCE_JOBS = 1 (drops obsolete frames; 0 queue buildup)
  ├─ LowLightPreprocessor: Adaptive luminance/contrast inspection + CLAHE & Gamma enhancement
  ├─ Stage 1 LiteRtNsfwClassifier: Google LiteRT + nsfw_mobilenet_v2_140_224.tflite (17.25 MB, 5 classes)
  ├─ Stage 2 OpenNsfwResNetClassifier: Yahoo OpenNSFW ResNet-50 (open_nsfw_resnet50.tflite, 5.96 MB)
  ├─ Taxonomy Mapping Layer: EXPLICIT, NUDITY, SEXUAL_ACTIVITY, SUGGESTIVE, NON_EXPLICIT, SAFE, UNCERTAIN
  └─ TemporalDecisionEngine: EWMA confidence smoothing, N-frame confirmation, cooldown & recovery
  │
  ▼
Protection Decision Engine & System-Wide Protective Overlay (Milestone 3)
  ├─ ProtectionDecisionEngine: Category-specific thresholds, BALANCED vs STRICT sensitivity modes,
  │  multi-frame confirmation, 2.5s–3.0s minimum display anti-flicker guard, & dismissal cooldowns
  ├─ ScreenGuardOverlayController: Native WindowManager TYPE_ACCESSIBILITY_OVERLAY shield
  │  (with TYPE_APPLICATION_OVERLAY fallback), touch-interception backdrop, & orientation handling
  └─ ProtectionCoordinator: Clears stale overlays on app switch, screen-off (ACTION_SCREEN_OFF),
     pause, or service unbind; handles "Return to Safe Browsing" and "Dismiss for This Screen"
  │
  ▼
Result Handler (LocalStorage metadata log + EventBridge real-time emission to React Native)
```

---

## 2. Project Structure

```text
ScreenGuard/
├── App.tsx
├── src/
│   ├── components/
│   │   ├── ActivityEventRow.tsx
│   │   ├── MetricRow.tsx
│   │   └── StatusBadge.tsx
│   ├── hooks/
│   │   └── useScreenGuard.ts
│   ├── navigation/
│   │   └── MainNavigator.tsx
│   ├── screens/
│   │   ├── ActivityScreen.tsx
│   │   ├── DashboardScreen.tsx
│   │   ├── SettingsScreen.tsx
│   │   └── TestingScreen.tsx
│   ├── services/
│   │   └── ScreenGuardNative.ts
│   ├── theme/
│   │   └── theme.ts
│   └── types/
│       └── screenguard.ts
└── android/
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/paulfruitful/screenguard/
        │   ├── MainActivity.kt
        │   ├── MainApplication.kt
        │   ├── accessibility/
        │   │   ├── ActiveWindowResolver.kt
        │   │   ├── EventDebouncer.kt
        │   │   ├── MonitoringNotificationManager.kt
        │   │   └── ScreenGuardAccessibilityService.kt
        │   ├── bridge/
        │   │   ├── EventBridge.kt
        │   │   ├── ScreenGuardModule.kt
        │   │   └── ScreenGuardPackage.kt
        │   ├── capture/
        │   │   ├── AccessibilityScreenshotCapturer.kt
        │   │   ├── BufferReleaseTracker.kt
        │   │   └── ScreenshotProcessor.kt
        │   ├── classifier/
        │   │   ├── ClassificationResult.kt
        │   │   ├── ClassifierEvaluator.kt
        │   │   ├── ContentClassifier.kt
        │   │   ├── DetectionEngine.kt
        │   │   ├── LiteRtNsfwClassifier.kt
        │   │   ├── LowLightPreprocessor.kt
        │   │   ├── MockContentClassifier.kt
        │   │   ├── OpenNsfwResNetClassifier.kt
        │   │   ├── PerceptualFrameHasher.kt
        │   │   └── TemporalDecisionEngine.kt
        │   ├── protection/
        │   │   ├── OverlayController.kt
        │   │   ├── ProtectionCoordinator.kt
        │   │   ├── ProtectionDecisionEngine.kt
        │   │   └── ProtectionModels.kt
        │   └── storage/
        │       └── LocalStorage.kt
        └── res/
            ├── values/
            │   ├── strings.xml
            │   └── styles.xml
            └── xml/
                └── accessibility_service_config.xml
```

---

## 3. Android Limitations & Application Compatibility

Screen Guard operates strictly within Android's documented security boundaries and never attempts to bypass platform restrictions:

1. **`FLAG_SECURE` Protected Windows (`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`)**:
   - Windows that set `WindowManager.LayoutParams.FLAG_SECURE` (e.g., Chrome Incognito tabs, banking applications, password managers, DRM video players, and certain private messaging views) cannot be captured.
   - Screen Guard catches `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`, records a `SECURE_WINDOW` metadata event, and never attempts to circumvent the restriction.
2. **Android Rate Limiting (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`)**:
   - Android enforces an internal minimum interval (~1,000 ms) between consecutive `AccessibilityService.takeScreenshot` / `takeScreenshotOfWindow` calls.
   - Screen Guard enforces a minimum 1,050 ms hardware rate guard in `AccessibilityScreenshotCapturer` and a configurable sampling cooldown (default 2,500 ms) in `EventDebouncer`.
3. **DRM Hardware Overlays (Widevine L1)**:
   - Protected media surfaces decoded directly into secure hardware composer planes (such as purchased Movies/TV streams in YouTube or Netflix) will either reject screenshot capture or render black video planes.
4. **Password & Input Privacy**:
   - Screen Guard does not subscribe to `TYPE_VIEW_TEXT_CHANGED`, ignores any event where `event.isPassword == true`, and skips screenshot capture whenever an input-focused node has `isPassword == true`.
5. **Target Application Behavior Matrix**:
   - **Android Settings (`com.android.settings`)**: Normal settings screens support `takeScreenshotOfWindow` and `takeScreenshot`; credential/lockscreen setup sub-activities enforce `FLAG_SECURE`.
   - **Google Chrome (`com.android.chrome`)**: Standard tabs are capturable; Incognito tabs enforce `FLAG_SECURE`.
   - **YouTube (`com.google.android.youtube`)**: Home feed, search, shorts, and standard video playback are capturable; DRM-protected purchases are blocked.
   - **Instagram (`com.instagram.android`)**: Feed, Reels, and Stories windows are capturable; disappearing direct messages may set `FLAG_SECURE`.
   - **TikTok (`com.zhiliaoapp.musically`)**: Main feed and video surfaces are capturable on Android 11+.
   - **Home Screen Launcher**: Workspace and app drawer windows are capturable.

---

## 4. On-Device LiteRT NSFW Classifier & Evaluation Report (Milestone 2)

Milestone 2 replaces the mock classifier with a production on-device **Google LiteRT (`com.google.ai.edge.litert:litert:1.4.2`)** inference engine running the pretrained 5-class **`nsfw_mobilenet_v2_140_224.tflite`** model (`GantMan/nsfw_model`, MIT License, `17,251,928` bytes):

- **Input Tensor**: `[1, 224, 224, 3]` `FLOAT32`, RGB channel ordering, normalized to `[0.0, 1.0]` (`pixel / 255.0f`).
- **Output Tensor**: `[1, 5]` `FLOAT32` softmax probabilities (`drawings`, `hentai`, `neutral`, `porn`, `sexy`).
- **Taxonomy Mapping**: Preserves all 5 raw probabilities in `ClassificationResult.scores` and maps to `EXPLICIT`, `NUDITY`, `SUGGESTIVE`, `NON_EXPLICIT`, `SAFE`, and `UNCERTAIN`.
- **Temporal Decision Engine ([TemporalDecisionEngine.kt](file:///c:/Users/HomePC/ScreenGuard/android/app/src/main/java/com/paulfruitful/screenguard/classifier/TemporalDecisionEngine.kt))**: Applies EWMA smoothing ($\alpha = 0.60$), requires consecutive confirmations before entering `CONFIRMED_DETECTION`, enforces detection cooldowns, and recovers cleanly after consecutive safe frames.
- **Full Evaluation & Benchmark Reports**:
  - [MODEL_EVALUATION_REPORT.md](file:///c:/Users/HomePC/ScreenGuard/MODEL_EVALUATION_REPORT.md): Milestones 2 & 2.1 two-stage LiteRT + low-light CLAHE/Gamma evaluation across 36 validation and held-out test samples (`100.0%` Precision, `100.0%` Recall, `0.0%` FPR, `0.0%` Uncertain Rate).
  - [MILESTONE_3_TEST_REPORT.md](file:///c:/Users/HomePC/ScreenGuard/MILESTONE_3_TEST_REPORT.md): Milestone 3 WindowManager `TYPE_ACCESSIBILITY_OVERLAY` vs `TYPE_APPLICATION_OVERLAY` evaluation, 5-state overlay lifecycle state machine (`HIDDEN`, `PENDING`, `PROTECTED`, `RECOVERING`, `DISMISSED`), `BALANCED` vs `STRICT` policy calibration, and cross-application compatibility report.
  - [PHASE_ABCD_ENGINEERING_REPORT.md](file:///c:/Users/HomePC/ScreenGuard/PHASE_ABCD_ENGINEERING_REPORT.md): Phase A–D Reliability, Performance & Multimodal Detection Upgrade report (ADB ANR root-cause proof & fix, `< 50 ms` Stage-1 / `< 150 ms` screenshot-to-prediction latency pipeline, local-only Multimodal Visual + AudioPlaybackCapture + Caption/URL + OCR Fusion Engine, and 52-sample Development/Validation/Locked-Test benchmark with 95% Wilson confidence intervals).

---

## 5. Setup, Building & On-Device Testing (Samsung Galaxy S24 / Android 16)

### Prerequisites
- JDK 17
- Android SDK Platform 36 & Build Tools 36.0.0
- Node.js >= 22.11.0

### Building the Standalone Debug APK
```powershell
cd android
.\gradlew.bat assembleDebug
```
The compiled standalone Debug APK (with the JavaScript bundle embedded so no Metro server is required on the phone) is output to:
`android/app/build/outputs/apk/debug/app-debug.apk`

### Installing & Enabling on a Physical Device (Android 13–16 Note)
1. Install via ADB:
   ```powershell
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```
   *(Note: Installing over `adb install` automatically grants access to toggle the Accessibility Service. If you copy the APK file manually to a Samsung Galaxy S24 running Android 15/16 and install via a file manager, Android's **Restricted Settings** guard may grey out the Accessibility toggle until you open **Settings → Apps → Screen Guard → ⋮ (top right) → Allow restricted settings**).*
2. Launch **Screen Guard** and tap **Enable Protection in Accessibility Settings** on the Dashboard.
3. Enable **Screen Guard Content Observer** in Android Accessibility Settings.
4. Open the **Testing** tab in Screen Guard to:
   - Verify Accessibility permission and service connection status.
   - Run **Capture Now (Zero-Retention)** or **3s Delayed Capture (Switch App)** to switch to Chrome, YouTube, Instagram, TikTok, Settings, or the Home Screen and verify real hardware buffer capture.
   - Use **Capture & Hold In-Memory Debug Preview** (explicit user action) to inspect the downsampled frame in volatile RAM, and tap **Purge RAM** (or leave the tab) to free it.
   - Verify in the **HardwareBuffer & Memory Release Audit** card that `HardwareBuffers Allocated == HardwareBuffers Closed` and `Currently Active Hardware/Bitmap Buffers == 0`.
   - Run the **25x Burst Event Debouncer Test** and **Disconnection Cleanup Test**.

---

## 6. Verified Physical Device Test Results (Android 16 / API 36)

Verified on connected physical device (`24117RN76G`, Android 16 / API 36):

- **Accessibility Service Binding**: `com.paulfruitful.screenguard/.accessibility.ScreenGuardAccessibilityService` bound with `capabilities=129` (`CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT | CAPABILITY_CAN_TAKE_SCREENSHOT`).
- **Per-Window & Display Fallback Screenshot Capture (`1080×2400` -> `224×224`)**:
  - **Android Settings (`com.android.settings`)**: `SUPPORTED_WINDOW_API` via `takeScreenshotOfWindow(1815)` (`1080×2400`, `38 ms` capture, `5 ms` classification).
  - **Instagram (`com.instagram.android`)**: `SUPPORTED_WINDOW_API` via `takeScreenshotOfWindow(1923)` (`1080×2400`, `35 ms` capture, `13 ms` classification).
  - **X / Twitter (`com.twitter.android`)**: `SUPPORTED_WINDOW_API` across 45+ continuous scroll/content captures via `takeScreenshotOfWindow` (`1080×2400`, `14–83 ms` typical capture latency, `1–9 ms` classification duration).
  - **System Home Launcher (`com.miui.home`)**: Verified both per-window `takeScreenshotOfWindow(1907)` (`1080×2400`, `14–112 ms`) and automatic fallback `takeScreenshot(DEFAULT_DISPLAY_FALLBACK)` (`1080×2400`, `53 ms`, `1 ms` classification).
- **Memory & Buffer Lifecycle Audit**:
  - After 50+ full `1080×2400` `HardwareBuffer` captures, `dumpsys meminfo com.paulfruitful.screenguard` confirmed a stable Dalvik Heap of **7.8 MB** (`7,876 KB`) and Native Heap of **26.1 MB** (`26,164 KB`) with zero leaked `HardwareBuffer` or `Bitmap` allocations.

