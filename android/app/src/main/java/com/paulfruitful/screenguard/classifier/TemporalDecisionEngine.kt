package com.paulfruitful.screenguard.classifier

import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

enum class TemporalProtectionState {
    SAFE_STABLE,
    OBSERVING_NON_EXPLICIT,
    UNCERTAIN_MONITORING,
    PENDING_CONFIRMATION,
    CONFIRMED_DETECTION,
    COOLDOWN_RECOVERY
}

data class TemporalDecisionSnapshot(
    val state: TemporalProtectionState,
    val consecutiveSuspiciousCount: Int,
    val consecutiveSafeCount: Int,
    val smoothedExplicitScore: Float,
    val smoothedNudityScore: Float,
    val smoothedSexualActivityScore: Float,
    val smoothedSafeScore: Float,
    val confirmedIntervention: Boolean,
    val cooldownRemainingMs: Long,
    val totalFramesClassified: Int,
    val explicitDetections: Int,
    val nudityDetections: Int,
    val sexualActivityDetections: Int,
    val suggestiveDetections: Int,
    val nonExplicitDetections: Int,
    val safeClassifications: Int,
    val uncertainClassifications: Int,
    val duplicateFramesSkipped: Int,
    val obsoleteFramesDropped: Int
)

private data class TemporalFrameEntry(
    val category: String,
    val pornScore: Float,
    val sexyScore: Float,
    val safeScore: Float,
    val stage2NsfwScore: Float,
    val sexualActivityScore: Float,
    val dHash: Long,
    val timestampMs: Long
)

/**
 * Milestone 2.1 Temporal Decision Engine:
 *
 * - Maintains a bounded in-memory sliding window (`MAX_HISTORY_FRAMES = 5`) of recent
 *   observations within the current foreground package.
 * - Detects scene transitions using 64-bit perceptual `dHash` Hamming distance (`>= 30` bits)
 *   so probabilities from a previous scene do not bleed across a hard scene cut.
 * - Uses EWMA smoothing and multi-frame evidence aggregation to resolve single-frame
 *   `UNCERTAIN` predictions when corroborated by actual visual evidence in the same scene,
 *   while NEVER converting repeated uncertainty into a positive detection without supporting
 *   `porn` / `stage2Nsfw` evidence.
 */
object TemporalDecisionEngine {
    private const val EWMA_ALPHA = 0.60f
    private const val DEFAULT_DETECTION_COOLDOWN_MS = 8000L
    private const val RECOVERY_SAFE_FRAMES_REQUIRED = 2
    private const val MAX_HISTORY_FRAMES = 5
    private const val SCENE_CUT_HAMMING_THRESHOLD = 30
    private const val HISTORY_TTL_MS = 12_000L

    private val lock = Any()

    private var currentPackage: String = ""
    private var currentState: TemporalProtectionState = TemporalProtectionState.SAFE_STABLE
    private var consecutiveSuspicious = 0
    private var consecutiveSafe = 0
    private var ewmaExplicit = 0f
    private var ewmaNudity = 0f
    private var ewmaSexualActivity = 0f
    private var ewmaSafe = 1f
    private var ewmaInitialized = false
    private var lastConfirmedDetectionElapsedMs = 0L

    private val recentHistory = ArrayDeque<TemporalFrameEntry>(MAX_HISTORY_FRAMES)

    private val totalFramesClassified = AtomicInteger(0)
    private val explicitDetections = AtomicInteger(0)
    private val nudityDetections = AtomicInteger(0)
    private val sexualActivityDetections = AtomicInteger(0)
    private val suggestiveDetections = AtomicInteger(0)
    private val nonExplicitDetections = AtomicInteger(0)
    private val safeClassifications = AtomicInteger(0)
    private val uncertainClassifications = AtomicInteger(0)
    private val duplicateFramesSkipped = AtomicInteger(0)
    private val obsoleteFramesDropped = AtomicInteger(0)

    fun onPackageChanged(newPackage: String) {
        synchronized(lock) {
            if (newPackage != currentPackage) {
                currentPackage = newPackage
                consecutiveSuspicious = 0
                consecutiveSafe = 0
                ewmaInitialized = false
                recentHistory.clear()
                if (currentState != TemporalProtectionState.CONFIRMED_DETECTION) {
                    currentState = TemporalProtectionState.SAFE_STABLE
                }
            }
        }
    }

    fun recordDuplicateFrameSkipped() {
        duplicateFramesSkipped.incrementAndGet()
    }

    fun recordObsoleteFrameDropped() {
        obsoleteFramesDropped.incrementAndGet()
    }

    fun resetState() {
        synchronized(lock) {
            currentState = TemporalProtectionState.SAFE_STABLE
            consecutiveSuspicious = 0
            consecutiveSafe = 0
            ewmaExplicit = 0f
            ewmaNudity = 0f
            ewmaSexualActivity = 0f
            ewmaSafe = 1f
            ewmaInitialized = false
            lastConfirmedDetectionElapsedMs = 0L
            recentHistory.clear()
        }
    }

    fun evaluateTemporalFrame(
        rawResult: ClassificationResult,
        packageName: String,
        requiredConfirmationFrames: Int,
        confidenceThreshold: Float
    ): ClassificationResult {
        synchronized(lock) {
            val nowMs = System.currentTimeMillis()
            if (packageName != currentPackage) {
                currentPackage = packageName
                consecutiveSuspicious = 0
                consecutiveSafe = 0
                ewmaInitialized = false
                recentHistory.clear()
            }

            // Expire stale history entries older than HISTORY_TTL_MS
            while (recentHistory.isNotEmpty() && (nowMs - recentHistory.peekFirst()!!.timestampMs) > HISTORY_TTL_MS) {
                recentHistory.removeFirst()
            }

            // Detect hard scene transitions via perceptual dHash distance
            val currentHashLong = try {
                if (rawResult.frameHash.isNotBlank()) {
                    java.lang.Long.parseUnsignedLong(rawResult.frameHash, 16)
                } else 0L
            } catch (_: Exception) {
                0L
            }

            val lastEntry = recentHistory.peekLast()
            if (lastEntry != null && currentHashLong != 0L && lastEntry.dHash != 0L) {
                val dist = PerceptualFrameHasher.hammingDistance(currentHashLong, lastEntry.dHash)
                if (dist >= SCENE_CUT_HAMMING_THRESHOLD) {
                    // Hard scene transition detected: clear prior scene buffer so old context does not bleed
                    recentHistory.clear()
                    ewmaInitialized = false
                }
            }

            // Temporal Uncertainty Resolution (Section 5):
            // Only resolve an UNCERTAIN frame if recent same-scene history provides concrete supporting evidence.
            var resolvedCategory = rawResult.predictedCategory
            var resolvedConfidence = rawResult.confidence

            if (resolvedCategory == ContentCategory.UNCERTAIN.name && recentHistory.isNotEmpty()) {
                val windowEntries = recentHistory.toList()
                val hadRecentPositive = windowEntries.any {
                    it.category == ContentCategory.EXPLICIT.name ||
                        it.category == ContentCategory.NUDITY.name ||
                        it.category == ContentCategory.SEXUAL_ACTIVITY.name
                }
                val avgPorn = (windowEntries.sumOf { it.pornScore.toDouble() }.toFloat() + rawResult.pornScore) /
                    (windowEntries.size + 1)
                val avgStage2Nsfw = (windowEntries.sumOf { it.stage2NsfwScore.toDouble() }.toFloat() + rawResult.stage2NsfwScore) /
                    (windowEntries.size + 1)
                val avgSafe = (windowEntries.sumOf { it.safeScore.toDouble() }.toFloat() + rawResult.safeScore) /
                    (windowEntries.size + 1)
                val maxPorn = maxOf(rawResult.pornScore, windowEntries.maxOf { it.pornScore })

                // Supporting positive evidence check: never convert pure uncertainty without real porn / stage2 signal
                if (hadRecentPositive && (rawResult.pornScore >= 0.14f || rawResult.stage2NsfwScore >= 0.35f)) {
                    resolvedCategory = if (avgPorn >= 0.32f) {
                        ContentCategory.NUDITY.name
                    } else {
                        ContentCategory.SEXUAL_ACTIVITY.name
                    }
                    resolvedConfidence = maxOf(rawResult.confidence, avgPorn + 0.4f * avgStage2Nsfw).coerceIn(0f, 1f)
                } else if (avgPorn >= 0.22f && avgStage2Nsfw >= 0.42f) {
                    resolvedCategory = ContentCategory.SEXUAL_ACTIVITY.name
                    resolvedConfidence = (0.5f * avgPorn + 0.5f * avgStage2Nsfw).coerceIn(0f, 1f)
                } else if (!hadRecentPositive && avgSafe >= 0.62f && maxPorn < 0.14f && avgStage2Nsfw < 0.35f) {
                    resolvedCategory = ContentCategory.NON_EXPLICIT.name
                    resolvedConfidence = avgSafe.coerceIn(0f, 1f)
                }
            }

            // Record observation in bounded sliding window
            if (recentHistory.size >= MAX_HISTORY_FRAMES) {
                recentHistory.removeFirst()
            }
            recentHistory.addLast(
                TemporalFrameEntry(
                    category = resolvedCategory,
                    pornScore = rawResult.pornScore,
                    sexyScore = rawResult.sexyScore,
                    safeScore = rawResult.safeScore,
                    stage2NsfwScore = rawResult.stage2NsfwScore,
                    sexualActivityScore = rawResult.sexualActivityScore,
                    dHash = currentHashLong,
                    timestampMs = nowMs
                )
            )

            if (rawResult.successful && !rawResult.isDuplicateSkipped) {
                totalFramesClassified.incrementAndGet()
                when (resolvedCategory) {
                    ContentCategory.EXPLICIT.name -> explicitDetections.incrementAndGet()
                    ContentCategory.NUDITY.name -> nudityDetections.incrementAndGet()
                    ContentCategory.SEXUAL_ACTIVITY.name -> sexualActivityDetections.incrementAndGet()
                    ContentCategory.SUGGESTIVE.name -> suggestiveDetections.incrementAndGet()
                    ContentCategory.NON_EXPLICIT.name -> nonExplicitDetections.incrementAndGet()
                    ContentCategory.UNCERTAIN.name -> uncertainClassifications.incrementAndGet()
                    else -> safeClassifications.incrementAndGet()
                }
            }

            // Update EWMA smoothed scores
            if (!ewmaInitialized) {
                ewmaExplicit = rawResult.explicitScore
                ewmaNudity = rawResult.nudityScore
                ewmaSexualActivity = rawResult.sexualActivityScore
                ewmaSafe = rawResult.safeScore
                ewmaInitialized = true
            } else {
                ewmaExplicit = EWMA_ALPHA * rawResult.explicitScore + (1f - EWMA_ALPHA) * ewmaExplicit
                ewmaNudity = EWMA_ALPHA * rawResult.nudityScore + (1f - EWMA_ALPHA) * ewmaNudity
                ewmaSexualActivity = EWMA_ALPHA * rawResult.sexualActivityScore + (1f - EWMA_ALPHA) * ewmaSexualActivity
                ewmaSafe = EWMA_ALPHA * rawResult.safeScore + (1f - EWMA_ALPHA) * ewmaSafe
            }

            val isFrameSuspicious =
                resolvedCategory == ContentCategory.EXPLICIT.name ||
                    resolvedCategory == ContentCategory.NUDITY.name ||
                    resolvedCategory == ContentCategory.SEXUAL_ACTIVITY.name

            val isFrameUncertain =
                resolvedCategory == ContentCategory.UNCERTAIN.name

            val isFrameNonExplicit =
                resolvedCategory == ContentCategory.NON_EXPLICIT.name ||
                    resolvedCategory == ContentCategory.SUGGESTIVE.name ||
                    resolvedCategory == ContentCategory.SAFE.name

            val nowElapsed = SystemClock.elapsedRealtime()
            val cooldownRemaining = if (lastConfirmedDetectionElapsedMs > 0L) {
                maxOf(0L, DEFAULT_DETECTION_COOLDOWN_MS - (nowElapsed - lastConfirmedDetectionElapsedMs))
            } else {
                0L
            }

            val neededFrames = requiredConfirmationFrames.coerceAtLeast(2)

            if (isFrameSuspicious) {
                consecutiveSuspicious++
                consecutiveSafe = 0
                val peakSmoothed = maxOf(ewmaExplicit, ewmaNudity, ewmaSexualActivity)
                val smoothedTrigger = peakSmoothed >= (confidenceThreshold * 0.72f)
                if (consecutiveSuspicious >= neededFrames && smoothedTrigger) {
                    currentState = TemporalProtectionState.CONFIRMED_DETECTION
                    lastConfirmedDetectionElapsedMs = nowElapsed
                } else {
                    currentState = TemporalProtectionState.PENDING_CONFIRMATION
                }
            } else if (isFrameUncertain) {
                consecutiveSafe = 0
                if (currentState != TemporalProtectionState.CONFIRMED_DETECTION) {
                    currentState = TemporalProtectionState.UNCERTAIN_MONITORING
                }
            } else {
                consecutiveSafe++
                if (currentState == TemporalProtectionState.CONFIRMED_DETECTION) {
                    if (cooldownRemaining > 0L || consecutiveSafe < RECOVERY_SAFE_FRAMES_REQUIRED) {
                        currentState = TemporalProtectionState.COOLDOWN_RECOVERY
                    } else {
                        consecutiveSuspicious = 0
                        currentState = if (isFrameNonExplicit) {
                            TemporalProtectionState.OBSERVING_NON_EXPLICIT
                        } else {
                            TemporalProtectionState.SAFE_STABLE
                        }
                    }
                } else if (currentState == TemporalProtectionState.COOLDOWN_RECOVERY) {
                    if (consecutiveSafe >= RECOVERY_SAFE_FRAMES_REQUIRED && cooldownRemaining == 0L) {
                        consecutiveSuspicious = 0
                        currentState = if (isFrameNonExplicit) {
                            TemporalProtectionState.OBSERVING_NON_EXPLICIT
                        } else {
                            TemporalProtectionState.SAFE_STABLE
                        }
                    }
                } else {
                    consecutiveSuspicious = 0
                    currentState = if (isFrameNonExplicit) {
                        TemporalProtectionState.OBSERVING_NON_EXPLICIT
                    } else {
                        TemporalProtectionState.SAFE_STABLE
                    }
                }
            }

            val isConfirmed = currentState == TemporalProtectionState.CONFIRMED_DETECTION

            return rawResult.copy(
                predictedCategory = resolvedCategory,
                confidence = resolvedConfidence,
                isExplicit = isFrameSuspicious,
                consecutiveDetections = consecutiveSuspicious,
                confirmedIntervention = isConfirmed,
                temporalState = currentState.name,
                smoothedExplicitScore = maxOf(ewmaExplicit, ewmaNudity, ewmaSexualActivity),
                packageName = packageName
            )
        }
    }

    fun getSnapshot(): TemporalDecisionSnapshot {
        synchronized(lock) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val cooldownRemaining = if (lastConfirmedDetectionElapsedMs > 0L) {
                maxOf(0L, DEFAULT_DETECTION_COOLDOWN_MS - (nowElapsed - lastConfirmedDetectionElapsedMs))
            } else {
                0L
            }
            return TemporalDecisionSnapshot(
                state = currentState,
                consecutiveSuspiciousCount = consecutiveSuspicious,
                consecutiveSafeCount = consecutiveSafe,
                smoothedExplicitScore = ewmaExplicit,
                smoothedNudityScore = ewmaNudity,
                smoothedSexualActivityScore = ewmaSexualActivity,
                smoothedSafeScore = ewmaSafe,
                confirmedIntervention = currentState == TemporalProtectionState.CONFIRMED_DETECTION,
                cooldownRemainingMs = cooldownRemaining,
                totalFramesClassified = totalFramesClassified.get(),
                explicitDetections = explicitDetections.get(),
                nudityDetections = nudityDetections.get(),
                sexualActivityDetections = sexualActivityDetections.get(),
                suggestiveDetections = suggestiveDetections.get(),
                nonExplicitDetections = nonExplicitDetections.get(),
                safeClassifications = safeClassifications.get(),
                uncertainClassifications = uncertainClassifications.get(),
                duplicateFramesSkipped = duplicateFramesSkipped.get(),
                obsoleteFramesDropped = obsoleteFramesDropped.get()
            )
        }
    }
}
