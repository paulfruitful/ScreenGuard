package com.paulfruitful.screenguard.doomscroll

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.paulfruitful.screenguard.accessibility.ScreenGuardAccessibilityService
import com.paulfruitful.screenguard.protection.ProtectionCoordinator
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Facade connecting the Behavioural Protection Engine to:
 * - [ScreenGuardAccessibilityService] (lightweight inline event intake + screen/app lifecycle)
 * - [ProtectionCoordinator] (single shared Protective Shield & Cool-Off intervention interface)
 * - [CoolOffSessionManager] (risk reset on completed Cool-Off session)
 * - [ScreenGuardModule] (optional Settings toggle, intended usage config, and live status)
 *
 * Zero-overhead guarantee when disabled (`config.enabled == false`):
 * - All hooks return immediately before allocating snapshots or launching background work.
 * - Background scoring timer runs ONLY while enabled AND inside a supported social app.
 */
object BehaviouralProtectionCoordinator {

    private const val MAX_NODE_INSPECTION = 180
    private const val MAX_NODE_BUDGET_MS = 35L
    private const val NODE_INSPECTION_MIN_INTERVAL_MS = 1200L
    private const val PERSIST_DEBOUNCE_MS = 15_000L

    private val lock = Any()
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val feedDetector = FeedDetector()
    private val tracker = BehaviourTracker()
    private val interventionManager = DoomscrollInterventionManager()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var latestBreakdown: SignalBreakdown = SignalBreakdown()

    @Volatile
    private var activeScoringJob: Job? = null

    @Volatile
    private var lastNodeInspectionMs: Long = 0L

    @Volatile
    private var lastPersistedAtMs: Long = 0L

    @Volatile
    private var currentSupportedPkg: String = ""

    @Volatile
    private var consecutiveUnknownFullScans: Int = 0

    fun init(context: Context) {
        if (initialized && appContext != null) return
        synchronized(lock) {
            if (initialized && appContext != null) return
            val ctx = context.applicationContext
            appContext = ctx
            val cfg = DoomscrollConfigStore.load(ctx)
            val saved = DoomscrollSessionStore.loadActiveSession(ctx)
            if (saved != null) {
                tracker.restorePersistedSession(
                    restoredSessionId = saved.sessionId,
                    packageName = saved.packageName,
                    startTimestampMs = saved.startTimestampMs,
                    activeDurationMs = saved.activeDurationMs,
                    transitionCount = saved.videoTransitionCount,
                    shortTransitions = saved.shortTransitionCount,
                    reopeningCount = saved.reopeningCount,
                    lastActiveMs = saved.lastActiveMs,
                    nowMs = System.currentTimeMillis(),
                    config = cfg
                )
            }
            initialized = true
        }
    }

    fun getConfig(context: Context): DoomscrollConfig {
        init(context)
        return DoomscrollConfigStore.load(context)
    }

    fun updateConfigFromJson(context: Context, patchJson: String): DoomscrollConfig {
        init(context)
        val updated = DoomscrollConfigStore.updateFromJson(context, patchJson)
        if (!updated.enabled) {
            stopScoringTicker()
            currentSupportedPkg = ""
        } else if (currentSupportedPkg.isNotBlank()) {
            startScoringTicker(context)
        }
        return updated
    }

    /**
     * Main-thread safe intake from [ScreenGuardAccessibilityService.onAccessibilityEvent].
     * Reads ONLY inline primitive parcel fields (< 0.05 ms, ZERO Binder IPC) and dispatches
     * classification & scoring to [workerScope].
     */
    fun onAccessibilityEventInline(
        context: Context,
        event: AccessibilityEvent,
        foregroundPackage: String
    ) {
        val cfg = DoomscrollConfigStore.load(context)
        if (!cfg.enabled) return

        val app = SupportedSocialApp.fromPackage(foregroundPackage)
        if (app == SupportedSocialApp.UNSUPPORTED || !cfg.isAppEnabled(app)) return

        init(context)
        if (currentSupportedPkg != foregroundPackage) {
            currentSupportedPkg = foregroundPackage
            startScoringTicker(context)
        }

        val className = event.className?.toString() ?: ""
        val contentDesc = event.contentDescription?.toString()
        val texts = buildList {
            for (t in event.text) {
                val s = t?.toString()?.trim()
                if (!s.isNullOrBlank()) add(s.take(80))
                if (size >= 6) break
            }
        }
        val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaY else 0
        val deltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) event.scrollDeltaX else 0
        val inlineSnapshot = FeedObservationSnapshot(
            packageName = foregroundPackage,
            eventType = event.eventType,
            className = className,
            contentDescriptions = if (!contentDesc.isNullOrBlank()) listOf(contentDesc.take(80)) else emptyList(),
            texts = texts,
            isScrollable = event.isScrollable,
            scrollDeltaY = deltaY,
            scrollDeltaX = deltaX,
            fromIndex = event.fromIndex,
            toIndex = event.toIndex,
            itemCount = event.itemCount,
            timestampMs = System.currentTimeMillis()
        )

        workerScope.launch {
            try {
                processObservationBackground(context.applicationContext, inlineSnapshot, cfg)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Opportunistically consumes the 64-bit perceptual frame hash already computed by
     * Screen Guard's visual pipeline — ZERO extra screenshots or AI inference.
     */
    fun onExistingVisualFrameHash(context: Context, packageName: String, frameHash: Long) {
        val cfg = DoomscrollConfigStore.load(context)
        if (!cfg.enabled || frameHash == 0L) return
        val app = SupportedSocialApp.fromPackage(packageName)
        if (app == SupportedSocialApp.UNSUPPORTED || !cfg.isAppEnabled(app)) return
        init(context)

        val nowMs = System.currentTimeMillis()
        workerScope.launch {
            try {
                synchronized(lock) {
                    tracker.onVisualFrameHash(packageName, frameHash, nowMs, cfg)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun onForegroundAppChanged(context: Context, newPackageName: String) {
        val cfg = DoomscrollConfigStore.load(context)
        if (!cfg.enabled) {
            stopScoringTicker()
            return
        }
        init(context)
        val nowMs = System.currentTimeMillis()
        val app = SupportedSocialApp.fromPackage(newPackageName)
        val isSupported = app != SupportedSocialApp.UNSUPPORTED && cfg.isAppEnabled(app)
        currentSupportedPkg = if (isSupported) newPackageName else ""

        workerScope.launch {
            try {
                val closed: FeedSessionRecord?
                val snap: BehaviourSnapshot
                synchronized(lock) {
                    closed = tracker.onForegroundAppChanged(newPackageName, nowMs, cfg)
                    snap = tracker.snapshot(nowMs, cfg)
                }
                if (closed != null) {
                    DoomscrollSessionStore.appendHistory(context, closed)
                    DoomscrollSessionStore.clearActiveSession(context)
                } else if (snap.sessionId.isNotBlank()) {
                    DoomscrollSessionStore.saveActiveSession(context, snap)
                }
            } catch (_: Throwable) {
            }
        }

        if (isSupported) {
            startScoringTicker(context)
        } else {
            stopScoringTicker()
        }
    }

    fun onScreenStateChanged(context: Context, interactive: Boolean) {
        val cfg = DoomscrollConfigStore.load(context)
        if (!cfg.enabled) return
        init(context)
        val nowMs = System.currentTimeMillis()

        if (!interactive) {
            stopScoringTicker()
        } else if (currentSupportedPkg.isNotBlank()) {
            startScoringTicker(context)
        }

        workerScope.launch {
            try {
                val closed: FeedSessionRecord?
                val snap: BehaviourSnapshot
                synchronized(lock) {
                    closed = tracker.onScreenStateChanged(interactive, nowMs, cfg)
                    snap = tracker.snapshot(nowMs, cfg)
                }
                if (closed != null) {
                    DoomscrollSessionStore.appendHistory(context, closed)
                    DoomscrollSessionStore.clearActiveSession(context)
                } else if (snap.sessionId.isNotBlank()) {
                    DoomscrollSessionStore.saveActiveSession(context, snap)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun onServiceDisconnected(context: Context) {
        stopScoringTicker()
        currentSupportedPkg = ""
        val cfg = DoomscrollConfigStore.load(context)
        if (!cfg.enabled) return
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            val snap = tracker.snapshot(nowMs, cfg)
            if (snap.sessionId.isNotBlank()) {
                DoomscrollSessionStore.saveActiveSession(context, snap)
            }
        }
    }

    fun onUserContinuedFromGentleReminder(context: Context) {
        val nowMs = System.currentTimeMillis()
        synchronized(lock) {
            interventionManager.onUserContinuedFromGentleReminder(nowMs)
        }
        try {
            LocalStorage.appendActivityLog(
                context,
                ActivityLogRecord(
                    type = "SERVICE_STATE",
                    title = "Doomscroll Reminder Dismissed (Continue)",
                    detail = "User chose to continue watching. Gentle reminder cooldown active.",
                    packageName = currentSupportedPkg.ifBlank { context.packageName }
                )
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Called by [CoolOffSessionManager] whenever the user genuinely completes a Cool-Off session.
     * Resets active doomscroll risk while preserving local historical stats.
     */
    fun onCoolOffSessionCompleted(context: Context) {
        init(context)
        val cfg = DoomscrollConfigStore.load(context)
        val nowMs = System.currentTimeMillis()
        val closed: FeedSessionRecord?
        synchronized(lock) {
            closed = tracker.onCoolOffCompleted(nowMs, cfg)
            interventionManager.onCoolOffCompleted(nowMs)
            latestBreakdown = SignalBreakdown(
                totalScore = 0,
                riskLevel = RiskLevel.NORMAL,
                explanation = "Reset after completed Cool-Off session."
            )
        }
        if (closed != null) {
            DoomscrollSessionStore.appendHistory(context, closed)
        }
        DoomscrollSessionStore.clearActiveSession(context)
    }

    fun getLiveStatus(context: Context): DoomscrollLiveStatus {
        init(context)
        val cfg = DoomscrollConfigStore.load(context)
        val nowMs = System.currentTimeMillis()
        val snap: BehaviourSnapshot
        val breakdown: SignalBreakdown
        val lastLevel: DoomscrollInterventionLevel
        synchronized(lock) {
            snap = tracker.snapshot(nowMs, cfg)
            breakdown = DoomscrollDetectionEngine.evaluate(snap, cfg)
            latestBreakdown = breakdown
            lastLevel = interventionManager.getLastInterventionLevel()
        }
        val (sessionsToday, activeMinToday) = DoomscrollSessionStore.getTodaySummary(
            context,
            snap.activeFeedDurationMs
        )
        return DoomscrollLiveStatus(
            enabled = cfg.enabled,
            activeAppPackage = snap.applicationPackage.ifBlank { "None" },
            activeAppName = snap.app.displayName,
            feedContextType = snap.feedContextType.name,
            isInConfirmedFeed = snap.isInConfirmedFeed,
            feedConfidence = snap.feedConfidence,
            activeDurationSeconds = (snap.activeFeedDurationMs / 1000L).toInt(),
            videoTransitionCount = snap.videoTransitionCount,
            averageDwellSeconds = snap.averageDwellTimeMs / 1000f,
            transitionsPerMinute = snap.transitionsPerMinute,
            reopeningCount = snap.reopeningCountInWindow,
            riskScore = breakdown.totalScore,
            riskLevel = breakdown.riskLevel.name,
            lastInterventionLevel = lastLevel.name,
            intendedDurationMinutes = cfg.safeIntendedDurationMinutes,
            totalSessionsToday = sessionsToday,
            totalActiveMinutesToday = activeMinToday,
            signalExplanation = breakdown.explanation
        )
    }

    private fun processObservationBackground(
        context: Context,
        inlineSnapshot: FeedObservationSnapshot,
        config: DoomscrollConfig
    ) {
        // First try zero-IPC inline classification
        var effectiveSnapshot = inlineSnapshot
        var classification = feedDetector.detect(effectiveSnapshot, config)
        var performedFullNodeInspection = false

        // When inline event fields are sparse/unknown (very common for TYPE_WINDOW_CONTENT_CHANGED
        // timer ticks and animations in TikTok/IG/FB/X), run a bounded background node inspection
        // at most once every 1.2 seconds.
        val nowMs = inlineSnapshot.timestampMs
        val isVerticalScroll = AdapterSignalUtils.isVerticalScrollEvent(inlineSnapshot)
        if (classification.contextType == FeedContextType.UNKNOWN) {
            if ((nowMs - lastNodeInspectionMs) >= NODE_INSPECTION_MIN_INTERVAL_MS) {
                lastNodeInspectionMs = nowMs
                val enriched = enrichSnapshotFromActiveWindowSafe(inlineSnapshot)
                if (enriched != null) {
                    performedFullNodeInspection = true
                    effectiveSnapshot = enriched
                    classification = feedDetector.detect(effectiveSnapshot, config)
                }
                // Hysteresis: when already inside a confirmed short-form feed, require 2 consecutive
                // full-node UNKNOWN scans before pausing active feed duration so transient mid-swipe
                // frames never slice away real scrolling time. (Explicit non-feed contexts like
                // COMMENTS/DMs/PROFILE/SEARCH still pause immediately on the first hit.)
                if (classification.contextType == FeedContextType.UNKNOWN) {
                    val currentlyConfirmed = synchronized(lock) {
                        tracker.isConfirmedFeedForPackage(inlineSnapshot.packageName)
                    }
                    if (currentlyConfirmed) {
                        consecutiveUnknownFullScans++
                        if (consecutiveUnknownFullScans < 2) {
                            return
                        }
                    }
                } else {
                    consecutiveUnknownFullScans = 0
                }
            } else {
                // CRITICAL FIX: A throttled sparse inline event (e.g. video progress/animation tick)
                // must NEVER downgrade an already-confirmed active short-form feed to UNKNOWN!
                // If it's a vertical scroll inside an already-confirmed feed, treat it as a scroll signal
                // for the active feed; otherwise ignore the noisy empty content-change event.
                val currentlyConfirmed = synchronized(lock) {
                    tracker.isConfirmedFeedForPackage(inlineSnapshot.packageName)
                }
                if (!currentlyConfirmed) return
                if (!isVerticalScroll) return
                classification = FeedClassification(
                    app = SupportedSocialApp.fromPackage(inlineSnapshot.packageName),
                    contextType = FeedContextType.SHORT_FORM_FEED,
                    confidence = config.safeMinFeedConfidence,
                    adapterName = "ActiveFeedScrollContinuation",
                    contentSignature = AdapterSignalUtils.buildContentSignature(inlineSnapshot),
                    pagerIndex = inlineSnapshot.fromIndex,
                    isVerticalScrollSignal = true,
                    diagnosticReason = "active_feed_vertical_scroll"
                )
            }
        }

        val closed: FeedSessionRecord?
        val snap: BehaviourSnapshot
        val breakdown: SignalBreakdown
        val intervention: InterventionDecision
        val prevConfirmed: Boolean
        val prevTransitions: Int

        synchronized(lock) {
            prevConfirmed = tracker.isConfirmedFeedForPackage(inlineSnapshot.packageName)
            prevTransitions = tracker.snapshot(nowMs, config).videoTransitionCount
            closed = tracker.onFeedClassification(classification, effectiveSnapshot, config)
            snap = tracker.snapshot(nowMs, config)
            breakdown = DoomscrollDetectionEngine.evaluate(snap, config)
            latestBreakdown = breakdown
            intervention = interventionManager.evaluate(
                snapshot = snap,
                breakdown = breakdown,
                config = config,
                nowMs = nowMs,
                isOverlayCurrentlyVisible = ProtectionCoordinator.isOverlayVisible()
            )
            tracker.recordScoringOutcome(breakdown.totalScore, intervention.shouldIntervene)
        }

        if (prevConfirmed != snap.isInConfirmedFeed ||
            snap.videoTransitionCount != prevTransitions ||
            intervention.shouldIntervene
        ) {
            android.util.Log.i(
                "SG_DOOMSCROLL",
                "pkg=${inlineSnapshot.packageName} ctx=${classification.contextType}(${"%.2f".format(classification.confidence)}) inFeed=${snap.isInConfirmedFeed} activeSec=${snap.activeFeedDurationMs / 1000} vids=${snap.videoTransitionCount} score=${breakdown.totalScore}/${breakdown.riskLevel} fullNode=$performedFullNodeInspection reason=${classification.diagnosticReason}"
            )
        }

        if (closed != null) {
            DoomscrollSessionStore.appendHistory(context, closed)
            DoomscrollSessionStore.clearActiveSession(context)
        } else if (snap.sessionId.isNotBlank() && (nowMs - lastPersistedAtMs) >= PERSIST_DEBOUNCE_MS) {
            lastPersistedAtMs = nowMs
            DoomscrollSessionStore.saveActiveSession(context, snap)
        }

        if (intervention.shouldIntervene) {
            dispatchIntervention(context, snap, intervention)
        }
    }

    private fun startScoringTicker(context: Context) {
        val cfg = DoomscrollConfigStore.load(context)
        if (!cfg.enabled) return
        if (activeScoringJob?.isActive == true) return

        val appCtx = context.applicationContext
        activeScoringJob = workerScope.launch {
            while (isActive) {
                val liveCfg = DoomscrollConfigStore.load(appCtx)
                if (!liveCfg.enabled || currentSupportedPkg.isBlank()) break
                delay(liveCfg.safeScoringIntervalMs)
                try {
                    val nowMs = System.currentTimeMillis()
                    val closed: FeedSessionRecord?
                    val snap: BehaviourSnapshot
                    val breakdown: SignalBreakdown
                    val intervention: InterventionDecision
                    synchronized(lock) {
                        closed = tracker.onTick(nowMs, liveCfg)
                        snap = tracker.snapshot(nowMs, liveCfg)
                        breakdown = DoomscrollDetectionEngine.evaluate(snap, liveCfg)
                        latestBreakdown = breakdown
                        intervention = interventionManager.evaluate(
                            snapshot = snap,
                            breakdown = breakdown,
                            config = liveCfg,
                            nowMs = nowMs,
                            isOverlayCurrentlyVisible = ProtectionCoordinator.isOverlayVisible()
                        )
                        tracker.recordScoringOutcome(breakdown.totalScore, intervention.shouldIntervene)
                    }
                    if (closed != null) {
                        DoomscrollSessionStore.appendHistory(appCtx, closed)
                        DoomscrollSessionStore.clearActiveSession(appCtx)
                    } else if (snap.sessionId.isNotBlank() && (nowMs - lastPersistedAtMs) >= PERSIST_DEBOUNCE_MS) {
                        lastPersistedAtMs = nowMs
                        DoomscrollSessionStore.saveActiveSession(appCtx, snap)
                    }
                    if (intervention.shouldIntervene) {
                        dispatchIntervention(appCtx, snap, intervention)
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun stopScoringTicker() {
        activeScoringJob?.cancel()
        activeScoringJob = null
    }

    private fun dispatchIntervention(
        context: Context,
        snapshot: BehaviourSnapshot,
        decision: InterventionDecision
    ) {
        val appLabel = snapshot.app.displayName
        val pkg = snapshot.applicationPackage.ifBlank { currentSupportedPkg }
        ProtectionCoordinator.requestBehaviouralIntervention(
            context = context,
            packageName = pkg,
            appLabel = appLabel,
            level = decision.level,
            riskScore = decision.riskScore,
            activeDurationMinutes = (snapshot.activeFeedDurationMs / 60_000L).toInt(),
            videoCount = snapshot.videoTransitionCount,
            explanation = decision.reason
        )
    }

    /**
     * Bounded off-main-thread node inspection (< 20 ms, <= 32 nodes) to extract view resource IDs
     * and content descriptions when inline event fields alone are insufficient.
     */
    private fun enrichSnapshotFromActiveWindowSafe(
        base: FeedObservationSnapshot
    ): FeedObservationSnapshot? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val service: AccessibilityService = ScreenGuardAccessibilityService.getActiveInstance() ?: return null
        val root = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        val rootPkg = root.packageName?.toString()
        if (rootPkg != null && !rootPkg.equals(base.packageName, ignoreCase = true)) {
            try { root.recycle() } catch (_: Exception) {}
            return null
        }

        val viewIds = mutableListOf<String>()
        val descs = base.contentDescriptions.toMutableList()
        val texts = base.texts.toMutableList()
        val classes = StringBuilder(base.className)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val startMono = SystemClock.elapsedRealtime()
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MAX_NODE_INSPECTION) {
                if ((SystemClock.elapsedRealtime() - startMono) >= MAX_NODE_BUDGET_MS) break
                val node = queue.removeFirst()
                visited++
                try {
                    val resId = node.viewIdResourceName
                    if (!resId.isNullOrBlank() && viewIds.size < 48) {
                        viewIds.add(resId.substringAfterLast('/'))
                    }
                    val cls = node.className?.toString()
                    if (!cls.isNullOrBlank() && (cls.contains("Pager") || cls.contains("Recycler") || cls.contains("Video"))) {
                        classes.append(' ').append(cls.substringAfterLast('.'))
                    }
                    val d = node.contentDescription?.toString()?.trim()
                    if (!d.isNullOrBlank() && descs.size < 32) {
                        descs.add(d.take(64))
                    }
                    val t = node.text?.toString()?.trim()
                    if (!t.isNullOrBlank() && texts.size < 32) {
                        texts.add(t.take(64))
                    }
                    val childCount = node.childCount.coerceAtMost(24)
                    for (i in 0 until childCount) {
                        if (visited + queue.size >= MAX_NODE_INSPECTION) break
                        node.getChild(i)?.let { queue.addLast(it) }
                    }
                } finally {
                    if (node !== root) {
                        try { node.recycle() } catch (_: Exception) {}
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val leftover = queue.removeFirst()
                if (leftover !== root) {
                    try { leftover.recycle() } catch (_: Exception) {}
                }
            }
            try { root.recycle() } catch (_: Exception) {}
        }

        return base.copy(
            className = classes.toString(),
            contentDescriptions = descs,
            texts = texts,
            viewIdResourceNames = viewIds
        )
    }
}
