package com.paulfruitful.screenguard.protection

import android.os.SystemClock
import com.paulfruitful.screenguard.classifier.ClassificationResult
import com.paulfruitful.screenguard.classifier.ContentCategory

/**
 * Dedicated ProtectionDecisionEngine for Milestone 3 (Sections 5 & 6).
 *
 * Applies category-specific confidence thresholds, Balanced vs. Strict sensitivity rules,
 * multi-frame confirmation, minimum display duration anti-flicker guarantees,
 * recovery verification, and manual dismissal cooldowns.
 */
class ProtectionDecisionEngine {

    private val lock = Any()

    private var trackedPackage: String = ""
    private var currentOverlayState: OverlayState = OverlayState.HIDDEN
    private var consecutivePositiveFrames: Int = 0
    private var consecutiveSafeFrames: Int = 0
    private var ewmaViolationConfidence: Float = 0f
    private var ewmaInitialized: Boolean = false

    private var protectionShownAtElapsedMs: Long = 0L
    private var dismissedAtElapsedMs: Long = 0L
    private var dismissedForPackage: String = ""
    private var activeTriggerCategory: String = "NONE"

    companion object {
        private const val EWMA_ALPHA = 0.62f
    }

    fun getCurrentState(): OverlayState = synchronized(lock) { currentOverlayState }

    fun getActiveTriggerCategory(): String = synchronized(lock) { activeTriggerCategory }

    fun getSmoothedConfidence(): Float = synchronized(lock) { ewmaViolationConfidence }

    fun getDismissCooldownRemainingMs(
        packageName: String,
        policy: ProtectionPolicyConfig
    ): Long = synchronized(lock) {
        if (dismissedAtElapsedMs <= 0L || dismissedForPackage != packageName) {
            return 0L
        }
        val elapsed = SystemClock.elapsedRealtime() - dismissedAtElapsedMs
        return maxOf(0L, policy.dismissCooldownMs - elapsed)
    }

    /**
     * Resets application-specific temporal protection state when the foreground application changes
     * or when the device screen turns off (Sections 6 & 7).
     */
    fun onForegroundAppChanged(newPackageName: String): Boolean = synchronized(lock) {
        if (newPackageName == trackedPackage) {
            return false
        }
        trackedPackage = newPackageName
        consecutivePositiveFrames = 0
        consecutiveSafeFrames = 0
        ewmaViolationConfidence = 0f
        ewmaInitialized = false
        activeTriggerCategory = "NONE"

        val wasVisible = currentOverlayState == OverlayState.PROTECTED ||
            currentOverlayState == OverlayState.RECOVERING
        currentOverlayState = OverlayState.HIDDEN
        protectionShownAtElapsedMs = 0L
        return wasVisible
    }

    /**
     * Records a manual user dismissal of the protective overlay, entering [OverlayState.DISMISSED]
     * and starting the per-package dismissal cooldown timer.
     */
    fun onUserDismissedOverlay(packageName: String) = synchronized(lock) {
        currentOverlayState = OverlayState.DISMISSED
        dismissedAtElapsedMs = SystemClock.elapsedRealtime()
        dismissedForPackage = packageName
        consecutivePositiveFrames = 0
        consecutiveSafeFrames = 0
        protectionShownAtElapsedMs = 0L
    }

    /**
     * Resets all transient protection states (e.g., when protection is paused or screen turns off).
     */
    fun resetAll() = synchronized(lock) {
        currentOverlayState = OverlayState.HIDDEN
        consecutivePositiveFrames = 0
        consecutiveSafeFrames = 0
        ewmaViolationConfidence = 0f
        ewmaInitialized = false
        protectionShownAtElapsedMs = 0L
        activeTriggerCategory = "NONE"
    }

    /**
     * Evaluates a new [ClassificationResult] against the active [ProtectionPolicyConfig]
     * and returns a deterministic [ProtectionDecision].
     */
    fun evaluate(
        result: ClassificationResult,
        packageName: String,
        protectionEnabled: Boolean,
        isExcludedPackage: Boolean,
        policy: ProtectionPolicyConfig
    ): ProtectionDecision = synchronized(lock) {
        if (!protectionEnabled || isExcludedPackage || !result.successful) {
            val shouldHide = currentOverlayState == OverlayState.PROTECTED ||
                currentOverlayState == OverlayState.RECOVERING
            if (shouldHide) {
                currentOverlayState = OverlayState.HIDDEN
            }
            return ProtectionDecision(
                directive = DecisionDirective.NO_ACTION,
                targetOverlayState = currentOverlayState,
                triggerCategory = result.predictedCategory,
                categoryThresholdUsed = policy.explicitThreshold,
                rawConfidence = result.confidence,
                smoothedConfidence = ewmaViolationConfidence,
                consecutivePositiveCount = consecutivePositiveFrames,
                consecutiveSafeCount = consecutiveSafeFrames,
                reasonSummary = if (!protectionEnabled) {
                    "Protection disabled"
                } else if (isExcludedPackage) {
                    "Application excluded from protection"
                } else {
                    "Inference unsuccessful; ignoring frame"
                },
                shouldShowOverlay = false,
                shouldHideOverlay = shouldHide
            )
        }

        if (packageName.isNotBlank() && packageName != trackedPackage) {
            trackedPackage = packageName
            consecutivePositiveFrames = 0
            consecutiveSafeFrames = 0
            ewmaViolationConfidence = 0f
            ewmaInitialized = false
            activeTriggerCategory = "NONE"
            currentOverlayState = OverlayState.HIDDEN
        }

        val nowElapsed = SystemClock.elapsedRealtime()

        // 1. Check active dismissal cooldown for this application
        val cooldownRemaining = getDismissCooldownRemainingMs(packageName, policy)
        if (cooldownRemaining > 0L) {
            currentOverlayState = OverlayState.DISMISSED
            return ProtectionDecision(
                directive = DecisionDirective.SUPPRESSED_BY_COOLDOWN,
                targetOverlayState = OverlayState.DISMISSED,
                triggerCategory = result.predictedCategory,
                categoryThresholdUsed = policy.explicitThreshold,
                rawConfidence = result.confidence,
                smoothedConfidence = ewmaViolationConfidence,
                consecutivePositiveCount = 0,
                consecutiveSafeCount = consecutiveSafeFrames,
                reasonSummary = "Dismissal cooldown active (${cooldownRemaining / 1000}s remaining)",
                shouldShowOverlay = false,
                shouldHideOverlay = false
            )
        } else if (currentOverlayState == OverlayState.DISMISSED) {
            currentOverlayState = OverlayState.HIDDEN
        }

        // 2. Evaluate category-specific threshold (Section 5)
        val catEval = evaluateCategoryPolicy(result, policy)

        // Update EWMA smoothed violation confidence
        val instantViolationSignal = if (catEval.violatesPolicy) {
            catEval.effectiveScore
        } else if (result.predictedCategory == ContentCategory.UNCERTAIN.name) {
            ewmaViolationConfidence * 0.85f
        } else {
            0.0f
        }

        if (!ewmaInitialized) {
            ewmaViolationConfidence = instantViolationSignal
            ewmaInitialized = true
        } else {
            ewmaViolationConfidence =
                EWMA_ALPHA * instantViolationSignal + (1f - EWMA_ALPHA) * ewmaViolationConfidence
        }

        val isCurrentlyShowingOverlay =
            currentOverlayState == OverlayState.PROTECTED ||
                currentOverlayState == OverlayState.RECOVERING

        // 3. State transitions based on classification category and temporal history
        if (catEval.violatesPolicy) {
            consecutivePositiveFrames++
            consecutiveSafeFrames = 0
            activeTriggerCategory = result.predictedCategory

            val requiredFrames = policy.confirmationFramesRequired.coerceAtLeast(1)
            val meetsSmoothedGate = ewmaViolationConfidence >= (catEval.thresholdUsed * 0.72f)
            val isHighConfidenceCorroborated =
                result.confirmedIntervention ||
                    (result.predictedCategory == ContentCategory.EXPLICIT.name &&
                        catEval.effectiveScore >= catEval.thresholdUsed) ||
                    (result.predictedCategory == ContentCategory.NUDITY.name &&
                        (catEval.effectiveScore >= 0.48f ||
                            (result.stage2Triggered && result.stage2NsfwScore >= 0.42f))) ||
                    (result.predictedCategory == ContentCategory.SEXUAL_ACTIVITY.name &&
                        (catEval.effectiveScore >= 0.50f ||
                            (result.stage2Triggered && result.stage2NsfwScore >= 0.45f)))

            val confirmed = (consecutivePositiveFrames >= requiredFrames && meetsSmoothedGate) ||
                (consecutivePositiveFrames >= 1 && isHighConfidenceCorroborated)

            return if (confirmed || isCurrentlyShowingOverlay) {
                if (!isCurrentlyShowingOverlay) {
                    protectionShownAtElapsedMs = nowElapsed
                }
                currentOverlayState = OverlayState.PROTECTED
                ProtectionDecision(
                    directive = if (isCurrentlyShowingOverlay) {
                        DecisionDirective.MAINTAIN_PROTECTION
                    } else {
                        DecisionDirective.TRIGGER_PROTECTION
                    },
                    targetOverlayState = OverlayState.PROTECTED,
                    triggerCategory = result.predictedCategory,
                    categoryThresholdUsed = catEval.thresholdUsed,
                    rawConfidence = catEval.effectiveScore,
                    smoothedConfidence = ewmaViolationConfidence,
                    consecutivePositiveCount = consecutivePositiveFrames,
                    consecutiveSafeCount = 0,
                    reasonSummary = "Confirmed ${result.predictedCategory} (${"%.1f".format(catEval.effectiveScore * 100f)}% >= ${"%.0f".format(catEval.thresholdUsed * 100f)}%)",
                    shouldShowOverlay = true,
                    shouldHideOverlay = false
                )
            } else {
                currentOverlayState = OverlayState.PENDING
                ProtectionDecision(
                    directive = DecisionDirective.ENTER_PENDING_CONFIRMATION,
                    targetOverlayState = OverlayState.PENDING,
                    triggerCategory = result.predictedCategory,
                    categoryThresholdUsed = catEval.thresholdUsed,
                    rawConfidence = catEval.effectiveScore,
                    smoothedConfidence = ewmaViolationConfidence,
                    consecutivePositiveCount = consecutivePositiveFrames,
                    consecutiveSafeCount = 0,
                    reasonSummary = "Pending confirmation ($consecutivePositiveFrames/$requiredFrames frames for ${result.predictedCategory})",
                    shouldShowOverlay = false,
                    shouldHideOverlay = false
                )
            }
        }

        // 4. Handle UNCERTAIN classification: continue observing, never trigger full protection,
        // and never immediately remove an already-displayed overlay on temporary uncertainty (Section 6)
        if (result.predictedCategory == ContentCategory.UNCERTAIN.name) {
            consecutiveSafeFrames = 0
            return if (isCurrentlyShowingOverlay) {
                ProtectionDecision(
                    directive = DecisionDirective.MAINTAIN_PROTECTION,
                    targetOverlayState = currentOverlayState,
                    triggerCategory = activeTriggerCategory,
                    categoryThresholdUsed = catEval.thresholdUsed,
                    rawConfidence = result.confidence,
                    smoothedConfidence = ewmaViolationConfidence,
                    consecutivePositiveCount = consecutivePositiveFrames,
                    consecutiveSafeCount = 0,
                    reasonSummary = "Uncertain frame while protected; maintaining overlay until safe content is confirmed",
                    shouldShowOverlay = true,
                    shouldHideOverlay = false
                )
            } else {
                val nextState = if (consecutivePositiveFrames > 0) {
                    OverlayState.PENDING
                } else {
                    OverlayState.HIDDEN
                }
                currentOverlayState = nextState
                ProtectionDecision(
                    directive = DecisionDirective.CONTINUE_OBSERVING,
                    targetOverlayState = nextState,
                    triggerCategory = ContentCategory.UNCERTAIN.name,
                    categoryThresholdUsed = catEval.thresholdUsed,
                    rawConfidence = result.confidence,
                    smoothedConfidence = ewmaViolationConfidence,
                    consecutivePositiveCount = consecutivePositiveFrames,
                    consecutiveSafeCount = 0,
                    reasonSummary = "Uncertain classification; continuing observation without intervention",
                    shouldShowOverlay = false,
                    shouldHideOverlay = false
                )
            }
        }

        // 5. Handle SAFE, NON_EXPLICIT, or non-violating SUGGESTIVE frames
        consecutiveSafeFrames++
        if (isCurrentlyShowingOverlay) {
            val shownDurationMs = nowElapsed - protectionShownAtElapsedMs
            val minDurationMet = shownDurationMs >= policy.minDisplayDurationMs
            val recoveryFramesMet = consecutiveSafeFrames >= policy.recoverySafeFramesRequired

            return if (minDurationMet && recoveryFramesMet) {
                consecutivePositiveFrames = 0
                activeTriggerCategory = "NONE"
                currentOverlayState = OverlayState.HIDDEN
                protectionShownAtElapsedMs = 0L
                ProtectionDecision(
                    directive = DecisionDirective.CLEAR_PROTECTION,
                    targetOverlayState = OverlayState.HIDDEN,
                    triggerCategory = result.predictedCategory,
                    categoryThresholdUsed = catEval.thresholdUsed,
                    rawConfidence = result.confidence,
                    smoothedConfidence = ewmaViolationConfidence,
                    consecutivePositiveCount = 0,
                    consecutiveSafeCount = consecutiveSafeFrames,
                    reasonSummary = "Safe content confirmed ($consecutiveSafeFrames consecutive safe frames); removing overlay",
                    shouldShowOverlay = false,
                    shouldHideOverlay = true
                )
            } else {
                currentOverlayState = OverlayState.RECOVERING
                ProtectionDecision(
                    directive = DecisionDirective.ENTER_RECOVERING,
                    targetOverlayState = OverlayState.RECOVERING,
                    triggerCategory = activeTriggerCategory,
                    categoryThresholdUsed = catEval.thresholdUsed,
                    rawConfidence = result.confidence,
                    smoothedConfidence = ewmaViolationConfidence,
                    consecutivePositiveCount = consecutivePositiveFrames,
                    consecutiveSafeCount = consecutiveSafeFrames,
                    reasonSummary = "Recovering: verifying safe content ($consecutiveSafeFrames/${policy.recoverySafeFramesRequired} frames, ${shownDurationMs}ms/${policy.minDisplayDurationMs}ms min display)",
                    shouldShowOverlay = true,
                    shouldHideOverlay = false
                )
            }
        } else {
            consecutivePositiveFrames = 0
            activeTriggerCategory = "NONE"
            currentOverlayState = OverlayState.HIDDEN
            return ProtectionDecision(
                directive = DecisionDirective.NO_ACTION,
                targetOverlayState = OverlayState.HIDDEN,
                triggerCategory = result.predictedCategory,
                categoryThresholdUsed = catEval.thresholdUsed,
                rawConfidence = result.confidence,
                smoothedConfidence = ewmaViolationConfidence,
                consecutivePositiveCount = 0,
                consecutiveSafeCount = consecutiveSafeFrames,
                reasonSummary = "Content within safe policy (${result.predictedCategory})",
                shouldShowOverlay = false,
                shouldHideOverlay = false
            )
        }
    }

    private data class CategoryPolicyEvaluation(
        val violatesPolicy: Boolean,
        val effectiveScore: Float,
        val thresholdUsed: Float
    )

    private fun evaluateCategoryPolicy(
        result: ClassificationResult,
        policy: ProtectionPolicyConfig
    ): CategoryPolicyEvaluation {
        return when (result.predictedCategory) {
            ContentCategory.EXPLICIT.name -> {
                val score = maxOf(result.confidence, result.explicitScore, result.pornScore)
                CategoryPolicyEvaluation(
                    violatesPolicy = score >= policy.explicitThreshold,
                    effectiveScore = score,
                    thresholdUsed = policy.explicitThreshold
                )
            }
            ContentCategory.NUDITY.name -> {
                val score = maxOf(result.confidence, result.nudityScore, result.pornScore)
                CategoryPolicyEvaluation(
                    violatesPolicy = score >= policy.nudityThreshold,
                    effectiveScore = score,
                    thresholdUsed = policy.nudityThreshold
                )
            }
            ContentCategory.SEXUAL_ACTIVITY.name -> {
                val score = maxOf(result.confidence, result.sexualActivityScore)
                CategoryPolicyEvaluation(
                    violatesPolicy = score >= policy.sexualActivityThreshold,
                    effectiveScore = score,
                    thresholdUsed = policy.sexualActivityThreshold
                )
            }
            ContentCategory.SUGGESTIVE.name -> {
                val score = maxOf(result.confidence, result.suggestiveScore, result.sexyScore)
                val shouldIntervene =
                    policy.sensitivityMode == ProtectionSensitivityMode.STRICT &&
                        policy.interveneOnSuggestiveInStrict &&
                        score >= policy.suggestiveThresholdStrict
                CategoryPolicyEvaluation(
                    violatesPolicy = shouldIntervene,
                    effectiveScore = score,
                    thresholdUsed = policy.suggestiveThresholdStrict
                )
            }
            ContentCategory.UNCERTAIN.name,
            ContentCategory.NON_EXPLICIT.name,
            ContentCategory.SAFE.name -> {
                CategoryPolicyEvaluation(
                    violatesPolicy = false,
                    effectiveScore = result.confidence,
                    thresholdUsed = policy.explicitThreshold
                )
            }
            else -> {
                CategoryPolicyEvaluation(
                    violatesPolicy = false,
                    effectiveScore = result.confidence,
                    thresholdUsed = policy.explicitThreshold
                )
            }
        }
    }
}
