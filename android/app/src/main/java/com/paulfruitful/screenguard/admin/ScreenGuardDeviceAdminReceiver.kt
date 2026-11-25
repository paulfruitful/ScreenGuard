package com.paulfruitful.screenguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.paulfruitful.screenguard.accountability.AccountabilityManager
import com.paulfruitful.screenguard.bridge.EventBridge
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage

/**
 * DeviceAdminReceiver is used only for a disclosed, supported capability (lock screen)
 * and for accurate status reporting of administrator activation/deactivation.
 *
 * It does not block uninstall. If Android or the user removes this administrator,
 * [onDisabled] updates the UI status immediately instead of hiding that fact.
 */
class ScreenGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        log(context, "DEVICE_ADMIN_ENABLED", "Device administrator enabled", "Screen Guard can now lock the screen on request. This does not prevent uninstall.")
        publish(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        log(context, "DEVICE_ADMIN_DISABLED", "Device administrator deactivated", "Screen-restriction capability removed. Accountability status, if any, is unaffected.")
        publish(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        log(context, "DEVICE_ADMIN_DISABLE_REQUESTED", "Uninstall protection removal requested", "Android is asking for confirmation to remove Screen Guard device administrator.")
        publish(context)
        return "Pause for a moment. Removing Screen Guard Device Admin weakens your uninstall protection and makes relapse easier. Are you sure you want to continue?"
    }

    private fun publish(context: Context) {
        try {
            AccountabilityManager.init(context)
            EventBridge.emitAccountabilityStatus(AccountabilityManager.getStatus(context))
        } catch (_: Exception) {
        }
    }

    private fun log(context: Context, type: String, title: String, detail: String) {
        try {
            LocalStorage.init(context)
            LocalStorage.appendActivityLog(
                context,
                ActivityLogRecord(
                    type = type,
                    title = title,
                    detail = detail,
                    packageName = context.packageName
                )
            )
        } catch (_: Exception) {
        }
    }
}
