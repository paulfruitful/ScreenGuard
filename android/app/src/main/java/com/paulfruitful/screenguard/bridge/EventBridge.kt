package com.paulfruitful.screenguard.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.paulfruitful.screenguard.classifier.ClassificationResult
import com.paulfruitful.screenguard.protection.ProtectionActivitySummary
import com.paulfruitful.screenguard.protection.ProtectionDecision
import java.lang.ref.WeakReference

/**
 * Thread-safe event bridge that delivers native AccessibilityService and DetectionEngine
 * events to React Native over `DeviceEventManagerModule.RCTDeviceEventEmitter`.
 *
 * The native AccessibilityService never depends on the React Native JS bridge being alive;
 * if the UI is backgrounded or not yet initialized, events are persisted to [LocalStorage]
 * and live telemetry counters without throwing.
 */
object EventBridge {
    const val EVENT_CHANNEL = "ScreenGuardNativeEvent"

    @Volatile
    private var reactContextRef: WeakReference<ReactApplicationContext>? = null

    fun attachReactContext(context: ReactApplicationContext) {
        reactContextRef = WeakReference(context)
    }

    fun detachReactContext() {
        reactContextRef = null
    }

    fun emitServiceStatus(
        enabled: Boolean,
        connected: Boolean,
        monitoringActive: Boolean
    ) {
        val payload = Arguments.createMap().apply {
            putString("type", "SERVICE_STATUS")
            putBoolean("enabled", enabled)
            putBoolean("connected", connected)
            putBoolean("monitoringActive", monitoringActive)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        emitRaw(payload)
    }

    fun emitForegroundAppChanged(
        packageName: String,
        appLabel: String,
        windowId: Int,
        isExcluded: Boolean
    ) {
        val payload = Arguments.createMap().apply {
            putString("type", "FOREGROUND_APP_CHANGED")
            putString("packageName", packageName)
            putString("appLabel", appLabel)
            putInt("windowId", windowId)
            putBoolean("isExcluded", isExcluded)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        emitRaw(payload)
    }

    fun emitScreenshotSuccess(
        width: Int,
        height: Int,
        durationMs: Long,
        captureMethod: String,
        packageName: String,
        windowId: Int
    ) {
        val payload = Arguments.createMap().apply {
            putString("type", "SCREENSHOT_SUCCESS")
            putInt("width", width)
            putInt("height", height)
            putDouble("durationMs", durationMs.toDouble())
            putString("captureMethod", captureMethod)
            putString("packageName", packageName)
            putInt("windowId", windowId)
            putBoolean("buffersReleased", true)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        emitRaw(payload)
    }

    fun emitScreenshotFailure(
        errorCode: String,
        captureMethod: String,
        packageName: String,
        windowId: Int
    ) {
        val payload = Arguments.createMap().apply {
            putString("type", "SCREENSHOT_FAILURE")
            putString("errorCode", errorCode)
            putString("captureMethod", captureMethod)
            putString("packageName", packageName)
            putInt("windowId", windowId)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        emitRaw(payload)
    }

    fun emitClassificationResult(result: ClassificationResult) {
        val payload = Arguments.createMap().apply {
            putString("type", "CLASSIFICATION_RESULT")
            putMap("result", classificationResultToWritableMap(result))
        }
        emitRaw(payload)
    }

    fun emitProtectionStateChanged(
        summary: ProtectionActivitySummary,
        decision: ProtectionDecision? = null
    ) {
        val payload = Arguments.createMap().apply {
            putString("type", "PROTECTION_STATE_CHANGED")
            putMap("summary", protectionSummaryToWritableMap(summary))
            if (decision != null) {
                putString("directive", decision.directive.name)
                putString("reasonSummary", decision.reasonSummary)
            }
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        emitRaw(payload)
    }

    fun protectionSummaryToWritableMap(summary: ProtectionActivitySummary): WritableMap {
        val pkgArr = Arguments.createArray()
        summary.protectedPackageNames.forEach { pkgArr.pushString(it) }
        return Arguments.createMap().apply {
            putString("overlayState", summary.overlayState)
            putBoolean("overlayVisible", summary.overlayVisible)
            putString("activeProtectedPackage", summary.activeProtectedPackage)
            putString("activeProtectedAppLabel", summary.activeProtectedAppLabel)
            putString("activeTriggerCategory", summary.activeTriggerCategory)
            putDouble("activeConfidence", summary.activeConfidence.toDouble())
            putString("sensitivityMode", summary.sensitivityMode)
            putInt("totalProtectionEvents", summary.totalProtectionEvents)
            putInt("protectedAppsCount", summary.protectedAppsCount)
            putArray("protectedPackageNames", pkgArr)
            putInt("totalDismissals", summary.totalDismissals)
            putInt("totalReturnToSafetyClicks", summary.totalReturnToSafetyClicks)
            putInt("totalAutoRecoveries", summary.totalAutoRecoveries)
            putDouble("cooldownRemainingMs", summary.cooldownRemainingMs.toDouble())
            putString("lastOverlayError", summary.lastOverlayError)
        }
    }

    fun classificationResultToWritableMap(result: ClassificationResult): WritableMap {
        val scoresMap = Arguments.createMap()
        result.scores.forEach { (k, v) ->
            scoresMap.putDouble(k, v.toDouble())
        }
        return Arguments.createMap().apply {
            putMap("scores", scoresMap)
            putString("predictedCategory", result.predictedCategory)
            putString("category", result.predictedCategory)
            putDouble("confidence", result.confidence.toDouble())
            putDouble("processingDurationMs", result.processingDurationMs.toDouble())
            putString("modelVersion", result.modelVersion)
            putBoolean("successful", result.successful)
            if (result.error != null) {
                putString("error", result.error)
            } else {
                putNull("error")
            }
            putBoolean("isExplicit", result.isExplicit)
            putDouble("nudityScore", result.nudityScore.toDouble())
            putDouble("explicitScore", result.explicitScore.toDouble())
            putDouble("sexualActivityScore", result.sexualActivityScore.toDouble())
            putDouble("suggestiveScore", result.suggestiveScore.toDouble())
            putDouble("safeScore", result.safeScore.toDouble())
            putDouble("drawingsScore", result.drawingsScore.toDouble())
            putDouble("hentaiScore", result.hentaiScore.toDouble())
            putDouble("neutralScore", result.neutralScore.toDouble())
            putDouble("pornScore", result.pornScore.toDouble())
            putDouble("sexyScore", result.sexyScore.toDouble())
            putBoolean("stage2Triggered", result.stage2Triggered)
            putDouble("stage2NsfwScore", result.stage2NsfwScore.toDouble())
            putDouble("stage2SfwScore", result.stage2SfwScore.toDouble())
            putDouble("stage2DurationMs", result.stage2DurationMs.toDouble())
            putString("preprocessingApplied", result.preprocessingApplied)
            putBoolean("isLowLightFrame", result.isLowLightFrame)
            putDouble("meanLuminance", result.meanLuminance.toDouble())
            putString("originalCategory", result.originalCategory)
            putDouble("originalConfidence", result.originalConfidence.toDouble())
            putInt("consecutiveDetections", result.consecutiveDetections)
            putBoolean("confirmedIntervention", result.confirmedIntervention)
            putString("temporalState", result.temporalState)
            putDouble("smoothedExplicitScore", result.smoothedExplicitScore.toDouble())
            putBoolean("isDuplicateSkipped", result.isDuplicateSkipped)
            putString("frameHash", result.frameHash)
            putString("delegateUsed", result.delegateUsed)
            putDouble("thresholdUsed", result.thresholdUsed.toDouble())
            putString("modelId", result.modelId)
            putBoolean("isMockModel", result.isMockModel)
            putInt("frameWidth", result.frameWidth)
            putInt("frameHeight", result.frameHeight)
            putString("packageName", result.packageName)
            putInt("windowId", result.windowId)
            putDouble("timestamp", result.timestamp.toDouble())
        }
    }

    private fun emitRaw(params: WritableMap) {
        val ctx = reactContextRef?.get() ?: return
        if (!ctx.hasActiveReactInstance()) return
        try {
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                ?.emit(EVENT_CHANNEL, params)
        } catch (_: Exception) {
        }
    }
}
