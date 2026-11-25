<div align="center">

# 🛡️ Screen Guard

### **Browse Safe. Live Free.**

A fast, private, on-device Android content-protection app that automatically shields you from explicit content across social media, browsers, and third-party apps.

![Platform](https://img.shields.io/badge/Platform-Android%2011%2B-0C2D84?style=for-the-badge&logo=android&logoColor=white)
![Privacy](https://img.shields.io/badge/Privacy-100%25%20On--Device-1BB55C?style=for-the-badge)
![Mode](https://img.shields.io/badge/Protection-Strict%20Mode-F5B82E?style=for-the-badge)

</div>

---

## ✨ What Screen Guard Does

Screen Guard quietly runs in the background while you use your phone. Whenever explicit or inappropriate imagery appears on your screen, it covers the screen immediately with a clean protective shield — without closing or breaking your apps.

- **Works Across Your Favorite Apps** — Protects you while browsing **X (Twitter)**, **Instagram**, **TikTok**, **Chrome**, **YouTube**, **Facebook**, and other third-party apps.
- **Instant & Strict Protection** — Detects explicit photos and videos in real time and brings up the shield right away.
- **Auto-Fade Recovery** — Once you scroll or navigate back to safe content, the protective shield automatically fades away.
- **Encouragement & Scripture** — Each time the shield activates, it shares an encouraging Bible verse to help guard your heart and mind, with a one-tap button to read the full passage in your Bible app or on `bible.com`.
- **Return to Safe Browsing** — One tap closes the offending screen and brings you safely back to the Screen Guard Home screen.
- **Optional DNS Content Filter** — Block explicit sites at the DNS lookup stage with a family-safe resolver plus your own custom domain list. Requires Android VPN approval; only DNS is routed, never your other traffic.

---

## 🔒 100% Private by Design

Your privacy comes first. Everything happens **completely on your phone**:

- **No Cloud Uploads** — Screenshots are never sent to any server.
- **Zero Saved Images** — Every frame is checked in temporary memory and erased immediately after inspection.
- **Password & Private Screen Respect** — Screen Guard automatically skips password fields, banking apps, and private/incognito windows.

---

## 📱 App Overview

| Screen | What It's For |
| :--- | :--- |
| **Home** | Turn protection on or off at any time and view your daily safety summary. |
| **Activity** | See a clear timeline of blocked items and protection checks. |
| **Capture** | Test the protective shield and check compatibility across your apps. |
| **Settings** | Manage permissions and pick apps to skip via a searchable **Choose apps to exclude** list — no package names to type. |
| **Protect** | Optional accountability commitment, PIN/cooling-off, and honest device-administrator status. |
| **DNS Filter** | Pick a family-safe DNS resolver, add your own blocked domains, and start/stop filtering with Android VPN approval. |

---

## 🚀 Quick Setup on Android

1. **Install the App**
   - Download or install [ScreenGuard-debug.apk](ScreenGuard-debug.apk) on your Android 11+ phone.
   - *(If installing from a file manager on Android 13+, open **Settings → Apps → Screen Guard → ⋮ → Allow restricted settings** if the Accessibility toggle is greyed out.)*
2. **Turn On Protection**
   - Open **Screen Guard** and tap **Enable Protection**.
   - Switch **Screen Guard Content Observer** **ON** in Android Accessibility Settings.
3. **Allow Background Protection** *(Xiaomi / HyperOS / Battery-Saver Phones)*
   - If prompted on the Home screen, tap **Keep Alive in Background** so your phone's battery manager doesn't pause protection.
 4. **Optional: Block Explicit Sites with DNS**
    - Open **Settings → DNS Content Filter**.
    - Approve the Android VPN dialog, choose a family-safe resolver, add any custom domains, then tap **Start DNS filter**.
    - Android shows a VPN indicator while active. Stop any time from the same screen, the notification, or Android Settings → VPN.
    - This filters ordinary DNS only; encrypted DNS, hardcoded IPs, Android Private DNS, and other VPNs can bypass it.

---

## 🛠️ Building from Source

> **Requirements:** Node.js 22+, JDK 17, Android SDK (API 36)

```powershell
# 1. Install JavaScript dependencies
npm install

# 2. Build the standalone Android APK
cd android
.\gradlew.bat assembleDebug

# 3. Install on a connected phone via ADB
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 📄 Technical Documentation

For developers and researchers interested in the on-device AI models, latency benchmarks, and Android system architecture:

- [PHASE_ABCD_ENGINEERING_REPORT.md](PHASE_ABCD_ENGINEERING_REPORT.md) — Reliability diagnosis, low-latency pipeline, multimodal fusion, and benchmark results.
- [MODEL_EVALUATION_REPORT.md](MODEL_EVALUATION_REPORT.md) — Two-stage on-device classifier accuracy and low-light evaluation.
- [MILESTONE_3_TEST_REPORT.md](MILESTONE_3_TEST_REPORT.md) — Protective overlay design and cross-app compatibility testing.
- [MILESTONE_4_CAPABILITY_INVESTIGATION.md](MILESTONE_4_CAPABILITY_INVESTIGATION.md) — What Android device administration can and cannot enforce.
- [MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md](MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md) — Advanced managed-device mode assessment.
- [MILESTONE_4_RECOVERY.md](MILESTONE_4_RECOVERY.md) — User recovery and unenrollment procedures.
- [MILESTONE_4_TEST_REPORT.md](MILESTONE_4_TEST_REPORT.md) — Verification scope and physical-device test plan.
- [MILESTONE_5_DNS_FILTER.md](MILESTONE_5_DNS_FILTER.md) — User-approved DNS filtering design, limitations, and verification checklist.

### Uninstall Protection (Milestone 4)

The **Protect** tab lets a user voluntarily enable an accountability commitment (1, 7, 30, or custom days), set an optional PIN, choose a cooling-off period, and review the exact enforcement status. An ordinary Android app cannot make itself permanently unremovable: Screen Guard does **not** claim enforced uninstall protection. Optional device administrator privileges only provide a supported lock-now action and are removable in Android Settings at any time. Recovery instructions are always shown in the app and in [MILESTONE_4_RECOVERY.md](MILESTONE_4_RECOVERY.md).

### DNS Content Filter (Milestone 5)

The **DNS Filter** screen configures an optional, user-approved VPN-based DNS filter. It redirects only DNS queries through Screen Guard, blocks a starter list and your own domains, and forwards allowed queries to a family-safe resolver. Android consent is mandatory, the VPN indicator is visible while active, and stopping is always possible from the app, notification, or Android Settings. The app does not store browsing history and does not route or inspect non-DNS traffic. Encrypted DNS, hardcoded IPs, Android Private DNS, and other VPNs can bypass ordinary DNS filtering; see [MILESTONE_5_DNS_FILTER.md](MILESTONE_5_DNS_FILTER.md) for the full capability assessment.
