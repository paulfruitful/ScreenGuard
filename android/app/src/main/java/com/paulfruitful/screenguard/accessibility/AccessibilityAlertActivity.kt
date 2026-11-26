package com.paulfruitful.screenguard.accessibility

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Full-screen urgent notification Activity that renders identically to the Screen Guard
 * protective shield when the user taps the urgent disconnect notification or when a
 * full-screen intent fires after Android / Xiaomi stops the AccessibilityService.
 */
class AccessibilityAlertActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // If the service already reconnected, close immediately.
        if (ScreenGuardAccessibilityService.isServiceConnected()) {
            AccessibilityDisconnectAlertManager.onServiceReconnected(applicationContext)
            finish()
            return
        }

        AccessibilityDisconnectAlertManager.activeAlertActivity = this

        val shieldView = AccessibilityDisconnectAlertManager.buildShieldAlertView(
            context = this,
            onResetClicked = {
                AccessibilityDisconnectAlertManager.openAccessibilitySettings(this)
                finish()
            },
            onSnoozeClicked = {
                AccessibilityDisconnectAlertManager.snoozeOverlay(this)
                finish()
            }
        )
        setContentView(shieldView)
    }

    override fun onResume() {
        super.onResume()
        if (ScreenGuardAccessibilityService.isServiceConnected()) {
            AccessibilityDisconnectAlertManager.onServiceReconnected(applicationContext)
            finish()
        }
    }

    override fun onDestroy() {
        if (AccessibilityDisconnectAlertManager.activeAlertActivity === this) {
            AccessibilityDisconnectAlertManager.activeAlertActivity = null
        }
        super.onDestroy()
    }
}
