package com.paulfruitful.screenguard.bridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.paulfruitful.screenguard.accessibility.EventDebouncer
import com.paulfruitful.screenguard.accessibility.MonitoringNotificationManager
import com.paulfruitful.screenguard.accessibility.ScreenGuardAccessibilityService
import com.paulfruitful.screenguard.accessibility.ServiceHealthMonitor
import com.paulfruitful.screenguard.capture.BufferReleaseTracker
import com.paulfruitful.screenguard.capture.ProcessedScreenshotOutcome
import com.paulfruitful.screenguard.capture.ScreenshotProcessor
import com.paulfruitful.screenguard.classifier.ClassifierEvaluator
import com.paulfruitful.screenguard.classifier.DelegateBenchmarkResult
import com.paulfruitful.screenguard.classifier.DetectionEngine
import com.paulfruitful.screenguard.classifier.FullEvaluationReport
import com.paulfruitful.screenguard.classifier.SingleSampleEvalResult
import com.paulfruitful.screenguard.classifier.SplitEvaluationMetrics
import com.paulfruitful.screenguard.classifier.TemporalDecisionEngine
import com.paulfruitful.screenguard.pipeline.PipelineTelemetry
import com.paulfruitful.screenguard.protection.ProtectionCoordinator
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.AppCompatibilityEntry
import com.paulfruitful.screenguard.storage.LocalStorage
import com.paulfruitful.screenguard.storage.UserPreferencesData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenGuardModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

    private val moduleScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val fallbackDebouncer = EventDebouncer(moduleScope)

    init {
        LocalStorage.init(reactContext.applicationContext)
        DetectionEngine.init(reactContext.applicationContext)
        ProtectionCoordinator.init(reactContext.applicationContext)
        EventBridge.attachReactContext(reactContext)
        moduleScope.launch {
            DetectionEngine.ensureModelLoaded(reactContext.applicationContext)
        }
    }

    override fun getName(): String = "ScreenGuardModule"

    override fun invalidate() {
        EventBridge.detachReactContext()
        moduleScope.cancel()
        super.invalidate()
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for React Native NativeEventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Double) {
        // Required for React Native NativeEventEmitter
    }

    @ReactMethod
    fun isAccessibilityPermissionEnabled(promise: Promise) {
        try {
            val enabled = ScreenGuardAccessibilityService.isAccessibilityServiceEnabledInSettings(
                reactContext.applicationContext
            )
            promise.resolve(enabled)
        } catch (e: Exception) {
            promise.reject("ERR_PERMISSION_CHECK", e.message, e)
        }
    }

    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERR_OPEN_SETTINGS", e.message, e)
        }
    }

    @ReactMethod
    fun canDrawOverlays(promise: Promise) {
        try {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(reactContext.applicationContext)
            promise.resolve(granted)
        } catch (e: Exception) {
            promise.reject("ERR_OVERLAY_CHECK", e.message, e)
        }
    }

    @ReactMethod
    fun openOverlaySettings(promise: Promise) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${reactContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${reactContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            try {
                reactContext.startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                reactContext.startActivity(fallback)
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERR_OPEN_OVERLAY_SETTINGS", e.message, e)
        }
    }

    @ReactMethod
    fun getProtectionSummary(promise: Promise) {
        try {
            val summary = ProtectionCoordinator.getActivitySummary(reactContext.applicationContext)
            promise.resolve(EventBridge.protectionSummaryToWritableMap(summary))
        } catch (e: Exception) {
            promise.reject("ERR_GET_PROTECTION_SUMMARY", e.message, e)
        }
    }

    @ReactMethod
    fun dismissActiveOverlay(promise: Promise) {
        try {
            val summary = ProtectionCoordinator.dismissActiveOverlayFromUi(reactContext.applicationContext)
            promise.resolve(EventBridge.protectionSummaryToWritableMap(summary))
        } catch (e: Exception) {
            promise.reject("ERR_DISMISS_OVERLAY", e.message, e)
        }
    }

    @ReactMethod
    fun triggerOverlayTest(
        category: String,
        delayMs: Double,
        autoHideDurationMs: Double,
        promise: Promise
    ) {
        moduleScope.launch {
            try {
                val safeDelay = delayMs.toLong().coerceIn(0L, 15_000L)
                if (safeDelay > 0L) {
                    delay(safeDelay)
                }
                val summary = ProtectionCoordinator.triggerDiagnosticOverlayTest(
                    context = reactContext.applicationContext,
                    testCategory = category.ifBlank { "EXPLICIT" },
                    autoHideDurationMs = autoHideDurationMs.toLong().coerceIn(0L, 30_000L)
                )
                promise.resolve(EventBridge.protectionSummaryToWritableMap(summary))
            } catch (e: Exception) {
                promise.reject("ERR_TRIGGER_OVERLAY_TEST", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getMonitoringStatus(promise: Promise) {
        try {
            promise.resolve(buildMonitoringStatusMap())
        } catch (e: Exception) {
            promise.reject("ERR_GET_STATUS", e.message, e)
        }
    }

    @ReactMethod
    fun setProtectionEnabled(enabled: Boolean, promise: Promise) {
        try {
            val appCtx = reactContext.applicationContext
            val updated = LocalStorage.setProtectionEnabled(appCtx, enabled)
            if (!enabled) {
                ProtectionCoordinator.onProtectionPaused(appCtx)
            }
            val serviceConnected = ScreenGuardAccessibilityService.isServiceConnected()
            val permissionGranted = ScreenGuardAccessibilityService
                .isAccessibilityServiceEnabledInSettings(appCtx)

            MonitoringNotificationManager.updateStatusNotification(
                context = appCtx,
                active = enabled && serviceConnected,
                currentPackage = ScreenGuardAccessibilityService.currentForegroundPackage
            )

            LocalStorage.appendActivityLog(
                appCtx,
                ActivityLogRecord(
                    type = "SERVICE_STATE",
                    title = if (enabled) "Protection Enabled by User" else "Protection Paused by User",
                    detail = if (enabled) {
                        "Event-driven accessibility window observation resumed."
                    } else {
                        "Screenshot capture and classification paused."
                    },
                    packageName = ScreenGuardAccessibilityService.currentForegroundPackage
                )
            )

            EventBridge.emitServiceStatus(
                enabled = permissionGranted,
                connected = serviceConnected,
                monitoringActive = permissionGranted && serviceConnected && updated.protectionEnabled
            )

            promise.resolve(buildMonitoringStatusMap())
        } catch (e: Exception) {
            promise.reject("ERR_SET_PROTECTION", e.message, e)
        }
    }

    @ReactMethod
    fun getPreferences(promise: Promise) {
        try {
            val prefs = LocalStorage.getPreferences(reactContext.applicationContext)
            promise.resolve(preferencesToWritableMap(prefs))
        } catch (e: Exception) {
            promise.reject("ERR_GET_PREFS", e.message, e)
        }
    }

    @ReactMethod
    fun updatePreferences(prefsJson: String, promise: Promise) {
        try {
            val appCtx = reactContext.applicationContext
            val updated = LocalStorage.updatePreferencesFromJson(appCtx, prefsJson)
            if (!updated.protectionEnabled) {
                ProtectionCoordinator.onProtectionPaused(appCtx)
            }
            val serviceConnected = ScreenGuardAccessibilityService.isServiceConnected()

            MonitoringNotificationManager.updateStatusNotification(
                context = appCtx,
                active = updated.protectionEnabled && serviceConnected,
                currentPackage = ScreenGuardAccessibilityService.currentForegroundPackage
            )

            promise.resolve(preferencesToWritableMap(updated))
        } catch (e: Exception) {
            promise.reject("ERR_UPDATE_PREFS", e.message, e)
        }
    }

    @ReactMethod
    fun getActivityLogs(limit: Double, promise: Promise) {
        try {
            val safeLimit = limit.toInt().coerceIn(1, 200)
            val logs = LocalStorage.getActivityLogs(
                reactContext.applicationContext,
                safeLimit
            )
            val arr = Arguments.createArray()
            logs.forEach { item ->
                arr.pushMap(activityLogToWritableMap(item))
            }
            promise.resolve(arr)
        } catch (e: Exception) {
            promise.reject("ERR_GET_LOGS", e.message, e)
        }
    }

    @ReactMethod
    fun clearActivityLogs(promise: Promise) {
        try {
            LocalStorage.clearActivityLogs(reactContext.applicationContext)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERR_CLEAR_LOGS", e.message, e)
        }
    }

    @ReactMethod
    fun getCompatibilityRecords(promise: Promise) {
        try {
            val list = LocalStorage.getCompatibilityRecords(reactContext.applicationContext)
            promise.resolve(compatibilityListToWritableArray(list))
        } catch (e: Exception) {
            promise.reject("ERR_GET_COMPAT", e.message, e)
        }
    }

    @ReactMethod
    fun updateCompatibilityRecord(recordJson: String, promise: Promise) {
        try {
            val updated = LocalStorage.updateCompatibilityRecordFromJson(
                reactContext.applicationContext,
                recordJson
            )
            promise.resolve(compatibilityListToWritableArray(updated))
        } catch (e: Exception) {
            promise.reject("ERR_UPDATE_COMPAT", e.message, e)
        }
    }

    @ReactMethod
    fun triggerManualScreenshot(
        delayMs: Double,
        holdInMemoryPreview: Boolean,
        promise: Promise
    ) {
        moduleScope.launch {
            try {
                val activeService = ScreenGuardAccessibilityService.getActiveInstance()
                if (activeService == null) {
                    val disconnected = ProcessedScreenshotOutcome(
                        succeeded = false,
                        originalWidth = 0,
                        originalHeight = 0,
                        processedWidth = 0,
                        processedHeight = 0,
                        captureDurationMs = 0L,
                        processingDurationMs = 0L,
                        captureMethod = "MANUAL_DIAGNOSTIC",
                        packageName = ScreenGuardAccessibilityService.currentForegroundPackage,
                        windowId = ScreenGuardAccessibilityService.currentWindowId,
                        errorCode = "SERVICE_DISCONNECTED",
                        classificationResult = null,
                        debugPreviewBase64 = null
                    )
                    BufferReleaseTracker.recordCaptureFailure(
                        "MANUAL_DIAGNOSTIC",
                        "SERVICE_DISCONNECTED"
                    )
                    LocalStorage.appendActivityLog(
                        reactContext.applicationContext,
                        ActivityLogRecord(
                            type = "SCREENSHOT_FAILURE",
                            title = "Diagnostic Capture Failed (SERVICE_DISCONNECTED)",
                            detail = "Enable Screen Guard in Android Accessibility Settings before capturing.",
                            packageName = "com.paulfruitful.screenguard",
                            errorCode = "SERVICE_DISCONNECTED",
                            captureMethod = "MANUAL_DIAGNOSTIC"
                        )
                    )
                    promise.resolve(debugPreviewOutcomeToMap(disconnected))
                    return@launch
                }

                if (!holdInMemoryPreview) {
                    ScreenshotProcessor.clearVolatileDebugPreview()
                }

                val outcome = activeService.executeObservationPipeline(
                    delayMs = delayMs.toLong().coerceIn(0L, 10_000L),
                    holdInMemoryPreview = holdInMemoryPreview,
                    bypassSelfExclusion = true
                )
                promise.resolve(debugPreviewOutcomeToMap(outcome))
            } catch (e: Exception) {
                promise.reject("ERR_MANUAL_CAPTURE", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getInMemoryDebugPreview(promise: Promise) {
        try {
            val preview = ScreenshotProcessor.getVolatileDebugPreview()
            if (preview != null) {
                promise.resolve(debugPreviewOutcomeToMap(preview))
            } else {
                val empty = Arguments.createMap().apply {
                    putBoolean("captured", false)
                    putNull("base64Jpeg")
                    putInt("originalWidth", 0)
                    putInt("originalHeight", 0)
                    putInt("previewWidth", 0)
                    putInt("previewHeight", 0)
                    putDouble("captureDurationMs", 0.0)
                    putDouble("processingDurationMs", 0.0)
                    putString("captureMethod", "NONE")
                    putString("packageName", "None")
                    putInt("windowId", -1)
                    putString("errorCode", "NO_PREVIEW_HELD")
                    putBoolean("buffersReleased", true)
                }
                promise.resolve(empty)
            }
        } catch (e: Exception) {
            promise.reject("ERR_GET_PREVIEW", e.message, e)
        }
    }

    @ReactMethod
    fun clearInMemoryDebugPreview(promise: Promise) {
        try {
            ScreenshotProcessor.clearVolatileDebugPreview()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERR_CLEAR_PREVIEW", e.message, e)
        }
    }

    @ReactMethod
    fun runDebounceStressTest(
        burstCount: Double,
        intervalMs: Double,
        promise: Promise
    ) {
        try {
            val safeBurst = burstCount.toInt().coerceIn(2, 200)
            val safeInterval = intervalMs.toLong().coerceIn(1L, 500L)
            val service = ScreenGuardAccessibilityService.getActiveInstance()
            val prefs = LocalStorage.getPreferences(reactContext.applicationContext)

            val result = service?.runDebouncerBurstTest(safeBurst, safeInterval)
                ?: fallbackDebouncer.runBurstSimulation(
                    burstCount = safeBurst,
                    intervalMs = safeInterval,
                    debounceWindowMs = prefs.debounceDelayMs
                )

            LocalStorage.appendActivityLog(
                reactContext.applicationContext,
                ActivityLogRecord(
                    type = "SERVICE_STATE",
                    title = "Event Debouncer Burst Test Passed",
                    detail = "Fired ${result.burstCount} rapid events (${safeInterval}ms apart, ${result.debounceWindowMs}ms window): ${result.acceptedCount} accepted, ${result.suppressedCount} suppressed.",
                    packageName = ScreenGuardAccessibilityService.currentForegroundPackage,
                    durationMs = result.elapsedMs
                )
            )

            val map = Arguments.createMap().apply {
                putInt("burstCount", result.burstCount)
                putDouble("debounceWindowMs", result.debounceWindowMs.toDouble())
                putInt("acceptedCount", result.acceptedCount)
                putInt("suppressedCount", result.suppressedCount)
                putDouble("elapsedMs", result.elapsedMs.toDouble())
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("ERR_DEBOUNCE_TEST", e.message, e)
        }
    }

    @ReactMethod
    fun simulateServiceDisconnectionTest(promise: Promise) {
        try {
            ScreenGuardAccessibilityService.runDisconnectionRecoveryDrill(
                reactContext.applicationContext
            )
            promise.resolve(buildMonitoringStatusMap())
        } catch (e: Exception) {
            promise.reject("ERR_DISCONNECT_TEST", e.message, e)
        }
    }

    @ReactMethod
    fun initializeModel(promise: Promise) {
        moduleScope.launch {
            try {
                DetectionEngine.ensureModelLoaded(reactContext.applicationContext)
                promise.resolve(buildMonitoringStatusMap())
            } catch (e: Exception) {
                promise.reject("ERR_MODEL_INIT", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getEvaluationSamples(promise: Promise) {
        try {
            val samples = ClassifierEvaluator.listEvaluationSamples(reactContext.applicationContext)
            val arr = Arguments.createArray()
            samples.forEach { s ->
                arr.pushMap(
                    Arguments.createMap().apply {
                        putString("id", s.id)
                        putString("fileName", s.fileName)
                        putString("split", s.split)
                        putString("expectedGroup", s.expectedGroup)
                        putBoolean("isPositiveGroundTruth", s.isPositiveGroundTruth)
                        putString("categoryLabel", s.categoryLabel)
                        putString("description", s.description)
                    }
                )
            }
            promise.resolve(arr)
        } catch (e: Exception) {
            promise.reject("ERR_GET_EVAL_SAMPLES", e.message, e)
        }
    }

    @ReactMethod
    fun classifyEvaluationSample(sampleId: String, promise: Promise) {
        moduleScope.launch {
            try {
                val res = ClassifierEvaluator.classifySingleSample(
                    context = reactContext.applicationContext,
                    sampleId = sampleId,
                    includePreviewBase64 = true
                )
                if (res == null) {
                    promise.reject("ERR_SAMPLE_NOT_FOUND", "Sample $sampleId not found")
                    return@launch
                }
                promise.resolve(singleSampleEvalToMap(res))
            } catch (e: Exception) {
                promise.reject("ERR_CLASSIFY_SAMPLE", e.message, e)
            }
        }
    }

    @ReactMethod
    fun runFullModelEvaluation(includeDelegateBenchmark: Boolean, promise: Promise) {
        moduleScope.launch {
            try {
                val report = ClassifierEvaluator.runFullEvaluationSuite(
                    context = reactContext.applicationContext,
                    includeDelegateBenchmark = includeDelegateBenchmark
                )
                LocalStorage.appendActivityLog(
                    reactContext.applicationContext,
                    ActivityLogRecord(
                        type = "CLASSIFICATION_COMPLETED",
                        title = "On-Device LiteRT Evaluation Suite Completed",
                        detail = "Test Split (n=${report.testSplitMetrics.totalSamples}): Precision ${"%.1f".format(report.testSplitMetrics.precision * 100f)}% · Recall ${"%.1f".format(report.testSplitMetrics.recall * 100f)}% · FPR ${"%.1f".format(report.testSplitMetrics.falsePositiveRate * 100f)}% · F1 ${"%.2f".format(report.testSplitMetrics.f1Score)} · Avg ${"%.1f".format(report.avgLatencyMs)}ms (P95 ${report.p95LatencyMs}ms)",
                        packageName = "com.paulfruitful.screenguard",
                        durationMs = report.p50LatencyMs
                    )
                )
                promise.resolve(fullEvaluationReportToMap(report))
            } catch (e: Exception) {
                promise.reject("ERR_FULL_EVAL", e.message, e)
            }
        }
    }

    private fun buildMonitoringStatusMap(): WritableMap {
        val appCtx = reactContext.applicationContext
        val prefs = LocalStorage.getPreferences(appCtx)
        val permissionGranted = ScreenGuardAccessibilityService
            .isAccessibilityServiceEnabledInSettings(appCtx)
        val overlayPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(appCtx)
        val serviceConnected = ScreenGuardAccessibilityService.isServiceConnected()
        val monitoringActive = permissionGranted && serviceConnected && prefs.protectionEnabled
        val latestClassification = DetectionEngine.getLatestResult()
        val activeClassifier = DetectionEngine.getActiveClassifier()
        val temporalSnap = TemporalDecisionEngine.getSnapshot()
        val protectionSummary = ProtectionCoordinator.getActivitySummary(appCtx)

        val diagMap = Arguments.createMap().apply {
            putInt("hardwareBuffersAllocated", BufferReleaseTracker.getHardwareBuffersAllocated())
            putInt("hardwareBuffersClosed", BufferReleaseTracker.getHardwareBuffersClosed())
            putInt("bitmapsAllocated", BufferReleaseTracker.getBitmapsAllocated())
            putInt("bitmapsRecycled", BufferReleaseTracker.getBitmapsRecycled())
            putInt("activeInMemoryBuffers", BufferReleaseTracker.getActiveInMemoryBuffers())
            putBoolean("hasInMemoryDebugPreview", BufferReleaseTracker.hasInMemoryDebugPreview())
            putString("lastCaptureMethod", BufferReleaseTracker.lastCaptureMethod)
            putString("lastErrorCode", BufferReleaseTracker.lastErrorCode)
            putInt("lastOriginalWidth", BufferReleaseTracker.lastOriginalWidth)
            putInt("lastOriginalHeight", BufferReleaseTracker.lastOriginalHeight)
            putInt("lastProcessedWidth", BufferReleaseTracker.lastProcessedWidth)
            putInt("lastProcessedHeight", BufferReleaseTracker.lastProcessedHeight)
            putDouble("lastCaptureDurationMs", BufferReleaseTracker.getLastCaptureDurationMs().toDouble())
            putDouble("lastClassificationDurationMs", BufferReleaseTracker.getLastClassificationDurationMs().toDouble())
            putInt("debounceEventsReceived", BufferReleaseTracker.getDebounceEventsReceived())
            putInt("debounceEventsAccepted", BufferReleaseTracker.getDebounceEventsAccepted())
            putInt("debounceEventsSuppressed", BufferReleaseTracker.getDebounceEventsSuppressed())
            putInt("serviceDisconnectCount", BufferReleaseTracker.getServiceDisconnectCount())
            putInt("serviceReconnectCount", BufferReleaseTracker.getServiceReconnectCount())
        }

        return Arguments.createMap().apply {
            putBoolean("accessibilityPermissionGranted", permissionGranted)
            putBoolean("overlayPermissionGranted", overlayPermissionGranted)
            putBoolean("serviceConnected", serviceConnected)
            putBoolean("protectionEnabled", prefs.protectionEnabled)
            putBoolean("monitoringActive", monitoringActive)
            putString("sensitivityMode", prefs.sensitivityMode)
            putMap(
                "protectionSummary",
                EventBridge.protectionSummaryToWritableMap(protectionSummary)
            )
            putString("currentForegroundPackage", ScreenGuardAccessibilityService.currentForegroundPackage)
            putString("currentForegroundAppLabel", ScreenGuardAccessibilityService.currentForegroundAppLabel)
            putInt("currentWindowId", ScreenGuardAccessibilityService.currentWindowId)
            putInt("androidSdkInt", Build.VERSION.SDK_INT)
            putString("androidRelease", Build.VERSION.RELEASE ?: "Unknown")
            putString("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            putBoolean("supportsTakeScreenshot", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            putBoolean(
                "supportsTakeScreenshotOfWindow",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
            putString("lastScreenshotStatus", ScreenGuardAccessibilityService.lastScreenshotStatus)
            putDouble(
                "lastScreenshotTimestamp",
                ScreenGuardAccessibilityService.lastScreenshotTimestamp.toDouble()
            )
            putInt("lastScreenshotWidth", BufferReleaseTracker.lastOriginalWidth)
            putInt("lastScreenshotHeight", BufferReleaseTracker.lastOriginalHeight)
            putDouble(
                "lastScreenshotDurationMs",
                BufferReleaseTracker.getLastCaptureDurationMs().toDouble()
            )
            putString("lastScreenshotMethod", BufferReleaseTracker.lastCaptureMethod)
            putString("lastScreenshotErrorCode", BufferReleaseTracker.lastErrorCode)
            putInt("totalScreenshotsCaptured", BufferReleaseTracker.getTotalScreenshotsCaptured())
            putInt("totalScreenshotFailures", BufferReleaseTracker.getTotalScreenshotFailures())
            putInt("totalDetections", DetectionEngine.getTotalDetections())
            putInt("totalConfirmedInterventions", DetectionEngine.getTotalConfirmedInterventions())
            // Milestone 2 Classifier & Temporal Decision Telemetry
            putBoolean("modelLoaded", activeClassifier.isModelLoaded())
            putString("modelId", activeClassifier.modelId)
            putString("modelVersion", activeClassifier.modelVersion)
            putDouble("modelSizeBytes", activeClassifier.getModelSizeBytes().toDouble())
            putDouble("modelInitDurationMs", activeClassifier.getInitializationDurationMs().toDouble())
            putString("activeDelegate", activeClassifier.getActiveDelegateName())
            putString("inputTensorSpec", activeClassifier.getInputTensorSpec())
            putString("outputTensorSpec", activeClassifier.getOutputTensorSpec())
            putString("temporalState", temporalSnap.state.name)
            putDouble("smoothedExplicitScore", temporalSnap.smoothedExplicitScore.toDouble())
            putDouble("smoothedNudityScore", temporalSnap.smoothedNudityScore.toDouble())
            putDouble("smoothedSafeScore", temporalSnap.smoothedSafeScore.toDouble())
            putInt("totalFramesClassified", temporalSnap.totalFramesClassified)
            putInt("explicitDetections", temporalSnap.explicitDetections)
            putInt("nudityDetections", temporalSnap.nudityDetections)
            putInt("sexualActivityDetections", temporalSnap.sexualActivityDetections)
            putInt("suggestiveDetections", temporalSnap.suggestiveDetections)
            putInt("nonExplicitDetections", temporalSnap.nonExplicitDetections)
            putInt("safeClassifications", temporalSnap.safeClassifications)
            putInt("uncertainClassifications", temporalSnap.uncertainClassifications)
            putInt("duplicateFramesSkipped", temporalSnap.duplicateFramesSkipped)
            putInt("obsoleteFramesDropped", temporalSnap.obsoleteFramesDropped)
            if (latestClassification != null) {
                putMap(
                    "latestClassification",
                    EventBridge.classificationResultToWritableMap(latestClassification)
                )
            } else {
                putNull("latestClassification")
            }
            putMap("bufferDiagnostics", diagMap)
            val health = ServiceHealthMonitor.snapshot(appCtx)
            putMap(
                "serviceHealth",
                Arguments.createMap().apply {
                    putString("healthState", health.healthState)
                    putDouble("uptimeMs", health.uptimeMs.toDouble())
                    putInt("consecutiveCaptureFailures", health.consecutiveCaptureFailures)
                    putInt("consecutiveWindowApiFailures", health.consecutiveWindowApiFailures)
                    putBoolean("windowApiCircuitOpen", health.windowApiCircuitOpen)
                    putInt("inferenceTimeouts", health.inferenceTimeouts)
                    putInt("lowMemoryTrims", health.lowMemoryTrims)
                    putDouble("javaHeapUsedKb", health.javaHeapUsedKb.toDouble())
                    putDouble("nativeHeapUsedKb", health.nativeHeapUsedKb.toDouble())
                    putDouble("totalPssKb", health.totalPssKb.toDouble())
                    putString("lastRecoveryAction", health.lastRecoveryAction)
                }
            )
            val tele = PipelineTelemetry.snapshot()
            putMap(
                "pipelineTelemetry",
                Arguments.createMap().apply {
                    putInt("frames", tele.frames)
                    putDouble("captureP50Ms", tele.capture.p50.toDouble())
                    putDouble("captureP95Ms", tele.capture.p95.toDouble())
                    putDouble("preprocessP50Ms", tele.preprocess.p50.toDouble())
                    putDouble("preprocessP95Ms", tele.preprocess.p95.toDouble())
                    putDouble("stage1P50Ms", tele.stage1.p50.toDouble())
                    putDouble("stage1P95Ms", tele.stage1.p95.toDouble())
                    putDouble("inferenceP50Ms", tele.inference.p50.toDouble())
                    putDouble("inferenceP95Ms", tele.inference.p95.toDouble())
                    putDouble("decisionP50Ms", tele.decision.p50.toDouble())
                    putDouble("captureToPredictionP50Ms", tele.captureToPrediction.p50.toDouble())
                    putDouble("captureToPredictionP95Ms", tele.captureToPrediction.p95.toDouble())
                    putDouble("eventToDecisionP50Ms", tele.eventToDecision.p50.toDouble())
                    putDouble("eventToDecisionP95Ms", tele.eventToDecision.p95.toDouble())
                    putDouble("droppedObsoleteTriggers", tele.droppedObsoleteTriggers.toDouble())
                }
            )
        }
    }

    private fun singleSampleEvalToMap(res: SingleSampleEvalResult): WritableMap {
        return Arguments.createMap().apply {
            putMap(
                "sample",
                Arguments.createMap().apply {
                    putString("id", res.sample.id)
                    putString("fileName", res.sample.fileName)
                    putString("split", res.sample.split)
                    putString("expectedGroup", res.sample.expectedGroup)
                    putBoolean("isPositiveGroundTruth", res.sample.isPositiveGroundTruth)
                    putString("categoryLabel", res.sample.categoryLabel)
                    putString("description", res.sample.description)
                }
            )
            putMap(
                "classification",
                EventBridge.classificationResultToWritableMap(res.classification)
            )
            if (res.baselineClassification != null) {
                putMap(
                    "baselineClassification",
                    EventBridge.classificationResultToWritableMap(res.baselineClassification)
                )
            } else {
                putNull("baselineClassification")
            }
            putBoolean("predictedPositive", res.predictedPositive)
            putBoolean("baselinePredictedPositive", res.baselinePredictedPositive)
            putBoolean("isCorrectBinary", res.isCorrectBinary)
            putBoolean("isFalsePositive", res.isFalsePositive)
            putBoolean("isFalseNegative", res.isFalseNegative)
            if (res.previewBase64 != null) {
                putString("previewBase64", res.previewBase64)
            } else {
                putNull("previewBase64")
            }
        }
    }

    private fun splitMetricsToMap(m: SplitEvaluationMetrics): WritableMap {
        return Arguments.createMap().apply {
            putString("splitName", m.splitName)
            putInt("totalSamples", m.totalSamples)
            putInt("truePositives", m.truePositives)
            putInt("falsePositives", m.falsePositives)
            putInt("trueNegatives", m.trueNegatives)
            putInt("falseNegatives", m.falseNegatives)
            putInt("uncertainCount", m.uncertainCount)
            putDouble("uncertainRate", m.uncertainRate.toDouble())
            putDouble("precision", m.precision.toDouble())
            putDouble("recall", m.recall.toDouble())
            putDouble("falsePositiveRate", m.falsePositiveRate.toDouble())
            putDouble("falseNegativeRate", m.falseNegativeRate.toDouble())
            putDouble("f1Score", m.f1Score.toDouble())
            putDouble("accuracy", m.accuracy.toDouble())
        }
    }

    private fun delegateBenchmarkToMap(b: DelegateBenchmarkResult): WritableMap {
        return Arguments.createMap().apply {
            putString("delegateName", b.delegateName)
            putBoolean("supported", b.supported)
            putDouble("initDurationMs", b.initDurationMs.toDouble())
            putDouble("avgLatencyMs", b.avgLatencyMs.toDouble())
            putDouble("p50LatencyMs", b.p50LatencyMs.toDouble())
            putDouble("p95LatencyMs", b.p95LatencyMs.toDouble())
            putDouble("minLatencyMs", b.minLatencyMs.toDouble())
            putDouble("maxLatencyMs", b.maxLatencyMs.toDouble())
            if (b.error != null) {
                putString("error", b.error)
            } else {
                putNull("error")
            }
        }
    }

    private fun fullEvaluationReportToMap(r: FullEvaluationReport): WritableMap {
        val samplesArr = Arguments.createArray()
        r.sampleResults.forEach { item ->
            samplesArr.pushMap(singleSampleEvalToMap(item))
        }
        return Arguments.createMap().apply {
            putString("modelId", r.modelId)
            putString("modelVersion", r.modelVersion)
            putDouble("modelSizeBytes", r.modelSizeBytes.toDouble())
            putDouble("initDurationMs", r.initDurationMs.toDouble())
            putString("inputTensorSpec", r.inputTensorSpec)
            putString("outputTensorSpec", r.outputTensorSpec)
            putString("activeDelegate", r.activeDelegate)
            putDouble("thresholdUsed", r.thresholdUsed.toDouble())
            putMap("testSplitMetrics", splitMetricsToMap(r.testSplitMetrics))
            putMap("baselineTestSplitMetrics", splitMetricsToMap(r.baselineTestSplitMetrics))
            putMap("validationSplitMetrics", splitMetricsToMap(r.validationSplitMetrics))
            putMap("baselineValidationSplitMetrics", splitMetricsToMap(r.baselineValidationSplitMetrics))
            putMap("overallMetrics", splitMetricsToMap(r.overallMetrics))
            putMap("baselineOverallMetrics", splitMetricsToMap(r.baselineOverallMetrics))
            val preprocArr = Arguments.createArray()
            r.preprocessingBenchmarks.forEach { pb ->
                preprocArr.pushMap(
                    Arguments.createMap().apply {
                        putString("modeName", pb.modeName)
                        putInt("lowLightSamplesEvaluated", pb.lowLightSamplesEvaluated)
                        putDouble("recallOnLowLightPositives", pb.recallOnLowLightPositives.toDouble())
                        putDouble("fprOnOrdinaryDarkScenes", pb.fprOnOrdinaryDarkScenes.toDouble())
                        putInt("uncertainCount", pb.uncertainCount)
                        putDouble("avgLatencyMs", pb.avgLatencyMs.toDouble())
                    }
                )
            }
            putArray("preprocessingBenchmarks", preprocArr)
            putDouble("avgLatencyMs", r.avgLatencyMs.toDouble())
            putDouble("p50LatencyMs", r.p50LatencyMs.toDouble())
            putDouble("p95LatencyMs", r.p95LatencyMs.toDouble())
            putDouble("minLatencyMs", r.minLatencyMs.toDouble())
            putDouble("maxLatencyMs", r.maxLatencyMs.toDouble())
            putDouble("nativeHeapAllocatedKb", r.nativeHeapAllocatedKb.toDouble())
            putDouble("javaHeapAllocatedKb", r.javaHeapAllocatedKb.toDouble())
            if (r.cpuBenchmark != null) {
                putMap("cpuBenchmark", delegateBenchmarkToMap(r.cpuBenchmark))
            } else {
                putNull("cpuBenchmark")
            }
            if (r.nnapiBenchmark != null) {
                putMap("nnapiBenchmark", delegateBenchmarkToMap(r.nnapiBenchmark))
            } else {
                putNull("nnapiBenchmark")
            }
            putArray("sampleResults", samplesArr)
            putDouble("timestamp", r.timestamp.toDouble())
        }
    }

    private fun preferencesToWritableMap(prefs: UserPreferencesData): WritableMap {
        val excludedArr = Arguments.createArray()
        prefs.excludedPackages.forEach { pkg -> excludedArr.pushString(pkg) }
        return Arguments.createMap().apply {
            putBoolean("protectionEnabled", prefs.protectionEnabled)
            putArray("excludedPackages", excludedArr)
            putDouble("samplingIntervalMs", prefs.samplingIntervalMs.toDouble())
            putDouble("debounceDelayMs", prefs.debounceDelayMs.toDouble())
            putBoolean("preferWindowCapture", prefs.preferWindowCapture)
            putDouble("confidenceThreshold", prefs.confidenceThreshold.toDouble())
            putInt("confirmationFramesRequired", prefs.confirmationFramesRequired)
            putString("interventionBehavior", prefs.interventionBehavior)
            putString("sensitivityMode", prefs.sensitivityMode)
            putBoolean("interveneOnSuggestiveInStrict", prefs.interveneOnSuggestiveInStrict)
            putBoolean("allowManualDismiss", prefs.allowManualDismiss)
            putInt("dismissCooldownSeconds", prefs.dismissCooldownSeconds)
            putString("mockClassifierProfile", prefs.mockClassifierProfile)
            putBoolean("allowSelfPackageInDiagnostics", prefs.allowSelfPackageInDiagnostics)
        }
    }

    private fun activityLogToWritableMap(item: ActivityLogRecord): WritableMap {
        return Arguments.createMap().apply {
            putString("id", item.id)
            putString("type", item.type)
            putString("title", item.title)
            putString("detail", item.detail)
            putString("packageName", item.packageName)
            putDouble("timestamp", item.timestamp.toDouble())
            putDouble("durationMs", item.durationMs.toDouble())
            putInt("width", item.width)
            putInt("height", item.height)
            putString("errorCode", item.errorCode)
            putString("captureMethod", item.captureMethod)
            if (item.category.isNotEmpty()) {
                putString("category", item.category)
            }
            putDouble("nudityScore", item.nudityScore.toDouble())
            putDouble("explicitScore", item.explicitScore.toDouble())
            putDouble("suggestiveScore", item.suggestiveScore.toDouble())
            putDouble("safeScore", item.safeScore.toDouble())
            putBoolean("confirmedIntervention", item.confirmedIntervention)
        }
    }

    private fun compatibilityListToWritableArray(
        list: List<AppCompatibilityEntry>
    ): WritableArray {
        val arr = Arguments.createArray()
        list.forEach { item ->
            val map = Arguments.createMap().apply {
                putString("packageName", item.packageName)
                putString("displayName", item.displayName)
                putString("category", item.category)
                putString("outcome", item.outcome)
                if (item.lastTestedAt != null) {
                    putDouble("lastTestedAt", item.lastTestedAt.toDouble())
                } else {
                    putNull("lastTestedAt")
                }
                putString("captureMethod", item.captureMethod)
                putString("resolution", item.resolution)
                putDouble("durationMs", item.durationMs.toDouble())
                putString("errorCode", item.errorCode)
                putString("notes", item.notes)
            }
            arr.pushMap(map)
        }
        return arr
    }

    private fun debugPreviewOutcomeToMap(outcome: ProcessedScreenshotOutcome): WritableMap {
        return Arguments.createMap().apply {
            putBoolean("captured", outcome.succeeded)
            if (outcome.debugPreviewBase64 != null) {
                putString("base64Jpeg", outcome.debugPreviewBase64)
            } else {
                putNull("base64Jpeg")
            }
            putInt("originalWidth", outcome.originalWidth)
            putInt("originalHeight", outcome.originalHeight)
            putInt("previewWidth", outcome.processedWidth)
            putInt("previewHeight", outcome.processedHeight)
            putDouble("captureDurationMs", outcome.captureDurationMs.toDouble())
            putDouble("processingDurationMs", outcome.processingDurationMs.toDouble())
            putString("captureMethod", outcome.captureMethod)
            putString("packageName", outcome.packageName)
            putInt("windowId", outcome.windowId)
            putString("errorCode", outcome.errorCode)
            putBoolean(
                "buffersReleased",
                BufferReleaseTracker.getActiveInMemoryBuffers() == 0
            )
        }
    }
}
