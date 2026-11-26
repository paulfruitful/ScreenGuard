package com.paulfruitful.screenguard.doomscroll

import com.paulfruitful.screenguard.classifier.PerceptualFrameHasher
import java.util.UUID
import kotlin.math.max

/**
 * Pure, testable Behavioural Tracking Engine + AppMonitor state machine.
 *
 * Responsibilities:
 * - Tracks foreground app entry/exit, screen-off pauses, and non-feed pauses.
 * - Counts ONLY confirmed active short-form video feed time (never counts DMs, profiles,
 *   comments, background time, or unknown/unreliable screens).
 * - Detects distinct video transitions via accessibility scroll/pager/signature changes
 *   OR lightweight 64-bit perceptual frame hash differences (with duplicate-event suppression).
 * - Measures inter-video dwell times (average, median, short transitions, consecutive rapid transitions),
 *   rolling scroll intensity (transitions/min), reopening frequency, and meaningful breaks.
 */
class BehaviourTracker(
    private val visualHashDiffThreshold: Int = 12
) {
    private data class ReopenEntry(val timestampMs: Long, val app: SupportedSocialApp)

    private var currentForegroundPkg: String = ""
    private var currentApp: SupportedSocialApp = SupportedSocialApp.UNSUPPORTED
    private var currentContextType: FeedContextType = FeedContextType.UNKNOWN
    private var currentFeedConfidence: Float = 0f
    private var isInConfirmedFeed: Boolean = false
    private var isScreenInteractive: Boolean = true

    // Active session state
    private var sessionId: String = ""
    private var sessionApp: SupportedSocialApp = SupportedSocialApp.UNSUPPORTED
    private var sessionPackage: String = ""
    private var sessionStartMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var activeFeedDurationMs: Long = 0L
    private var pausedDurationMs: Long = 0L
    private var backgroundDurationMs: Long = 0L
    private var lastFeedActiveMs: Long = 0L

    // Transition tracking + duplicate suppression
    private var videoTransitionCount: Int = 0
    private var lastTransitionMs: Long = 0L
    private var lastContentSignature: String = ""
    private var lastPagerIndex: Int = -1
    private var lastVisualHash: Long? = null
    private val dwellIntervalsMs = ArrayDeque<Long>()
    private val transitionTimestampsMs = ArrayDeque<Long>()
    private var shortTransitionCount: Int = 0
    private var consecutiveRapidTransitions: Int = 0

    // Reopening & break tracking
    private val reopeningHistory = ArrayDeque<ReopenEntry>()
    private var shortInterruptionCount: Int = 0
    private var meaningfulBreakCount: Int = 0
    private var lastMeaningfulBreakMs: Long = 0L
    private var pendingBreakDecayPoints: Float = 0f

    // Session summary metrics
    private var maxRiskScoreInSession: Int = 0
    private var interventionsInSession: Int = 0
    private var lastClosedSession: FeedSessionRecord? = null

    fun onForegroundAppChanged(
        newPackageName: String,
        nowMs: Long,
        config: DoomscrollConfig
    ): FeedSessionRecord? {
        advanceClocks(nowMs, config)
        val newApp = SupportedSocialApp.fromPackage(newPackageName)
        if (newPackageName == currentForegroundPkg) return null

        val wasInFeed = isInConfirmedFeed
        currentForegroundPkg = newPackageName
        currentApp = newApp

        if (wasInFeed) {
            isInConfirmedFeed = false
            currentContextType = FeedContextType.UNKNOWN
            currentFeedConfidence = 0f
            lastFeedActiveMs = nowMs
        }
        return checkAndFinalizeIfMeaningfulBreak(nowMs, config)
    }

    fun onScreenStateChanged(
        interactive: Boolean,
        nowMs: Long,
        config: DoomscrollConfig
    ): FeedSessionRecord? {
        advanceClocks(nowMs, config)
        isScreenInteractive = interactive
        if (!interactive && isInConfirmedFeed) {
            isInConfirmedFeed = false
            lastFeedActiveMs = nowMs
        }
        return checkAndFinalizeIfMeaningfulBreak(nowMs, config)
    }

    fun onFeedClassification(
        classification: FeedClassification,
        snapshot: FeedObservationSnapshot,
        config: DoomscrollConfig
    ): FeedSessionRecord? {
        val nowMs = snapshot.timestampMs
        advanceClocks(nowMs, config)

        if (!isScreenInteractive || !config.enabled || !config.isAppEnabled(classification.app)) {
            if (isInConfirmedFeed) {
                isInConfirmedFeed = false
                lastFeedActiveMs = nowMs
            }
            currentContextType = classification.contextType
            currentFeedConfidence = classification.confidence
            return null
        }

        currentForegroundPkg = snapshot.packageName
        currentApp = classification.app
        currentContextType = classification.contextType
        currentFeedConfidence = classification.confidence

        val confirmedFeed = classification.isConfirmedShortFormFeed(config.safeMinFeedConfidence)
        var finalizedSession: FeedSessionRecord? = null

        if (confirmedFeed) {
            if (!isInConfirmedFeed) {
                finalizedSession = handleFeedEntry(classification.app, snapshot.packageName, nowMs, config)
            }
            isInConfirmedFeed = true
            lastFeedActiveMs = nowMs

            // Evaluate distinct video transition
            maybeRecordVideoTransition(classification, snapshot, config)
        } else {
            // Comments, Profile, Search, DMs, Long-form video, or Unknown UI:
            // pause active feed timing immediately without fabricating transitions.
            if (isInConfirmedFeed) {
                isInConfirmedFeed = false
                lastFeedActiveMs = nowMs
            }
            finalizedSession = checkAndFinalizeIfMeaningfulBreak(nowMs, config)
        }

        return finalizedSession
    }

    /**
     * Lightweight visual frame hash signal from existing capture pipeline (no extra AI inference).
     * Used ONLY as a fallback when a confirmed feed is active and accessibility scroll/signature
     * events did not already record a transition.
     */
    fun onVisualFrameHash(
        packageName: String,
        frameHash: Long,
        nowMs: Long,
        config: DoomscrollConfig
    ) {
        if (!config.enabled || !isScreenInteractive || !isInConfirmedFeed) return
        if (packageName != sessionPackage || frameHash == 0L) return

        advanceClocks(nowMs, config)
        val prev = lastVisualHash
        lastVisualHash = frameHash
        if (prev == null) return

        val elapsedSinceLast = nowMs - lastTransitionMs
        if (elapsedSinceLast < config.safeDuplicateSuppressionMs) return

        val dist = PerceptualFrameHasher.hammingDistance(prev, frameHash)
        if (dist >= visualHashDiffThreshold) {
            recordConfirmedTransition(nowMs, config)
        }
    }

    fun onTick(nowMs: Long, config: DoomscrollConfig): FeedSessionRecord? {
        advanceClocks(nowMs, config)
        return checkAndFinalizeIfMeaningfulBreak(nowMs, config)
    }

    fun recordScoringOutcome(riskScore: Int, interventionTriggered: Boolean) {
        if (riskScore > maxRiskScoreInSession) {
            maxRiskScoreInSession = riskScore
        }
        if (interventionTriggered) {
            interventionsInSession++
        }
    }

    /**
     * Completing a Cool-Off session closes the risky session and resets accumulated active risk
     * without erasing historical records or reopening counts outside the reset.
     */
    fun onCoolOffCompleted(nowMs: Long, config: DoomscrollConfig): FeedSessionRecord? {
        advanceClocks(nowMs, config)
        val closed = if (sessionId.isNotBlank() && activeFeedDurationMs > 0L) {
            buildSessionRecord(nowMs, SessionCompletionStatus.ENDED_BY_COOL_OFF, config)
        } else null
        resetActiveSessionState(nowMs)
        pendingBreakDecayPoints = 0f
        lastMeaningfulBreakMs = nowMs
        meaningfulBreakCount++
        // Also prune reopening burst after a genuine completed Cool-Off so the user gets a clean reset
        reopeningHistory.clear()
        lastClosedSession = closed
        return closed
    }

    fun snapshot(nowMs: Long, config: DoomscrollConfig): BehaviourSnapshot {
        advanceClocks(nowMs, config)
        pruneRollingWindows(nowMs, config)

        val avgDwell = if (dwellIntervalsMs.isNotEmpty()) {
            dwellIntervalsMs.sum() / dwellIntervalsMs.size
        } else 0L

        val medianDwell = if (dwellIntervalsMs.isNotEmpty()) {
            val sorted = dwellIntervalsMs.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2L else sorted[mid]
        } else 0L

        val windowMinutes = max(0.5f, minOf(activeFeedDurationMs, config.safeScrollRateWindowMs) / 60_000f)
        val tpm = if (transitionTimestampsMs.isEmpty() || activeFeedDurationMs < 15_000L) {
            0f
        } else {
            transitionTimestampsMs.size / windowMinutes
        }

        val intendedMs = config.safeIntendedDurationMinutes * 60_000L
        val strictExceeded = config.strictTimeLimitEnabled && activeFeedDurationMs >= intendedMs

        return BehaviourSnapshot(
            sessionId = sessionId,
            app = if (sessionApp != SupportedSocialApp.UNSUPPORTED) sessionApp else currentApp,
            applicationPackage = sessionPackage.ifBlank { currentForegroundPkg },
            isInConfirmedFeed = isInConfirmedFeed,
            feedConfidence = currentFeedConfidence,
            feedContextType = currentContextType,
            sessionStartTimestampMs = sessionStartMs,
            activeFeedDurationMs = activeFeedDurationMs,
            pausedDurationMs = pausedDurationMs,
            backgroundDurationMs = backgroundDurationMs,
            videoTransitionCount = videoTransitionCount,
            averageDwellTimeMs = avgDwell,
            medianDwellTimeMs = medianDwell,
            shortTransitionCount = shortTransitionCount,
            consecutiveRapidTransitions = consecutiveRapidTransitions,
            transitionsPerMinute = tpm,
            reopeningCountInWindow = reopeningHistory.size,
            shortInterruptionCount = shortInterruptionCount,
            meaningfulBreakCount = meaningfulBreakCount,
            elapsedSinceMeaningfulBreakMs = if (lastMeaningfulBreakMs > 0L) (nowMs - lastMeaningfulBreakMs).coerceAtLeast(0L) else Long.MAX_VALUE,
            pendingBreakDecayPoints = pendingBreakDecayPoints,
            intendedDurationMinutes = config.safeIntendedDurationMinutes,
            strictTimeLimitExceeded = strictExceeded,
            lastUpdatedMs = nowMs
        )
    }

    /**
     * Restores an active session persisted before process death / Activity recreation.
     */
    fun restorePersistedSession(
        restoredSessionId: String,
        packageName: String,
        startTimestampMs: Long,
        activeDurationMs: Long,
        transitionCount: Int,
        shortTransitions: Int,
        reopeningCount: Int,
        lastActiveMs: Long,
        nowMs: Long,
        config: DoomscrollConfig
    ) {
        val app = SupportedSocialApp.fromPackage(packageName)
        if (app == SupportedSocialApp.UNSUPPORTED || restoredSessionId.isBlank()) return
        val gapMs = (nowMs - lastActiveMs).coerceAtLeast(0L)
        if (gapMs >= config.safeMeaningfulBreakMs) {
            // User was away long enough for a meaningful break; keep reopening history only
            repeat(reopeningCount.coerceIn(0, 10)) {
                reopeningHistory.addLast(ReopenEntry(lastActiveMs, app))
            }
            return
        }
        sessionId = restoredSessionId
        sessionApp = app
        sessionPackage = packageName
        sessionStartMs = startTimestampMs
        activeFeedDurationMs = activeDurationMs.coerceAtLeast(0L)
        videoTransitionCount = transitionCount.coerceAtLeast(0)
        shortTransitionCount = shortTransitions.coerceAtLeast(0)
        lastFeedActiveMs = lastActiveMs
        lastTickMs = nowMs
        isInConfirmedFeed = false
        repeat(reopeningCount.coerceIn(0, 10)) {
            reopeningHistory.addLast(ReopenEntry(lastActiveMs, app))
        }
    }

    fun getLastClosedSession(): FeedSessionRecord? = lastClosedSession

    fun isConfirmedFeedForPackage(packageName: String): Boolean {
        return isInConfirmedFeed && sessionPackage.equals(packageName, ignoreCase = true)
    }

    private fun handleFeedEntry(
        app: SupportedSocialApp,
        packageName: String,
        nowMs: Long,
        config: DoomscrollConfig
    ): FeedSessionRecord? {
        pruneRollingWindows(nowMs, config)
        var closed: FeedSessionRecord? = null

        val hasActiveSession = sessionId.isNotBlank()
        val gapMs = if (lastFeedActiveMs > 0L) (nowMs - lastFeedActiveMs).coerceAtLeast(0L) else Long.MAX_VALUE

        if (!hasActiveSession) {
            startNewSession(app, packageName, nowMs)
            reopeningHistory.addLast(ReopenEntry(nowMs, app))
        } else if (sessionApp != app && gapMs >= config.safeShortInterruptionMaxMs) {
            // Switched to a different supported feed after >30s: close previous, start new
            closed = buildSessionRecord(nowMs, SessionCompletionStatus.ENDED_NATURALLY, config)
            startNewSession(app, packageName, nowMs)
            reopeningHistory.addLast(ReopenEntry(nowMs, app))
        } else if (gapMs >= config.safeMeaningfulBreakMs) {
            // Meaningful break (>= 60s): close previous session, apply decay credit, start fresh session
            closed = buildSessionRecord(nowMs, SessionCompletionStatus.ENDED_BY_BREAK, config)
            meaningfulBreakCount++
            lastMeaningfulBreakMs = nowMs
            val breakMinutes = (gapMs / 60_000f).coerceIn(1f, 15f)
            pendingBreakDecayPoints = (breakMinutes * 5f).coerceAtMost(30f)
            startNewSession(app, packageName, nowMs)
            reopeningHistory.addLast(ReopenEntry(nowMs, app))
        } else if (gapMs >= 3_000L) {
            // Short interruption (< 60s): continue existing session, track reopening if app was left
            shortInterruptionCount++
            if (gapMs >= config.safeShortInterruptionMaxMs) {
                reopeningHistory.addLast(ReopenEntry(nowMs, app))
            }
        }
        return closed
    }

    private fun startNewSession(app: SupportedSocialApp, packageName: String, nowMs: Long) {
        sessionId = UUID.randomUUID().toString()
        sessionApp = app
        sessionPackage = packageName
        sessionStartMs = nowMs
        lastTickMs = nowMs
        lastFeedActiveMs = nowMs
        activeFeedDurationMs = 0L
        pausedDurationMs = 0L
        backgroundDurationMs = 0L
        videoTransitionCount = 0
        lastTransitionMs = nowMs
        lastContentSignature = ""
        lastPagerIndex = -1
        lastVisualHash = null
        dwellIntervalsMs.clear()
        transitionTimestampsMs.clear()
        shortTransitionCount = 0
        consecutiveRapidTransitions = 0
        maxRiskScoreInSession = 0
        interventionsInSession = 0
    }

    private fun resetActiveSessionState(nowMs: Long) {
        sessionId = ""
        sessionApp = SupportedSocialApp.UNSUPPORTED
        sessionPackage = ""
        sessionStartMs = 0L
        lastTickMs = nowMs
        lastFeedActiveMs = 0L
        activeFeedDurationMs = 0L
        pausedDurationMs = 0L
        backgroundDurationMs = 0L
        videoTransitionCount = 0
        lastTransitionMs = 0L
        lastContentSignature = ""
        lastPagerIndex = -1
        lastVisualHash = null
        dwellIntervalsMs.clear()
        transitionTimestampsMs.clear()
        shortTransitionCount = 0
        consecutiveRapidTransitions = 0
        maxRiskScoreInSession = 0
        interventionsInSession = 0
        isInConfirmedFeed = false
    }

    private fun advanceClocks(nowMs: Long, config: DoomscrollConfig) {
        if (lastTickMs <= 0L) {
            lastTickMs = nowMs
            return
        }
        val delta = (nowMs - lastTickMs).coerceAtLeast(0L)
        lastTickMs = nowMs
        if (delta == 0L || sessionId.isBlank()) return

        // Cap single tick delta to avoid clock-jump inflation during device sleep
        val safeDelta = delta.coerceAtMost(config.safeScoringIntervalMs * 3)

        if (isScreenInteractive && isInConfirmedFeed) {
            activeFeedDurationMs += safeDelta
            // Gradually wear down break decay credit once continuous scrolling resumes
            if (pendingBreakDecayPoints > 0f) {
                pendingBreakDecayPoints = (pendingBreakDecayPoints - (safeDelta / 15_000f)).coerceAtLeast(0f)
            }
        } else if (!isScreenInteractive || currentApp != sessionApp) {
            backgroundDurationMs += safeDelta
        } else {
            pausedDurationMs += safeDelta
        }
    }

    private fun checkAndFinalizeIfMeaningfulBreak(
        nowMs: Long,
        config: DoomscrollConfig
    ): FeedSessionRecord? {
        if (sessionId.isBlank() || isInConfirmedFeed || lastFeedActiveMs <= 0L) return null
        val awayMs = (nowMs - lastFeedActiveMs).coerceAtLeast(0L)
        if (awayMs >= config.safeMeaningfulBreakMs) {
            val closed = buildSessionRecord(nowMs, SessionCompletionStatus.ENDED_BY_BREAK, config)
            meaningfulBreakCount++
            lastMeaningfulBreakMs = nowMs
            val breakMinutes = (awayMs / 60_000f).coerceIn(1f, 15f)
            pendingBreakDecayPoints = (breakMinutes * 5f).coerceAtMost(30f)
            resetActiveSessionState(nowMs)
            lastClosedSession = closed
            return closed
        }
        return null
    }

    private fun maybeRecordVideoTransition(
        classification: FeedClassification,
        snapshot: FeedObservationSnapshot,
        config: DoomscrollConfig
    ) {
        val nowMs = snapshot.timestampMs
        val elapsedSinceLast = nowMs - lastTransitionMs
        val sigChanged = classification.contentSignature.isNotBlank() &&
            lastContentSignature.isNotBlank() &&
            classification.contentSignature != lastContentSignature
        val pagerChanged = classification.pagerIndex >= 0 &&
            lastPagerIndex >= 0 &&
            classification.pagerIndex != lastPagerIndex
        val visualChanged = snapshot.visualFrameHash != null &&
            snapshot.visualFrameHash != 0L &&
            lastVisualHash != null &&
            PerceptualFrameHasher.hammingDistance(lastVisualHash!!, snapshot.visualFrameHash) >= visualHashDiffThreshold

        // Seed initial state on first frame in feed without falsely counting video #0 -> #1
        if (lastContentSignature.isBlank() && classification.contentSignature.isNotBlank()) {
            lastContentSignature = classification.contentSignature
        }
        if (lastPagerIndex < 0 && classification.pagerIndex >= 0) {
            lastPagerIndex = classification.pagerIndex
        }
        if (lastVisualHash == null && snapshot.visualFrameHash != null && snapshot.visualFrameHash != 0L) {
            lastVisualHash = snapshot.visualFrameHash
        }

        val hasDistinctEvidence = sigChanged || pagerChanged || visualChanged ||
            (classification.isVerticalScrollSignal && elapsedSinceLast >= config.safeDuplicateSuppressionMs * 2)

        if (hasDistinctEvidence && elapsedSinceLast >= config.safeDuplicateSuppressionMs) {
            recordConfirmedTransition(nowMs, config)
            if (classification.contentSignature.isNotBlank()) {
                lastContentSignature = classification.contentSignature
            }
            if (classification.pagerIndex >= 0) {
                lastPagerIndex = classification.pagerIndex
            }
            if (snapshot.visualFrameHash != null && snapshot.visualFrameHash != 0L) {
                lastVisualHash = snapshot.visualFrameHash
            }
        }
    }

    private fun recordConfirmedTransition(nowMs: Long, config: DoomscrollConfig) {
        val dwellMs = (nowMs - lastTransitionMs).coerceAtLeast(config.safeDuplicateSuppressionMs)
        lastTransitionMs = nowMs
        videoTransitionCount++
        transitionTimestampsMs.addLast(nowMs)

        // Cap dwell sample window to last 60 transitions for O(1) memory
        if (dwellIntervalsMs.size >= 60) dwellIntervalsMs.removeFirst()
        dwellIntervalsMs.addLast(dwellMs)

        if (dwellMs <= config.safeShortDwellThresholdMs) {
            shortTransitionCount++
            consecutiveRapidTransitions++
        } else {
            consecutiveRapidTransitions = 0
        }
        pruneRollingWindows(nowMs, config)
    }

    private fun pruneRollingWindows(nowMs: Long, config: DoomscrollConfig) {
        val scrollCutoff = nowMs - config.safeScrollRateWindowMs
        while (transitionTimestampsMs.isNotEmpty() && transitionTimestampsMs.first() < scrollCutoff) {
            transitionTimestampsMs.removeFirst()
        }
        val reopenCutoff = nowMs - config.safeReopeningWindowMs
        while (reopeningHistory.isNotEmpty() && reopeningHistory.first().timestampMs < reopenCutoff) {
            reopeningHistory.removeFirst()
        }
    }

    private fun buildSessionRecord(
        endMs: Long,
        status: SessionCompletionStatus,
        config: DoomscrollConfig
    ): FeedSessionRecord {
        val avgDwell = if (dwellIntervalsMs.isNotEmpty()) dwellIntervalsMs.sum() / dwellIntervalsMs.size else 0L
        return FeedSessionRecord(
            sessionId = sessionId,
            applicationPackage = sessionPackage,
            startTimestamp = sessionStartMs,
            endTimestamp = endMs,
            activeDurationMs = activeFeedDurationMs,
            videoTransitionCount = videoTransitionCount,
            averageDwellTimeMs = avgDwell,
            shortTransitionCount = shortTransitionCount,
            reopeningCount = reopeningHistory.size,
            intendedDurationMinutes = config.safeIntendedDurationMinutes,
            maximumRiskScore = maxRiskScoreInSession,
            interventionCount = interventionsInSession,
            completionStatus = status
        )
    }
}
