# Screen Guard — Milestone 4 Verification & Physical-Device Test Report

**Project:** Screen Guard (`com.paulfruitful.screenguard`)
**Milestone:** 4 — Uninstall Protection & User Accountability
**Version:** 1.1.0 (versionCode 2)
**Stack:** Native Kotlin (device administration, accountability state machine) + React Native 0.88.1 (TypeScript)
**Date:** 2026-11-24

---

## 1. Verification summary (what was actually run)

| Check | Command / method | Result |
| :--- | :--- | :--- |
| TypeScript typecheck | `npx tsc --noEmit` | **PASS** — no errors |
| Android debug build | `.\gradlew.bat assembleDebug --no-daemon` | **PASS** — `app-debug.apk` produced |
| APK output | `android/app/build/outputs/apk/debug/app-debug.apk` | **Produced** (v1.1.0, ~57 MB) |
| ADB device discovery | `adb devices -l` | **No device attached** — see Section 3 |

> **Honesty note:** a built APK and a passing compile are *not* proof of on-device behaviour. The interactive cases in Section 3 require a dedicated physical Android device and are documented as a plan with a results column to be completed by the tester. No physical device was connected to this build environment, so those cases are marked **NOT RUN** rather than reported as passing.

---

## 2. Implementation-to-requirement coverage

### 2.1 Capability investigation
Documented in [MILESTONE_4_CAPABILITY_INVESTIGATION.md](MILESTONE_4_CAPABILITY_INVESTIGATION.md): `DeviceAdminReceiver`, `DevicePolicyManager`, Device Owner provisioning, managed policies, API/OEM matrix, Play policy, and time-source analysis. Conclusion: an ordinary app cannot make itself permanently unremovable; administrator ≠ uninstall protection.

### 2.2 Voluntary accountability (Kotlin)
Implemented in `accountability/AccountabilityModels.kt` and `accountability/AccountabilityManager.kt`:
- Explicit acknowledgement checkbox in the UI before enablement.
- Durations: 1 day / 7 days / 30 days / custom (1–365), remaining time from wall clock.
- Optional PIN (PBKDF2 salted hash), configurable cooling-off (5/10/30/60 min presets).
- Clock rollback detection (one-minute tolerance) with explicit in-app warning.
- Started/end timestamps persisted in app-private `SharedPreferences` (`commit()`), surviving process death, reboot, and app update; reboot/update only append an activity log.
- Recovery code generated once, shown once, stored hashed; hint shown later. PIN is never the only recovery path.
- Bookkeeping events written to the existing activity log.

### 2.3 Device administrator integration (only justified capability)
Implemented in `admin/DeviceAdminController.kt` and `admin/ScreenGuardDeviceAdminReceiver.kt`:
- Dedicated activation screen listing requested privileges and explicit “does NOT prevent uninstall” warning; requires acknowledgement.
- Only `<force-lock/>` is declared (`res/xml/device_admin.xml`); `lockNow()` is the only privileged action exposed.
- Activation state queried live from `DevicePolicyManager.isAdminActive()` on every status read; `onEnabled` / `onDisabled` / `onDisableRequested` log and emit status events.
- Deactivation goes through Android's own confirmation flow with a Settings fallback; no interception or blocking.
- No password, wipe, settings-block, kiosk, or hidden-management policy is requested.

### 2.4 Device Owner feasibility
Documented in [MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md](MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md). **Not implemented.** Status is read-only (`isDeviceOwnerApp`, `isProfileOwnerApp`, `isUninstallBlocked`), no setter is ever called, no silent enrollment.

### 2.5 React Native dashboard / settings
- `UninstallProtectionSummaryCard.tsx` on the dashboard shows protection mode, duration, remaining time, administrator status, managed-device status, what is enforced vs accountability-only, deactivation instructions, and emergency recovery.
- `UninstallProtectionScreen.tsx` (new **Protect** tab) hosts enable/deactivate/PIN controls, dedicated admin activation screen, managed-device explanation, and recovery info.
- `SettingsScreen.tsx` links to the Protect tab.
- `commitmentFormat.ts` centralises honest labels; `enforcementLabel()` never claims uninstall enforcement for admin-only or accountability-only states.
- Status updates propagate via the `ACCOUNTABILITY_STATUS_CHANGED` native event and are refreshed on foreground.

### 2.6 Failure handling
| Event | Handling | Verification |
| :--- | :--- | :--- |
| App data cleared | Commitment record removed; status truthfully reports “Off — no commitment” | On-device: **NOT RUN** |
| Reboot | State preserved via persisted wall clock; boot receiver only logs | On-device: **NOT RUN** |
| App update | State preserved; `MY_PACKAGE_REPLACED` only logs | On-device: **NOT RUN** |
| Permission revoked | Monitoring status recomputed from Android each read | On-device: **NOT RUN** |
| Admin deactivated | Live `isAdminActive()` read flips status to inactive; lock-now disabled | On-device: **NOT RUN** |
| Service terminated | Monitoring status shows disconnected; accountability status independent | On-device: **NOT RUN** |
| Device reset | All state removed by Android | On-device: **NOT RUN** |
| Failed provisioning | No owner authority; status “Not enrolled” | On-device: **NOT RUN** |

### 2.7 Never show false enforcement
`AccountabilityStatus.uninstallEnforcementActive` is true **only** when `deviceOwnerActive && managedUninstallPolicyActive` is observed from `DevicePolicyManager`. All other states report `technicalEnforcement = NONE` or `DEVICE_ADMIN_SUPPORT_ONLY`, with `uninstallEnforcementActive = false`. The UI renders “Not enforced by Android — voluntary commitment” for those states.

---

## 3. Physical-device test plan (dedicated device required)

> Run these on a **dedicated physical Android device** (not a production phone). `adb devices -l` at build time reported no attached device, so all rows below are **NOT RUN**. Fill the result column when a device is available.

| # | Scenario | Steps | Expected result | Result |
| :-- | :--- | :--- | :--- | :--- |
| 1 | Activation acknowledgement | Open Protect, attempt enable without ticking acknowledgement | Enable blocked with explanatory error | NOT RUN |
| 2 | Enable 1/7/30/custom | Tick acknowledgement, choose each duration, enable | Correct remaining time displayed; recovery code shown once | NOT RUN |
| 3 | Persistence — restart | Force-stop and reopen app after enabling | Commitment still active with reduced remaining time | NOT RUN |
| 4 | Persistence — reboot | Reboot device, reopen app | Commitment active; boot log entry present | NOT RUN |
| 5 | PIN confirmation | Set PIN, request deactivation, wait cooling-off, enter PIN | Commitment ends; event logged | NOT RUN |
| 6 | Wrong PIN / recovery code | Enter wrong secret, then saved recovery code | Wrong rejected; recovery code accepted | NOT RUN |
| 7 | Cooling-off enforcement | Request deactivation, try confirm immediately | Confirm disabled until timer elapses | NOT RUN |
| 8 | Cancel deactivation | Request then cancel during cooling-off | Commitment continues unchanged | NOT RUN |
| 9 | Admin activation screen | Review dedicated screen, acknowledge, approve system dialog | `deviceAdminActive = true`; lock-now works | NOT RUN |
| 10 | Admin status on deactivation | Deactivate in Android Settings | Dashboard immediately shows admin inactive; lock-now disabled | NOT RUN |
| 11 | Uninstall with admin active | Attempt uninstall with admin active | Android prompts deactivation; uninstall possible afterwards | NOT RUN |
| 12 | Clear app data | Settings → Clear data, reopen | Status “Off”, no false active claim | NOT RUN |
| 13 | App update | Install newer APK over existing with same key | Commitment preserved | NOT RUN |
| 14 | Clock rollback | Change device time backwards, refresh | Warning shown; timing labelled approximate | NOT RUN |
| 15 | Managed provisioning (if ever implemented) | Enrol dedicated device via supported flow | Owner state reported; policy behaviour matches docs | NOT RUN — mode not implemented |
| 16 | Recovery / unenrollment | Follow `MILESTONE_4_RECOVERY.md` | Control regained via documented steps | NOT RUN |
| 17 | No blocked essentials | Try Settings, installer, emergency dialer, recovery while protected | All reachable; no blocking overlay | NOT RUN |

---

## 4. Privacy / control safeguards verified in source

- No policy disables Settings, the installer, accessibility controls, emergency calling, recovery, or factory reset (only `<force-lock/>` declared).
- No concealed management enrollment; Device Owner is read-only status.
- No screenshot/preview of detected content is stored or transmitted by this feature; only hashed secrets and timestamps are persisted.
- Accountability status is independent of protection/monitoring status, so revoking Accessibility does not fabricate uninstall enforcement.

---

## 5. Deliverables index

- [MILESTONE_4_CAPABILITY_INVESTIGATION.md](MILESTONE_4_CAPABILITY_INVESTIGATION.md)
- [MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md](MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md)
- [MILESTONE_4_RECOVERY.md](MILESTONE_4_RECOVERY.md)
- `ScreenGuard-debug.apk` — updated v1.1.0 build (root copy of `app-debug.apk`)
- Kotlin: `accountability/*`, `admin/*`, `boot/AppLifecycleReceiver.kt`
- TypeScript: `UninstallProtectionScreen.tsx`, `UninstallProtectionSummaryCard.tsx`, `commitmentFormat.ts`, updated hook/service/types/navigation
