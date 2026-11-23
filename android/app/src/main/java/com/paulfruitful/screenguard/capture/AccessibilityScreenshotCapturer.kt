package com.paulfruitful.screenguard.capture

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.Display
import com.paulfruitful.screenguard.storage.LocalStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Handles asynchronous screenshot capture via Android Accessibility APIs:
 *
 * - Android 14+ (API 34+): Prefers [AccessibilityService.takeScreenshotOfWindow] when
 *   a valid active `windowId` is available and per-window capture is enabled, falling back
 *   to [AccessibilityService.takeScreenshot] if the window rejects per-window capture.
 * - Android 11+ (API 30+): Uses [AccessibilityService.takeScreenshot] on [Display.DEFAULT_DISPLAY].
 *
 * Respects Android's internal screenshot rate limit (~1000ms) and explicitly handles
 * `FLAG_SECURE`, invalid window IDs, disconnected service states, and unsupported API levels.
 */
object AccessibilityScreenshotCapturer {
    private const val ANDROID_MIN_SCREENSHOT_INTERVAL_MS = 1050L

    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ScreenGuard-ScreenshotCallback").apply { isDaemon = true }
    }

    private val captureInProgress = AtomicBoolean(false)
    private val lastScreenshotRequestElapsedMs = AtomicLong(0L)

    fun mapErrorCodeToString(errorCode: Int): String {
        return when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
            else -> "UNKNOWN_ERROR_$errorCode"
        }
    }

    suspend fun captureAndProcess(
        service: AccessibilityService?,
        windowId: Int,
        packageName: String,
        preferWindowCapture: Boolean,
        holdInMemoryPreview: Boolean,
        bypassCooldownForDiagnostic: Boolean = false
    ): ProcessedScreenshotOutcome {
        if (service == null) {
            BufferReleaseTracker.recordCaptureFailure("NONE", "SERVICE_DISCONNECTED")
            return failureOutcome("NONE", packageName, windowId, "SERVICE_DISCONNECTED")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            BufferReleaseTracker.recordCaptureFailure("NONE", "UNSUPPORTED_ANDROID_VERSION")
            return failureOutcome("NONE", packageName, windowId, "UNSUPPORTED_ANDROID_VERSION")
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedSinceLast = nowElapsed - lastScreenshotRequestElapsedMs.get()
        if (!bypassCooldownForDiagnostic && elapsedSinceLast < ANDROID_MIN_SCREENSHOT_INTERVAL_MS) {
            BufferReleaseTracker.recordCaptureFailure("RATE_GUARD", "INTERVAL_TIME_SHORT")
            return failureOutcome("RATE_GUARD", packageName, windowId, "INTERVAL_TIME_SHORT")
        }

        if (!captureInProgress.compareAndSet(false, true)) {
            return failureOutcome("CONCURRENT_GUARD", packageName, windowId, "CAPTURE_IN_PROGRESS")
        }

        try {
            lastScreenshotRequestElapsedMs.set(SystemClock.elapsedRealtime())
            val startNs = SystemClock.elapsedRealtimeNanos()

            val canUseWindowApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                preferWindowCapture &&
                windowId >= 0

            if (canUseWindowApi) {
                val windowAttempt = withTimeoutOrNull(2000L) {
                    requestWindowScreenshot(service, windowId)
                } ?: RawCaptureAttempt(
                    null,
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
                )
                if (windowAttempt.result != null) {
                    val outcome = ScreenshotProcessor.processScreenshotResult(
                        screenshotResult = windowAttempt.result,
                        captureStartedElapsedNs = startNs,
                        captureMethod = "takeScreenshotOfWindow($windowId)",
                        packageName = packageName,
                        windowId = windowId,
                        holdInMemoryPreview = holdInMemoryPreview
                    )
                    LocalStorage.recordAutomaticCompatibilityOutcome(
                        packageName = packageName,
                        succeeded = outcome.succeeded,
                        captureMethod = outcome.captureMethod,
                        width = outcome.originalWidth,
                        height = outcome.originalHeight,
                        durationMs = outcome.captureDurationMs,
                        errorCode = outcome.errorCode
                    )
                    return outcome
                }

                // If per-window screenshot failed because the windowId became stale/invalid or
                // surface compositing returned INTERNAL_ERROR, fall back once to display-level
                // takeScreenshot(DEFAULT_DISPLAY) (never bypassing SECURE_WINDOW).
                val errStr = mapErrorCodeToString(windowAttempt.errorCode)
                val shouldFallbackToDisplay =
                    windowAttempt.errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ||
                        windowAttempt.errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                if (!shouldFallbackToDisplay) {
                    BufferReleaseTracker.recordCaptureFailure(
                        "takeScreenshotOfWindow($windowId)",
                        errStr
                    )
                    LocalStorage.recordAutomaticCompatibilityOutcome(
                        packageName = packageName,
                        succeeded = false,
                        captureMethod = "takeScreenshotOfWindow($windowId)",
                        width = 0,
                        height = 0,
                        durationMs = 0L,
                        errorCode = errStr
                    )
                    return failureOutcome(
                        "takeScreenshotOfWindow($windowId)",
                        packageName,
                        windowId,
                        errStr
                    )
                }
            }

            // Display-level screenshot (Android 11+ or fallback from invalid windowId)
            val displayMethod = if (canUseWindowApi) {
                "takeScreenshot(DEFAULT_DISPLAY_FALLBACK)"
            } else {
                "takeScreenshot(DEFAULT_DISPLAY)"
            }

            val displayAttempt = withTimeoutOrNull(2000L) {
                requestDisplayScreenshot(service)
            } ?: RawCaptureAttempt(
                null,
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            )
            if (displayAttempt.result != null) {
                val outcome = ScreenshotProcessor.processScreenshotResult(
                    screenshotResult = displayAttempt.result,
                    captureStartedElapsedNs = startNs,
                    captureMethod = displayMethod,
                    packageName = packageName,
                    windowId = windowId,
                    holdInMemoryPreview = holdInMemoryPreview
                )
                LocalStorage.recordAutomaticCompatibilityOutcome(
                    packageName = packageName,
                    succeeded = outcome.succeeded,
                    captureMethod = outcome.captureMethod,
                    width = outcome.originalWidth,
                    height = outcome.originalHeight,
                    durationMs = outcome.captureDurationMs,
                    errorCode = outcome.errorCode
                )
                return outcome
            } else {
                val errStr = mapErrorCodeToString(displayAttempt.errorCode)
                BufferReleaseTracker.recordCaptureFailure(displayMethod, errStr)
                LocalStorage.recordAutomaticCompatibilityOutcome(
                    packageName = packageName,
                    succeeded = false,
                    captureMethod = displayMethod,
                    width = 0,
                    height = 0,
                    durationMs = 0L,
                    errorCode = errStr
                )
                return failureOutcome(displayMethod, packageName, windowId, errStr)
            }
        } finally {
            captureInProgress.set(false)
        }
    }

    private data class RawCaptureAttempt(
        val result: AccessibilityService.ScreenshotResult?,
        val errorCode: Int
    )

    private suspend fun requestWindowScreenshot(
        service: AccessibilityService,
        windowId: Int
    ): RawCaptureAttempt = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cont.resume(
                RawCaptureAttempt(
                    null,
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
                )
            )
            return@suspendCancellableCoroutine
        }
        try {
            service.takeScreenshotOfWindow(
                windowId,
                captureExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (cont.isActive) {
                            cont.resume(RawCaptureAttempt(screenshot, 0))
                        } else {
                            try {
                                screenshot.hardwareBuffer.close()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(RawCaptureAttempt(null, errorCode))
                        }
                    }
                }
            )
        } catch (_: SecurityException) {
            if (cont.isActive) {
                cont.resume(
                    RawCaptureAttempt(
                        null,
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                    )
                )
            }
        } catch (_: Exception) {
            if (cont.isActive) {
                cont.resume(
                    RawCaptureAttempt(
                        null,
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    )
                )
            }
        }
    }

    private suspend fun requestDisplayScreenshot(
        service: AccessibilityService
    ): RawCaptureAttempt = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(
                RawCaptureAttempt(
                    null,
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                )
            )
            return@suspendCancellableCoroutine
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                captureExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (cont.isActive) {
                            cont.resume(RawCaptureAttempt(screenshot, 0))
                        } else {
                            try {
                                screenshot.hardwareBuffer.close()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(RawCaptureAttempt(null, errorCode))
                        }
                    }
                }
            )
        } catch (_: SecurityException) {
            if (cont.isActive) {
                cont.resume(
                    RawCaptureAttempt(
                        null,
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                    )
                )
            }
        } catch (_: Exception) {
            if (cont.isActive) {
                cont.resume(
                    RawCaptureAttempt(
                        null,
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    )
                )
            }
        }
    }

    private fun failureOutcome(
        method: String,
        packageName: String,
        windowId: Int,
        errorCode: String
    ): ProcessedScreenshotOutcome {
        return ProcessedScreenshotOutcome(
            succeeded = false,
            originalWidth = 0,
            originalHeight = 0,
            processedWidth = 0,
            processedHeight = 0,
            captureDurationMs = 0L,
            processingDurationMs = 0L,
            captureMethod = method,
            packageName = packageName,
            windowId = windowId,
            errorCode = errorCode,
            classificationResult = null,
            debugPreviewBase64 = null
        )
    }
}
