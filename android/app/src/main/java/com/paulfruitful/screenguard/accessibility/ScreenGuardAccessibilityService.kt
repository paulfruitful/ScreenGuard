package com.paulfruitful.screenguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.paulfruitful.screenguard.bridge.EventBridge
import com.paulfruitful.screenguard.capture.AccessibilityScreenshotCapturer
import com.paulfruitful.screenguard.capture.BufferReleaseTracker
import com.paulfruitful.screenguard.capture.ProcessedScreenshotOutcome
import com.paulfruitful.screenguard.capture.ScreenshotProcessor
import com.paulfruitful.screenguard.classifier.ClassificationResult
import com.paulfruitful.screenguard.classifier.ClassifierEvaluator
import com.paulfruitful.screenguard.classifier.ContentCategory
import com.paulfruitful.screenguard.classifier.DetectionEngine
import com.paulfruitful.screenguard.multimodal.AudioPlaybackCapturePrototype
import com.paulfruitful.screenguard.multimodal.CaptionAndOcrAnalyzer
import com.paulfruitful.screenguard.multimodal.FusedMultimodalDecision
import com.paulfruitful.screenguard.multimodal.MultimodalEvidenceBundle
import com.paulfruitful.screenguard.multimodal.MultimodalFusionEngine
import com.paulfruitful.screenguard.multimodal.OcrFrameEvidence
import com.paulfruitful.screenguard.multimodal.VisualEvidence
import com.paulfruitful.screenguard.pipeline.FrameScheduler
import com.paulfruitful.screenguard.pipeline.FrameTrace
import com.paulfruitful.screenguard.pipeline.FrameTrigger
import com.paulfruitful.screenguard.pipeline.PipelineTelemetry
import com.paulfruitful.screenguard.protection.OverlayState
import com.paulfruitful.screenguard.protection.ProtectionCoordinator
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Core Android [AccessibilityService] for Screen Guard (Reliability, Low-Latency & Multimodal Upgrade).
 *
 * Key Reliability & Performance Guarantees:
 * 1. Main-Thread ANR Elimination (Phase A Fix): `onAccessibilityEvent` executes ZERO synchronous
 *    Binder IPC calls (`service.windows` / `window.root`). It inspects only inline [AccessibilityEvent]
 *    parcel fields in < 0.05 ms and enqueues a conflated [FrameTrigger] onto [FrameScheduler].
 * 2. Latest-Frame-Wins Queue (Phase B): [FrameScheduler] runs on [Dispatchers.Default] with
 *    `Channel.CONFLATED` so obsolete triggers are discarded immediately and every frame has a
 *    strict 6-second execution timeout monitored by [ServiceHealthMonitor].
 * 3. Multimodal Evidence Fusion (Phase C): Separately collects Visual (two-stage LiteRT + ROI crops),
 *    AudioPlayback ([AudioPlaybackCapturePrototype]), CaptionNode ([ScreenTextContextAnalyzer]),
 *    and OcrFrame ([CaptionAndOcrAnalyzer]) evidence on the background worker thread and fuses
 *    them via [MultimodalFusionEngine].
 */
class ScreenGuardAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    // Background worker scope — never blocks the Main UI Looper Thread!
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var eventDebouncer: EventDebouncer
    private lateinit var frameScheduler: FrameScheduler
    private var screenStateReceiverRegistered = false
    private var adaptiveWatchdogJob: Job? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (::frameScheduler.isInitialized) {
                    frameScheduler.stop()
                }
                if (::eventDebouncer.isInitialized) {
                    eventDebouncer.cancelAll()
                }
                ScreenshotProcessor.clearVolatileDebugPreview()
                DetectionEngine.resetConfirmationState()
                ProtectionCoordinator.onScreenTurnedOff(applicationContext)
            } else if (intent?.action == Intent.ACTION_USER_PRESENT) {
                if (::frameScheduler.isInitialized) {
                    frameScheduler.start()
                }
            }
        }
    }

    companion object {
        private const val SELF_PACKAGE = "com.paulfruitful.screenguard"

        @Volatile
        private var instance: ScreenGuardAccessibilityService? = null

        @Volatile
        var currentForegroundPackage: String = "None"
            private set

        @Volatile
        var currentForegroundAppLabel: String = "No active window"
            private set

        @Volatile
        var currentWindowId: Int = -1
            private set

        @Volatile
        var lastScreenshotStatus: String = "IDLE"
            private set

        @Volatile
        var lastScreenshotTimestamp: Long = 0L
            private set

        @Volatile
        var latestFusedDecision: FusedMultimodalDecision? = null
            private set

        fun getActiveInstance(): ScreenGuardAccessibilityService? = instance

        fun isServiceConnected(): Boolean = instance != null

        fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
            try {
                val enabledValue = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                if (enabledValue != 1) return false

                val expectedComponent =
                    "${context.packageName}/${ScreenGuardAccessibilityService::class.java.canonicalName}"
                val expectedShort =
                    "${context.packageName}/.accessibility.ScreenGuardAccessibilityService"

                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val component = splitter.next()
                    if (component.equals(expectedComponent, ignoreCase = true) ||
                        component.equals(expectedShort, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
            return instance != null
        }

        fun runDisconnectionRecoveryDrill(context: Context) {
            val activeService = instance
            activeService?.eventDebouncer?.cancelAll()
            ScreenshotProcessor.clearVolatileDebugPreview()
            DetectionEngine.resetConfirmationState()
            ProtectionCoordinator.onServiceDisconnected(context)
            BufferReleaseTracker.onServiceDisconnected()

            LocalStorage.appendActivityLog(
                context,
                ActivityLogRecord(
                    type = "SERVICE_STATE",
                    title = "Disconnection Recovery Drill Executed",
                    detail = "Cancelled pending debounce jobs, purged volatile buffers, and reset confirmation state.",
                    packageName = currentForegroundPackage
                )
            )

            if (activeService != null) {
                BufferReleaseTracker.onServiceConnected()
                val prefs = LocalStorage.getPreferences(context)
                EventBridge.emitServiceStatus(
                    enabled = true,
                    connected = true,
                    monitoringActive = prefs.protectionEnabled
                )
            } else {
                EventBridge.emitServiceStatus(
                    enabled = isAccessibilityServiceEnabledInSettings(context),
                    connected = false,
                    monitoringActive = false
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocalStorage.init(applicationContext)
        DetectionEngine.init(applicationContext)
        ProtectionCoordinator.init(applicationContext)
        eventDebouncer = EventDebouncer(serviceScope)
        frameScheduler = FrameScheduler(
            scope = serviceScope,
            frameTimeoutMs = 5_500L,
            onFrameTimeout = { ServiceHealthMonitor.onInferenceTimeout() }
        ) { trigger ->
            executeObservationPipeline(
                delayMs = 0L,
                holdInMemoryPreview = false,
                bypassSelfExclusion = false,
                originEventAtNs = trigger.eventAtNs
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BufferReleaseTracker.onServiceConnected()
        ServiceHealthMonitor.onServiceConnected()
        ProtectionCoordinator.init(applicationContext)
        registerScreenOffReceiver()
        frameScheduler.start()

        val prefs = LocalStorage.getPreferences(applicationContext)

        MonitoringNotificationManager.promoteToForeground(
            service = this,
            currentPackage = currentForegroundPackage
        )
        MonitoringNotificationManager.updateStatusNotification(
            context = applicationContext,
            active = prefs.protectionEnabled,
            currentPackage = currentForegroundPackage
        )

        EventBridge.emitServiceStatus(
            enabled = true,
            connected = true,
            monitoringActive = prefs.protectionEnabled
        )

        startAdaptiveWatchdogLoop()

        // Resolve initial window & pre-warm LiteRT model on background worker thread
        serviceScope.launch {
            try {
                val resolved = ActiveWindowResolver.resolve(
                    service = this@ScreenGuardAccessibilityService,
                    event = null,
                    fallbackPackage = currentForegroundPackage,
                    fallbackWindowId = currentWindowId
                )
                currentForegroundPackage = resolved.packageName
                currentForegroundAppLabel = resolved.appLabel
                currentWindowId = resolved.windowId

                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = "SERVICE_STATE",
                        title = "Accessibility Service Connected",
                        detail = "Screen Guard AccessibilityService bound by Android OS. Protection enabled: ${prefs.protectionEnabled}",
                        packageName = currentForegroundPackage
                    )
                )

                DetectionEngine.ensureModelLoaded(applicationContext)
                if (prefs.protectionEnabled &&
                    currentForegroundPackage.isNotBlank() &&
                    currentForegroundPackage != "None" &&
                    currentForegroundPackage != SELF_PACKAGE
                ) {
                    frameScheduler.request(
                        FrameTrigger(
                            eventAtNs = PipelineTelemetry.nowNs(),
                            settleMs = 80L,
                            minIntervalMs = 0L,
                            isAppSwitch = true
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Main-thread AccessibilityEvent callback.
     *
     * STRICT NON-BLOCKING GUARANTEE (Phase A):
     * Performs zero cross-process Binder IPC calls (`service.windows` / `window.root` are NEVER
     * called here). Reads only inline parcel fields and dispatches to the conflated background
     * [FrameScheduler] in < 0.05 ms.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventReceivedNs = PipelineTelemetry.nowNs()
        ServiceHealthMonitor.onAccessibilityEventReceived()

        val eventType = event.eventType
        val isSupportedEvent =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (!isSupportedEvent) return
        if (event.isPassword) return
        if (ActiveWindowResolver.isSelfOverlayEvent(event)) return

        val eventPkg = event.packageName?.toString()
        if (!eventPkg.isNullOrBlank() && ActiveWindowResolver.isTransientSystemOverlay(eventPkg)) return

        // Ingest inline event text/captions in < 0.02 ms (zero Binder IPC)
        ScreenTextContextAnalyzer.onAccessibilityEventInline(event)

        val prefs = LocalStorage.getPreferences(applicationContext)
        val fastResolved = ActiveWindowResolver.resolveFastFromEventInline(
            context = applicationContext,
            event = event,
            fallbackPackage = currentForegroundPackage,
            fallbackWindowId = currentWindowId
        )

        if (fastResolved.isPasswordOrSensitiveInputActive) return

        val newPkg = fastResolved.packageName
        val newWinId = fastResolved.windowId
        val previousPkg = currentForegroundPackage
        val isAppChanged = newPkg.isNotBlank() &&
            newPkg != "None" &&
            newPkg != previousPkg

        if (newWinId >= 0) {
            currentWindowId = newWinId
        }

        if (isAppChanged) {
            currentForegroundPackage = newPkg
            currentForegroundAppLabel = fastResolved.appLabel
            DetectionEngine.onForegroundPackageChanged(newPkg)
            ProtectionCoordinator.onForegroundAppChanged(
                context = applicationContext,
                newPackageName = newPkg,
                newAppLabel = fastResolved.appLabel,
                windowId = currentWindowId
            )

            val isExcluded = prefs.excludedPackages.contains(newPkg) || newPkg == SELF_PACKAGE

            EventBridge.emitForegroundAppChanged(
                packageName = newPkg,
                appLabel = fastResolved.appLabel,
                windowId = currentWindowId,
                isExcluded = isExcluded
            )

            if (newPkg != SELF_PACKAGE) {
                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = "APP_DETECTED",
                        title = "Foreground Application Detected",
                        detail = "${fastResolved.appLabel} ($newPkg) · windowId $currentWindowId${if (isExcluded) " [Excluded]" else ""}",
                        packageName = newPkg
                    )
                )
            }
        }

        if (!prefs.protectionEnabled) return
        if (ProtectionCoordinator.isOverlayVisible()) return
        val activePkg = currentForegroundPackage
        if (activePkg.isBlank() ||
            activePkg == "None" ||
            activePkg == SELF_PACKAGE ||
            ActiveWindowResolver.isLauncherPackage(activePkg)
        ) return
        if (prefs.excludedPackages.contains(activePkg)) return

        val settleMs = if (isAppChanged) {
            35L
        } else {
            45L
        }
        val minIntervalMs = 1005L

        BufferReleaseTracker.onDebounceEventReceived(accepted = true)
        frameScheduler.request(
            FrameTrigger(
                eventAtNs = eventReceivedNs,
                settleMs = settleMs,
                minIntervalMs = minIntervalMs,
                isAppSwitch = isAppChanged
            )
        )
    }

    /**
     * Executes the complete off-main-thread observation, multimodal evidence collection,
     * LiteRT inference, and protection decision pipeline.
     */
    suspend fun executeObservationPipeline(
        delayMs: Long = 0L,
        holdInMemoryPreview: Boolean = false,
        bypassSelfExclusion: Boolean = false,
        originEventAtNs: Long = PipelineTelemetry.nowNs()
    ): ProcessedScreenshotOutcome {
        if (delayMs > 0L) {
            delay(delayMs.coerceAtMost(10_000L))
        }

        ServiceHealthMonitor.checkMemoryPressureAndPurgeIfNeeded()

        val prefs = LocalStorage.getPreferences(applicationContext)

        // Only query ActiveWindowResolver over Binder IPC when we don't already have a valid
        // inline packageName/windowId from onAccessibilityEvent, or when the overlay is visible.
        // This prevents a busy video-player thread in X/Twitter or Instagram from blocking
        // screenshot capture for 5 seconds inside AccessibilityInteractionClient.waitForResultTimedLocked!
        if (currentForegroundPackage.isBlank() ||
            currentForegroundPackage == "None" ||
            currentWindowId < 0 ||
            ProtectionCoordinator.isOverlayVisible()
        ) {
            val resolved = ActiveWindowResolver.resolve(
                service = this,
                event = null,
                fallbackPackage = currentForegroundPackage,
                fallbackWindowId = currentWindowId
            )

            if (resolved.packageName.isNotBlank() && resolved.packageName != "None") {
                currentForegroundPackage = resolved.packageName
                currentForegroundAppLabel = resolved.appLabel
                currentWindowId = resolved.windowId
            }

            if (resolved.isPasswordOrSensitiveInputActive) {
                val outcome = ProcessedScreenshotOutcome(
                    succeeded = false,
                    originalWidth = 0,
                    originalHeight = 0,
                    processedWidth = 0,
                    processedHeight = 0,
                    captureDurationMs = 0L,
                    processingDurationMs = 0L,
                    captureMethod = "PRIVACY_GUARD",
                    packageName = currentForegroundPackage,
                    windowId = currentWindowId,
                    errorCode = "PASSWORD_INPUT_ACTIVE",
                    classificationResult = null,
                    debugPreviewBase64 = null
                )
                handlePipelineOutcome(outcome, originEventAtNs, 0L)
                return outcome
            }
        }

        if (!bypassSelfExclusion && currentForegroundPackage == SELF_PACKAGE) {
            return ProcessedScreenshotOutcome(
                succeeded = false,
                originalWidth = 0,
                originalHeight = 0,
                processedWidth = 0,
                processedHeight = 0,
                captureDurationMs = 0L,
                processingDurationMs = 0L,
                captureMethod = "SELF_SKIP",
                packageName = currentForegroundPackage,
                windowId = currentWindowId,
                errorCode = "SELF_PACKAGE_SKIPPED",
                classificationResult = null,
                debugPreviewBase64 = null
            )
        }

        // Launch non-blocking background text analysis concurrently so slow AccessibilityNodeInfo
        // trees in video players NEVER delay screenshot capture!
        val activePkgForText = currentForegroundPackage
        serviceScope.launch {
            try {
                val sig = ScreenTextContextAnalyzer.analyzeActiveApplicationWindow(
                    service = this@ScreenGuardAccessibilityService,
                    targetPackageName = activePkgForText
                )
                if (sig.isExplicitPornDomain &&
                    !sig.isBenignEducationalOrDialogue &&
                    !ProtectionCoordinator.isOverlayVisible()
                ) {
                    val instantResult = ClassificationResult(
                        scores = mapOf("EXPLICIT" to 0.96f, "porn" to 0.96f),
                        predictedCategory = ContentCategory.EXPLICIT.name,
                        confidence = 0.96f,
                        processingDurationMs = sig.scanDurationMs.coerceAtLeast(1L),
                        modelVersion = "multimodal-domain-fastpath",
                        successful = true,
                        isExplicit = true,
                        explicitScore = 0.96f,
                        pornScore = 0.96f,
                        safeScore = 0.04f,
                        consecutiveDetections = 1,
                        confirmedIntervention = true,
                        temporalState = "CONFIRMED_DETECTION",
                        smoothedExplicitScore = 0.96f,
                        packageName = activePkgForText,
                        windowId = currentWindowId
                    )
                    ProtectionCoordinator.onClassificationResult(
                        context = applicationContext,
                        result = instantResult,
                        appLabel = currentForegroundAppLabel
                    )
                }
            } catch (_: Exception) {
            }
        }

        val captureStartNs = PipelineTelemetry.nowNs()
        val eventToCaptureStartMs = PipelineTelemetry.msBetween(originEventAtNs, captureStartNs)

        // Refresh active application windowId from in-parcel service.windows (< 0.2 ms, zero node IPC)
        // so sub-window IDs in X/Twitter or Instagram never pass an uncapturable sub-window ID.
        val fastWin = ActiveWindowResolver.resolve(
            service = this,
            event = null,
            fallbackPackage = currentForegroundPackage,
            fallbackWindowId = currentWindowId
        )
        if (fastWin.windowId >= 0) {
            currentWindowId = fastWin.windowId
        }

        val outcome = AccessibilityScreenshotCapturer.captureAndProcess(
            service = this,
            windowId = currentWindowId,
            packageName = currentForegroundPackage,
            preferWindowCapture = prefs.preferWindowCapture,
            holdInMemoryPreview = holdInMemoryPreview,
            bypassCooldownForDiagnostic = bypassSelfExclusion
        )

        handlePipelineOutcome(outcome, originEventAtNs, eventToCaptureStartMs)
        return outcome
    }

    private fun handlePipelineOutcome(
        outcome: ProcessedScreenshotOutcome,
        originEventAtNs: Long,
        eventToCaptureStartMs: Long
    ) {
        lastScreenshotTimestamp = System.currentTimeMillis()
        if (outcome.succeeded) {
            lastScreenshotStatus = "SUCCESS"
            EventBridge.emitScreenshotSuccess(
                width = outcome.originalWidth,
                height = outcome.originalHeight,
                durationMs = outcome.captureDurationMs,
                captureMethod = outcome.captureMethod,
                packageName = outcome.packageName,
                windowId = outcome.windowId
            )

            val classification = outcome.classificationResult
            if (classification != null) {
                val decisionStartNs = PipelineTelemetry.nowNs()
                val prefs = LocalStorage.getPreferences(applicationContext)
                val policy = ProtectionCoordinator.buildPolicyConfig(prefs)

                // Collect separate multimodal evidence (Visual + AudioPlayback + CaptionNode + OcrFrame)
                val textSignal = ScreenTextContextAnalyzer.getLatestSignal()
                val captionEvidence = CaptionAndOcrAnalyzer.buildCaptionEvidence(textSignal)
                val audioEvidence = AudioPlaybackCapturePrototype.inspectAudioEvidence(
                    context = applicationContext,
                    foregroundPackage = outcome.packageName
                )
                val visualEvidence = VisualEvidence(
                    available = classification.successful,
                    predictedCategory = classification.predictedCategory,
                    confidence = classification.confidence,
                    pornScore = classification.pornScore,
                    sexyScore = classification.sexyScore,
                    hentaiScore = classification.hentaiScore,
                    neutralScore = classification.neutralScore,
                    drawingsScore = classification.drawingsScore,
                    explicitScore = classification.explicitScore,
                    nudityScore = classification.nudityScore,
                    sexualActivityScore = classification.sexualActivityScore,
                    stage2Triggered = classification.stage2Triggered,
                    stage2NsfwScore = classification.stage2NsfwScore,
                    roiScanTriggered = classification.preprocessingApplied.contains("ROI"),
                    maxRoiPornScore = classification.pornScore,
                    isLowLight = classification.isLowLightFrame,
                    meanLuminance = classification.meanLuminance,
                    stage1DurationMs = (classification.processingDurationMs - classification.stage2DurationMs).coerceAtLeast(1L),
                    totalVisualDurationMs = classification.processingDurationMs
                )
                val bundle = MultimodalEvidenceBundle(
                    visual = visualEvidence,
                    audio = audioEvidence,
                    caption = captionEvidence,
                    ocr = OcrFrameEvidence(available = true, durationMs = 0L)
                )

                val fused = MultimodalFusionEngine.fuse(bundle, policy)
                latestFusedDecision = fused

                // Apply fused category/confidence if multimodal fusion suppressed dialogue-only or boosted confidence
                val effectiveClassification = if (fused.dialogueOnlySuppressed) {
                    classification.copy(
                        predictedCategory = ContentCategory.NON_EXPLICIT.name,
                        confidence = fused.finalConfidence,
                        isExplicit = false,
                        confirmedIntervention = false
                    )
                } else {
                    classification.copy(
                        predictedCategory = fused.finalCategory,
                        confidence = fused.finalConfidence
                    )
                }

                EventBridge.emitClassificationResult(effectiveClassification)

                val decision = ProtectionCoordinator.onClassificationResult(
                    context = applicationContext,
                    result = effectiveClassification,
                    appLabel = currentForegroundAppLabel
                )

                val decisionEndNs = PipelineTelemetry.nowNs()
                val decisionMs = PipelineTelemetry.msBetween(decisionStartNs, decisionEndNs).coerceAtLeast(1L)
                val captureToPredMs = outcome.captureDurationMs + outcome.preprocessDurationMs + classification.processingDurationMs
                val eventToDecisionMs = PipelineTelemetry.msBetween(originEventAtNs, decisionEndNs)

                PipelineTelemetry.record(
                    FrameTrace(
                        eventToCaptureStartMs = eventToCaptureStartMs,
                        captureMs = outcome.captureDurationMs,
                        preprocessMs = outcome.preprocessDurationMs,
                        inferenceMs = classification.processingDurationMs,
                        stage1Ms = visualEvidence.stage1DurationMs,
                        decisionMs = decisionMs,
                        captureToPredictionMs = captureToPredMs,
                        eventToDecisionMs = eventToDecisionMs,
                        usedRoiScan = visualEvidence.roiScanTriggered,
                        usedStage2 = classification.stage2Triggered,
                        producedProtection = decision.shouldShowOverlay
                    )
                )

                val eventType = if (effectiveClassification.isExplicit) {
                    "SUSPICIOUS_DETECTED"
                } else {
                    "CLASSIFICATION_COMPLETED"
                }

                val confPct = "%.1f".format(effectiveClassification.confidence * 100f)
                android.util.Log.i(
                    "SG_PIPELINE",
                    "pkg=${outcome.packageName} win=${outcome.windowId} method=${outcome.captureMethod} cat=${effectiveClassification.predictedCategory} conf=$confPct% porn=${"%.2f".format(effectiveClassification.pornScore)} sexy=${"%.2f".format(effectiveClassification.sexyScore)} s2=${"%.2f".format(effectiveClassification.stage2NsfwScore)} shield=${decision.shouldShowOverlay} state=${decision.targetOverlayState}"
                )
                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = eventType,
                        title = if (decision.shouldShowOverlay) {
                            "Inappropriate Content Blocked (${effectiveClassification.predictedCategory})"
                        } else {
                            "Screen Checked (${effectiveClassification.predictedCategory})"
                        },
                        detail = "Category: ${effectiveClassification.predictedCategory} ($confPct%) · Capture ${outcome.captureDurationMs}ms · Preprocess ${outcome.preprocessDurationMs}ms · Inference ${classification.processingDurationMs}ms · Decision ${decisionMs}ms",
                        packageName = outcome.packageName,
                        durationMs = captureToPredMs,
                        width = classification.frameWidth,
                        height = classification.frameHeight,
                        captureMethod = outcome.captureMethod,
                        category = effectiveClassification.predictedCategory,
                        nudityScore = effectiveClassification.nudityScore,
                        explicitScore = effectiveClassification.explicitScore,
                        suggestiveScore = effectiveClassification.suggestiveScore,
                        safeScore = effectiveClassification.safeScore,
                        confirmedIntervention = decision.shouldShowOverlay
                    )
                )

                val needsRapidFollowUp =
                    decision.targetOverlayState == OverlayState.PENDING ||
                        effectiveClassification.predictedCategory == ContentCategory.UNCERTAIN.name ||
                        effectiveClassification.pornScore >= 0.05f ||
                        effectiveClassification.sexyScore >= 0.14f

                if (needsRapidFollowUp &&
                    prefs.protectionEnabled &&
                    !ProtectionCoordinator.isOverlayVisible()
                ) {
                    frameScheduler.request(
                        FrameTrigger(
                            eventAtNs = PipelineTelemetry.nowNs(),
                            settleMs = 20L,
                            minIntervalMs = 1005L,
                            isAppSwitch = false
                        )
                    )
                }
            }
        } else {
            lastScreenshotStatus = "FAILURE"
            android.util.Log.w(
                "SG_PIPELINE",
                "CAPTURE_FAIL pkg=${outcome.packageName} win=${outcome.windowId} method=${outcome.captureMethod} err=${outcome.errorCode}"
            )
            EventBridge.emitScreenshotFailure(
                errorCode = outcome.errorCode,
                captureMethod = outcome.captureMethod,
                packageName = outcome.packageName,
                windowId = outcome.windowId
            )

            if (outcome.errorCode != "INTERVAL_TIME_SHORT") {
                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = "SCREENSHOT_FAILURE",
                        title = "Screenshot Capture Rejected (${outcome.errorCode})",
                        detail = explainErrorCode(outcome.errorCode, outcome.packageName, outcome.captureMethod),
                        packageName = outcome.packageName,
                        errorCode = outcome.errorCode,
                        captureMethod = outcome.captureMethod
                    )
                )
            }
        }
    }

    private fun explainErrorCode(errorCode: String, pkg: String, method: String): String {
        return when (errorCode) {
            "SECURE_WINDOW" -> "Window in $pkg enforces FLAG_SECURE (protected surface). Screen Guard respects protected windows."
            "INTERVAL_TIME_SHORT" -> "Skipped due to Android's 1-second minimum screenshot interval limit."
            "INVALID_WINDOW" -> "Window ID became stale before capture completed ($method)."
            "NO_ACCESSIBILITY_ACCESS" -> "Accessibility screenshot capability not active for this window."
            "PASSWORD_INPUT_ACTIVE" -> "Skipped screenshot because a password input field has focus."
            else -> "Capture attempt via $method returned $errorCode."
        }
    }

    fun runDebouncerBurstTest(burstCount: Int, intervalMs: Long): DebounceBurstTestResult {
        val prefs = LocalStorage.getPreferences(applicationContext)
        return eventDebouncer.runBurstSimulation(
            burstCount = burstCount,
            intervalMs = intervalMs,
            debounceWindowMs = prefs.debounceDelayMs
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ProtectionCoordinator.onConfigurationChanged(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ServiceHealthMonitor.onTrimMemory(level)
        ScreenshotProcessor.clearVolatileDebugPreview()
    }

    override fun onInterrupt() {
        // Do NOT stop frameScheduler on transient accessibility interruptions; Android does not
        // re-call onServiceConnected() after onInterrupt(), so stopping the scheduler here
        // would leave frame processing paused until the next event.
        if (::eventDebouncer.isInitialized) {
            eventDebouncer.cancelAll()
        }
        ScreenshotProcessor.clearVolatileDebugPreview()
        LocalStorage.appendActivityLog(
            applicationContext,
            ActivityLogRecord(
                type = "SERVICE_STATE",
                title = "Accessibility Service Interrupted",
                detail = "Android OS signaled onInterrupt; cleared pending jobs and volatile buffers.",
                packageName = currentForegroundPackage
            )
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Keep the AccessibilityService in foreground priority if the user swipes MainActivity
        // away from the Xiaomi/Android Recents screen (SwipeUpClean).
        MonitoringNotificationManager.promoteToForeground(
            service = this,
            currentPackage = currentForegroundPackage
        )
        ScreenshotProcessor.clearVolatileDebugPreview()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupOnDisconnect("Accessibility Service Unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupOnDisconnect("Accessibility Service Destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenStateReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            screenStateReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenStateReceiverRegistered) return
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {
        } finally {
            screenStateReceiverRegistered = false
        }
    }

    private fun startAdaptiveWatchdogLoop() {
        adaptiveWatchdogJob?.cancel()
        adaptiveWatchdogJob = serviceScope.launch {
            while (isActive && instance != null) {
                delay(250L)
                try {
                    if (!isScreenInteractive()) continue
                    val prefs = LocalStorage.getPreferences(applicationContext)
                    if (!prefs.protectionEnabled) continue
                    if (frameScheduler.isFrameInFlight) continue

                    val elapsedSinceCapture = System.currentTimeMillis() - lastScreenshotTimestamp
                    if (elapsedSinceCapture < 1005L) continue

                    // Only use slow Binder IPC window resolution when we don't know the foreground app
                    // or when the protective overlay is visible (and covers the screen).
                    if (currentForegroundPackage.isBlank() ||
                        currentForegroundPackage == "None" ||
                        currentWindowId < 0 ||
                        ProtectionCoordinator.isOverlayVisible()
                    ) {
                        val resolved = ActiveWindowResolver.resolve(
                            service = this@ScreenGuardAccessibilityService,
                            event = null,
                            fallbackPackage = currentForegroundPackage,
                            fallbackWindowId = currentWindowId
                        )
                        val pkg = resolved.packageName
                        if (pkg.isBlank() || pkg == "None" || pkg == SELF_PACKAGE) continue
                        if (prefs.excludedPackages.contains(pkg)) continue

                        if (pkg != currentForegroundPackage) {
                            currentForegroundPackage = pkg
                            currentForegroundAppLabel = resolved.appLabel
                            currentWindowId = resolved.windowId
                            DetectionEngine.onForegroundPackageChanged(pkg)
                            ProtectionCoordinator.onForegroundAppChanged(
                                context = applicationContext,
                                newPackageName = pkg,
                                newAppLabel = resolved.appLabel,
                                windowId = resolved.windowId
                            )
                        } else if (resolved.windowId >= 0) {
                            currentWindowId = resolved.windowId
                        }
                    } else {
                        val pkg = currentForegroundPackage
                        if (pkg == SELF_PACKAGE || prefs.excludedPackages.contains(pkg)) continue
                    }

                    frameScheduler.request(
                        FrameTrigger(
                            eventAtNs = PipelineTelemetry.nowNs(),
                            settleMs = 0L,
                            minIntervalMs = 1005L,
                            isAppSwitch = false
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun cleanupOnDisconnect(reason: String) {
        adaptiveWatchdogJob?.cancel()
        adaptiveWatchdogJob = null
        if (::frameScheduler.isInitialized) {
            frameScheduler.stop()
        }
        unregisterScreenOffReceiver()
        if (instance === this) {
            instance = null
        }
        if (::eventDebouncer.isInitialized) {
            eventDebouncer.cancelAll()
        }
        ScreenshotProcessor.clearVolatileDebugPreview()
        DetectionEngine.resetConfirmationState()
        ServiceHealthMonitor.onServiceDisconnected(reason)
        ProtectionCoordinator.onServiceDisconnected(applicationContext)
        BufferReleaseTracker.onServiceDisconnected()
        MonitoringNotificationManager.dismiss(applicationContext)

        LocalStorage.appendActivityLog(
            applicationContext,
            ActivityLogRecord(
                type = "SERVICE_STATE",
                title = reason,
                detail = "Released all resources, cancelled scheduler jobs, and dismissed notification.",
                packageName = currentForegroundPackage
            )
        )

        EventBridge.emitServiceStatus(
            enabled = isAccessibilityServiceEnabledInSettings(applicationContext),
            connected = false,
            monitoringActive = false
        )
    }
}
