# Milestone 4 — Device Owner Feasibility Report

**Decision: NOT implemented in this build.** Device Owner status is detected and reported read-only; this app never attempts to acquire it.

---

## 1. Why not implemented

| Requirement | Consequence for Screen Guard |
| :--- | :--- |
| Provisioning must happen on a device in setup/unprovisioned state (typically factory reset, no accounts added) | Cannot be offered as an in-app upgrade to existing users without resetting their device. Milestone 4 forbids silent acquisition and requires clear disclosure of reset implications. |
| Enrollment transfers substantial management authority to the app (app installation/removal control, policy enforcement, possible kiosk mode) | Disproportionate for a voluntary content-protection app and inconsistent with "user accountability" framing. |
| Google Play policy treats sensitive device-policy permissions as enterprise-only / disclosure-bound | Consumer distribution risk; a separate managed-device distribution path would be required. |
| Must be tested on a dedicated physical device before offering to normal users | No dedicated test device is available in this build environment (see `MILESTONE_4_TEST_REPORT.md`). |

Implementing a half-tested owner mode would be misleading and unsafe. The UI therefore shows Managed-device status as **“Not enrolled — ordinary app”** on normal devices and explains that no silent enrollment is attempted.

---

## 2. Prerequisites if a future release implements it

1. A **dedicated physical Android device** whose data may be wiped. The device must be company-owned / authorized; Google explicitly recommends verifying ownership before enrollment and provisioning owner mode only on company-owned devices.
2. Device in unprovisioned/factory-reset state with **no user accounts added** — owner mode generally cannot be added after normal setup. This is a deliberate anti-malware safeguard.
3. Enrollment through a supported mechanism: QR code (API 24+), DPC identifier / `afw#` (API 23+), managed Google Play Accounts / Google Account provisioning (API 23+), NFC (API 21+), or Zero Touch (API 26+). The `dpm` command-line tool is suitable for lab testing.
4. **Android 12+ (API 31+) DPC requirements:** implement `ACTION_GET_PROVISIONING_MODE` and `ACTION_ADMIN_POLICY_COMPLIANCE`. The older `ACTION_PROVISION_MANAGED_DEVICE` was deprecated in API 31 and causes provisioning failure on S+ if used to start the flow.
5. Device/system support for `android.software.device_admin` and `android.software.managed_users`; verify with `adb shell pm list features`.
6. Compliance with encryption / verified-boot requirements applicable to the Android version and device model.
7. A distribution channel appropriate for a management app (not a silent update to consumer installs), with Play policy review. Note that new custom DPC registrations to the Google Play EMM API are no longer accepted — DPC approval is required.
8. Documented authority boundary: exactly which policies are applied, and exactly how the device is unenrolled.

---

## 3. Supported policies that would be relevant

All of the following require Device Owner (or profile owner where applicable) authority and are **not active in this build**:

| Policy / API | Purpose | Status here |
| :--- | :--- | :--- |
| `setUninstallBlocked(admin, package, true)` / `isUninstallBlocked` | Block removal of a managed package | Not applied |
| `addPackageToSuspendedList` / application restrictions | Limit specific apps | Not applied |
| `setLockTaskPackages` | Dedicated-device / kiosk restrictions | Not applied |
| Password quality and expiry rules | Managed device security policy | Not applied |
| `installPackage` / `uninstallPackage` callbacks | Silent managed app deployment | Not applied |

The build performs a **read-only** `isUninstallBlocked` query only when `isDeviceOwnerApp` or `isProfileOwnerApp` returns true, and reports `false`/“No” on any error. It never calls any policy setter.

---

## 4. Enrollment and unenrollment procedure (if implemented later)

**Enrollment:** must be started explicitly by the user from a dedicated activation screen; must display that setup/reset is required and describe the management authority being granted; must finish through Android's own provisioning UI. No bypass of provisioning or management authorization is acceptable.

**Recovery / unenrollment (documented, supported):**

1. Authorized owner app calls `DevicePolicyManager.clearDeviceOwnerApp(packageName)` where API level permits.
2. Enterprise-managed devices: unenroll via the organization's EMM console / admin, following their policy.
3. Remove any active device administrator entry in Settings → Security → Device administrators.
4. Factory reset removes Device Owner enrollment and all device management policies (last resort; wipes user data).

No in-app mechanism may hide enrollment or prevent these supported paths.

---

## 5. Verification required before offering this mode

A dedicated physical device test must cover: provisioning prerequisites, successful enrollment, observed owner state in the dashboard, applied policy behaviour, uninstall restriction behaviour, recovery code/PIN paths, administrator deactivation while owner is active, unenrollment, factory reset, and application update. Until every case passes on that device, the feature remains a documented feasibility assessment only.
