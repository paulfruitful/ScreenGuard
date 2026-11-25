package com.paulfruitful.screenguard.multimodal

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Structured output from the three lightweight on-device ML Kit verifiers
 * (Face Detection, Pose Estimation, and Burned-in Caption OCR).
 *
 * Only executed on borderline / suspicious frames so clearly safe screens keep < 30 ms latency.
 */
data class ContextVerificationEvidence(
    val executed: Boolean = false,
    // Face / Talking-head portrait geometry
    val faceCount: Int = 0,
    val largestFaceAreaRatio: Float = 0f,
    val isTalkingHeadPortrait: Boolean = false,
    // Clothed twerking / suggestive posture geometry (MoveNet / ML Kit Pose)
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
 * 2. **Pose Detection (~3 MB, < 15 ms):** Extracts 33 body landmarks regardless of clothing to
 *    detect bent-over / rear-facing / hip-centered stances and hip movement (clothed twerking).
 * 3. **On-Device OCR (~4 MB, < 30 ms) + [OnDeviceNlpContextClassifier]:** Reads burned-in captions
 *    inside videos (TikTok, Reels, WhatsApp, X) that Accessibility nodes cannot see and classifies
 *    whether the scene is non-sexual (tailoring, gym, MMA, medical, vlog) vs suggestive/explicit.
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

    private val poseDetector by lazy {
        val opts = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(opts)
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @Volatile
    private var lastHipCenterY: Float = -1f

    @Volatile
    private var lastHipTimestampMs: Long = 0L

    fun resetMotionHistory() {
        lastHipCenterY = -1f
        lastHipTimestampMs = 0L
    }

    /**
     * Runs Face + Pose + OCR + On-Device NLP verification on a borderline/suspicious frame.
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

        // 2. Pose Detection (Clothed twerking / bent-over / hip-centered posture)
        var poseDetected = false
        var bentOverOrRear = false
        var hipProminence = 0f
        var hipOscillation = 0f
        var clothedSuggestivePose = 0f
        try {
            val pose = Tasks.await(poseDetector.process(image), TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
            val rHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
            val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
            val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
            val lKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
            val rKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
            val nose = pose.getPoseLandmark(PoseLandmark.NOSE)

            if (lHip != null && rHip != null &&
                lHip.inFrameLikelihood >= 0.45f && rHip.inFrameLikelihood >= 0.45f
            ) {
                poseDetected = true
                val hipCenterX = (lHip.position.x + rHip.position.x) / 2f / bitmap.width.toFloat()
                val hipCenterY = (lHip.position.y + rHip.position.y) / 2f / bitmap.height.toFloat()
                val hipWidthNorm = abs(lHip.position.x - rHip.position.x) / bitmap.width.toFloat()

                // Hips prominent & centered in frame
                val centered = hipCenterX in 0.20f..0.80f && hipCenterY in 0.28f..0.80f
                if (centered && hipWidthNorm >= 0.14f) {
                    hipProminence = (hipWidthNorm * 2.4f).coerceIn(0f, 1f)
                }

                // Bent-over or rear-facing check:
                // - Face not visible or tiny (turned away or glancing back over shoulder while twerking)
                // - Shoulders close to hip height (bent forward) or knees flexed close to hips
                val faceHiddenOrTiny = (faceCount == 0 || largestFaceRatio < 0.055f) &&
                    (nose == null || nose.inFrameLikelihood < 0.45f || largestFaceRatio < 0.055f)
                val shoulderHipVerticalGap = if (lShoulder != null && rShoulder != null &&
                    lShoulder.inFrameLikelihood >= 0.40f && rShoulder.inFrameLikelihood >= 0.40f
                ) {
                    val shoulderY = (lShoulder.position.y + rShoulder.position.y) / 2f / bitmap.height.toFloat()
                    abs(hipCenterY - shoulderY)
                } else {
                    0.35f
                }
                val kneeHipVerticalGap = if (lKnee != null && rKnee != null &&
                    lKnee.inFrameLikelihood >= 0.40f && rKnee.inFrameLikelihood >= 0.40f
                ) {
                    val kneeY = (lKnee.position.y + rKnee.position.y) / 2f / bitmap.height.toFloat()
                    abs(kneeY - hipCenterY)
                } else {
                    0.35f
                }

                val isBentForward = shoulderHipVerticalGap < 0.20f
                val isDeepSquat = kneeHipVerticalGap < 0.20f
                bentOverOrRear = (faceHiddenOrTiny && hipProminence >= 0.34f) ||
                    (isBentForward && hipProminence >= 0.36f) ||
                    (faceHiddenOrTiny && isDeepSquat && hipProminence >= 0.30f)

                // Track frame-to-frame hip vertical displacement within 2.5s window
                val nowMs = System.currentTimeMillis()
                if (lastHipCenterY >= 0f && (nowMs - lastHipTimestampMs) in 400L..2500L) {
                    val deltaY = abs(hipCenterY - lastHipCenterY)
                    if (deltaY in 0.03f..0.28f && hipProminence >= 0.32f) {
                        hipOscillation = (deltaY * 4.5f).coerceIn(0f, 1f)
                    }
                }
                lastHipCenterY = hipCenterY
                lastHipTimestampMs = nowMs

                if (!isTalkingHead && bentOverOrRear) {
                    clothedSuggestivePose =
                        (0.55f * hipProminence + 0.25f * (if (faceHiddenOrTiny) 1f else 0.45f) + 0.20f * hipOscillation)
                            .coerceIn(0f, 1f)
                }
            }
        } catch (_: Exception) {
        }

        // 3. On-Device OCR (reads burned-in video captions) + Contextual NLP Classifier
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
            poseDetected = poseDetected,
            isBentOverOrRearFacingPose = bentOverOrRear,
            hipProminenceScore = hipProminence,
            hipOscillationScore = hipOscillation,
            clothedSuggestivePoseScore = clothedSuggestivePose,
            ocrTextFound = ocrText.isNotBlank(),
            nlpScore = nlpScore,
            durationMs = durMs
        )
    }
}
