package com.paulfruitful.screenguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.paulfruitful.screenguard.MainActivity

/**
 * Manages a persistent foreground-service notification so Android and OEM task killers
 * (such as Xiaomi HyperOS SwipeUpClean / SmartPower / LowMemoryKiller) keep
 * [ScreenGuardAccessibilityService] at foreground service priority instead of killing it
 * and putting it into the "Not working. Tap for info." state.
 */
object MonitoringNotificationManager {
    private const val CHANNEL_ID = "screenguard_monitoring_status"
    const val NOTIFICATION_ID = 4102

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Screen Guard Active Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows when Screen Guard accessibility observation is active"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun buildStatusNotification(
        context: Context,
        currentPackage: String,
        alertMessage: String? = null
    ): Notification {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(context, 0, openIntent, pendingFlags)

        val title = alertMessage ?: "Screen Guard Protection Active (Strict)"
        val body = if (currentPackage.isNotBlank() && currentPackage != "None") {
            "On-device protection active · Window: $currentPackage"
        } else {
            "On-device protection active · 100% private"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .build()
    }

    fun promoteToForeground(
        service: AccessibilityService,
        currentPackage: String
    ) {
        try {
            val notification = buildStatusNotification(
                context = service.applicationContext,
                currentPackage = currentPackage
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            updateStatusNotification(
                context = service.applicationContext,
                active = true,
                currentPackage = currentPackage
            )
        }
    }

    fun updateStatusNotification(
        context: Context,
        active: Boolean,
        currentPackage: String,
        alertMessage: String? = null
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (!active) {
                nm.cancel(NOTIFICATION_ID)
                return
            }
            val notification = buildStatusNotification(context, currentPackage, alertMessage)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}
