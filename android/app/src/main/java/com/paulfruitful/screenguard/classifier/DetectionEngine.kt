package com.paulfruitful.screenguard.classifier

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.paulfruitful.screenguard.storage.LocalStorage
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates on-device visual classification using [LiteRtNsfwClassifier],
 * duplicate-frame perceptual hash skipping ([PerceptualFrameHasher]),
 * bounded inference concurrency (`MAX_PENDING_INFERENCE_JOBS = 1`), and
 * multi-frame confirmation via [TemporalDecisionEngine].
 */
object DetectionEngine {
    private const val MAX_PENDING_INFERENCE_JOBS = 1
    private const val DUPLICATE_HASH_MAX_HAMMING_DIST = 2
    private const val DUPLICATE_CACHE_TTL_MS = 8000L

    @Volatile
    private var activeClassifier: ContentClassifier = MockContentClassifier()

    @Volatile
    private var latestResult: ClassificationResult? = null

    private val activeInferenceJobs = AtomicInteger(0)
    private val totalDetections = AtomicInteger(0)
    private val totalConfirmedInterventions = AtomicInteger(0)

    private val hashLock = Any()
    private var lastFrameHash: Long = 0L
    private var lastFramePackage: String = ""
    private var lastFrameElapsedMs: Long = 0L

    fun init(context: Context) {
        if (activeClassifier is MockContentClassifier) {
            synchronized(this) {
                if (activeClassifier is MockContentClassifier) {
                    activeClassifier = LiteRtNsfwClassifier(context.applicationContext)
                }
            }
        }
    }

    suspend fun ensureModelLoaded(context: Context): Boolean {
        init(context)
        val cls = activeClassifier
        return if (cls is LiteRtNsfwClassifier) {
            cls.ensureInitialized()
        } else {
            cls.isModelLoaded()
        }
    }

    fun setClassifier(newClassifier: ContentClassifier) {
        val old = activeClassifier
        activeClassifier = newClassifier
        if (old !== newClassifier) {
            old.release()
        }
        resetConfirmationState()
    }

    fun getActiveClassifier(): ContentClassifier = activeClassifier

    fun getLatestResult(): ClassificationResult? = latestResult

    fun getTotalDetections(): Int = totalDetections.get()

    fun getTotalConfirmedInterventions(): Int = totalConfirmedInterventions.get()

    fun getActiveInferenceJobCount(): Int = activeInferenceJobs.get()

    fun resetConfirmationState() {
        TemporalDecisionEngine.resetState()
        synchronized(hashLock) {
            lastFrameHash = 0L
            lastFramePackage = ""
            lastFrameElapsedMs = 0L
        }
    }

    fun onForegroundPackageChanged(newPackageName: String) {
        TemporalDecisionEngine.onPackageChanged(newPackageName)
        synchronized(hashLock) {
            if (newPackageName != lastFramePackage) {
                lastFramePackage = newPackageName
                lastFrameHash = 0L
                lastFrameElapsedMs = 0L
            }
        }
    }

    /**
     * Evaluates a 224x224 downsampled frame through duplicate-frame check,
     * LiteRT inference, and the [TemporalDecisionEngine].
     */
    suspend fun evaluateFrame(
        downsampledBitmap: Bitmap,
        packageName: String,
        windowId: Int,
        bypassDuplicateCheck: Boolean = false
    ): ClassificationResult {
        val prefs = LocalStorage.getPreferences()

        // 1. Backpressure guard: never allow pending inference jobs to queue up in memory
        val currentJobs = activeInferenceJobs.incrementAndGet()
        if (currentJobs > MAX_PENDING_INFERENCE_JOBS && !bypassDuplicateCheck) {
            activeInferenceJobs.decrementAndGet()
            TemporalDecisionEngine.recordObsoleteFrameDropped()
            return latestResult?.copy(
                error = "OBSOLETE_FRAME_DROPPED_BACKPRESSURE",
                timestamp = System.currentTimeMillis()
            ) ?: ClassificationResult(
                scores = emptyMap(),
                predictedCategory = ContentCategory.UNCERTAIN.name,
                confidence = 0f,
                processingDurationMs = 0L,
                modelVersion = activeClassifier.modelVersion,
                successful = false,
                error = "OBSOLETE_FRAME_DROPPED_BACKPRESSURE",
                packageName = packageName,
                windowId = windowId
            )
        }

        try {
            // 2. Duplicate-frame detection via 64-bit dHash
            val frameHash = PerceptualFrameHasher.computeDHash64(downsampledBitmap)
            val frameHashHex = PerceptualFrameHasher.formatHex(frameHash)
            val nowElapsed = SystemClock.elapsedRealtime()

            if (!bypassDuplicateCheck) {
                synchronized(hashLock) {
                    val prevResult = latestResult
                    val isPrevDefinitelySafe = prevResult != null &&
                        !prevResult.isExplicit &&
                        prevResult.predictedCategory != ContentCategory.UNCERTAIN.name &&
                        prevResult.predictedCategory != ContentCategory.SUGGESTIVE.name
                    if (prevResult != null &&
                        prevResult.successful &&
                        isPrevDefinitelySafe &&
                        packageName == lastFramePackage &&
                        (nowElapsed - lastFrameElapsedMs) < DUPLICATE_CACHE_TTL_MS &&
                        PerceptualFrameHasher.hammingDistance(frameHash, lastFrameHash) <= DUPLICATE_HASH_MAX_HAMMING_DIST
                    ) {
                        TemporalDecisionEngine.recordDuplicateFrameSkipped()
                        val cached = prevResult.copy(
                            isDuplicateSkipped = true,
                            frameHash = frameHashHex,
                            processingDurationMs = 0L,
                            timestamp = System.currentTimeMillis()
                        )
                        latestResult = cached
                        return cached
                    }
                }
            }

            // 3. Execute real on-device LiteRT inference
            val rawResult = activeClassifier.classify(downsampledBitmap).copy(
                frameHash = frameHashHex,
                frameWidth = downsampledBitmap.width,
                frameHeight = downsampledBitmap.height,
                packageName = packageName,
                windowId = windowId,
                timestamp = System.currentTimeMillis()
            )

            synchronized(hashLock) {
                lastFrameHash = frameHash
                lastFramePackage = packageName
                lastFrameElapsedMs = nowElapsed
            }

            // 4. Pass through Temporal Decision Engine for EWMA smoothing and multi-frame confirmation
            val temporalResult = TemporalDecisionEngine.evaluateTemporalFrame(
                rawResult = rawResult,
                packageName = packageName,
                requiredConfirmationFrames = prefs.confirmationFramesRequired,
                confidenceThreshold = prefs.confidenceThreshold
            )

            if (temporalResult.isExplicit) {
                totalDetections.incrementAndGet()
            }
            if (temporalResult.confirmedIntervention) {
                totalConfirmedInterventions.incrementAndGet()
            }

            latestResult = temporalResult
            return temporalResult
        } finally {
            activeInferenceJobs.decrementAndGet()
        }
    }

    /**
     * Adaptive frame sampling policy: when temporal state is PENDING_CONFIRMATION or UNCERTAIN_MONITORING,
     * shorten the sampling interval so confirmation or clearance happens quickly.
     */
    fun computeRecommendedCooldownMs(baseIntervalMs: Long): Long {
        val snap = TemporalDecisionEngine.getSnapshot()
        return when (snap.state) {
            TemporalProtectionState.PENDING_CONFIRMATION,
            TemporalProtectionState.UNCERTAIN_MONITORING -> maxOf(1150L, baseIntervalMs / 2L)
            else -> maxOf(1150L, baseIntervalMs)
        }
    }
}
