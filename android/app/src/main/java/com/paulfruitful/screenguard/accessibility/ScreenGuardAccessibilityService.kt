package com.paulfruitful.screenguard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.paulfruitful.screenguard.bridge.EventBridge
import com.paulfruitful.screenguard.capture.AccessibilityScreenshotCapturer
import com.paulfruitful.screenguard.capture.BufferReleaseTracker
import com.paulfruitful.screenguard.capture.ProcessedScreenshotOutcome
import com.paulfruitful.screenguard.capture.ScreenshotProcessor
import com.paulfruitful.screenguard.classifier.ClassifierEvaluator
import com.paulfruitful.screenguard.classifier.DetectionEngine
import com.paulfruitful.screenguard.protection.OverlayState
import com.paulfruitful.screenguard.protection.ProtectionCoordinator
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Core Android [AccessibilityService] for Screen Guard.
 *
 * Processing Pipeline:
 * Accessibility Event
 *   -> Event Filter & Privacy Guard (ignores password inputs, keyboards, excluded apps)
 *   -> Event Debouncer
 *   -> Active Window Resolver
 *   -> Screenshot Capture (takeScreenshotOfWindow on API 34+ / takeScreenshot on API 30+)
 *   -> Image Preprocessor (HardwareBuffer -> 224x224 Bitmap -> immediate HardwareBuffer close)
 *   -> Detection Engine (MockContentClassifier / replaceable ContentClassifier)
 *   -> Result Handler (LocalStorage metadata log + EventBridge emission to React Native)
 */
class ScreenGuardAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private lateinit var eventDebouncer: EventDebouncer
    private var screenStateReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (::eventDebouncer.isInitialized) {
                    eventDebouncer.cancelAll()
                }
                ScreenshotProcessor.clearVolatileDebugPreview()
                DetectionEngine.resetConfirmationState()
                ProtectionCoordinator.onScreenTurnedOff(applicationContext)
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

        fun getActiveInstance(): ScreenGuardAccessibilityService? = instance

        fun isServiceConnected(): Boolean = instance != null

        /**
         * Checks Android Secure Settings to determine if Screen Guard's AccessibilityService
         * is enabled by the user.
         */
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

        /**
         * Allows the Testing screen to verify graceful service disconnection cleanup
         * and immediate reconnection synchronization when the service is bound.
         */
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BufferReleaseTracker.onServiceConnected()
        ProtectionCoordinator.init(applicationContext)
        registerScreenOffReceiver()

        val prefs = LocalStorage.getPreferences(applicationContext)
        val resolved = ActiveWindowResolver.resolve(
            service = this,
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

        // Pre-warm LiteRT model and execute initial on-device evaluation verification on background thread
        serviceScope.launch(Dispatchers.Default) {
            try {
                DetectionEngine.ensureModelLoaded(applicationContext)
                val report = ClassifierEvaluator.runFullEvaluationSuite(
                    context = applicationContext,
                    includeDelegateBenchmark = true
                )
                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = "CLASSIFICATION_COMPLETED",
                        title = "LiteRT NSFW Model Initialized & Benchmarked",
                        detail = "Model: ${report.modelId} (${report.initDurationMs}ms init) · Test Split Precision ${"%.1f".format(report.testSplitMetrics.precision * 100f)}% · Recall ${"%.1f".format(report.testSplitMetrics.recall * 100f)}% · FPR ${"%.1f".format(report.testSplitMetrics.falsePositiveRate * 100f)}% · Avg ${"%.1f".format(report.avgLatencyMs)}ms (P95 ${report.p95LatencyMs}ms)",
                        packageName = currentForegroundPackage,
                        durationMs = report.p50LatencyMs
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isScreenInteractive()) return

        val eventType = event.eventType
        val isSupportedEvent =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (!isSupportedEvent) return

        // Privacy & System Overlay Guard: immediately ignore password events, transient system UI / IME events,
        // and any events originating from our own protective overlay view
        if (event.isPassword) return
        if (ActiveWindowResolver.isSelfOverlayEvent(event)) return
        val eventPkg = event.packageName?.toString()
        if (!eventPkg.isNullOrBlank() && ActiveWindowResolver.isTransientSystemOverlay(eventPkg)) return

        val prefs = LocalStorage.getPreferences(applicationContext)

        val resolved = ActiveWindowResolver.resolve(
            service = this,
            event = event,
            fallbackPackage = currentForegroundPackage,
            fallbackWindowId = currentWindowId
        )

        // If a content/scroll event came from a background or PiP window that does not match the resolved active foreground package, ignore it
        if ((eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            !eventPkg.isNullOrBlank() &&
            resolved.packageName.isNotBlank() &&
            resolved.packageName != "None" &&
            eventPkg != resolved.packageName
        ) {
            return
        }

        if (resolved.isPasswordOrSensitiveInputActive) {
            return
        }

        val newPkg = resolved.packageName
        val newWinId = resolved.windowId
        val previousPkg = currentForegroundPackage
        val isAppChanged = newPkg.isNotBlank() &&
            newPkg != "None" &&
            newPkg != previousPkg

        currentWindowId = newWinId

        if (isAppChanged) {
            currentForegroundPackage = newPkg
            currentForegroundAppLabel = resolved.appLabel
            DetectionEngine.onForegroundPackageChanged(newPkg)
            ProtectionCoordinator.onForegroundAppChanged(
                context = applicationContext,
                newPackageName = newPkg,
                newAppLabel = resolved.appLabel,
                windowId = newWinId
            )

            val isExcluded = prefs.excludedPackages.contains(newPkg) ||
                newPkg == SELF_PACKAGE

            EventBridge.emitForegroundAppChanged(
                packageName = newPkg,
                appLabel = resolved.appLabel,
                windowId = newWinId,
                isExcluded = isExcluded
            )

            if (newPkg != SELF_PACKAGE) {
                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = "APP_DETECTED",
                        title = "Foreground Application Detected",
                        detail = "${resolved.appLabel} ($newPkg) · windowId $newWinId${if (isExcluded) " [Excluded]" else ""}",
                        packageName = newPkg
                    )
                )
            }

            if (prefs.protectionEnabled) {
                MonitoringNotificationManager.updateStatusNotification(
                    context = applicationContext,
                    active = true,
                    currentPackage = newPkg
                )
            }
        }

        // Do not schedule automatic background screenshots when protection is paused,
        // when the full-screen protective overlay is already covering the screen,
        // when Screen Guard itself is in the foreground, or when the foreground app is excluded.
        if (!prefs.protectionEnabled) return
        if (ProtectionCoordinator.isOverlayVisible()) return
        val activePkg = currentForegroundPackage
        if (activePkg.isBlank() || activePkg == "None" || activePkg == SELF_PACKAGE) return
        if (prefs.excludedPackages.contains(activePkg)) return

        val adaptiveCooldown = DetectionEngine.computeRecommendedCooldownMs(
            prefs.samplingIntervalMs
        )

        eventDebouncer.schedule(
            isImmediateAppSwitch = isAppChanged,
            debounceDelayMs = prefs.debounceDelayMs,
            minCooldownMs = adaptiveCooldown
        ) {
            executeObservationPipeline(
                holdInMemoryPreview = false,
                bypassSelfExclusion = false
            )
        }
    }

    /**
     * Executes the screenshot capture + preprocessing + classification + result handling pipeline.
     * Can be triggered either by the debounced event pipeline or on-demand from the Testing screen.
     */
    suspend fun executeObservationPipeline(
        delayMs: Long = 0L,
        holdInMemoryPreview: Boolean = false,
        bypassSelfExclusion: Boolean = false
    ): ProcessedScreenshotOutcome {
        if (delayMs > 0L) {
            delay(delayMs.coerceAtMost(10_000L))
        }

        val prefs = LocalStorage.getPreferences(applicationContext)
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
            handlePipelineOutcome(outcome)
            return outcome
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

        val outcome = AccessibilityScreenshotCapturer.captureAndProcess(
            service = this,
            windowId = currentWindowId,
            packageName = currentForegroundPackage,
            preferWindowCapture = prefs.preferWindowCapture,
            holdInMemoryPreview = holdInMemoryPreview,
            bypassCooldownForDiagnostic = bypassSelfExclusion
        )

        handlePipelineOutcome(outcome)
        return outcome
    }

    private fun handlePipelineOutcome(outcome: ProcessedScreenshotOutcome) {
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

            LocalStorage.appendActivityLog(
                applicationContext,
                ActivityLogRecord(
                    type = "SCREENSHOT_SUCCESS",
                    title = "Screenshot Captured & Released",
                    detail = "Captured ${outcome.originalWidth}×${outcome.originalHeight} via ${outcome.captureMethod} in ${outcome.captureDurationMs}ms; downsampled to ${outcome.processedWidth}×${outcome.processedHeight} and closed HardwareBuffer.",
                    packageName = outcome.packageName,
                    durationMs = outcome.captureDurationMs,
                    width = outcome.originalWidth,
                    height = outcome.originalHeight,
                    captureMethod = outcome.captureMethod
                )
            )

            val classification = outcome.classificationResult
            if (classification != null) {
                EventBridge.emitClassificationResult(classification)

                val eventType = if (classification.isExplicit) {
                    "SUSPICIOUS_DETECTED"
                } else {
                    "CLASSIFICATION_COMPLETED"
                }

                val title = when {
                    classification.confirmedIntervention ->
                        "Confirmed Multi-Frame Detection (${classification.predictedCategory})"
                    classification.isExplicit ->
                        "Suspicious Frame Flagged: ${classification.predictedCategory} (${classification.consecutiveDetections} consecutive)"
                    classification.isDuplicateSkipped ->
                        "Duplicate Static Frame Skipped (dHash ${classification.frameHash})"
                    else ->
                        "On-Device LiteRT Classification: ${classification.predictedCategory}"
                }

                val confPct = "%.1f".format(classification.confidence * 100f)
                val neutralPct = "%.1f".format(classification.neutralScore * 100f)
                val pornPct = "%.1f".format(classification.pornScore * 100f)
                val sexyPct = "%.1f".format(classification.sexyScore * 100f)
                val drawingsPct = "%.1f".format(classification.drawingsScore * 100f)
                val hentaiPct = "%.1f".format(classification.hentaiScore * 100f)

                LocalStorage.appendActivityLog(
                    applicationContext,
                    ActivityLogRecord(
                        type = eventType,
                        title = title,
                        detail = "Category: ${classification.predictedCategory} ($confPct%) · State: ${classification.temporalState} · neutral $neutralPct% · porn $pornPct% · sexy $sexyPct% · drawings $drawingsPct% · hentai $hentaiPct% · ${classification.processingDurationMs}ms (${classification.delegateUsed})",
                        packageName = outcome.packageName,
                        durationMs = classification.processingDurationMs,
                        width = classification.frameWidth,
                        height = classification.frameHeight,
                        captureMethod = outcome.captureMethod,
                        category = classification.predictedCategory,
                        nudityScore = classification.nudityScore,
                        explicitScore = classification.explicitScore,
                        suggestiveScore = classification.suggestiveScore,
                        safeScore = classification.safeScore,
                        confirmedIntervention = classification.confirmedIntervention
                    )
                )

                val prefs = LocalStorage.getPreferences(applicationContext)
                val decision = ProtectionCoordinator.onClassificationResult(
                    context = applicationContext,
                    result = classification,
                    appLabel = currentForegroundAppLabel
                )
                // If a borderline positive detection entered PENDING confirmation on a static screen,
                // automatically schedule a follow-up verification capture in 1150ms even if the user stops scrolling.
                if (decision.targetOverlayState == OverlayState.PENDING &&
                    prefs.protectionEnabled &&
                    !ProtectionCoordinator.isOverlayVisible()
                ) {
                    eventDebouncer.schedule(
                        isImmediateAppSwitch = false,
                        debounceDelayMs = 1150L,
                        minCooldownMs = 1100L
                    ) {
                        executeObservationPipeline(
                            holdInMemoryPreview = false,
                            bypassSelfExclusion = false
                        )
                    }
                }
                if (classification.confirmedIntervention &&
                    prefs.interventionBehavior == "STATUS_NOTIFICATION"
                ) {
                    MonitoringNotificationManager.updateStatusNotification(
                        context = applicationContext,
                        active = prefs.protectionEnabled,
                        currentPackage = outcome.packageName,
                        alertMessage = "Screen Guard: Multi-frame threshold reached (${outcome.packageName})"
                    )
                }
            }
        } else {
            lastScreenshotStatus = "FAILURE"
            EventBridge.emitScreenshotFailure(
                errorCode = outcome.errorCode,
                captureMethod = outcome.captureMethod,
                packageName = outcome.packageName,
                windowId = outcome.windowId
            )

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

    override fun onInterrupt() {
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

    private fun cleanupOnDisconnect(reason: String) {
        unregisterScreenOffReceiver()
        if (instance === this) {
            instance = null
        }
        if (::eventDebouncer.isInitialized) {
            eventDebouncer.cancelAll()
        }
        ScreenshotProcessor.clearVolatileDebugPreview()
        DetectionEngine.resetConfirmationState()
        ProtectionCoordinator.onServiceDisconnected(applicationContext)
        BufferReleaseTracker.onServiceDisconnected()
        MonitoringNotificationManager.dismiss(applicationContext)

        LocalStorage.appendActivityLog(
            applicationContext,
            ActivityLogRecord(
                type = "SERVICE_STATE",
                title = reason,
                detail = "Released all resources, cancelled debouncer jobs, and dismissed notification.",
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
