package com.paulfruitful.screenguard.capture

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Atomic telemetry tracker verifying that every [android.hardware.HardwareBuffer]
 * and [android.graphics.Bitmap] allocated during accessibility screenshot capture
 * is deterministically closed and recycled.
 */
object BufferReleaseTracker {
    private val hardwareBuffersAllocated = AtomicInteger(0)
    private val hardwareBuffersClosed = AtomicInteger(0)
    private val bitmapsAllocated = AtomicInteger(0)
    private val bitmapsRecycled = AtomicInteger(0)
    private val activeBuffers = AtomicInteger(0)
    private val hasDebugPreview = AtomicBoolean(false)

    @Volatile
    var lastCaptureMethod: String = "NONE"
    @Volatile
    var lastErrorCode: String = "NONE"
    @Volatile
    var lastOriginalWidth: Int = 0
    @Volatile
    var lastOriginalHeight: Int = 0
    @Volatile
    var lastProcessedWidth: Int = 0
    @Volatile
    var lastProcessedHeight: Int = 0

    private val lastCaptureDurationMs = AtomicLong(0L)
    private val lastClassificationDurationMs = AtomicLong(0L)

    private val debounceReceived = AtomicInteger(0)
    private val debounceAccepted = AtomicInteger(0)
    private val debounceSuppressed = AtomicInteger(0)

    private val serviceDisconnects = AtomicInteger(0)
    private val serviceReconnects = AtomicInteger(0)

    private val totalScreenshotsCaptured = AtomicInteger(0)
    private val totalScreenshotFailures = AtomicInteger(0)

    fun onHardwareBufferAcquired() {
        hardwareBuffersAllocated.incrementAndGet()
        activeBuffers.incrementAndGet()
    }

    fun onHardwareBufferClosed() {
        hardwareBuffersClosed.incrementAndGet()
        activeBuffers.updateAndGet { current -> maxOf(0, current - 1) }
    }

    fun onBitmapAllocated() {
        bitmapsAllocated.incrementAndGet()
        activeBuffers.incrementAndGet()
    }

    fun onBitmapRecycled() {
        bitmapsRecycled.incrementAndGet()
        activeBuffers.updateAndGet { current -> maxOf(0, current - 1) }
    }

    fun setHasDebugPreview(present: Boolean) {
        hasDebugPreview.set(present)
    }

    fun recordCaptureSuccess(
        method: String,
        origWidth: Int,
        origHeight: Int,
        procWidth: Int,
        procHeight: Int,
        captureMs: Long
    ) {
        lastCaptureMethod = method
        lastErrorCode = "NONE"
        lastOriginalWidth = origWidth
        lastOriginalHeight = origHeight
        lastProcessedWidth = procWidth
        lastProcessedHeight = procHeight
        lastCaptureDurationMs.set(captureMs)
        totalScreenshotsCaptured.incrementAndGet()
    }

    fun recordCaptureFailure(method: String, errorCode: String) {
        lastCaptureMethod = method
        lastErrorCode = errorCode
        totalScreenshotFailures.incrementAndGet()
    }

    fun recordClassificationDuration(durationMs: Long) {
        lastClassificationDurationMs.set(durationMs)
    }

    fun onDebounceEventReceived(accepted: Boolean) {
        debounceReceived.incrementAndGet()
        if (accepted) {
            debounceAccepted.incrementAndGet()
        } else {
            debounceSuppressed.incrementAndGet()
        }
    }

    fun onServiceConnected() {
        serviceReconnects.incrementAndGet()
    }

    fun onServiceDisconnected() {
        serviceDisconnects.incrementAndGet()
    }

    fun getHardwareBuffersAllocated(): Int = hardwareBuffersAllocated.get()
    fun getHardwareBuffersClosed(): Int = hardwareBuffersClosed.get()
    fun getBitmapsAllocated(): Int = bitmapsAllocated.get()
    fun getBitmapsRecycled(): Int = bitmapsRecycled.get()
    fun getActiveInMemoryBuffers(): Int = activeBuffers.get()
    fun hasInMemoryDebugPreview(): Boolean = hasDebugPreview.get()
    fun getLastCaptureDurationMs(): Long = lastCaptureDurationMs.get()
    fun getLastClassificationDurationMs(): Long = lastClassificationDurationMs.get()
    fun getDebounceEventsReceived(): Int = debounceReceived.get()
    fun getDebounceEventsAccepted(): Int = debounceAccepted.get()
    fun getDebounceEventsSuppressed(): Int = debounceSuppressed.get()
    fun getServiceDisconnectCount(): Int = serviceDisconnects.get()
    fun getServiceReconnectCount(): Int = serviceReconnects.get()
    fun getTotalScreenshotsCaptured(): Int = totalScreenshotsCaptured.get()
    fun getTotalScreenshotFailures(): Int = totalScreenshotFailures.get()
}
