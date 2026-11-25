package com.paulfruitful.screenguard.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paulfruitful.screenguard.accountability.AccountabilityManager
import com.paulfruitful.screenguard.dns.DnsFilterVpnService
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage

/**
 * Boot and app-update lifecycle observer.
 *
 * Accountability state lives in app-private SharedPreferences and uses the Android wall
 * clock, so it is preserved across process death, reboot, and application updates. This
 * receiver only records those events; it never resets or shortens a commitment.
 */
class AppLifecycleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            return
        }
        try {
            LocalStorage.init(context)
            AccountabilityManager.init(context)
            DnsFilterVpnService.startIfPreparedAndEnabled(context)
            val status = AccountabilityManager.getStatus(context)
            val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
            LocalStorage.appendActivityLog(
                context,
                ActivityLogRecord(
                    type = "ACCOUNTABILITY",
                    title = if (isBoot) "Device rebooted - commitment preserved" else "App updated - commitment preserved",
                    detail = if (status.active) {
                        "Commitment remains active until ${java.util.Date(status.endsAtMs)}. Time is based on the Android wall clock (${if (status.clockTrusted) "no rollback detected" else "clock rollback detected"})."
                    } else {
                        "No active accountability commitment."
                    },
                    packageName = context.packageName
                )
            )
        } catch (_: Exception) {
        }
    }
}
