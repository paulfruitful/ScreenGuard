package com.paulfruitful.screenguard.accountability

/**
 * Milestone 4 data model.
 *
 * IMPORTANT: an accountability commitment is a *voluntary self-control* record kept in
 * app-private storage. It is NOT an Android-enforced uninstall restriction. Any status
 * showing real Android enforcement comes from [DeviceEnforcementState], which is queried
 * from DevicePolicyManager at read time.
 */

object CommitmentPresets {
    const val ONE_DAY = "ONE_DAY"
    const val SEVEN_DAYS = "SEVEN_DAYS"
    const val THIRTY_DAYS = "THIRTY_DAYS"
    const val CUSTOM = "CUSTOM"

    const val MIN_CUSTOM_DAYS = 1
    const val MAX_CUSTOM_DAYS = 365

    fun daysFor(type: String, customDays: Int): Int = when (type) {
        ONE_DAY -> 1
        SEVEN_DAYS -> 7
        THIRTY_DAYS -> 30
        CUSTOM -> customDays.coerceIn(MIN_CUSTOM_DAYS, MAX_CUSTOM_DAYS)
        else -> 1
    }

    fun isValidType(type: String): Boolean = type in setOf(
        ONE_DAY, SEVEN_DAYS, THIRTY_DAYS, CUSTOM
    )
}

data class AccountabilityConfig(
    val durationType: String,
    val customDays: Int = 1,
    val pin: String = "",
    val coolingOffMinutes: Int = AccountabilityRules.DEFAULT_COOLING_OFF_MINUTES
)

object AccountabilityRules {
    const val DEFAULT_COOLING_OFF_MINUTES = 10
    const val MIN_COOLING_OFF_MINUTES = 1
    const val MAX_COOLING_OFF_MINUTES = 1440
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 16
    const val PBKDF2_ITERATIONS = 120_000
    const val CLOCK_SKEW_TOLERANCE_MS = 60_000L
}

/**
 * Persisted state. Secret material is stored only as salted PBKDF2 hashes; the recovery
 * code is shown to the user exactly once when accountability is enabled.
 */
data class AccountabilityState(
    val active: Boolean = false,
    val durationType: String = "NONE",
    val commitmentDays: Int = 0,
    val startedAtMs: Long = 0L,
    val endsAtMs: Long = 0L,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val recoveryCodeHash: String? = null,
    val recoveryCodeSalt: String? = null,
    val recoveryCodeHint: String = "",
    val coolingOffMinutes: Int = AccountabilityRules.DEFAULT_COOLING_OFF_MINUTES,
    val deactivationRequestedAtMs: Long = 0L,
    val lastObservedWallMs: Long = 0L,
    val lastEvent: String = "NONE",
    val lastEventTimestampMs: Long = 0L
)

/** Snapshot of Android enforcement authority. Never inferred from app-private state. */
data class DeviceEnforcementState(
    val deviceAdminActive: Boolean = false,
    val deviceAdminComponent: String = "",
    val deviceOwnerActive: Boolean = false,
    val profileOwnerActive: Boolean = false,
    val managedUninstallPolicyActive: Boolean = false
)

/**
 * Single status object consumed by React Native. `technicalEnforcement` and
 * `uninstallEnforcementActive` are deliberately conservative: an ordinary device
 * administrator or a voluntary commitment never reports active uninstall enforcement.
 */
data class EnableAccountabilityResult(
    val status: AccountabilityStatus,
    val recoveryCode: String
)

data class AccountabilityStatus(
    val active: Boolean,
    val mode: String,
    val durationType: String,
    val commitmentDays: Int,
    val commitmentDurationMs: Long,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val remainingMs: Long,
    val expired: Boolean,
    val pinConfigured: Boolean,
    val coolingOffMinutes: Int,
    val deactivationRequested: Boolean,
    val deactivationEligibleAtMs: Long,
    val coolingOffRemainingMs: Long,
    val recoveryCodeHint: String,
    val clockTrusted: Boolean,
    val clockRollbackDetected: Boolean,
    val timingNote: String,
    val deviceAdminActive: Boolean,
    val deviceAdminComponent: String,
    val deviceOwnerActive: Boolean,
    val profileOwnerActive: Boolean,
    val managedUninstallPolicyActive: Boolean,
    val technicalEnforcement: String,
    val uninstallEnforcementActive: Boolean,
    val enforcementSummary: String,
    val lastEvent: String,
    val lastEventTimestampMs: Long
)
