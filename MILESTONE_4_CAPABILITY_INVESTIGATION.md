# Screen Guard — Milestone 4 Android Capability Investigation

**Package:** `com.paulfruitful.screenguard`
**Scope:** Can an ordinary Android application make uninstall difficult, and what does Android actually permit?
**Date:** 2026-11-24

---

## 0. Executive conclusion

**An ordinary Android application cannot make itself permanently unremovable.** Any app installed from an APK or a store can be removed by the device owner through Android Settings, the Play Store, `adb uninstall`, or by clearing app data / factory reset. Android deliberately preserves user control over installed software and device recovery.

What Screen Guard *can* legitimately do:

1. Keep a **voluntary accountability commitment** in app-private storage (1/7/30/custom days), with an optional PIN and a cooling-off period. This is a self-control aid, **not** an enforced uninstall restriction.
2. Ask for **device administrator** privileges in a dedicated, explicit screen. A device administrator can perform a small set of supported privileged actions (this build uses only `force-lock` / `DevicePolicyManager.lockNow()`), and the OS shows it in Settings so the user can deactivate it. **It does not prevent uninstall.**
3. **Observe** whether the device is enrolled as a Device Owner / profile owner and report that state honestly. This build does **not** enroll the device and does **not** apply owner-only uninstall policies.
4. Make the UI state reflect live queries of Android's policy manager, so it never displays active enforcement when the corresponding Android privilege is absent.

Design rule applied throughout: **accountability language in the UI is always separated from enforcement language.**

---

## 1. DeviceAdminReceiver and DevicePolicyManager

### 1.1 What it is

`DeviceAdminReceiver` is a `BroadcastReceiver` subclass declared with `android.permission.BIND_DEVICE_ADMIN`, an XML policy declaration (`<device-admin>` resource), and the `android.app.action.DEVICE_ADMIN_ENABLED` intent filter. The user must explicitly approve activation through the system dialog launched with:

```
DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
  + EXTRA_DEVICE_ADMIN -> ComponentName of the receiver
  + EXTRA_ADD_EXPLANATION -> human-readable justification
```

Once active, `DevicePolicyManager` exposes APIs gated to that administrator: screen locking (`lockNow`), password quality/expiry rules, remote wipe, camera disable, storage encryption checks, and related device policies. Which APIs are callable depends on the policies declared in the admin XML and the Android API level.

### 1.2 What it can **not** do

| Claim | Reality |
| :--- | :--- |
| “Device admin prevents uninstall” | **False.** The `ACTION_DEVICE_ADMIN_DISABLE_REQUESTED` / “prevent removal” mechanism was **removed in Android 7.0 (Nougat)** for security reasons. AOSP Settings always lets the user deactivate an admin and then uninstall. |
| “Device admin can hide itself / block Settings” | **False.** Active administrators are listed in Settings → Security → Device administrators and can be deactivated there. |
| “Device admin can force a PIN that the app owns” | Only within declared password policies enforced by Android; the user retains supported recovery (e.g., device recovery / factory reset), and this build requests no password policy. |
| “Device admin = Device Owner” | **False.** They are different privilege tiers with different APIs and provisioning paths. |
| “The app can detect who uninstalled it” | **False.** `onDisableRequested()` is a warning hook, not a veto, and cannot survive force-stop or identify the person. |

### 1.3 This implementation

- `ScreenGuardDeviceAdminReceiver` declares **only** `<force-lock/>` in `res/xml/device_admin.xml`.
- The dedicated activation screen lists exactly what is requested, explicitly states that uninstall is **not** blocked, and requires an acknowledgement before the user can proceed.
- `onEnabled`, `onDisableRequested`, and `onDisabled` update activity logs and the native status snapshot. `getStatus()` queries `DevicePolicyManager.isAdminActive()` on every read, so administrator deactivation is reflected immediately.
- `DevicePolicyManager.lockNow()` is exposed as “Lock now” and only works while administrator is active.
- Deactivation uses Android's own confirmation flow where available, with a fallback to Android Settings. No attempt is made to intercept or block it.

---

## 2. Device Owner / managed-device policies

Device Owner is a **separate, higher privilege tier** intended for enterprise-managed devices. It is granted only during device provisioning (factory-reset / unprovisioned device, no user accounts added), through supported enrollment mechanisms such as QR code (API 24+), DPC identifier / `afw#` (API 23+), managed Google Play Accounts / Google Account provisioning (API 23+), NFC (API 21+), and Zero Touch (API 26+). On Android 12+ (API 31+) a DPC must implement `ACTION_GET_PROVISIONING_MODE` and `ACTION_ADMIN_POLICY_COMPLIANCE`; starting the flow with the deprecated `ACTION_PROVISION_MANAGED_DEVICE` fails on S+.

An ordinary app installed later cannot silently promote itself to Device Owner. Android validates the provisioning state and requires the enrollment flow to be started from an unprovisioned device — this is specifically designed to stop malware taking over an in-use phone. Google also recommends provisioning owner mode only on company-owned devices and verifying ownership before enrollment.

Owner-only capabilities include (authorization-gated, API level dependent):

| Capability | Minimum API | Who may call it | Relevance here |
| :--- | :--- | :--- | :--- |
| `setUninstallBlocked(admin, package, blocked)` / `isUninstallBlocked` | API 21 | Device owner, profile owner, `DELEGATION_BLOCK_UNINSTALL` delegate (API 26+), or `MANAGE_DEVICE_POLICY_APPS_CONTROL` holder. Others get `SecurityException`. | This is the only supported anti-uninstall API. Not callable by this app. |
| `setPermissionPolicy`, `setLockTaskPackages`, lock-task / kiosk mode | API 21–23 | Owner/profile owner or delegated permission holder. | Restricts task switching / app controls on dedicated devices; out of scope for a consumer app. |
| `setSecurityLoggingEnabled` | API 24 | Device owner or org-owned profile owner. | Audit logs have visibility/redaction limits; not available here. |
| `setTime` / `setAutoTimeEnabled` | API 28 / 30 | Device owner or qualifying profile owner; works only when auto-time is off. | Relevant only to managed devices; see Section 5. |
| `clearDeviceOwnerApp` / unenrollment | API 18+ | The authorized owner app. | Supported removal path; factory reset also removes enrollment. |

**Reported, not implemented.** `DeviceAdminController.currentState()` reads `isDeviceOwnerApp`, `isProfileOwnerApp`, and (only when one of those is true) `isUninstallBlocked` for this package. Results may be filtered by package visibility on target 30+, so a `false` reading is never presented as proof beyond "not observed". The UI reports “Not enrolled — ordinary app” on normal devices. See `MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md` for why this mode is not offered in this build.

---

## 3. Google Play policy implications

There is **no official device-admin-specific declaration form** in the Play Policy Center at the time of writing — do not assume one exists. The general policies still apply, and several are directly relevant:

- **Sensitive Information:** the Accessibility API must **not** be used to prevent users from disabling or uninstalling an app (except parental controls or authorized enterprise management). This app uses Accessibility only for on-device content observation, never for uninstall blocking.
- **Mobile Unwanted Software:** uninstall must be "clear", "simple, clear and straightforward"; system changes must be disclosed with user review and approval.
- **Device and Network Abuse:** prohibits unauthorized interference with the device, apps, network, APIs, or services, including bypassing power management.
- **User Data:** the persistent-device-identifier exception applies to enterprise device-owner management with prominent disclosure, not to an ordinary consumer app.
- **`QUERY_ALL_PACKAGES`:** broad installed-app visibility is restricted by Play and should be used only where core functionality requires it.

Practical consequences for Screen Guard:

- Activation is **opt-in** with a dedicated explanation screen; no admin privilege is requested during installation or first launch.
- The store listing and in-app text must say plainly that this is not uninstall protection, that Android Settings and uninstall remain available, and that administrator can be removed.
- No policy that disables Settings, installer, emergency call, accessibility controls, recovery, or factory reset is requested. No watcher/killer loop or hidden obstruction of Settings is used.
- If a future release adds Device Owner mode, it must be distributed only through an approved managed-device workflow (not as a silent upgrade to consumer installs) and reviewed against the current Play policy text before release. Android Enterprise also no longer accepts new custom DPC registrations to the Google Play EMM API (DPC approval is required), reinforcing that this is an enterprise deployment project, not an in-app toggle.

Policy wording changes over time; verify the current text in Play Console Help before publishing. Sources are listed in Section 6.

---

## 4. Android version, OEM and Play distribution compatibility

| Area | Observation / design response |
| :--- | :--- |
| API levels | Project compiles with SDK 36, targets API 35, minSdk 24. Device-owner APIs used for *status only* are guarded with version checks and `try/catch`. |
| Android 7.0+ (API 24) | The "prevent removal of device administrator" policy was removed in Nougat; an active admin can always be deactivated and uninstalled. |
| Android 12 (API 31) | Foreground services cannot be started freely from the background, and exact-alarm permission behaviour changed. A background service, alarm, or scheduled job cannot be relied upon for anti-uninstall behaviour (nor should it be). |
| Android 13 (API 33) | `POST_NOTIFICATIONS` runtime permission is required; Task Manager lets users stop apps with ongoing foreground services. Sideloaded apps may need **Settings → Allow restricted settings** before Accessibility can be enabled. This build already requests notification permission and documents restricted settings in the README. |
| Android 14 (API 34) | `SCHEDULE_EXACT_ALARM` is denied by default for most newly installed target 33+ apps, another reason not to depend on alarms for persistence. |
| Android 15 (API 35) | Enhanced stopped states change background behaviour after force-stop; the app treats this as a normal condition and recomputes status from Android on every read. |
| Android 16 (API 36) | JobScheduler quota optimizations further limit background work. None of these changes affect the user's ability to deactivate admins or uninstall apps. |
| OEM skins (Xiaomi/HyperOS, Samsung One UI, etc.) | Battery managers can kill background services; some OEMs relabel or hide parts of the device-admin UI. The app never depends on an OEM-specific UI path: it falls back to generic Android Settings actions and reports unknown/unavailable states as inactive. Verify management features with `adb shell pm list features` on the target device; AOSP is the reference, not a universal OEM guarantee. |
| Managed devices (work profile / Device Owner) | Some privileges may be restricted by the employer's policy. Status is derived from `DevicePolicyManager`; when authority is absent, the UI reports inactive. |

**OEM caveat:** the in-app deactivation instructions use standard Android Settings actions; on heavily customized builds the exact menu label may differ. The user can always reach the app's Android App Info page and uninstall.

---

## 5. Trustworthy time source for commitments

| Source | Survives app restart | Survives reboot | Tamper resistant on unmanaged device? |
| :--- | :--- | :--- | :--- |
| `System.currentTimeMillis()` (wall clock) | Yes | Yes | **No.** User/network-settable; can jump either way. Appropriate for calendars, not audit ordering. |
| `SystemClock.elapsedRealtime()` (monotonic since boot, includes deep sleep) | Yes (process restart) | No (resets at boot) | Better against simple clock changes, but cannot measure a multi-day commitment across reboots. `uptimeMillis()` also stops during deep sleep. |
| `SystemClock.currentNetworkTimeClock()` (API 33+) | Yes | Yes | **No for security use.** Its docs state the user cannot adjust it but synchronization may be insecure, accuracy is not guaranteed, and consecutive reads can be out of order. |
| Server-issued signed timestamp over TLS | Yes (if persisted) | Yes | Strongest, but requires a backend, network/permission, and privacy trade-offs; not appropriate for this on-device-only app. |
| Managed-device `setTime` / `setAutoTimeEnabled` (API 28/30) | Yes | Yes | Owner/profile-owner only; docs recommend `DISALLOW_CONFIG_DATE_TIME` to prevent user changes. Not available to this app. |

**Chosen approach:** persist `startedAtMs` and `endsAtMs` from `System.currentTimeMillis()` in app-private storage. Also persist a high-water mark of the wall clock; if the clock moves backwards by more than one minute, the UI shows an explicit warning and states that timing is not tamper-proof. Reboot and application-update broadcasts are logged and never reset the commitment. App data clearing removes the commitment record entirely; the app then honestly reports “Off — no commitment”.

**Honest limitation:** on an unmanaged device no app can guarantee tamper-proof multi-day timing. This feature is an accountability aid, not a security boundary. If a future release needs audit-grade time, it should add server-signed timestamps rather than claim the device clock is trusted.

---

## 6. Sources

Authoritative references consulted for this investigation (verify current wording before publishing to Google Play):

**Android APIs**
- Device Administration overview: <https://developer.android.com/guide/topics/admin/device-admin>
- `DeviceAdminReceiver`: <https://developer.android.com/reference/android/app/admin/DeviceAdminReceiver>
- `DevicePolicyManager`: <https://developer.android.com/reference/android/app/admin/DevicePolicyManager>
- `ACTION_ADD_DEVICE_ADMIN`: <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#ACTION_ADD_DEVICE_ADMIN>
- `setUninstallBlocked`: <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUninstallBlocked(android.content.ComponentName,%20java.lang.String,%20boolean)> (also `isUninstallBlocked`, `DELEGATION_BLOCK_UNINSTALL`)
- Device admin deprecation / removal of "prevent removal" in Nougat: <https://developers.google.com/android/work/device-admin-deprecation>
- `removeActiveAdmin`: <https://developer.android.com/reference/android/app/admin/DevicePolicyManager#removeActiveAdmin(android.content.ComponentName)>
- AOSP Settings `DeviceAdminAdd`: <https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/master/src/com/android/settings/applications/specialaccess/deviceadmin/DeviceAdminAdd.java>

**Enterprise provisioning**
- Android Enterprise — Provision devices: <https://developers.google.com/android/work/play/emm-api/prov-devices>
- Device owner only on new / factory-reset devices: <https://developers.google.com/android/work/play/emm-api/prov-devices#device-must-be-new-or-factory-reset>
- Company-owned device requirement: <https://developers.google.com/android/work/play/emm-api/prov-devices#set-up-device-owner-mode-only-on-company-owned-devices>
- Building a DPC (Android 12+ requirements): <https://developer.android.com/work/dpc/build-dpc>
- AOSP managed provisioning features: <https://source.android.com/docs/devices/admin/implement>
- DPC approval / EMM API registration change: <https://support.google.com/work/android/answer/16694822>

**Google Play policy**
- Play Policy Center: <https://support.google.com/googleplay/android-developer/topic/9858052>
- Sensitive Information (Accessibility must not block uninstall): <https://support.google.com/googleplay/android-developer/answer/16558241>
- Mobile Unwanted Software (clear uninstall, disclosed system changes): <https://support.google.com/googleplay/android-developer/answer/9970222>
- Device and Network Abuse: <https://support.google.com/googleplay/android-developer/answer/16559646>
- User Data (enterprise device-owner exception): <https://support.google.com/googleplay/android-developer/answer/10144311>
- `QUERY_ALL_PACKAGES` usage policy: <https://support.google.com/googleplay/android-developer/answer/10158779> · <https://developer.android.com/training/package-visibility>

**Time and version behaviour**
- `SystemClock`: <https://developer.android.com/reference/android/os/SystemClock> · `System.currentTimeMillis()`: <https://developer.android.com/reference/java/lang/System#currentTimeMillis()>
- Android 12 background FGS / exact alarms: <https://developer.android.com/about/versions/12/behavior-changes-12>
- Android 13 Task Manager: <https://developer.android.com/about/versions/13/behavior-changes-all>
- Android 14 exact alarms: <https://developer.android.com/about/versions/14/behavior-changes-all>
- Android 15 stopped states: <https://developer.android.com/about/versions/15/behavior-changes-all>
- Android 16 JobScheduler quotas: <https://developer.android.com/about/versions/16/behavior-changes-all>
- `POST_NOTIFICATIONS`: <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- Restricted settings for sideloaded apps: <https://developer.android.com/guide/topics/manifest/uses-permission-element#restrictedPermissions>

Any statement above that cannot be verified against current official documentation should be treated as a compatibility caveat rather than a guarantee.
