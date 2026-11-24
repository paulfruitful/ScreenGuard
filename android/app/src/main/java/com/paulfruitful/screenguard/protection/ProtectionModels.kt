package com.paulfruitful.screenguard.protection

/**
 * Central overlay lifecycle states required by Milestone 3 (Section 4.2):
 * - HIDDEN: No overlay is visible.
 * - PENDING: A positive detection has been received but has not yet met temporal confirmation requirements.
 *   No disruptive full-screen intervention occurs in this state.
 * - PROTECTED: The full protective overlay is displayed and intercepts touch interaction with the covered app.
 * - RECOVERING: The protection engine is evaluating whether the underlying content is no longer present
 *   while keeping the protective surface active until recovery requirements and minimum display duration are met.
 * - DISMISSED: The user has manually dismissed the protection, subject to the active policy and dismissal cooldown.
 */
enum class OverlayState {
    HIDDEN,
    PENDING,
    PROTECTED,
    RECOVERING,
    DISMISSED
}

/**
 * Protection sensitivity mode:
 * Screen Guard operates exclusively in STRICT mode.
 */
enum class ProtectionSensitivityMode {
    STRICT;

    companion object {
        fun fromString(raw: String?): ProtectionSensitivityMode = STRICT
    }
}

/**
 * Category-specific threshold configuration and temporal timing policy (Strict Mode).
 */
data class ProtectionPolicyConfig(
    val sensitivityMode: ProtectionSensitivityMode = ProtectionSensitivityMode.STRICT,
    val interveneOnSuggestiveInStrict: Boolean = true,
    val allowManualDismiss: Boolean = false,
    val explicitThreshold: Float = 0.38f,
    val nudityThreshold: Float = 0.25f,
    val sexualActivityThreshold: Float = 0.26f,
    val suggestiveThresholdStrict: Float = 0.46f,
    val confirmationFramesRequired: Int = 1,
    val recoverySafeFramesRequired: Int = 1,
    val minDisplayDurationMs: Long = 1100L,
    val dismissCooldownMs: Long = 0L
) {
    companion object {
        fun forMode(
            mode: ProtectionSensitivityMode = ProtectionSensitivityMode.STRICT,
            baseConfidenceThreshold: Float = 0.55f,
            confirmationFrames: Int = 1,
            allowDismiss: Boolean = false
        ): ProtectionPolicyConfig {
            return ProtectionPolicyConfig(
                sensitivityMode = ProtectionSensitivityMode.STRICT,
                interveneOnSuggestiveInStrict = true,
                allowManualDismiss = false,
                explicitThreshold = (baseConfidenceThreshold * 0.65f).coerceIn(0.35f, 0.50f),
                nudityThreshold = (baseConfidenceThreshold * 0.40f).coerceIn(0.24f, 0.35f),
                sexualActivityThreshold = (baseConfidenceThreshold * 0.42f).coerceIn(0.25f, 0.36f),
                suggestiveThresholdStrict = 0.46f,
                confirmationFramesRequired = 1,
                recoverySafeFramesRequired = 1,
                minDisplayDurationMs = 1100L,
                dismissCooldownMs = 0L
            )
        }
    }
}

data class ProtectionReason(
    val packageName: String,
    val appLabel: String,
    val windowId: Int,
    val triggerCategory: String,
    val confidence: Float,
    val smoothedConfidence: Float,
    val consecutiveConfirmations: Int,
    val requiredConfirmations: Int,
    val sensitivityMode: ProtectionSensitivityMode,
    val allowManualDismiss: Boolean,
    val overlayState: OverlayState,
    val triggeredAtEpochMs: Long = System.currentTimeMillis()
)

enum class DecisionDirective {
    NO_ACTION,
    CONTINUE_OBSERVING,
    ENTER_PENDING_CONFIRMATION,
    TRIGGER_PROTECTION,
    MAINTAIN_PROTECTION,
    ENTER_RECOVERING,
    CLEAR_PROTECTION,
    SUPPRESSED_BY_COOLDOWN
}

data class ProtectionDecision(
    val directive: DecisionDirective,
    val targetOverlayState: OverlayState,
    val triggerCategory: String,
    val categoryThresholdUsed: Float,
    val rawConfidence: Float,
    val smoothedConfidence: Float,
    val consecutivePositiveCount: Int,
    val consecutiveSafeCount: Int,
    val reasonSummary: String,
    val shouldShowOverlay: Boolean,
    val shouldHideOverlay: Boolean
)

data class ProtectionActivitySummary(
    val overlayState: String,
    val overlayVisible: Boolean,
    val activeProtectedPackage: String,
    val activeProtectedAppLabel: String,
    val activeTriggerCategory: String,
    val activeConfidence: Float,
    val sensitivityMode: String,
    val totalProtectionEvents: Int,
    val protectedAppsCount: Int,
    val protectedPackageNames: List<String>,
    val totalDismissals: Int,
    val totalReturnToSafetyClicks: Int,
    val totalAutoRecoveries: Int,
    val cooldownRemainingMs: Long,
    val lastOverlayError: String
)
