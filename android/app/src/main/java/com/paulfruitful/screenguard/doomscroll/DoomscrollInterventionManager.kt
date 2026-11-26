package com.paulfruitful.screenguard.doomscroll

data class InterventionDecision(
    val shouldIntervene: Boolean,
    val level: DoomscrollInterventionLevel,
    val riskScore: Int,
    val riskLevel: RiskLevel,
    val cooldownRemainingMs: Long = 0L,
    val reason: String = ""
)

/**
 * Manages the 3-level Doomscroll Intervention policy & cooldowns (Sections 9 & 10):
 * - Level 1 (MODERATE, 50..69): Gentle Reminder (5-minute cooldown)
 * - Level 2 (HIGH, 70..84): Strong Intervention (10-minute cooldown)
 * - Level 3 (CRITICAL, 85..100 or strict time limit): Cool-Off Intervention via Protective Shield
 *
 * Guarantees:
 * - Prevents duplicate interventions during one session.
 * - Escalating to a higher level is permitted even if a lower-level reminder already fired,
 *   while repeated lower-level nudges respect their cooldown windows.
 * - Completing a Cool-Off session resets session intervention locks and applies fresh cooldown.
 */
class DoomscrollInterventionManager {
    private var lastGentleMs: Long = 0L
    private var lastStrongMs: Long = 0L
    private var lastCriticalMs: Long = 0L
    private var lastInterventionLevel: DoomscrollInterventionLevel = DoomscrollInterventionLevel.NONE
    private var lastSessionId: String = ""

    fun evaluate(
        snapshot: BehaviourSnapshot,
        breakdown: SignalBreakdown,
        config: DoomscrollConfig,
        nowMs: Long,
        isOverlayCurrentlyVisible: Boolean
    ): InterventionDecision {
        if (!config.enabled || !snapshot.isInConfirmedFeed) {
            return InterventionDecision(
                shouldIntervene = false,
                level = DoomscrollInterventionLevel.NONE,
                riskScore = breakdown.totalScore,
                riskLevel = breakdown.riskLevel,
                reason = "not_in_active_feed"
            )
        }

        // Never fight an already visible protective shield or duplicate-launch overlays
        if (isOverlayCurrentlyVisible) {
            return InterventionDecision(
                shouldIntervene = false,
                level = DoomscrollInterventionLevel.NONE,
                riskScore = breakdown.totalScore,
                riskLevel = breakdown.riskLevel,
                reason = "overlay_already_visible"
            )
        }

        if (snapshot.sessionId.isNotBlank() && snapshot.sessionId != lastSessionId) {
            lastSessionId = snapshot.sessionId
            lastInterventionLevel = DoomscrollInterventionLevel.NONE
        }

        val desiredLevel = when (breakdown.riskLevel) {
            RiskLevel.CRITICAL -> DoomscrollInterventionLevel.COOL_OFF_CRITICAL
            RiskLevel.HIGH -> DoomscrollInterventionLevel.STRONG_INTERVENTION
            RiskLevel.MODERATE -> DoomscrollInterventionLevel.GENTLE_REMINDER
            RiskLevel.LOW, RiskLevel.NORMAL -> DoomscrollInterventionLevel.NONE
        }

        if (desiredLevel == DoomscrollInterventionLevel.NONE) {
            return InterventionDecision(
                shouldIntervene = false,
                level = DoomscrollInterventionLevel.NONE,
                riskScore = breakdown.totalScore,
                riskLevel = breakdown.riskLevel,
                reason = "below_intervention_threshold"
            )
        }

        val remainingCooldownMs = when (desiredLevel) {
            DoomscrollInterventionLevel.GENTLE_REMINDER -> {
                if (lastGentleMs <= 0L) 0L else (lastGentleMs + config.safeReminderCooldownMs - nowMs).coerceAtLeast(0L)
            }
            DoomscrollInterventionLevel.STRONG_INTERVENTION -> {
                if (lastStrongMs <= 0L) 0L else (lastStrongMs + config.safeStrongCooldownMs - nowMs).coerceAtLeast(0L)
            }
            DoomscrollInterventionLevel.COOL_OFF_CRITICAL -> {
                if (lastCriticalMs <= 0L) 0L else (lastCriticalMs + config.safeCriticalCooldownMs - nowMs).coerceAtLeast(0L)
            }
            DoomscrollInterventionLevel.NONE -> 0L
        }

        // Strict time limit overrides critical cooldown only once per session
        val strictBypass = snapshot.strictTimeLimitExceeded &&
            desiredLevel == DoomscrollInterventionLevel.COOL_OFF_CRITICAL &&
            lastInterventionLevel != DoomscrollInterventionLevel.COOL_OFF_CRITICAL

        if (remainingCooldownMs > 0L && !strictBypass) {
            return InterventionDecision(
                shouldIntervene = false,
                level = DoomscrollInterventionLevel.NONE,
                riskScore = breakdown.totalScore,
                riskLevel = breakdown.riskLevel,
                cooldownRemainingMs = remainingCooldownMs,
                reason = "cooldown_active"
            )
        }

        // Record intervention timestamp
        when (desiredLevel) {
            DoomscrollInterventionLevel.GENTLE_REMINDER -> lastGentleMs = nowMs
            DoomscrollInterventionLevel.STRONG_INTERVENTION -> lastStrongMs = nowMs
            DoomscrollInterventionLevel.COOL_OFF_CRITICAL -> lastCriticalMs = nowMs
            DoomscrollInterventionLevel.NONE -> Unit
        }
        lastInterventionLevel = desiredLevel

        return InterventionDecision(
            shouldIntervene = true,
            level = desiredLevel,
            riskScore = breakdown.totalScore,
            riskLevel = breakdown.riskLevel,
            reason = breakdown.explanation
        )
    }

    fun onUserContinuedFromGentleReminder(nowMs: Long) {
        lastGentleMs = nowMs
    }

    fun onCoolOffCompleted(nowMs: Long) {
        lastGentleMs = nowMs
        lastStrongMs = nowMs
        lastCriticalMs = nowMs
        lastInterventionLevel = DoomscrollInterventionLevel.NONE
    }

    fun getLastInterventionLevel(): DoomscrollInterventionLevel = lastInterventionLevel

    fun resetForTests() {
        lastGentleMs = 0L
        lastStrongMs = 0L
        lastCriticalMs = 0L
        lastInterventionLevel = DoomscrollInterventionLevel.NONE
        lastSessionId = ""
    }
}
