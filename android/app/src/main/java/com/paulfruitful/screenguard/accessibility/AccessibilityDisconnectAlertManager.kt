package com.paulfruitful.screenguard.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.paulfruitful.screenguard.storage.LocalStorage

/**
 * Monitors AccessibilityService connectivity and displays an urgent protective-shield-styled
 * recovery alert (both a full-screen WindowManager shield overlay when SYSTEM_ALERT_WINDOW is
 * granted AND a high-priority urgent notification backed by [AccessibilityAlertActivity])
 * whenever Android or an OEM task killer disconnects Screen Guard.
 */
object AccessibilityDisconnectAlertManager {

    private const val URGENT_CHANNEL_ID = "screenguard_urgent_accessibility_alert"
    private const val URGENT_NOTIFICATION_ID = 4109
    private const val SNOOZE_DURATION_MS = 5 * 60 * 1000L
    private const val MIN_ALERT_INTERVAL_MS = 45_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var snoozedUntilMs: Long = 0L

    @Volatile
    private var lastAlertShownMs: Long = 0L

    @Volatile
    private var attachedWindowManager: WindowManager? = null

    @Volatile
    private var alertOverlayView: FrameLayout? = null

    @Volatile
    private var isOverlayShowing: Boolean = false

    @Volatile
    var activeAlertActivity: AccessibilityAlertActivity? = null

    /**
     * Checks whether Screen Guard protection is supposed to be active while the
     * AccessibilityService is disconnected (e.g., placed into Android's `Crashed services`
     * by Xiaomi HyperOS OneKeyClean). If so, raises the urgent shield alert.
     */
    fun checkAndAlertIfDisconnected(
        context: Context,
        forceImmediate: Boolean = false
    ): Boolean {
        val appCtx = context.applicationContext
        val prefs = try {
            LocalStorage.getPreferences(appCtx)
        } catch (_: Exception) {
            return false
        }

        if (!prefs.protectionEnabled) {
            dismissAllAlerts(appCtx)
            return false
        }

        val isConnected = ScreenGuardAccessibilityService.isServiceConnected()
        if (isConnected) {
            dismissAllAlerts(appCtx)
            return false
        }

        val isEnabledInSettings =
            ScreenGuardAccessibilityService.isAccessibilityServiceEnabledInSettings(appCtx)
        if (!isEnabledInSettings) {
            // User has not completed initial setup yet (or intentionally disabled the permission)
            return false
        }

        val now = System.currentTimeMillis()
        if (!forceImmediate) {
            if (now < snoozedUntilMs) {
                // Keep the urgent notification visible in the shade, but respect overlay snooze
                postUrgentNotification(appCtx)
                return true
            }
            if ((now - lastAlertShownMs) < MIN_ALERT_INTERVAL_MS && isOverlayShowing) {
                return true
            }
        }

        lastAlertShownMs = now
        postUrgentNotification(appCtx)

        if (canDrawSystemOverlay(appCtx)) {
            showUrgentShieldOverlay(appCtx)
        }
        return true
    }

    /**
     * Called when [ScreenGuardAccessibilityService.onServiceConnected] fires.
     * Immediately clears any urgent shield overlay, activity, and notification.
     */
    fun onServiceReconnected(context: Context) {
        snoozedUntilMs = 0L
        dismissAllAlerts(context.applicationContext)
    }

    fun snoozeOverlay(context: Context) {
        snoozedUntilMs = System.currentTimeMillis() + SNOOZE_DURATION_MS
        hideUrgentShieldOverlay()
        activeAlertActivity?.finish()
        activeAlertActivity = null
    }

    fun dismissAllAlerts(context: Context) {
        hideUrgentShieldOverlay()
        mainHandler.post {
            try {
                activeAlertActivity?.finish()
            } catch (_: Exception) {
            }
            activeAlertActivity = null
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(URGENT_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    fun openAccessibilitySettings(context: Context) {
        // Snooze for 90 seconds while the user toggles the switch OFF and ON so the overlay
        // does not cover Android Settings while they are fixing it.
        snoozedUntilMs = System.currentTimeMillis() + 90_000L
        hideUrgentShieldOverlay()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.applicationContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun canDrawSystemOverlay(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private fun ensureUrgentChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(URGENT_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    URGENT_CHANNEL_ID,
                    "Urgent Protection Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts you immediately if your phone stops Screen Guard Accessibility"
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun postUrgentNotification(context: Context) {
        try {
            ensureUrgentChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val shieldActivityIntent = Intent(context, AccessibilityAlertActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val fullScreenPi = PendingIntent.getActivity(
                context,
                4109,
                shieldActivityIntent,
                pendingFlags
            )

            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val settingsPi = PendingIntent.getActivity(
                context,
                4110,
                settingsIntent,
                pendingFlags
            )

            val notification = NotificationCompat.Builder(context, URGENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("URGENT: Screen Guard Protection Stopped")
                .setContentText("Your phone closed Accessibility. Tap to toggle Screen Guard OFF then ON.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Your phone stopped Screen Guard in the background. Open Accessibility Settings, toggle Screen Guard OFF, then back ON to restore protection."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPi)
                .setFullScreenIntent(fullScreenPi, true)
                .addAction(
                    android.R.drawable.ic_menu_manage,
                    "Reset Accessibility Now",
                    settingsPi
                )
                .build()

            nm.notify(URGENT_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    fun showUrgentShieldOverlay(context: Context) {
        val appCtx = context.applicationContext
        if (!canDrawSystemOverlay(appCtx)) return
        mainHandler.post {
            if (isOverlayShowing && alertOverlayView != null) return@post
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val view = buildShieldAlertView(
                context = appCtx,
                onResetClicked = { openAccessibilitySettings(appCtx) },
                onSnoozeClicked = { snoozeOverlay(appCtx) }
            )
            try {
                if (view.parent != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                }
                view.alpha = 0f
                wm.addView(view, params)
                view.animate().alpha(1f).setDuration(160L).start()
                alertOverlayView = view
                attachedWindowManager = wm
                isOverlayShowing = true
            } catch (_: Exception) {
                isOverlayShowing = false
            }
        }
    }

    fun hideUrgentShieldOverlay() {
        mainHandler.post {
            val view = alertOverlayView
            val wm = attachedWindowManager
            if (view != null && wm != null && isOverlayShowing) {
                try {
                    wm.removeViewImmediate(view)
                } catch (_: Exception) {
                    try {
                        wm.removeView(view)
                    } catch (_: Exception) {
                    }
                }
            }
            alertOverlayView = null
            attachedWindowManager = null
            isOverlayShowing = false
        }
    }

    /**
     * Builds the Protective-Shield-styled urgent reconnect view shared by both the
     * system-wide overlay and [AccessibilityAlertActivity].
     */
    fun buildShieldAlertView(
        context: Context,
        onResetClicked: () -> Unit,
        onSnoozeClicked: () -> Unit
    ): FrameLayout {
        val root = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
        }.apply {
            setBackgroundColor(Color.parseColor("#FA0C2D84"))
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, _ -> true }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 24f)
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dpInt(context, 1.5f), Color.parseColor("#F5B82E"))
            }
            val padH = dpInt(context, 24f)
            val padV = dpInt(context, 26f)
            setPadding(padH, padV, padH, padV)
        }

        // 1. Brand Shield-with-Cross Emblem
        val shieldLogo = AlertShieldEmblemView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(context, 68f), dpInt(context, 68f)).apply {
                bottomMargin = dpInt(context, 14f)
            }
        }
        card.addView(shieldLogo)

        // 2. Urgent Alert Pill
        val badge = TextView(context).apply {
            text = "!  Protection Interrupted"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#DC2626"))
            }
            setPadding(dpInt(context, 14f), dpInt(context, 6f), dpInt(context, 14f), dpInt(context, 6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 14f)
            }
        }
        card.addView(badge)

        // 3. Heading
        val heading = TextView(context).apply {
            text = "Reconnect Screen Guard Protection"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20.5f)
            setTextColor(Color.parseColor("#0F1F4B"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 8f)
            }
        }
        card.addView(heading)

        // 4. Supporting Subtitle
        val subtitle = TextView(context).apply {
            text = "Your phone stopped the Screen Guard Accessibility service in the background. Reset the switch to resume full screen protection."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#5B6B8C"))
            gravity = Gravity.CENTER
            setLineSpacing(dp(context, 3f), 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 16f)
            }
        }
        card.addView(subtitle)

        // 5. 3-Step Reset Card (styled like the scripture box in OverlayController)
        val stepsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 16f)
                setColor(Color.parseColor("#F8FAFF"))
                setStroke(dpInt(context, 1f), Color.parseColor("#DCE6F7"))
            }
            val padH = dpInt(context, 16f)
            val padV = dpInt(context, 14f)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 18f)
            }
        }

        val stepsText = TextView(context).apply {
            text = "1. Tap \"Reset Accessibility Now\" below\n2. Open Downloaded Apps -> Screen Guard\n3. Toggle Screen Guard OFF, then back ON"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Color.parseColor("#0F1F4B"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(context, 5f), 1.0f)
        }
        stepsBox.addView(stepsText)
        card.addView(stepsBox)

        // 6. Primary Gold Pill CTA Button
        val resetBtn = TextView(context).apply {
            text = "Reset Accessibility Now"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(Color.parseColor("#0A1D4E"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#F5B82E"))
            }
            setPadding(dpInt(context, 20f), dpInt(context, 15f), dpInt(context, 20f), dpInt(context, 15f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 10f)
            }
            setOnClickListener { onResetClicked() }
        }
        card.addView(resetBtn)

        // 7. Secondary Snooze Button
        val snoozeBtn = TextView(context).apply {
            text = "Remind Me in 5 Minutes"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Color.parseColor("#5B6B8C"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#F4F7FC"))
                setStroke(dpInt(context, 1f), Color.parseColor("#E2E8F4"))
            }
            setPadding(dpInt(context, 16f), dpInt(context, 12f), dpInt(context, 16f), dpInt(context, 12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onSnoozeClicked() }
        }
        card.addView(snoozeBtn)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            val marginH = dpInt(context, 24f)
            setMargins(marginH, marginH, marginH, marginH)
        }
        root.addView(card, cardParams)
        return root
    }

    private fun dp(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }

    private fun dpInt(context: Context, value: Float): Int = dp(context, value).toInt()

    private class AlertShieldEmblemView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#0C2D84")
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#F5B82E")
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#FFFFFF")
        }
        private val shieldPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            shieldPath.reset()
            shieldPath.moveTo(w * 0.50f, h * 0.08f)
            shieldPath.lineTo(w * 0.85f, h * 0.21f)
            shieldPath.lineTo(w * 0.85f, h * 0.50f)
            shieldPath.quadTo(w * 0.85f, h * 0.78f, w * 0.50f, h * 0.93f)
            shieldPath.quadTo(w * 0.15f, h * 0.78f, w * 0.15f, h * 0.50f)
            shieldPath.lineTo(w * 0.15f, h * 0.21f)
            shieldPath.close()

            canvas.drawPath(shieldPath, fillPaint)
            canvas.drawPath(shieldPath, strokePaint)

            val cx = w * 0.50f
            val cy = h * 0.49f
            val arm = w * 0.16f
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        }
    }
}
