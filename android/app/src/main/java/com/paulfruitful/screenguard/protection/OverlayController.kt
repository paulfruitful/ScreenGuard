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
                bindReasonToViews(reason)
            } else {
                attachOverlayInternal(reason)
            }
        }
    }

    override fun hideProtection() {
        runOnMain {
            detachOverlayInternal()
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
            wm.addView(view, layoutParams)
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
            setBackgroundColor(Color.parseColor("#FA0B0F14"))
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
                cornerRadius = dp(context, 16f)
                setColor(Color.parseColor("#121820"))
                setStroke(dpInt(context, 1f), Color.parseColor("#2D3A4B"))
            }
            val padH = dpInt(context, 24f)
            val padV = dpInt(context, 28f)
            setPadding(padH, padV, padH, padV)
        }

        // 1. Custom Vector Shield Logo View
        val shieldLogo = ShieldEmblemView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpInt(context, 56f), dpInt(context, 56f)).apply {
                bottomMargin = dpInt(context, 14f)
            }
        }
        card.addView(shieldLogo)

        // 2. State / Brand Pill
        val badge = TextView(context).apply {
            text = "SCREEN GUARD PROTECTION"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#60A5FA"))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999f)
                setColor(Color.parseColor("#1D283A"))
                setStroke(dpInt(context, 1f), Color.parseColor("#3B82F6"))
            }
            setPadding(dpInt(context, 12f), dpInt(context, 5f), dpInt(context, 12f), dpInt(context, 5f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 16f)
            }
        }
        stateBadgeText = badge
        card.addView(badge)

        // 3. Required Heading: "Content hidden for your protection." (Section 4.1)
        val heading = TextView(context).apply {
            text = "Content hidden for your protection."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.parseColor("#F3F6FA"))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#9BA8B8"))
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

        // 5. Minimal Metadata Context Chip (Never displays thumbnails or screenshots)
        val meta = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 8f)
                setColor(Color.parseColor("#0E141B"))
                setStroke(dpInt(context, 1f), Color.parseColor("#1F2937"))
            }
            setPadding(dpInt(context, 12f), dpInt(context, 8f), dpInt(context, 12f), dpInt(context, 8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpInt(context, 22f)
            }
        }
        detailMetaText = meta
        card.addView(meta)

        // 6. Primary Action: "Return to Safe Browsing"
        val primaryBtn = TextView(context).apply {
            text = "Return to Safe Browsing"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 10f)
                setColor(Color.parseColor("#2563EB"))
            }
            setPadding(dpInt(context, 18f), dpInt(context, 14f), dpInt(context, 18f), dpInt(context, 14f))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#9BA8B8"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 10f)
                setColor(Color.parseColor("#171F2A"))
                setStroke(dpInt(context, 1f), Color.parseColor("#2D3A4B"))
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
            "SCREEN GUARD · VERIFYING RECOVERY"
        } else {
            "SCREEN GUARD · ${reason.sensitivityMode.name} POLICY"
        }
        stateBadgeText?.text = stateLabel
        headingText?.text = "Content hidden for your protection."
        supportingText?.text =
            "Screen Guard detected content that matches your protection settings."

        val appName = reason.appLabel.ifBlank { reason.packageName }
        val confPct = "%.0f".format(reason.confidence * 100f)
        detailMetaText?.text =
            "App: $appName\nPolicy Match: ${reason.triggerCategory} ($confPct% confidence)"

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
     * Minimal custom vector shield emblem drawn directly on Canvas (no external bitmap dependencies).
     */
    private class ShieldEmblemView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#1E3A8A")
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#3B82F6")
        }
        private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#F3F6FA")
        }
        private val shieldPath = Path()
        private val checkPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            shieldPath.reset()
            shieldPath.moveTo(w * 0.50f, h * 0.08f)
            shieldPath.lineTo(w * 0.86f, h * 0.22f)
            shieldPath.lineTo(w * 0.86f, h * 0.52f)
            shieldPath.quadTo(w * 0.86f, h * 0.80f, w * 0.50f, h * 0.94f)
            shieldPath.quadTo(w * 0.14f, h * 0.80f, w * 0.14f, h * 0.52f)
            shieldPath.lineTo(w * 0.14f, h * 0.22f)
            shieldPath.close()

            canvas.drawPath(shieldPath, fillPaint)
            canvas.drawPath(shieldPath, strokePaint)

            checkPath.reset()
            checkPath.moveTo(w * 0.36f, h * 0.52f)
            checkPath.lineTo(w * 0.46f, h * 0.62f)
            checkPath.lineTo(w * 0.66f, h * 0.40f)
            canvas.drawPath(checkPath, checkPaint)
        }
    }
}
