package com.paulfruitful.screenguard.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
    val preprocessDurationMs: Long = 0L,
    val processingDurationMs: Long,
    val captureMethod: String,
    val packageName: String,
    val windowId: Int,
    val errorCode: String,
    val classificationResult: ClassificationResult?,
    val debugPreviewBase64: String?
)

/**
 * Phase B Optimized ScreenshotProcessor:
 *
 * Eliminates the 10.36 MB full-screen (`1080x2400`) intermediate software Bitmap allocation!
 * Instead, wraps the [android.hardware.HardwareBuffer] and draws it directly into a `224x224`
 * (`200 KB`, 50x smaller) software `ARGB_8888` [Bitmap] via [Canvas], closing the [android.hardware.HardwareBuffer]
 * within ~2-4 ms.
 *
 * Screenshots are NEVER written to persistent storage or uploaded.
 */
object ScreenshotProcessor {
    private const val CLASSIFIER_TARGET_SIZE = 224
    private const val PREVIEW_MAX_DIMENSION = 420

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

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
        val captureEndNs = SystemClock.elapsedRealtimeNanos()
        val captureDurationMs = max(
            1L,
            (captureEndNs - captureStartedElapsedNs) / 1_000_000L
        )

        val hardwareBuffer = screenshotResult.hardwareBuffer
        val colorSpace = screenshotResult.colorSpace
        BufferReleaseTracker.onHardwareBufferAcquired()

        var hardwareBitmap: Bitmap? = null
        var softwareClassifierBitmap: Bitmap? = null
        var previewBitmap: Bitmap? = null

        var origWidth = 0
        var origHeight = 0
        val procWidth = CLASSIFIER_TARGET_SIZE
        val procHeight = CLASSIFIER_TARGET_SIZE
        var debugBase64: String? = null
        var preprocessDurationMs = 1L

        try {
            // 1. Direct HardwareBuffer -> 224x224 ARGB_8888 downsampling (zero 1080x2400 software copy!)
            try {
                origWidth = hardwareBuffer.width
                origHeight = hardwareBuffer.height

                val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                if (wrapped != null) {
                    BufferReleaseTracker.onBitmapAllocated()
                    hardwareBitmap = wrapped

                    val target224 = Bitmap.createBitmap(
                        CLASSIFIER_TARGET_SIZE,
                        CLASSIFIER_TARGET_SIZE,
                        Bitmap.Config.ARGB_8888
                    )
                    BufferReleaseTracker.onBitmapAllocated()
                    val canvas = Canvas(target224)
                    val srcRect = Rect(0, 0, origWidth, origHeight)
                    val dstRect = Rect(0, 0, CLASSIFIER_TARGET_SIZE, CLASSIFIER_TARGET_SIZE)
                    canvas.drawBitmap(wrapped, srcRect, dstRect, scalePaint)
                    softwareClassifierBitmap = target224

                    if (holdInMemoryPreview) {
                        val ratio = minOf(
                            1.0f,
                            PREVIEW_MAX_DIMENSION.toFloat() / max(origWidth, origHeight).toFloat()
                        )
                        val pW = max(64, (origWidth * ratio).toInt())
                        val pH = max(64, (origHeight * ratio).toInt())
                        val pBmp = Bitmap.createBitmap(pW, pH, Bitmap.Config.ARGB_8888)
                        BufferReleaseTracker.onBitmapAllocated()
                        previewBitmap = pBmp
                        val pCanvas = Canvas(pBmp)
                        pCanvas.drawBitmap(wrapped, srcRect, Rect(0, 0, pW, pH), scalePaint)
                        val out = ByteArrayOutputStream(16384)
                        pBmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
                        debugBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                }
            } finally {
                // Immediately release hardwareBitmap & HardwareBuffer (< 3 ms total hold time!)
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

            preprocessDurationMs = max(
                1L,
                (SystemClock.elapsedRealtimeNanos() - captureEndNs) / 1_000_000L
            )

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
                    preprocessDurationMs = preprocessDurationMs,
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
                preprocessDurationMs = preprocessDurationMs,
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
