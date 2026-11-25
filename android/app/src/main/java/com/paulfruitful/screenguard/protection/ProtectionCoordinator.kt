package com.paulfruitful.screenguard.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import com.paulfruitful.screenguard.MainActivity
import com.paulfruitful.screenguard.accessibility.ScreenGuardAccessibilityService
import com.paulfruitful.screenguard.bridge.EventBridge
import com.paulfruitful.screenguard.classifier.ClassificationResult
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage
import com.paulfruitful.screenguard.storage.UserPreferencesData

/**
 * Central coordinator that connects:
 * - [ScreenGuardAccessibilityService] foreground app/window & screen-off lifecycle events
 * - [DetectionEngine] classification results
 * - [ProtectionDecisionEngine] category-specific policy & temporal state machine
 * - [ScreenGuardOverlayController] native WindowManager protective overlay
 * - [LocalStorage] & [EventBridge] persistence and React Native telemetry
 */
object ProtectionCoordinator {

    private const val SELF_PACKAGE = "com.paulfruitful.screenguard"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decisionEngine = ProtectionDecisionEngine()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var overlayController: ScreenGuardOverlayController? = null

    @Volatile
    private var activeProtectedPackage: String = "None"

    @Volatile
    private var activeProtectedAppLabel: String = "None"

    @Volatile
    private var activeTriggerCategory: String = "NONE"

    @Volatile
    private var activeConfidence: Float = 0f

    fun isOverlayVisible(): Boolean = overlayController?.isVisible() == true

    fun setOverlayTransparentForProbe(transparent: Boolean) {
        overlayController?.setTemporarilyTransparentForProbe(transparent)
    }

    fun init(context: Context) {
        if (overlayController != null && appContext != null) return
        synchronized(lock) {
            val ctx = context.applicationContext
            appContext = ctx
            LocalStorage.init(ctx)
            if (overlayController == null) {
                overlayController = ScreenGuardOverlayController(
                    appContext = ctx,
                    onReturnToSafetyClicked = { reason -> handleReturnToSafety(reason) },
                    onOpenScriptureClicked = { reason, verse -> handleOpenScripture(reason, verse) },
                    onDismissClicked = { reason -> handleManualDismiss(reason) }
                )
            }
        }
    }

    fun buildPolicyConfig(prefs: UserPreferencesData): ProtectionPolicyConfig {
        val baseConfig = ProtectionPolicyConfig.forMode(
            mode = ProtectionSensitivityMode.STRICT,
            baseConfidenceThreshold = prefs.confidenceThreshold,
            confirmationFrames = 2,
            allowDismiss = false
        )
        return baseConfig.copy(
            sensitivityMode = ProtectionSensitivityMode.STRICT,
            interveneOnSuggestiveInStrict = true,
            allowManualDismiss = false,
            dismissCooldownMs = 0L
        )
    }

    fun onClassificationResult(
        context: Context,
        result: ClassificationResult,
        appLabel: String
    ): ProtectionDecision {
        init(context)
        val prefs = LocalStorage.getPreferences(context)
        val policy = buildPolicyConfig(prefs)
        val pkg = result.packageName.ifBlank {
            ScreenGuardAccessibilityService.currentForegroundPackage
        }
        val isExcluded = prefs.excludedPackages.contains(pkg) || pkg == SELF_PACKAGE

        val decision = decisionEngine.evaluate(
            result = result,
            packageName = pkg,
            protectionEnabled = prefs.protectionEnabled,
            isExcludedPackage = isExcluded,
            policy = policy
        )

        val overlayEnabledByBehavior = prefs.interventionBehavior != "LOG_ONLY"

        if (decision.shouldShowOverlay && overlayEnabledByBehavior) {
            activeProtectedPackage = pkg
            activeProtectedAppLabel = appLabel.ifBlank { pkg }
            activeTriggerCategory = decision.triggerCategory
            activeConfidence = decision.rawConfidence

            val reason = ProtectionReason(
                packageName = pkg,
                appLabel = activeProtectedAppLabel,
                windowId = result.windowId,
                triggerCategory = decision.triggerCategory,
                confidence = decision.rawConfidence,
                smoothedConfidence = decision.smoothedConfidence,
                consecutiveConfirmations = decision.consecutivePositiveCount,
                requiredConfirmations = policy.confirmationFramesRequired,
                sensitivityMode = policy.sensitivityMode,
                allowManualDismiss = policy.allowManualDismiss,
                overlayState = decision.targetOverlayState
            )

            if (decision.directive == DecisionDirective.TRIGGER_PROTECTION) {
                overlayController?.showProtection(reason)
                LocalStorage.recordProtectionTriggered(context, pkg)
                LocalStorage.appendActivityLog(
                    context,
                    ActivityLogRecord(
                        type = "SUSPICIOUS_DETECTED",
                        title = "Protective Overlay Displayed (${decision.triggerCategory})",
                        detail = "${decision.reasonSummary} in $activeProtectedAppLabel ($pkg) · Mode: ${policy.sensitivityMode.name}",
                        packageName = pkg,
                        category = decision.triggerCategory,
                        nudityScore = result.nudityScore,
                        explicitScore = result.explicitScore,
                        suggestiveScore = result.suggestiveScore,
                        safeScore = result.safeScore,
                        confirmedIntervention = true
                    )
                )
            } else {
                overlayController?.updateProtection(reason)
            }
        } else if (decision.shouldHideOverlay) {
            if (decision.directive == DecisionDirective.CLEAR_PROTECTION) {
                overlayController?.fadeOutProtection()
                LocalStorage.recordAutoRecovery(context)
                LocalStorage.appendActivityLog(
                    context,
                    ActivityLogRecord(
                        type = "SERVICE_STATE",
                        title = "Protective Overlay Faded Away (Safe Content Confirmed)",
                        detail = "${decision.reasonSummary} in $pkg.",
                        packageName = pkg
                    )
                )
            } else {
                overlayController?.hideProtection()
            }
            clearActiveMetadata()
        }

        val summary = getActivitySummary(context)
        EventBridge.emitProtectionStateChanged(summary, decision)
        return decision
    }

    fun onForegroundAppChanged(
        context: Context,
        newPackageName: String,
        newAppLabel: String,
        windowId: Int
    ) {
        init(context)
        val wasVisible = decisionEngine.onForegroundAppChanged(newPackageName)
        if (wasVisible || overlayController?.isVisible() == true) {
            overlayController?.hideProtection()
            clearActiveMetadata()
            LocalStorage.appendActivityLog(
                context,
                ActivityLogRecord(
                    type = "SERVICE_STATE",
                    title = "Protective Overlay Cleared on App Switch",
                    detail = "User switched foreground application to $newAppLabel ($newPackageName, window $windowId).",
                    packageName = newPackageName
                )
            )
        }
        EventBridge.emitProtectionStateChanged(getActivitySummary(context))
    }

    fun onScreenTurnedOff(context: Context) {
        init(context)
        val wasVisible = overlayController?.isVisible() == true
        overlayController?.hideProtection()
        decisionEngine.resetAll()
        clearActiveMetadata()
        if (wasVisible) {
            LocalStorage.appendActivityLog(
                context,
                ActivityLogRecord(
                    type = "SERVICE_STATE",
                    title = "Protective Overlay Cleared on Screen Off",
                    detail = "Device screen turned off; removed active overlay and reset transient protection state.",
                    packageName = ScreenGuardAccessibilityService.currentForegroundPackage
                )
            )
        }
        EventBridge.emitProtectionStateChanged(getActivitySummary(context))
    }

    fun onProtectionPaused(context: Context) {
        init(context)
        overlayController?.hideProtection()
        decisionEngine.resetAll()
        clearActiveMetadata()
        EventBridge.emitProtectionStateChanged(getActivitySummary(context))
    }

    fun onServiceDisconnected(context: Context) {
        init(context)
        overlayController?.hideProtection()
        decisionEngine.resetAll()
        clearActiveMetadata()
        EventBridge.emitProtectionStateChanged(getActivitySummary(context))
    }

    fun onConfigurationChanged(newConfig: Configuration?) {
        overlayController?.onConfigurationChanged(newConfig)
    }

    fun dismissActiveOverlayFromUi(context: Context): ProtectionActivitySummary {
        init(context)
        val pkg = activeProtectedPackage.ifBlank {
            ScreenGuardAccessibilityService.currentForegroundPackage
        }
        overlayController?.hideProtection()
        decisionEngine.onUserDismissedOverlay(pkg)
        LocalStorage.recordProtectionDismissed(context)
        clearActiveMetadata()
        val summary = getActivitySummary(context)
        EventBridge.emitProtectionStateChanged(summary)
        return summary
    }

    /**
     * Triggers a live protective overlay for diagnostic verification (e.g., testing full-screen
     * coverage, orientation changes, touch interception, and action buttons over Screen Guard
     * or over a third-party application after a delay).
     */
    fun triggerDiagnosticOverlayTest(
        context: Context,
        testCategory: String = "EXPLICIT",
        autoHideDurationMs: Long = 0L
    ): ProtectionActivitySummary {
        init(context)
        val prefs = LocalStorage.getPreferences(context)
        val policy = buildPolicyConfig(prefs)
        val fgPkg = ScreenGuardAccessibilityService.currentForegroundPackage.ifBlank { SELF_PACKAGE }
        val fgLabel = ScreenGuardAccessibilityService.currentForegroundAppLabel.ifBlank { "Active Window" }
        val fgWinId = ScreenGuardAccessibilityService.currentWindowId

        activeProtectedPackage = fgPkg
        activeProtectedAppLabel = fgLabel
        activeTriggerCategory = testCategory
        activeConfidence = 0.96f

        val reason = ProtectionReason(
            packageName = fgPkg,
            appLabel = fgLabel,
            windowId = fgWinId,
            triggerCategory = testCategory,
            confidence = 0.96f,
            smoothedConfidence = 0.94f,
            consecutiveConfirmations = policy.confirmationFramesRequired,
            requiredConfirmations = policy.confirmationFramesRequired,
            sensitivityMode = policy.sensitivityMode,
            allowManualDismiss = policy.allowManualDismiss,
            overlayState = OverlayState.PROTECTED
        )

        overlayController?.showProtection(reason)
        LocalStorage.recordProtectionTriggered(context, fgPkg)
        LocalStorage.appendActivityLog(
            context,
            ActivityLogRecord(
                type = "SUSPICIOUS_DETECTED",
                title = "Diagnostic Protective Overlay Triggered ($testCategory)",
                detail = "Overlay attached over $fgLabel ($fgPkg) · Mode: ${policy.sensitivityMode.name}",
                packageName = fgPkg,
                category = testCategory,
                explicitScore = 0.96f,
                confirmedIntervention = true
            )
        )

        if (autoHideDurationMs in 1000L..30_000L) {
            mainHandler.postDelayed({
                if (overlayController?.isVisible() == true) {
                    overlayController?.hideProtection()
                    clearActiveMetadata()
                    appContext?.let { ctx ->
                        EventBridge.emitProtectionStateChanged(getActivitySummary(ctx))
                    }
                }
            }, autoHideDurationMs)
        }

        val summary = getActivitySummary(context)
        EventBridge.emitProtectionStateChanged(summary)
        return summary
    }

    fun getActivitySummary(context: Context): ProtectionActivitySummary {
        init(context)
        val prefs = LocalStorage.getPreferences(context)
        val policy = buildPolicyConfig(prefs)
        val stats = LocalStorage.getProtectionStats(context)
        val visible = overlayController?.isVisible() == true
        val engineState = decisionEngine.getCurrentState()
        val effectiveState = if (visible && engineState == OverlayState.HIDDEN) {
            OverlayState.PROTECTED
        } else {
            engineState
        }
        val fgPkg = ScreenGuardAccessibilityService.currentForegroundPackage
        val cooldownMs = decisionEngine.getDismissCooldownRemainingMs(fgPkg, policy)

        return ProtectionActivitySummary(
            overlayState = effectiveState.name,
            overlayVisible = visible,
            activeProtectedPackage = if (visible) activeProtectedPackage else "None",
            activeProtectedAppLabel = if (visible) activeProtectedAppLabel else "None",
            activeTriggerCategory = if (visible) activeTriggerCategory else decisionEngine.getActiveTriggerCategory(),
            activeConfidence = if (visible) activeConfidence else decisionEngine.getSmoothedConfidence(),
            sensitivityMode = policy.sensitivityMode.name,
            totalProtectionEvents = stats.totalProtectionEvents,
            protectedAppsCount = stats.protectedPackages.size,
            protectedPackageNames = stats.protectedPackages.toList(),
            totalDismissals = stats.totalDismissals,
            totalReturnToSafetyClicks = stats.totalReturnToSafetyClicks,
            totalAutoRecoveries = stats.totalAutoRecoveries,
            cooldownRemainingMs = cooldownMs,
            lastOverlayError = overlayController?.getLastError() ?: "NONE"
        )
    }

    private fun handleReturnToSafety(reason: ProtectionReason) {
        val ctx = appContext ?: return
        overlayController?.hideProtection()
        decisionEngine.resetAll()
        clearActiveMetadata()
        LocalStorage.recordReturnToSafetyClicked(ctx)

        val targetPkg = reason.packageName.ifBlank {
            ScreenGuardAccessibilityService.currentForegroundPackage
        }
        navigateToApplicationHomePage(ctx, targetPkg)

        LocalStorage.appendActivityLog(
            ctx,
            ActivityLogRecord(
                type = "SERVICE_STATE",
                title = "User Clicked 'Return to Safe Browsing'",
                detail = "Hid protective overlay and navigated to the home page of ${reason.appLabel} ($targetPkg).",
                packageName = targetPkg
            )
        )

        EventBridge.emitProtectionStateChanged(getActivitySummary(ctx))
    }

    private fun handleOpenScripture(reason: ProtectionReason, verse: MinistryBibleVerse) {
        val ctx = appContext ?: return
        overlayController?.hideProtection()
        decisionEngine.resetAll()
        clearActiveMetadata()
        LocalStorage.recordReturnToSafetyClicked(ctx)

        val targetPkg = reason.packageName.ifBlank {
            ScreenGuardAccessibilityService.currentForegroundPackage
        }
        // Exit the offending post/screen in the background before opening the Bible app / Bible.com
        val service = ScreenGuardAccessibilityService.getActiveInstance()
        if (service != null && targetPkg != SELF_PACKAGE) {
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (_: Exception) {
            }
        }

        BibleVerseRepository.openScriptureInBibleAppOrWeb(ctx, verse)

        LocalStorage.appendActivityLog(
            ctx,
            ActivityLogRecord(
                type = "SERVICE_STATE",
                title = "Opened Scripture (${verse.reference})",
                detail = "User expanded ${verse.reference} on Bible App / Bible.com from protective shield over ${reason.appLabel}.",
                packageName = targetPkg
            )
        )

        EventBridge.emitProtectionStateChanged(getActivitySummary(ctx))
    }

    /**
     * Exits the offending post/video in the underlying app and navigates the user directly
     * to the Home page of the Screen Guard application.
     */
    private fun navigateToApplicationHomePage(context: Context, packageName: String) {
        val service = ScreenGuardAccessibilityService.getActiveInstance()
        val pkg = packageName.lowercase()

        // 1. First dismiss/pause any open media lightbox or video player in the underlying app via BACK
        if (service != null && pkg != SELF_PACKAGE) {
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (_: Exception) {
            }
        }

        // 2. Open the Home page of the Screen Guard application
        try {
            val sgHome = Intent(context, MainActivity::class.java).apply {
                putExtra("navigateToTab", "HOME")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(sgHome)
        } catch (_: Exception) {
        }
    }

    private fun handleManualDismiss(reason: ProtectionReason) {
        val ctx = appContext ?: return
        overlayController?.hideProtection()
        decisionEngine.onUserDismissedOverlay(reason.packageName)
        clearActiveMetadata()
        LocalStorage.recordProtectionDismissed(ctx)

        val prefs = LocalStorage.getPreferences(ctx)
        LocalStorage.appendActivityLog(
            ctx,
            ActivityLogRecord(
                type = "SERVICE_STATE",
                title = "Protective Overlay Dismissed by User",
                detail = "User dismissed overlay for ${reason.appLabel} (${reason.packageName}) · ${prefs.dismissCooldownSeconds}s cooldown active.",
                packageName = reason.packageName
            )
        )

        EventBridge.emitProtectionStateChanged(getActivitySummary(ctx))
    }

    private fun clearActiveMetadata() {
        activeProtectedPackage = "None"
        activeProtectedAppLabel = "None"
        activeTriggerCategory = "NONE"
        activeConfidence = 0f
    }
}
