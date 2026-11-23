package com.paulfruitful.screenguard.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.paulfruitful.screenguard.MainActivity

/**
 * Manages a transparent, low-priority status notification so the user is always
 * informed when Screen Guard is actively observing window events and can open the app
 * to pause or disable protection at any time.
 */
object MonitoringNotificationManager {
    private const val CHANNEL_ID = "screenguard_monitoring_status"
    private const val NOTIFICATION_ID = 4102

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

            val title = alertMessage ?: "Screen Guard Protection Active"
            val body = if (currentPackage.isNotBlank() && currentPackage != "None") {
                "On-device observation active · Current window: $currentPackage"
            } else {
                "On-device accessibility observation active · Zero disk retention"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .build()

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
