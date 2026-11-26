package com.paulfruitful.screenguard.multimodal

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * Structured output from the lightweight on-device ML Kit verifiers
 * (Face Detection and Burned-in Caption OCR + On-Device NLP).
 *
 * Only executed on borderline / suspicious frames or active social feeds so clearly safe screens
 * keep low latency.
 */
data class ContextVerificationEvidence(
    val executed: Boolean = false,
    // Face / Talking-head portrait geometry
    val faceCount: Int = 0,
    val largestFaceAreaRatio: Float = 0f,
    val isTalkingHeadPortrait: Boolean = false,
    // Reserved posture fields for future LiteRT/MediaPipe-Tasks pose model
    val poseDetected: Boolean = false,
    val isBentOverOrRearFacingPose: Boolean = false,
    val hipProminenceScore: Float = 0f,
    val hipOscillationScore: Float = 0f,
    val clothedSuggestivePoseScore: Float = 0f,
    // Burned-in video caption OCR + On-device NLP
    val ocrTextFound: Boolean = false,
    val nlpScore: NlpContextScore = NlpContextScore(),
    val durationMs: Long = 0L
)

/**
 * Lightweight on-device multi-modal verifier powered by Google ML Kit (100% local, zero cloud):
 *
 * 1. **Face Detection (~2 MB, < 8 ms):** Detects close-up talking-head / vlog / video-call faces
 *    so face-dominated frames (e.g., WhatsApp/TikTok talking videos) are not mislabeled as nudity.
 * 2. **On-Device OCR (~4 MB, < 30 ms) + [OnDeviceNlpContextClassifier]:** Reads burned-in captions
 *    inside videos (TikTok, Reels, WhatsApp, X) that Accessibility nodes cannot see and classifies
 *    whether the scene is non-sexual (tailoring, gym, MMA, medical, vlog) vs suggestive/explicit.
 *
 * Note: Legacy `com.google.mlkit:pose-detection:18.0.0-beta5` (`mediapipe-internal:17.0.0-beta10`)
 * was intentionally removed because it triggers a native JNI `SIGABRT` (`NoSuchFieldError` on
 * `mlkit_vision_mediapipe/zzib.value`) on Android 16 devices.
 */
object OnDeviceContextVerifier {

    private const val TASK_TIMEOUT_MS = 180L

    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.12f)
            .build()
        FaceDetection.getClient(opts)
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun resetMotionHistory() {
        // No-op until a modern Android 16-safe LiteRT pose model is added.
    }

    /**
     * Runs Face + OCR + On-Device NLP verification on a borderline/suspicious frame.
     * Safe against exceptions or timeouts — always returns a bounded [ContextVerificationEvidence].
     */
    fun verifyFrame(
        bitmap: Bitmap,
        accessibilityText: String = "",
        runOcr: Boolean = true
    ): ContextVerificationEvidence {
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) {
            return ContextVerificationEvidence(executed = false)
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val image = InputImage.fromBitmap(bitmap, 0)
        val frameArea = (bitmap.width * bitmap.height).toFloat().coerceAtLeast(1f)

        // 1. Face Detection (Talking-head / portrait suppression)
        var faceCount = 0
        var largestFaceRatio = 0f
        try {
            val faces = Tasks.await(faceDetector.process(image), TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            faceCount = faces.size
            for (face in faces) {
                val box = face.boundingBox
                val ratio = ((box.width().coerceAtLeast(0) * box.height().coerceAtLeast(0)).toFloat() / frameArea)
                    .coerceIn(0f, 1f)
                if (ratio > largestFaceRatio) {
                    largestFaceRatio = ratio
                }
            }
        } catch (_: Exception) {
        }
        // A close-up talking head / vlog / reaction video typically has a face occupying >= 10% of the frame.
        val isTalkingHead = faceCount >= 1 && largestFaceRatio >= 0.10f

        // 2. On-Device OCR (reads burned-in video captions) + Contextual NLP Classifier
        var ocrText = ""
        if (runOcr) {
            try {
                val visionText = Tasks.await(textRecognizer.process(image), TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ocrText = visionText.text.orEmpty().take(500)
            } catch (_: Exception) {
            }
        }

        val combinedText = buildString {
            if (accessibilityText.isNotBlank()) append(accessibilityText).append(' ')
            if (ocrText.isNotBlank()) append(ocrText)
        }.trim()

        val nlpScore = OnDeviceNlpContextClassifier.classify(combinedText)
        val durMs = maxOf(1L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)

        return ContextVerificationEvidence(
            executed = true,
            faceCount = faceCount,
            largestFaceAreaRatio = largestFaceRatio,
            isTalkingHeadPortrait = isTalkingHead,
            poseDetected = false,
            isBentOverOrRearFacingPose = false,
            hipProminenceScore = 0f,
            hipOscillationScore = 0f,
            clothedSuggestivePoseScore = 0f,
            ocrTextFound = ocrText.isNotBlank(),
            nlpScore = nlpScore,
            durationMs = durMs
        )
    }
}
