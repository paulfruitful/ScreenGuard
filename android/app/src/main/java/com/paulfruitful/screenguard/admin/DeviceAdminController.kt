package com.paulfruitful.screenguard.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.paulfruitful.screenguard.accountability.DeviceEnforcementState

/**
 * Transparent, read-mostly wrapper around Android device-management APIs.
 *
 * Device administrator (BIND_DEVICE_ADMIN) is NOT uninstall protection. Since Android 7,
 * the system shows a deactivation dialog before removing an active administrator, and the
 * user can always deactivate it in Android Settings. The only powerful supported action we
 * request is `force-lock` (lock the screen now), disclosed in the activation screen.
 *
 * Device Owner privileges are only enumerated here for honest status reporting. This app
 * does not provision itself as Device Owner and does not apply owner-only policies.
 */
object DeviceAdminController {

    fun componentName(context: Context): ComponentName =
        ComponentName(context, ScreenGuardDeviceAdminReceiver::class.java)

    private fun policyManager(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    fun isAdminActive(context: Context): Boolean = try {
        policyManager(context)?.isAdminActive(componentName(context)) == true
    } catch (_: Exception) {
        false
    }

    fun currentState(context: Context): DeviceEnforcementState {
        val dpm = policyManager(context) ?: return DeviceEnforcementState()
        val component = componentName(context)
        val adminActive = try {
            dpm.isAdminActive(component)
        } catch (_: Exception) {
            false
        }
        val ownerActive = try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
        val profileOwnerActive = try {
            dpm.isProfileOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
        val uninstallPolicy = if (ownerActive || profileOwnerActive) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.isUninstallBlocked(component, context.packageName)
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return DeviceEnforcementState(
            deviceAdminActive = adminActive,
            deviceAdminComponent = component.flattenToString(),
            deviceOwnerActive = ownerActive,
            profileOwnerActive = profileOwnerActive,
            managedUninstallPolicyActive = uninstallPolicy
        )
    }

    fun buildActivationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enabling Device Administrator protects Screen Guard from quick one-tap " +
                    "uninstallation so you stay guarded in moments of temptation."
            )
            // Do NOT add FLAG_ACTIVITY_NEW_TASK: Android's DeviceAdminAdd explicitly calls
            // finish() and refuses to open if FLAG_ACTIVITY_NEW_TASK is present.
        }

    fun buildDeactivationIntent(context: Context): Intent =
        Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLE_REQUESTED).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(context))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun openSettingsIntent(context: Context): Intent {
        val primary = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (primary.resolveActivity(context.packageManager) != null) {
            primary
        } else {
            Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
    }

    fun canResolve(intent: Intent, context: Context): Boolean = try {
        intent.resolveActivity(context.packageManager) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Supported device-admin capability: lock the screen immediately. Returns false when
     * administrator privileges are not active; never falls back to privileged tricks.
     */
    fun lockNow(context: Context): Boolean = try {
        val dpm = policyManager(context) ?: return false
        if (!isAdminActive(context)) {
            false
        } else {
            dpm.lockNow()
            true
        }
    } catch (_: Exception) {
        false
    }
}
