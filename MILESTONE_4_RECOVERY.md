# Milestone 4 — Recovery & Unenrollment Documentation

This document is shown in summary form in the app's **Protect** tab. It applies to `com.paulfruitful.screenguard` v1.1.0 (Milestone 4).

## Key fact

An accountability commitment is **not** an Android-enforced uninstall restriction. Device administrator is **not** an uninstall restriction either. You always retain control of your device through Android-supported procedures. Screen Guard does not block Settings, the package installer, accessibility controls, emergency calling, Android recovery, or factory reset.

---

## 1. In-app deactivation (normal path)

1. Open **Protect → Deactivate accountability** (or **Start emergency recovery** if the commitment is still running).
2. Wait through the cooling-off period (default 10 minutes, configurable at enablement).
3. Enter your PIN **or** the emergency recovery code you saved when enabling, then confirm. If no PIN was configured, confirmation alone ends the commitment.
4. To continue the commitment instead, tap **Cancel request** during cooling-off.

A commitment that has finished still stays “enabled” until you disable it; the app never silently resets or extends it.

## 2. Forgotten PIN / lost recovery code

Your PIN is never the only recovery path:

- Use the **emergency recovery code** shown once at activation (hint shown in the app, e.g. `SG-AB••-••••-••••`).
- If the code is unavailable, continue with the Android-supported steps below. There is no permanent lockout.

## 3. Device administrator deactivation (if activated)

1. Android Settings → Security → Device administrators.
2. Select **Screen Guard Device Administrator** → **Deactivate**.
3. Confirm the Android dialog. The dashboard immediately updates to “Administrator: Not active”.
4. In the app, **Protect → Remove administrator** opens the same Android confirmation flow; there is no hidden override.

Deactivating administrator does not change an accountability commitment, and disabling accountability does not deactivate administrator. They are separate controls.

## 4. Uninstall / app data clearing

- **Uninstall:** Android Settings → Apps → Screen Guard → Uninstall, or Play Store → Screen Guard → Uninstall, or `adb uninstall com.paulfruitful.screenguard`. If administrator is active, Android first asks you to deactivate it (step 3).
- **Clear app data:** Android Settings → Apps → Screen Guard → Storage → Clear data. This deletes the local commitment record; after that the app reports “Off — no commitment”. Clearing app data cannot be silently prevented or reversed by the app.
- **Application update:** the commitment record is preserved (same signing key/package). Updates never reset or shorten it.
- **Reboot:** the commitment is preserved; the Android wall clock and the persisted record survive restart.

## 5. Device reset (last resort)

Factory reset returns the device to its original state and removes all apps and management policies, including Device Owner enrollment if one was ever applied. Use Android Settings → System → Reset options, or the device's hardware recovery procedure. Screen Guard does not interfere with this path or with emergency calling.

## 6. Managed-device / Device Owner unenrollment (future advanced mode)

This build does not enroll the device as Device Owner. If a future build does:

1. Ask your administrator to unenroll the device through the organization's EMM console, or
2. Have the authorized owner app call `DevicePolicyManager.clearDeviceOwnerApp()` (where supported), then
3. Remove any Device administrator entry as in step 3, or
4. Factory reset the dedicated device as the guaranteed recovery path.

See `MILESTONE_4_DEVICE_OWNER_FEASIBILITY.md` for prerequisites and policy scope.

## 7. Failure handling summary

| Event | Behaviour |
| :--- | :--- |
| App data cleared | Commitment record gone; status truthfully reports no active commitment. |
| Device reboot | Commitment preserved; boot broadcast only logs the event. |
| App updated | Commitment preserved; update broadcast only logs the event. |
| Permission revoked (Accessibility / overlay) | Monitoring status recomputed from Android on every status read; no false “active” claim. |
| Administrator deactivated | `DevicePolicyManager.isAdminActive()` read on every status update; UI switches to admin inactive and lock-now is disabled. |
| Service terminated | Monitoring status shows disconnected; accountability status is independent and derived from its own persisted state. |
| Device reset | All app state removed; Device Owner enrollment (if any) removed by Android. |
| Failed provisioning | No owner authority; status remains “Not enrolled”; ordinary app features continue. |
