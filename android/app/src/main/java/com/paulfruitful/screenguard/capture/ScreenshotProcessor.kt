package com.paulfruitful.screenguard.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.RequiresApi
import com.paulfruitful.screenguard.classifier.ClassificationResult
import com.paulfruitful.screenguard.classifier.DetectionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

data class ProcessedScreenshotOutcome(
    val succeeded: Boolean,
    val originalWidth: Int,
    val originalHeight: Int,
    val processedWidth: Int,
    val processedHeight: Int,
    val captureDurationMs: Long,
    val processingDurationMs: Long,
    val captureMethod: String,
    val packageName: String,
    val windowId: Int,
    val errorCode: String,
    val classificationResult: ClassificationResult?,
    val debugPreviewBase64: String?
)

/**
 * Converts [AccessibilityService.ScreenshotResult] hardware buffers into low-resolution
 * in-memory software [Bitmap] tensors suitable for classification, guaranteeing that all
 * [android.hardware.HardwareBuffer] and intermediate [Bitmap] instances are closed and
 * recycled inside `finally` blocks.
 *
 * Screenshots are NEVER written to persistent storage or uploaded.
 */
object ScreenshotProcessor {
    private const val CLASSIFIER_TARGET_SIZE = 224
    private const val PREVIEW_MAX_DIMENSION = 420

    @Volatile
    private var volatileDebugPreview: ProcessedScreenshotOutcome? = null

    fun getVolatileDebugPreview(): ProcessedScreenshotOutcome? = volatileDebugPreview

    fun clearVolatileDebugPreview() {
        volatileDebugPreview = null
        BufferReleaseTracker.setHasDebugPreview(false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun processScreenshotResult(
        screenshotResult: AccessibilityService.ScreenshotResult,
        captureStartedElapsedNs: Long,
        captureMethod: String,
        packageName: String,
        windowId: Int,
        holdInMemoryPreview: Boolean
    ): ProcessedScreenshotOutcome = withContext(Dispatchers.Default) {
        val captureDurationMs = max(
            1L,
            (SystemClock.elapsedRealtimeNanos() - captureStartedElapsedNs) / 1_000_000L
        )

        val hardwareBuffer = screenshotResult.hardwareBuffer
        val colorSpace = screenshotResult.colorSpace
        BufferReleaseTracker.onHardwareBufferAcquired()

        var hardwareBitmap: Bitmap? = null
        var softwareClassifierBitmap: Bitmap? = null
        var previewBitmap: Bitmap? = null

        var origWidth = 0
        var origHeight = 0
        var procWidth = CLASSIFIER_TARGET_SIZE
        var procHeight = CLASSIFIER_TARGET_SIZE
        var debugBase64: String? = null

        try {
            // 1. Wrap HardwareBuffer into a hardware-backed Bitmap, copy/downsample into software memory,
            // and IMMEDIATELY close the HardwareBuffer.
            try {
                origWidth = hardwareBuffer.width
                origHeight = hardwareBuffer.height

                val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                if (wrapped != null) {
                    BufferReleaseTracker.onBitmapAllocated()
                    hardwareBitmap = wrapped

                    // Copy and downsample directly into a small ARGB_8888 software Bitmap for the classifier
                    val softCopy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    if (softCopy != null) {
                        BufferReleaseTracker.onBitmapAllocated()
                        try {
                            val scaled = Bitmap.createScaledBitmap(
                                softCopy,
                                CLASSIFIER_TARGET_SIZE,
                                CLASSIFIER_TARGET_SIZE,
                                true
                            )
                            if (scaled !== softCopy) {
                                BufferReleaseTracker.onBitmapAllocated()
                            }
                            softwareClassifierBitmap = scaled
                            procWidth = scaled.width
                            procHeight = scaled.height

                            // Only if user explicitly clicked "Capture & Hold In-Memory Debug Preview" on Testing screen:
                            if (holdInMemoryPreview) {
                                val ratio = minOf(
                                    1.0f,
                                    PREVIEW_MAX_DIMENSION.toFloat() / max(origWidth, origHeight).toFloat()
                                )
                                val pW = max(64, (origWidth * ratio).toInt())
                                val pH = max(64, (origHeight * ratio).toInt())
                                val pBmp = Bitmap.createScaledBitmap(softCopy, pW, pH, true)
                                if (pBmp !== softCopy) {
                                    BufferReleaseTracker.onBitmapAllocated()
                                    previewBitmap = pBmp
                                }
                                val out = ByteArrayOutputStream(16384)
                                (previewBitmap ?: softCopy).compress(
                                    Bitmap.CompressFormat.JPEG,
                                    72,
                                    out
                                )
                                debugBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            }
                        } finally {
                            // If createScaledBitmap returned a distinct Bitmap, recycle softCopy immediately
                            if (softwareClassifierBitmap !== softCopy && !softCopy.isRecycled) {
                                softCopy.recycle()
                                BufferReleaseTracker.onBitmapRecycled()
                            }
                        }
                    }
                }
            } finally {
                // Unconditionally recycle hardwareBitmap and close HardwareBuffer
                hardwareBitmap?.let { bmp ->
                    if (!bmp.isRecycled) {
                        bmp.recycle()
                    }
                    BufferReleaseTracker.onBitmapRecycled()
                }
                hardwareBitmap = null

                try {
                    hardwareBuffer.close()
                } catch (_: Exception) {
                }
                BufferReleaseTracker.onHardwareBufferClosed()

                previewBitmap?.let { pBmp ->
                    if (!pBmp.isRecycled) {
                        pBmp.recycle()
                    }
                    BufferReleaseTracker.onBitmapRecycled()
                }
                previewBitmap = null
            }

            val classifierBmp = softwareClassifierBitmap
            if (classifierBmp == null || classifierBmp.isRecycled) {
                BufferReleaseTracker.recordCaptureFailure(captureMethod, "BUFFER_CONVERSION_FAILED")
                return@withContext ProcessedScreenshotOutcome(
                    succeeded = false,
                    originalWidth = origWidth,
                    originalHeight = origHeight,
                    processedWidth = 0,
                    processedHeight = 0,
                    captureDurationMs = captureDurationMs,
                    processingDurationMs = 0L,
                    captureMethod = captureMethod,
                    packageName = packageName,
                    windowId = windowId,
                    errorCode = "BUFFER_CONVERSION_FAILED",
                    classificationResult = null,
                    debugPreviewBase64 = null
                )
            }

            // 2. Run on-device DetectionEngine on the 224x224 software bitmap
            val classification = DetectionEngine.evaluateFrame(
                downsampledBitmap = classifierBmp,
                packageName = packageName,
                windowId = windowId
            )

            BufferReleaseTracker.recordCaptureSuccess(
                method = captureMethod,
                origWidth = origWidth,
                origHeight = origHeight,
                procWidth = procWidth,
                procHeight = procHeight,
                captureMs = captureDurationMs
            )
            BufferReleaseTracker.recordClassificationDuration(classification.processingDurationMs)

            val outcome = ProcessedScreenshotOutcome(
                succeeded = true,
                originalWidth = origWidth,
                originalHeight = origHeight,
                processedWidth = procWidth,
                processedHeight = procHeight,
                captureDurationMs = captureDurationMs,
                processingDurationMs = classification.processingDurationMs,
                captureMethod = captureMethod,
                packageName = packageName,
                windowId = windowId,
                errorCode = "NONE",
                classificationResult = classification,
                debugPreviewBase64 = debugBase64
            )

            if (holdInMemoryPreview && debugBase64 != null) {
                volatileDebugPreview = outcome
                BufferReleaseTracker.setHasDebugPreview(true)
            }

            return@withContext outcome
        } finally {
            // 3. Unconditionally recycle the 224x224 classifier Bitmap so 0 Bitmaps remain in RAM
            softwareClassifierBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
                BufferReleaseTracker.onBitmapRecycled()
            }
            softwareClassifierBitmap = null
        }
    }
}
