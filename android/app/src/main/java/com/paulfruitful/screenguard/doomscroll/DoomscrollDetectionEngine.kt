package com.paulfruitful.screenguard.doomscroll

import kotlin.math.roundToInt

/**
 * Explainable, progressive rule-based Doomscroll Prediction Engine (0–100 risk score).
 *
 * Scoring breakdown (max 100):
 * 1. Extended feed duration             (0..25)
 * 2. Continuous video transitions       (0..20)
 * 3. Very short inter-video intervals   (0..15)
 * 4. High scrolling intensity           (0..15)
 * 5. Repeated feed reopening            (0..15)
 * 6. Exceeding intended session time    (0..10)
 *
 * Anti-false-positive invariants:
 * - No single signal can push a user into HIGH or CRITICAL risk on its own.
 * - Watching a few selected videos (< 3 transitions) or a brief check (< minimumSessionDurationSeconds)
 *   is strictly capped in NORMAL (<= 29).
 * - Watching a single long-form video (0–2 transitions) is capped in NORMAL even if long.
 * - Rapid scrolling alone without sustained active feed duration (< 120s) is capped in LOW (<= 49).
 * - Meaningful breaks apply progressive score decay.
 */
object DoomscrollDetectionEngine {

    fun evaluate(snapshot: BehaviourSnapshot, config: DoomscrollConfig): SignalBreakdown {
        if (!config.enabled || snapshot.app == SupportedSocialApp.UNSUPPORTED) {
            return SignalBreakdown(
                totalScore = 0,
                riskLevel = RiskLevel.NORMAL,
                explanation = "Behavioural protection disabled or unsupported app."
            )
        }

        if (snapshot.activeFeedDurationMs <= 0L && snapshot.reopeningCountInWindow <= 1) {
            return SignalBreakdown(
                totalScore = 0,
                riskLevel = RiskLevel.NORMAL,
                explanation = "No active short-form feed consumption."
            )
        }

        val activeMinutes = snapshot.activeFeedDurationMs / 60_000f

        // 1. Extended feed duration (0..25) — progressive curve across 1.5m -> 25m
        val durationPts = scoreDuration(activeMinutes, config.maxDurationPoints.toFloat())

        // 2. Continuous video transitions (0..20) — progressive curve across 3 -> 40 transitions
        val transitionPts = scoreTransitions(snapshot.videoTransitionCount, config.maxTransitionPoints.toFloat())

        // 3. Very short inter-video intervals (0..15) — requires >= 4 transitions so skipping 2 videos != doomscrolling
        val shortDwellPts = scoreShortDwell(
            totalTransitions = snapshot.videoTransitionCount,
            shortTransitions = snapshot.shortTransitionCount,
            consecutiveRapid = snapshot.consecutiveRapidTransitions,
            maxPoints = config.maxShortDwellPoints.toFloat()
        )

        // 4. High scrolling intensity (0..15) — calibrated to Low 1-3, Moderate 4-8, High 9-15, Very High 16+
        val rawIntensityPts = scoreScrollIntensity(
            transitionsPerMinute = snapshot.transitionsPerMinute,
            maxPoints = config.maxScrollIntensityPoints.toFloat()
        )
        // Dampen scroll intensity during very brief sessions so rapid skipping alone stays low
        val intensityDampen = (activeMinutes / 2.5f).coerceIn(0.25f, 1.0f)
        val scrollIntensityPts = rawIntensityPts * intensityDampen

        // 5. Repeated feed reopening (0..15) — progressive curve for 2..6+ sessions in rolling window
        val reopeningPts = scoreReopening(
            reopeningCount = snapshot.reopeningCountInWindow,
            activeMinutes = activeMinutes,
            maxPoints = config.maxReopeningPoints.toFloat()
        )

        // 6. Exceeding intended session duration (0..10) — progressive ramp over overrun ratio
        val intendedOverrunPts = scoreIntendedOverrun(
            activeMinutes = activeMinutes,
            intendedMinutes = snapshot.intendedDurationMinutes.coerceAtLeast(1),
            maxPoints = config.maxIntendedOverrunPoints.toFloat()
        )

        val breakDecay = snapshot.pendingBreakDecayPoints.coerceIn(0f, 30f)

        // Sustained continuous marathon synergy: an unbroken 15m+ rapid-scrolling binge
        // should be able to reach CRITICAL (85+) even if reopeningCount == 1.
        val marathonBonus = if (activeMinutes >= 15f &&
            snapshot.videoTransitionCount >= 30 &&
            snapshot.shortTransitionCount >= 15
        ) {
            10f
        } else {
            0f
        }

        val rawSum = durationPts + transitionPts + shortDwellPts +
            scrollIntensityPts + reopeningPts + intendedOverrunPts + marathonBonus - breakDecay

        var finalScore = rawSum.roundToInt().coerceIn(0, 100)
        var cappedReason = ""

        // Strict anti-false-positive guardrails (Section 7.4)
        val minSessionMs = config.safeMinSessionDurationMs
        if (snapshot.activeFeedDurationMs < minSessionMs && snapshot.reopeningCountInWindow < 3) {
            if (finalScore > config.normalThresholdMax) {
                finalScore = config.normalThresholdMax
                cappedReason = "below_min_session_duration"
            }
        }

        // Intentional consumption / single long video guard: fewer than 3 distinct video transitions
        // cannot be classified as doomscrolling regardless of duration (unless strict limit explicitly enabled).
        if (snapshot.videoTransitionCount < 3 && !snapshot.strictTimeLimitExceeded) {
            if (finalScore > config.normalThresholdMax) {
                finalScore = config.normalThresholdMax
                cappedReason = "insufficient_video_transitions"
            }
        }

        // Rapid scrolling alone (< 2 minutes active) must never trigger High/Critical intervention
        if (snapshot.activeFeedDurationMs < 120_000L && snapshot.reopeningCountInWindow < 4 && !snapshot.strictTimeLimitExceeded) {
            if (finalScore > config.lowThresholdMax) {
                finalScore = config.lowThresholdMax
                cappedReason = "rapid_scroll_without_sustained_duration"
            }
        }

        // Explicit strict time limit override (only when user turned strictTimeLimitEnabled = true)
        if (snapshot.strictTimeLimitExceeded && snapshot.videoTransitionCount >= 2) {
            finalScore = maxOf(finalScore, config.highThresholdMax + 1)
            cappedReason = "strict_intended_time_limit_reached"
        }

        val riskLevel = classifyRisk(finalScore, config)
        val explanation = buildString {
            append("score=").append(finalScore)
            append(" [dur=").append("%.1f".format(durationPts))
            append(", trans=").append("%.1f".format(transitionPts))
            append(", dwell=").append("%.1f".format(shortDwellPts))
            append(", rate=").append("%.1f".format(scrollIntensityPts))
            append(", reopen=").append("%.1f".format(reopeningPts))
            append(", overrun=").append("%.1f".format(intendedOverrunPts))
            if (breakDecay > 0f) append(", decay=-").append("%.1f".format(breakDecay))
            append("]")
            if (cappedReason.isNotBlank()) append(" (").append(cappedReason).append(")")
        }

        return SignalBreakdown(
            durationPoints = durationPts,
            transitionPoints = transitionPts,
            shortDwellPoints = shortDwellPts,
            scrollIntensityPoints = scrollIntensityPts,
            reopeningPoints = reopeningPts,
            intendedOverrunPoints = intendedOverrunPts,
            breakDecayPoints = breakDecay,
            totalScore = finalScore,
            riskLevel = riskLevel,
            cappedReason = cappedReason,
            explanation = explanation
        )
    }

    fun classifyRisk(score: Int, config: DoomscrollConfig): RiskLevel {
        return when {
            score <= config.normalThresholdMax -> RiskLevel.NORMAL
            score <= config.lowThresholdMax -> RiskLevel.LOW
            score <= config.moderateThresholdMax -> RiskLevel.MODERATE
            score <= config.highThresholdMax -> RiskLevel.HIGH
            else -> RiskLevel.CRITICAL
        }
    }

    private fun scoreDuration(activeMinutes: Float, maxPoints: Float): Float {
        if (activeMinutes <= 1.5f) return 0f
        // Smooth piecewise progressive curve:
        // 1.5m -> 0%, 5m -> 25%, 10m -> 56%, 15m -> 84%, 20m+ -> 100%
        val fraction = when {
            activeMinutes <= 5f -> ((activeMinutes - 1.5f) / 3.5f) * 0.25f
            activeMinutes <= 10f -> 0.25f + ((activeMinutes - 5f) / 5f) * 0.31f
            activeMinutes <= 15f -> 0.56f + ((activeMinutes - 10f) / 5f) * 0.28f
            else -> (0.84f + ((activeMinutes - 15f) / 5f) * 0.16f).coerceAtMost(1f)
        }
        return (fraction * maxPoints).coerceIn(0f, maxPoints)
    }

    private fun scoreTransitions(transitions: Int, maxPoints: Float): Float {
        if (transitions < 3) return 0f
        // Progressive ramp: 3 -> 5%, 10 -> 35%, 20 -> 70%, 35+ -> 100%
        val fraction = when {
            transitions <= 10 -> 0.05f + ((transitions - 3) / 7f) * 0.30f
            transitions <= 20 -> 0.35f + ((transitions - 10) / 10f) * 0.35f
            else -> (0.70f + ((transitions - 20) / 15f) * 0.30f).coerceAtMost(1f)
        }
        return (fraction * maxPoints).coerceIn(0f, maxPoints)
    }

    private fun scoreShortDwell(
        totalTransitions: Int,
        shortTransitions: Int,
        consecutiveRapid: Int,
        maxPoints: Float
    ): Float {
        if (totalTransitions < 4 || shortTransitions < 2) return 0f
        val shortRatio = (shortTransitions.toFloat() / totalTransitions.toFloat()).coerceIn(0f, 1f)
        val countFactor = ((shortTransitions - 1) / 10f).coerceIn(0f, 1f)
        val streakBonus = ((consecutiveRapid - 2).coerceAtLeast(0) / 5f).coerceIn(0f, 0.35f)
        val combined = (shortRatio * 0.55f + countFactor * 0.35f + streakBonus).coerceIn(0f, 1f)
        return combined * maxPoints
    }

    private fun scoreScrollIntensity(transitionsPerMinute: Float, maxPoints: Float): Float {
        if (transitionsPerMinute < 1f) return 0f
        // Section 6.4 bands: 1-3 Low, 4-8 Moderate, 9-15 High, 16+ Very High
        val fraction = when {
            transitionsPerMinute <= 3f -> ((transitionsPerMinute - 1f) / 2f) * 0.15f
            transitionsPerMinute <= 8f -> 0.15f + ((transitionsPerMinute - 3f) / 5f) * 0.32f
            transitionsPerMinute <= 15f -> 0.47f + ((transitionsPerMinute - 8f) / 7f) * 0.36f
            else -> (0.83f + ((transitionsPerMinute - 15f) / 6f) * 0.17f).coerceAtMost(1f)
        }
        return (fraction * maxPoints).coerceIn(0f, maxPoints)
    }

    private fun scoreReopening(
        reopeningCount: Int,
        activeMinutes: Float,
        maxPoints: Float
    ): Float {
        // First opening is normal (0 pts); repeated reopening in rolling window adds progressive risk
        if (reopeningCount <= 1) return 0f
        val baseFraction = when (reopeningCount) {
            2 -> 0.25f
            3 -> 0.52f
            4 -> 0.78f
            else -> 1.0f
        }
        // Require at least minimal engagement (>30s) or 3+ reopens
        val engagementFactor = if (reopeningCount >= 3 || activeMinutes >= 0.5f) 1f else 0.5f
        return (baseFraction * engagementFactor * maxPoints).coerceIn(0f, maxPoints)
    }

    private fun scoreIntendedOverrun(
        activeMinutes: Float,
        intendedMinutes: Int,
        maxPoints: Float
    ): Float {
        if (activeMinutes <= intendedMinutes) return 0f
        val overrunMinutes = activeMinutes - intendedMinutes
        val overrunRatio = overrunMinutes / intendedMinutes.toFloat().coerceAtLeast(1f)
        // Reaches full 10 pts at 60% overrun (e.g. 10m intended -> 16m actual) or +6m overrun
        val fraction = maxOf(overrunRatio * 1.6f, overrunMinutes / 6f).coerceIn(0f, 1f)
        return fraction * maxPoints
    }
}
