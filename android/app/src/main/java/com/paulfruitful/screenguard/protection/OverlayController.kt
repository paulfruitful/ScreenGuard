package com.paulfruitful.screenguard.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
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
import com.paulfruitful.screenguard.accessibility.ScreenGuardAccessibilityService

/**
 * Interface for controlling the system-wide protective overlay (Section 8).
 */
interface OverlayController {
    fun showProtection(reason: ProtectionReason)
    fun hideProtection()
    fun fadeOutProtection()
    fun setTemporarilyTransparentForProbe(transparent: Boolean)
    fun updateProtection(reason: ProtectionReason)
    fun isVisible(): Boolean
    fun onConfigurationChanged(newConfig: Configuration?)
    fun getLastError(): String
}

/**
 * Native Kotlin WindowManager overlay implementation for Milestone 3 (Sections 3 & 4).
 *
 * Uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] bound directly to
 * [ScreenGuardAccessibilityService]'s WindowManager context as the primary mechanism:
 * - Intrinsically authorized by the enabled AccessibilityService without requiring a separate
 *   `SYSTEM_ALERT_WINDOW` grant.
 * - Treated as a trusted accessibility surface by Android's InputDispatcher (100% opaque,
 *   intercepts all touch events over covered apps without Android 12+ untrusted-occlusion penalties).
 * - Automatically falls back to [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] only if
 *   `Settings.canDrawOverlays` is granted and the accessibility window context is unavailable.
 */
class ScreenGuardOverlayController(
    private val appContext: Context,
    private val onReturnToSafetyClicked: (ProtectionReason) -> Unit,
    private val onDismissClicked: (ProtectionReason) -> Unit
) : OverlayController {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var attachedWindowManager: WindowManager? = null
    @Volatile
    private var rootOverlayView: FrameLayout? = null
    @Volatile
    private var isOverlayAttached: Boolean = false
    @Volatile
    private var currentReason: ProtectionReason? = null
    @Volatile
    private var lastOverlayError: String = "NONE"

    // View references updated in-place to avoid view recreation
    private var stateBadgeText: TextView? = null
    private var headingText: TextView? = null
    private var supportingText: TextView? = null
    private var detailMetaText: TextView? = null
    private var primarySafeButton: TextView? = null
    private var secondaryDismissButton: TextView? = null

    override fun isVisible(): Boolean = isOverlayAttached

    override fun getLastError(): String = lastOverlayError

    override fun showProtection(reason: ProtectionReason) {
        runOnMain {
            currentReason = reason
            if (isOverlayAttached && rootOverlayView != null) {
                rootOverlayView?.animate()?.cancel()
                rootOverlayView?.alpha = 1f
                bindReasonToViews(reason)
                return@runOnMain
            }
            attachOverlayInternal(reason)
        }
    }

    override fun updateProtection(reason: ProtectionReason) {
        runOnMain {
            currentReason = reason
            if (isOverlayAttached && rootOverlayView != null) {
                rootOverlayView?.animate()?.cancel()
                rootOverlayView?.alpha = 1f
                bindReasonToViews(reason)
            } else {
                attachOverlayInternal(reason)
            }
        }
    }

    override fun hideProtection() {
        runOnMain {
            rootOverlayView?.animate()?.cancel()
            detachOverlayInternal()
        }
    }

    override fun fadeOutProtection() {
        runOnMain {
            val view = rootOverlayView
            if (!isOverlayAttached || view == null) {
                detachOverlayInternal()
                return@runOnMain
            }
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(260L)
                .withEndAction {
                    detachOverlayInternal()
                }
                .start()
        }
    }

    override fun setTemporarilyTransparentForProbe(transparent: Boolean) {
        runOnMain {
            val view = rootOverlayView ?: return@runOnMain
            if (!isOverlayAttached) return@runOnMain
            view.alpha = if (transparent) 0f else 1f
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        runOnMain {
            val view = rootOverlayView ?: return@runOnMain
            val wm = attachedWindowManager ?: return@runOnMain
            if (!isOverlayAttached) return@runOnMain
            try {
                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@runOnMain
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                wm.updateViewLayout(view, params)
                currentReason?.let { bindReasonToViews(it) }
            } catch (e: Exception) {
                lastOverlayError = "CONFIG_UPDATE_ERROR: ${e.message}"
            }
        }
    }

    private fun attachOverlayInternal(reason: ProtectionReason) {
        if (isOverlayAttached) {
            bindReasonToViews(reason)
            return
        }

        val a11yService: AccessibilityService? = ScreenGuardAccessibilityService.getActiveInstance()
        val canUseA11yOverlay = a11yService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1
        val canUseAppOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(appContext)

        if (!canUseA11yOverlay && !canUseAppOverlay) {
            lastOverlayError = "OVERLAY_UNAVAILABLE_SERVICE_OR_PERMISSION_MISSING"
            return
        }

        val targetContext: Context = if (canUseA11yOverlay) a11yService!! else appContext
        val wm = targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            lastOverlayError = "WINDOW_MANAGER_UNAVAILABLE"
            return
        }

        val windowType = if (canUseA11yOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = buildWindowLayoutParams(windowType)
        val view = rootOverlayView ?: buildProtectiveOverlayView(targetContext).also {
            rootOverlayView = it
        }

        bindReasonToViews(reason)

        try {
            if (view.parent != null) {
                (view.parent as? ViewGroup)?.removeView(view)
            }
            view.alpha = 0f
            wm.addView(view, layoutParams)
            view.animate().alpha(1f).setDuration(140L).start()
            attachedWindowManager = wm
            isOverlayAttached = true
            lastOverlayError = "NONE"
        } catch (e: Exception) {
            // If TYPE_ACCESSIBILITY_OVERLAY failed and SYSTEM_ALERT_WINDOW is granted, try fallback once
            if (canUseA11yOverlay && canUseAppOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val appWm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val fallbackParams = buildWindowLayoutParams(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    )
                    appWm.addView(view, fallbackParams)
                    attachedWindowManager = appWm
                    isOverlayAttached = true
                    lastOverlayError = "NONE"
                    return
                } catch (fallbackEx: Exception) {
                    lastOverlayError = "ATTACH_FAILED: ${fallbackEx.javaClass.simpleName}: ${fallbackEx.message}"
                }
            } else {
                lastOverlayError = "ATTACH_FAILED: ${e.javaClass.simpleName}: ${e.message}"
            }
            isOverlayAttached = false
        }
    }

    private fun detachOverlayInternal() {
        val view = rootOverlayView
        val wm = attachedWindowManager
        if (view != null && wm != null && isOverlayAttached) {
            try {
                wm.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    wm.removeView(view)
                } catch (_: Exception) {
                }
            }
        }
        isOverlayAttached = false
        attachedWindowManager = null
    }

    private fun buildWindowLayoutParams(windowType: Int): WindowManager.LayoutParams {
        // Notice: FLAG_NOT_TOUCHABLE is intentionally NOT set so the overlay intercepts all touches
        // over the covered application. FLAG_NOT_FOCUSABLE keeps Android system navigation responsive.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        return WindowManager.LayoutParams(
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
    }

    private fun buildProtectiveOverlayView(context: Context): FrameLayout {
        val root = object : FrameLayout(context) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
                // Allow child buttons to receive clicks while blocking any touch-through to underlying window
                return false
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#FA0C2D84"))
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // Consume all stray touches on the backdrop so zero touches leak to the covered app
            setOnTouchListener { _, _ -> true }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 24f)
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dpInt(context, 1f), Color.parseColor("#E2E8F4"))
            }
            val padH = dpInt(context, 24f)
            val padV = dpInt(context, 28f)
            setPadding(padH, padV, padH, padV)
        }

        // 1. Custom Vector Shield with Cross Logo View
        val shieldLogo = ShieldEmblemView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(context, 68f), dpInt(context, 68f)).apply {
                bottomMargin = dpInt(context, 14f)
            }
        }
        card.addView(shieldLogo)

        // 2. Clean Green Status Pill
        val badge = TextView(context).apply {
            text = "✓  Screen Guard Protected"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#1BB55C"))
            }
            setPadding(dpInt(context, 14f), dpInt(context, 6f), dpInt(context, 14f), dpInt(context, 6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 16f)
            }
        }
        stateBadgeText = badge
        card.addView(badge)

        // 3. Required Heading: "Content hidden for your protection."
        val heading = TextView(context).apply {
            text = "Content hidden for your protection."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(Color.parseColor("#0F1F4B"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 10f)
            }
        }
        headingText = heading
        card.addView(heading)

        // 4. Required Supporting Text: "Screen Guard detected content that matches your protection settings."
        val supporting = TextView(context).apply {
            text = "Screen Guard detected content that matches your protection settings."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(Color.parseColor("#5B6B8C"))
            gravity = Gravity.CENTER
            setLineSpacing(dp(context, 3f), 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 14f)
            }
        }
        supportingText = supporting
        card.addView(supporting)

        // 5. Friendly Context Pill (Never displays thumbnails or technical scores)
        val meta = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor("#0C2D84"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#EEF3FA"))
            }
            setPadding(dpInt(context, 14f), dpInt(context, 7f), dpInt(context, 14f), dpInt(context, 7f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 22f)
            }
        }
        detailMetaText = meta
        card.addView(meta)

        // 6. Primary Action: Gold Pill "Return to Safe Browsing"
        val primaryBtn = TextView(context).apply {
            text = "Return to Safe Browsing"
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
            setOnClickListener {
                currentReason?.let { r -> onReturnToSafetyClicked(r) }
            }
        }
        primarySafeButton = primaryBtn
        card.addView(primaryBtn)

        // 7. Optional Dismiss Action (subject to active protection policy)
        val dismissBtn = TextView(context).apply {
            text = "Dismiss for This Screen"
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
            setOnClickListener {
                currentReason?.let { r -> onDismissClicked(r) }
            }
        }
        secondaryDismissButton = dismissBtn
        card.addView(dismissBtn)

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

    private fun bindReasonToViews(reason: ProtectionReason) {
        val stateLabel = if (reason.overlayState == OverlayState.RECOVERING) {
            "✓  Checking Screen Safety"
        } else {
            "✓  Screen Guard Protected"
        }
        stateBadgeText?.text = stateLabel
        headingText?.text = "Content hidden for your protection."
        supportingText?.text =
            "Screen Guard detected content that matches your protection settings."

        val appName = reason.appLabel.ifBlank { reason.packageName }
        detailMetaText?.text = "Protected in $appName"

        secondaryDismissButton?.visibility = if (reason.allowManualDismiss) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun dp(context: Context, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )
    }

    private fun dpInt(context: Context, value: Float): Int = dp(context, value).toInt()

    /**
     * Custom vector Shield with Cross emblem drawn directly on Canvas (matches Screen Guard brand logo).
     */
    private class ShieldEmblemView(context: Context) : View(context) {
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
