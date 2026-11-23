package com.paulfruitful.screenguard.classifier

import android.os.SystemClock
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
    val smoothedSafeScore: Float,
    val confirmedIntervention: Boolean,
    val cooldownRemainingMs: Long,
    val totalFramesClassified: Int,
    val explicitDetections: Int,
    val nudityDetections: Int,
    val suggestiveDetections: Int,
    val nonExplicitDetections: Int,
    val safeClassifications: Int,
    val uncertainClassifications: Int,
    val duplicateFramesSkipped: Int,
    val obsoleteFramesDropped: Int
)

/**
 * Separate temporal decision engine that evaluates consecutive [ClassificationResult] outputs.
 *
 * Ensures that single-frame predictions never trigger protection on their own:
 * - Applies Exponentially Weighted Moving Average (EWMA, alpha = 0.60) confidence smoothing.
 * - Requires [requiredConfirmationFrames] consecutive EXPLICIT/NUDITY frames (or sustained
 *   smoothed score above threshold) before entering [TemporalProtectionState.CONFIRMED_DETECTION].
 * - Enforces a configurable detection cooldown window and recovers cleanly to
 *   [TemporalProtectionState.SAFE_STABLE] after consecutive safe frames appear.
 */
object TemporalDecisionEngine {
    private const val EWMA_ALPHA = 0.60f
    private const val DEFAULT_DETECTION_COOLDOWN_MS = 8000L
    private const val RECOVERY_SAFE_FRAMES_REQUIRED = 2

    private val lock = Any()

    private var currentPackage: String = ""
    private var currentState: TemporalProtectionState = TemporalProtectionState.SAFE_STABLE
    private var consecutiveSuspicious = 0
    private var consecutiveSafe = 0
    private var ewmaExplicit = 0f
    private var ewmaNudity = 0f
    private var ewmaSafe = 1f
    private var ewmaInitialized = false
    private var lastConfirmedDetectionElapsedMs = 0L

    private val totalFramesClassified = AtomicInteger(0)
    private val explicitDetections = AtomicInteger(0)
    private val nudityDetections = AtomicInteger(0)
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
            ewmaSafe = 1f
            ewmaInitialized = false
            lastConfirmedDetectionElapsedMs = 0L
        }
    }

    fun evaluateTemporalFrame(
        rawResult: ClassificationResult,
        packageName: String,
        requiredConfirmationFrames: Int,
        confidenceThreshold: Float
    ): ClassificationResult {
        synchronized(lock) {
            if (packageName != currentPackage) {
                currentPackage = packageName
                consecutiveSuspicious = 0
                consecutiveSafe = 0
                ewmaInitialized = false
            }

            if (rawResult.successful && !rawResult.isDuplicateSkipped) {
                totalFramesClassified.incrementAndGet()
                when (rawResult.predictedCategory) {
                    ContentCategory.EXPLICIT.name -> explicitDetections.incrementAndGet()
                    ContentCategory.NUDITY.name -> nudityDetections.incrementAndGet()
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
                ewmaSafe = rawResult.safeScore
                ewmaInitialized = true
            } else {
                ewmaExplicit = EWMA_ALPHA * rawResult.explicitScore + (1f - EWMA_ALPHA) * ewmaExplicit
                ewmaNudity = EWMA_ALPHA * rawResult.nudityScore + (1f - EWMA_ALPHA) * ewmaNudity
                ewmaSafe = EWMA_ALPHA * rawResult.safeScore + (1f - EWMA_ALPHA) * ewmaSafe
            }

            val isFrameSuspicious =
                rawResult.predictedCategory == ContentCategory.EXPLICIT.name ||
                    rawResult.predictedCategory == ContentCategory.NUDITY.name

            val isFrameUncertain =
                rawResult.predictedCategory == ContentCategory.UNCERTAIN.name

            val isFrameNonExplicit =
                rawResult.predictedCategory == ContentCategory.NON_EXPLICIT.name ||
                    rawResult.predictedCategory == ContentCategory.SUGGESTIVE.name

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
                val smoothedTrigger = maxOf(ewmaExplicit, ewmaNudity) >= (confidenceThreshold * 0.85f)
                if (consecutiveSuspicious >= neededFrames && smoothedTrigger) {
                    currentState = TemporalProtectionState.CONFIRMED_DETECTION
                    lastConfirmedDetectionElapsedMs = nowElapsed
                } else {
                    currentState = TemporalProtectionState.PENDING_CONFIRMATION
                }
            } else if (isFrameUncertain) {
                // Keep streak paused rather than resetting immediately on a single ambiguous frame
                consecutiveSafe = 0
                if (currentState != TemporalProtectionState.CONFIRMED_DETECTION) {
                    currentState = TemporalProtectionState.UNCERTAIN_MONITORING
                }
            } else {
                // SAFE, NON_EXPLICIT, or SUGGESTIVE frame
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
                consecutiveDetections = consecutiveSuspicious,
                confirmedIntervention = isConfirmed,
                temporalState = currentState.name,
                smoothedExplicitScore = maxOf(ewmaExplicit, ewmaNudity),
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
                smoothedSafeScore = ewmaSafe,
                confirmedIntervention = currentState == TemporalProtectionState.CONFIRMED_DETECTION,
                cooldownRemainingMs = cooldownRemaining,
                totalFramesClassified = totalFramesClassified.get(),
                explicitDetections = explicitDetections.get(),
                nudityDetections = nudityDetections.get(),
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
