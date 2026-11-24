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
 * User-configurable protection sensitivity modes (Section 10):
 * - BALANCED: Intervenes on confirmed NUDITY, SEXUAL_ACTIVITY, and EXPLICIT content.
 *   Never triggers a full-screen intervention on SUGGESTIVE or UNCERTAIN classifications.
 * - STRICT: Applies more conservative thresholds for NUDITY, SEXUAL_ACTIVITY, and EXPLICIT content,
 *   and optionally intervenes on high-confidence confirmed SUGGESTIVE content.
 */
enum class ProtectionSensitivityMode {
    BALANCED,
    STRICT;

    companion object {
        fun fromString(raw: String?): ProtectionSensitivityMode {
            return if (raw.equals("STRICT", ignoreCase = true)) STRICT else BALANCED
        }
    }
}

/**
 * Category-specific threshold configuration and temporal timing policy (Sections 5 & 6).
 */
data class ProtectionPolicyConfig(
    val sensitivityMode: ProtectionSensitivityMode = ProtectionSensitivityMode.BALANCED,
    val interveneOnSuggestiveInStrict: Boolean = true,
    val allowManualDismiss: Boolean = true,
    val explicitThreshold: Float = 0.55f,
    val nudityThreshold: Float = 0.35f,
    val sexualActivityThreshold: Float = 0.36f,
    val suggestiveThresholdStrict: Float = 0.52f,
    val confirmationFramesRequired: Int = 1,
    val recoverySafeFramesRequired: Int = 1,
    val minDisplayDurationMs: Long = 1200L,
    val dismissCooldownMs: Long = 12_000L
) {
    companion object {
        fun forMode(
            mode: ProtectionSensitivityMode,
            baseConfidenceThreshold: Float = 0.60f,
            confirmationFrames: Int = 1,
            allowDismiss: Boolean = true
        ): ProtectionPolicyConfig {
            return when (mode) {
                ProtectionSensitivityMode.BALANCED -> ProtectionPolicyConfig(
                    sensitivityMode = ProtectionSensitivityMode.BALANCED,
                    interveneOnSuggestiveInStrict = false,
                    allowManualDismiss = allowDismiss,
                    explicitThreshold = (baseConfidenceThreshold * 0.80f).coerceIn(0.48f, 0.65f),
                    nudityThreshold = (baseConfidenceThreshold * 0.50f).coerceIn(0.32f, 0.45f),
                    sexualActivityThreshold = (baseConfidenceThreshold * 0.52f).coerceIn(0.34f, 0.46f),
                    suggestiveThresholdStrict = 0.85f,
                    confirmationFramesRequired = 1,
                    recoverySafeFramesRequired = 1,
                    minDisplayDurationMs = 1200L,
                    dismissCooldownMs = 12_000L
                )
                ProtectionSensitivityMode.STRICT -> ProtectionPolicyConfig(
                    sensitivityMode = ProtectionSensitivityMode.STRICT,
                    interveneOnSuggestiveInStrict = true,
                    allowManualDismiss = allowDismiss,
                    explicitThreshold = (baseConfidenceThreshold * 0.68f).coerceIn(0.40f, 0.55f),
                    nudityThreshold = (baseConfidenceThreshold * 0.42f).coerceIn(0.26f, 0.38f),
                    sexualActivityThreshold = (baseConfidenceThreshold * 0.44f).coerceIn(0.28f, 0.40f),
                    suggestiveThresholdStrict = 0.50f,
                    confirmationFramesRequired = 1,
                    recoverySafeFramesRequired = 1,
                    minDisplayDurationMs = 1300L,
                    dismissCooldownMs = 15_000L
                )
            }
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
