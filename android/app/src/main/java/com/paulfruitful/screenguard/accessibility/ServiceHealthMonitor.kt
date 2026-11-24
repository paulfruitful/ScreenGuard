package com.paulfruitful.screenguard.accessibility

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.paulfruitful.screenguard.capture.BufferReleaseTracker
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ServiceHealthSnapshot(
    val serviceConnected: Boolean,
    val uptimeMs: Long,
    val lastEventElapsedMs: Long,
    val lastCaptureSuccessElapsedMs: Long,
    val consecutiveCaptureFailures: Int,
    val consecutiveWindowApiFailures: Int,
    val windowApiCircuitOpen: Boolean,
    val inferenceTimeouts: Int,
    val lowMemoryTrims: Int,
    val javaHeapUsedKb: Long,
    val nativeHeapUsedKb: Long,
    val totalPssKb: Long,
    val healthState: String,
    val lastRecoveryAction: String
)

/**
 * Monitors long-running [ScreenGuardAccessibilityService] health, screenshot callback reliability,
 * per-window capture circuit breaking, inference timeout recovery, and memory footprint.
 *
 * Never creates uncontrolled service restart loops or repeatedly requests permissions.
 */
object ServiceHealthMonitor {
    private const val TAG = "SGHealthMonitor"
    private const val WINDOW_API_CIRCUIT_THRESHOLD = 3
    private const val WINDOW_API_COOLDOWN_MS = 15_000L
    private const val HIGH_JAVA_HEAP_KB = 48_000L

    private val connectedAtElapsedMs = AtomicLong(0L)
    private val lastEventElapsedMs = AtomicLong(0L)
    private val lastCaptureSuccessElapsedMs = AtomicLong(0L)
    private val consecutiveCaptureFailures = AtomicInteger(0)
    private val consecutiveWindowApiFailures = AtomicInteger(0)
    private val windowCircuitOpenedAtElapsedMs = AtomicLong(0L)
    private val inferenceTimeouts = AtomicInteger(0)
    private val lowMemoryTrims = AtomicInteger(0)

    @Volatile
    private var lastFailedWindowId: Int = -1

    @Volatile
    private var lastRecoveryAction: String = "NONE"

    fun onServiceConnected() {
        val now = SystemClock.elapsedRealtime()
        connectedAtElapsedMs.set(now)
        lastEventElapsedMs.set(now)
        consecutiveCaptureFailures.set(0)
        consecutiveWindowApiFailures.set(0)
        windowCircuitOpenedAtElapsedMs.set(0L)
        lastRecoveryAction = "SERVICE_BOUND"
    }

    fun onServiceDisconnected(reason: String) {
        connectedAtElapsedMs.set(0L)
        consecutiveCaptureFailures.set(0)
        consecutiveWindowApiFailures.set(0)
        lastRecoveryAction = "UNBOUND:$reason"
    }

    fun onAccessibilityEventReceived() {
        lastEventElapsedMs.set(SystemClock.elapsedRealtime())
    }

    fun onCaptureSuccess(usedWindowApi: Boolean) {
        lastCaptureSuccessElapsedMs.set(SystemClock.elapsedRealtime())
        consecutiveCaptureFailures.set(0)
        if (usedWindowApi) {
            consecutiveWindowApiFailures.set(0)
            windowCircuitOpenedAtElapsedMs.set(0L)
        }
    }

    fun onCaptureFailure(errorCode: String, usedWindowApi: Boolean, windowId: Int) {
        if (errorCode == "INTERVAL_TIME_SHORT" || errorCode == "SECURE_WINDOW") {
            // Expected platform rate limit or protected surface — not a service health fault
            return
        }
        val fails = consecutiveCaptureFailures.incrementAndGet()
        if (usedWindowApi) {
            lastFailedWindowId = windowId
            val winFails = consecutiveWindowApiFailures.incrementAndGet()
            if (winFails >= WINDOW_API_CIRCUIT_THRESHOLD) {
                windowCircuitOpenedAtElapsedMs.set(SystemClock.elapsedRealtime())
                lastRecoveryAction = "WINDOW_API_FALLBACK_TO_DISPLAY(win=$windowId,err=$errorCode)"
                Log.w(TAG, "Opening per-window capture circuit after $winFails failures ($errorCode); using display capture fallback")
            }
        }
        if (fails >= 6) {
            lastRecoveryAction = "CAPTURE_BACKOFF_AFTER_${fails}_FAILURES($errorCode)"
        }
    }

    /**
     * Returns true if per-window `takeScreenshotOfWindow` should be bypassed in favor of
     * `takeScreenshot(DEFAULT_DISPLAY)` due to repeated window-token failures.
     */
    fun shouldBypassWindowCapture(windowId: Int): Boolean {
        if (windowId < 0) return true
        val openedAt = windowCircuitOpenedAtElapsedMs.get()
        if (openedAt <= 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - openedAt
        if (elapsed < WINDOW_API_COOLDOWN_MS) {
            return true
        }
        // Cooldown expired; allow one probe
        windowCircuitOpenedAtElapsedMs.set(0L)
        consecutiveWindowApiFailures.set(0)
        return false
    }

    fun onInferenceTimeout() {
        val count = inferenceTimeouts.incrementAndGet()
        lastRecoveryAction = "INFERENCE_TIMEOUT_RECOVERED(#$count)"
        Log.w(TAG, "Frame inference timed out (#$count); scheduler recovered cleanly")
    }

    fun onTrimMemory(level: Int) {
        val count = lowMemoryTrims.incrementAndGet()
        lastRecoveryAction = "MEMORY_TRIM_LEVEL_$level(#$count)"
    }

    fun checkMemoryPressureAndPurgeIfNeeded() {
        val rt = Runtime.getRuntime()
        val javaUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L
        if (javaUsedKb > HIGH_JAVA_HEAP_KB || BufferReleaseTracker.hasInMemoryDebugPreview()) {
            com.paulfruitful.screenguard.capture.ScreenshotProcessor.clearVolatileDebugPreview()
        }
    }

    fun snapshot(context: Context? = null): ServiceHealthSnapshot {
        val now = SystemClock.elapsedRealtime()
        val connectedAt = connectedAtElapsedMs.get()
        val isConnected = ScreenGuardAccessibilityService.isServiceConnected()
        val uptime = if (isConnected && connectedAt > 0L) now - connectedAt else 0L
        val rt = Runtime.getRuntime()
        val javaUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L
        val nativeUsedKb = Debug.getNativeHeapAllocatedSize() / 1024L
        var pssKb = javaUsedKb + nativeUsedKb
        if (context != null) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(memInfo)
                pssKb = memInfo.totalPss.toLong()
            } catch (_: Exception) {
            }
        }
        val circuitOpen = windowCircuitOpenedAtElapsedMs.get() > 0L &&
            (now - windowCircuitOpenedAtElapsedMs.get()) < WINDOW_API_COOLDOWN_MS
        val state = when {
            !isConnected -> "DISCONNECTED"
            consecutiveCaptureFailures.get() >= 5 -> "DEGRADED_CAPTURE_BACKOFF"
            circuitOpen -> "HEALTHY_DISPLAY_FALLBACK"
            else -> "HEALTHY"
        }
        return ServiceHealthSnapshot(
            serviceConnected = isConnected,
            uptimeMs = uptime,
            lastEventElapsedMs = lastEventElapsedMs.get(),
            lastCaptureSuccessElapsedMs = lastCaptureSuccessElapsedMs.get(),
            consecutiveCaptureFailures = consecutiveCaptureFailures.get(),
            consecutiveWindowApiFailures = consecutiveWindowApiFailures.get(),
            windowApiCircuitOpen = circuitOpen,
            inferenceTimeouts = inferenceTimeouts.get(),
            lowMemoryTrims = lowMemoryTrims.get(),
            javaHeapUsedKb = javaUsedKb,
            nativeHeapUsedKb = nativeUsedKb,
            totalPssKb = pssKb,
            healthState = state,
            lastRecoveryAction = lastRecoveryAction
        )
    }
}
