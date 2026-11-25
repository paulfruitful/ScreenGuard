package com.paulfruitful.screenguard.accountability

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.paulfruitful.screenguard.admin.DeviceAdminController
import com.paulfruitful.screenguard.storage.ActivityLogRecord
import com.paulfruitful.screenguard.storage.LocalStorage
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Voluntary accountability state machine.
 *
 * Time source: [System.currentTimeMillis] is used because it is a wall-clock value that
 * survives process restarts and device reboots. It is not tamper-proof on an unmanaged
 * device: a user with unlocked device administration can change it. We record a monotonic
 * high-water mark of the wall clock and surface an explicit integrity warning if the clock
 * moves backwards more than one minute. Reboot and app update are logged by
 * [com.paulfruitful.screenguard.boot.AppLifecycleReceiver] and never reset the commitment.
 *
 * This component never blocks Android Settings, package installer screens, emergency
 * calling, accessibility controls, or device recovery. All deactivation paths remain
 * available through supported Android settings.
 */
object AccountabilityManager {

    private const val PREFS_NAME = "screenguard_accountability_v1"
    private const val KEY_STATE = "accountability_state_v1"
    private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val RECOVERY_GROUP_COUNT = 3
    private const val RECOVERY_GROUP_LENGTH = 4

    private val lock = Any()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        LocalStorage.init(appContext!!)
    }

    /** True when Uninstall Protection lock is currently enabled. */
    fun isLockActive(context: Context? = null): Boolean = synchronized(lock) {
        initOnce(context)
        loadState(context).active
    }

    private fun prefs(context: Context?) = (context?.applicationContext ?: appContext
    ?: throw IllegalStateException("AccountabilityManager is not initialized"))
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadState(context: Context? = null): AccountabilityState {
        val raw = prefs(context).getString(KEY_STATE, null) ?: return AccountabilityState()
        return try {
            val obj = JSONObject(raw)
            AccountabilityState(
                active = obj.optBoolean("active", false),
                durationType = obj.optString("durationType", "NONE"),
                commitmentDays = obj.optInt("commitmentDays", 0),
                startedAtMs = obj.optLong("startedAtMs", 0L),
                endsAtMs = obj.optLong("endsAtMs", 0L),
                pinHash = obj.optString("pinHash", "").ifEmpty { null },
                pinSalt = obj.optString("pinSalt", "").ifEmpty { null },
                recoveryCodeHash = obj.optString("recoveryCodeHash", "").ifEmpty { null },
                recoveryCodeSalt = obj.optString("recoveryCodeSalt", "").ifEmpty { null },
                recoveryCodeHint = obj.optString("recoveryCodeHint", ""),
                coolingOffMinutes = obj.optInt(
                    "coolingOffMinutes",
                    AccountabilityRules.DEFAULT_COOLING_OFF_MINUTES
                ),
                deactivationRequestedAtMs = obj.optLong("deactivationRequestedAtMs", 0L),
                lastObservedWallMs = obj.optLong("lastObservedWallMs", 0L),
                lastEvent = obj.optString("lastEvent", "NONE"),
                lastEventTimestampMs = obj.optLong("lastEventTimestampMs", 0L)
            )
        } catch (_: Exception) {
            AccountabilityState()
        }
    }

    private fun saveState(state: AccountabilityState, context: Context? = null) {
        val obj = JSONObject().apply {
            put("active", state.active)
            put("durationType", state.durationType)
            put("commitmentDays", state.commitmentDays)
            put("startedAtMs", state.startedAtMs)
            put("endsAtMs", state.endsAtMs)
            put("pinHash", state.pinHash ?: "")
            put("pinSalt", state.pinSalt ?: "")
            put("recoveryCodeHash", state.recoveryCodeHash ?: "")
            put("recoveryCodeSalt", state.recoveryCodeSalt ?: "")
            put("recoveryCodeHint", state.recoveryCodeHint)
            put("coolingOffMinutes", state.coolingOffMinutes)
            put("deactivationRequestedAtMs", state.deactivationRequestedAtMs)
            put("lastObservedWallMs", state.lastObservedWallMs)
            put("lastEvent", state.lastEvent)
            put("lastEventTimestampMs", state.lastEventTimestampMs)
        }
        // commit() so a commitment cannot be lost to a delayed write on app termination.
        prefs(context).edit().putString(KEY_STATE, obj.toString()).commit()
    }

    fun getStatus(context: Context? = null): AccountabilityStatus {
        synchronized(lock) {
            initOnce(context)
            val state = loadState(context)
            val now = System.currentTimeMillis()
            val rollbackDetected = state.lastObservedWallMs > 0L &&
                now < state.lastObservedWallMs - AccountabilityRules.CLOCK_SKEW_TOLERANCE_MS

            if (!rollbackDetected && now > state.lastObservedWallMs +
                AccountabilityRules.CLOCK_SKEW_TOLERANCE_MS
            ) {
                saveState(state.copy(lastObservedWallMs = now), context)
            }

            val active = state.active
            val expired = active && now >= state.endsAtMs
            val remaining = if (active) (state.endsAtMs - now).coerceAtLeast(0L) else 0L
            val requested = active && state.deactivationRequestedAtMs > 0L
            val eligibleAt = if (requested) {
                state.deactivationRequestedAtMs +
                    TimeUnit.MINUTES.toMillis(state.coolingOffMinutes.toLong())
            } else 0L
            val coolingRemaining = if (requested) (eligibleAt - now).coerceAtLeast(0L) else 0L

            val enforcement = DeviceAdminController.currentState(
                context?.applicationContext ?: appContext!!
            )
            val technicalEnforcement = when {
                enforcement.deviceOwnerActive && enforcement.managedUninstallPolicyActive ->
                    "DEVICE_OWNER_POLICY_OBSERVED"
                enforcement.deviceAdminActive -> "DEVICE_ADMIN_SUPPORT_ONLY"
                else -> "NONE"
            }
            val uninstallEnforced = enforcement.deviceOwnerActive &&
                enforcement.managedUninstallPolicyActive

            val mode = when {
                !active -> "OFF"
                enforcement.deviceOwnerActive -> "MANAGED_DEVICE_OBSERVED"
                enforcement.deviceAdminActive -> "ACCOUNTABILITY_DEVICE_ADMIN"
                else -> "ACCOUNTABILITY"
            }

            val timingNote = when {
                rollbackDetected ->
                    "Device clock moved backwards after the commitment started. Remaining time is approximate; no tamper-proof timing is claimed on an unmanaged device."
                !active -> "No active commitment."
                else ->
                    "Based on the Android wall clock, which survives restart and reboot but is not tamper-proof on an unmanaged device."
            }

            return AccountabilityStatus(
                active = active,
                mode = mode,
                durationType = if (active) state.durationType else "NONE",
                commitmentDays = if (active) state.commitmentDays else 0,
                commitmentDurationMs = if (active) {
                    state.endsAtMs - state.startedAtMs
                } else 0L,
                startedAtMs = if (active) state.startedAtMs else 0L,
                endsAtMs = if (active) state.endsAtMs else 0L,
                remainingMs = remaining,
                expired = expired,
                pinConfigured = state.pinHash != null,
                coolingOffMinutes = state.coolingOffMinutes,
                deactivationRequested = requested,
                deactivationEligibleAtMs = eligibleAt,
                coolingOffRemainingMs = coolingRemaining,
                recoveryCodeHint = state.recoveryCodeHint,
                clockTrusted = !rollbackDetected,
                clockRollbackDetected = rollbackDetected,
                timingNote = timingNote,
                deviceAdminActive = enforcement.deviceAdminActive,
                deviceAdminComponent = enforcement.deviceAdminComponent,
                deviceOwnerActive = enforcement.deviceOwnerActive,
                profileOwnerActive = enforcement.profileOwnerActive,
                managedUninstallPolicyActive = enforcement.managedUninstallPolicyActive,
                technicalEnforcement = technicalEnforcement,
                uninstallEnforcementActive = uninstallEnforced,
                enforcementSummary = buildEnforcementSummary(
                    technicalEnforcement,
                    uninstallEnforced,
                    enforcement
                ),
                lastEvent = state.lastEvent,
                lastEventTimestampMs = state.lastEventTimestampMs
            )
        }
    }

    fun enable(config: AccountabilityConfig, context: Context? = null): EnableAccountabilityResult {
        synchronized(lock) {
            initOnce(context)
            val now = System.currentTimeMillis()
            val current = loadState(context)
            if (current.active && now < current.endsAtMs) {
                throw IllegalStateException("An accountability commitment is already active.")
            }
            if (!CommitmentPresets.isValidType(config.durationType)) {
                throw IllegalArgumentException("Unsupported commitment duration.")
            }
            val days = CommitmentPresets.daysFor(config.durationType, config.customDays)
            val cooling = config.coolingOffMinutes.coerceIn(
                AccountabilityRules.MIN_COOLING_OFF_MINUTES,
                AccountabilityRules.MAX_COOLING_OFF_MINUTES
            )
            val pin = config.pin.trim()
            if (pin.isNotEmpty() && !isPinValid(pin)) {
                throw IllegalArgumentException(
                    "PIN must be ${AccountabilityRules.MIN_PIN_LENGTH}-${AccountabilityRules.MAX_PIN_LENGTH} digits."
                )
            }

            val recoveryCode = generateRecoveryCode()
            val pinSecrets = if (pin.isEmpty()) null to null else hashSecret(pin)
            val recoverySecrets = hashSecret(recoveryCode)
            val durationMs = TimeUnit.DAYS.toMillis(days.toLong())
            val newState = AccountabilityState(
                active = true,
                durationType = config.durationType,
                commitmentDays = days,
                startedAtMs = now,
                endsAtMs = now + durationMs,
                pinHash = pinSecrets.first,
                pinSalt = pinSecrets.second,
                recoveryCodeHash = recoverySecrets.first,
                recoveryCodeSalt = recoverySecrets.second,
                recoveryCodeHint = maskRecoveryCode(recoveryCode),
                coolingOffMinutes = cooling,
                deactivationRequestedAtMs = 0L,
                lastObservedWallMs = now,
                lastEvent = "ACCOUNTABILITY_ENABLED",
                lastEventTimestampMs = now
            )
            saveState(newState, context)
            appendLog(
                context,
                "ACCOUNTABILITY_ENABLED",
                "Accountability enabled for $days day(s)",
                "Voluntary commitment until ${java.util.Date(newState.endsAtMs)}. " +
                    "PIN confirmation ${if (pin.isEmpty()) "off" else "on"}; " +
                    "cooling-off ${cooling} min. This is not an Android-enforced uninstall block.",
                now
            )
            return EnableAccountabilityResult(getStatus(context), recoveryCode)
        }
    }

    fun requestDeactivation(context: Context? = null): AccountabilityStatus {
        synchronized(lock) {
            initOnce(context)
            val now = System.currentTimeMillis()
            val current = loadState(context)
            if (!current.active) {
                throw IllegalStateException("Accountability is not active.")
            }
            val requestedAt = if (current.deactivationRequestedAtMs > 0L) {
                current.deactivationRequestedAtMs
            } else {
                now
            }
            val updated = current.copy(
                deactivationRequestedAtMs = requestedAt,
                lastEvent = "DEACTIVATION_REQUESTED",
                lastEventTimestampMs = now
            )
            saveState(updated, context)
            appendLog(
                context,
                "DEACTIVATION_REQUESTED",
                "Accountability deactivation requested",
                "Cooling-off ends at ${java.util.Date(requestedAt + TimeUnit.MINUTES.toMillis(updated.coolingOffMinutes.toLong()))}. " +
                    "Commitment remains active until deactivation is confirmed or Android recovery is used.",
                now
            )
            return getStatus(context)
        }
    }

    fun cancelDeactivation(context: Context? = null): AccountabilityStatus {
        synchronized(lock) {
            initOnce(context)
            val now = System.currentTimeMillis()
            val current = loadState(context)
            val updated = current.copy(
                deactivationRequestedAtMs = 0L,
                lastEvent = "DEACTIVATION_CANCELLED",
                lastEventTimestampMs = now
            )
            saveState(updated, context)
            appendLog(
                context,
                "DEACTIVATION_CANCELLED",
                "Accountability deactivation request cancelled",
                "Commitment continues until ${java.util.Date(current.endsAtMs)}.",
                now
            )
            return getStatus(context)
        }
    }

    fun confirmDeactivation(secretInput: String, context: Context? = null): AccountabilityStatus {
        synchronized(lock) {
            initOnce(context)
            val now = System.currentTimeMillis()
            val current = loadState(context)
            if (!current.active) {
                throw IllegalStateException("Uninstall protection is not active.")
            }
            if (current.pinHash != null) {
                val input = secretInput.trim()
                if (input.isEmpty()) {
                    throw IllegalArgumentException("Enter your Lock PIN or recovery code to unlock.")
                }
                val pinMatches = verifySecret(input, current.pinHash, current.pinSalt)
                val recoveryMatches = verifySecret(
                    normalizeRecoveryCode(input),
                    current.recoveryCodeHash,
                    current.recoveryCodeSalt
                )
                if (!pinMatches && !recoveryMatches) {
                    throw IllegalArgumentException("Incorrect Lock PIN or recovery code.")
                }
            }

            val earlyExit = now < current.endsAtMs
            val updated = AccountabilityState(
                active = false,
                durationType = "NONE",
                commitmentDays = 0,
                startedAtMs = 0L,
                endsAtMs = 0L,
                pinHash = null,
                pinSalt = null,
                recoveryCodeHash = null,
                recoveryCodeSalt = null,
                recoveryCodeHint = "",
                coolingOffMinutes = AccountabilityRules.DEFAULT_COOLING_OFF_MINUTES,
                deactivationRequestedAtMs = 0L,
                lastObservedWallMs = now,
                lastEvent = if (earlyExit) {
                    "EMERGENCY_RECOVERY_USED"
                } else {
                    "ACCOUNTABILITY_DISABLED"
                },
                lastEventTimestampMs = now
            )
            saveState(updated, context)
            appendLog(
                context,
                updated.lastEvent,
                if (earlyExit) "Accountability ended through emergency recovery" else "Accountability disabled",
                "All Android recovery options remain available. Device administrator status is unchanged; " +
                    "deactivate it separately in Android Settings if it is active.",
                now
            )
            return getStatus(context)
        }
    }

    fun setPin(pin: String, context: Context? = null): AccountabilityStatus {
        synchronized(lock) {
            initOnce(context)
            val now = System.currentTimeMillis()
            val current = loadState(context)
            if (!current.active) {
                throw IllegalStateException("Enable accountability before setting a PIN.")
            }
            val trimmed = pin.trim()
            val (hash, salt) = if (trimmed.isEmpty()) null to null else {
                if (!isPinValid(trimmed)) {
                    throw IllegalArgumentException(
                        "PIN must be ${AccountabilityRules.MIN_PIN_LENGTH}-${AccountabilityRules.MAX_PIN_LENGTH} digits."
                    )
                }
                hashSecret(trimmed)
            }
            val updated = current.copy(
                pinHash = hash,
                pinSalt = salt,
                lastEvent = if (hash == null) "PIN_REMOVED" else "PIN_CHANGED",
                lastEventTimestampMs = now
            )
            saveState(updated, context)
            appendLog(
                context,
                updated.lastEvent,
                if (hash == null) "PIN confirmation removed" else "PIN confirmation updated",
                "Your saved emergency recovery code and Android settings remain available as recovery paths.",
                now
            )
            return getStatus(context)
        }
    }

    /** Test/diagnostic helper: returns true when the PIN or recovery code matches. */
    fun verifySecretForDiagnostics(input: String, context: Context? = null): Boolean {
        synchronized(lock) {
            initOnce(context)
            val state = loadState(context)
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return false
            return verifySecret(trimmed, state.pinHash, state.pinSalt) ||
                verifySecret(normalizeRecoveryCode(trimmed), state.recoveryCodeHash, state.recoveryCodeSalt)
        }
    }

    /** Test/diagnostic helper: reports the monotonic uptime observed at last status read. */
    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    private fun initOnce(context: Context?) {
        if (appContext == null) {
            init(context ?: throw IllegalStateException("Context required for accountability init"))
        }
    }

    private fun buildEnforcementSummary(
        technical: String,
        uninstallEnforced: Boolean,
        state: DeviceEnforcementState
    ): String = when {
        uninstallEnforced ->
            "A Device Owner uninstall policy is observed on this package. Only an authorized manager can change it."
        state.deviceOwnerActive ->
            "Device Owner enrollment is active. No uninstall policy enforced by this app is active."
        state.deviceAdminActive ->
            "Device administrator is active for Screen Guard. It can lock the screen and reports its own state; it does NOT prevent uninstall. Remove it in Android Settings > Security > Device administrators."
        else ->
            "No Android enforcement is active. Uninstall Protection is an accountability commitment only; Android Settings and uninstall remain fully available."
    }

    private fun appendLog(
        context: Context?,
        event: String,
        title: String,
        detail: String,
        timestamp: Long
    ) {
        val ctx = context?.applicationContext ?: appContext ?: return
        LocalStorage.appendActivityLog(
            ctx,
            ActivityLogRecord(
                type = "ACCOUNTABILITY",
                title = title,
                detail = detail,
                packageName = "com.paulfruitful.screenguard",
                timestamp = timestamp
            )
        )
    }

    private fun isPinValid(pin: String): Boolean =
        pin.length in AccountabilityRules.MIN_PIN_LENGTH..AccountabilityRules.MAX_PIN_LENGTH &&
            pin.all { it.isDigit() }

    private fun generateRecoveryCode(): String {
        val random = SecureRandom()
        val groups = (0 until RECOVERY_GROUP_COUNT).map {
            (0 until RECOVERY_GROUP_LENGTH).joinToString("") {
                RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)].toString()
            }
        }
        return "SG-" + groups.joinToString("-")
    }

    private fun normalizeRecoveryCode(value: String): String =
        value.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun maskRecoveryCode(code: String): String {
        val body = code.removePrefix("SG-")
        val first = body.take(2)
        return "SG-$first••-••••-••••"
    }

    private fun hashSecret(secret: String): Pair<String, String> {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val spec = PBEKeySpec(
            secret.toCharArray(),
            salt,
            AccountabilityRules.PBKDF2_ITERATIONS,
            256
        )
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(hash, Base64.NO_WRAP) to
            Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    private fun verifySecret(secret: String, hash: String?, salt: String?): Boolean {
        if (hash.isNullOrEmpty() || salt.isNullOrEmpty()) return false
        return try {
            val spec = PBEKeySpec(
                secret.toCharArray(),
                Base64.decode(salt, Base64.NO_WRAP),
                AccountabilityRules.PBKDF2_ITERATIONS,
                256
            )
            val candidate = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            spec.clearPassword()
            MessageDigest.isEqual(Base64.decode(hash, Base64.NO_WRAP), candidate)
        } catch (_: Exception) {
            false
        }
    }
}
