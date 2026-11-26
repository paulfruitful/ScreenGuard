package com.paulfruitful.screenguard.cooloff

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

data class TriggerFrequencyState(
    val recentActivationCount: Int,
    val threshold: Int,
    val windowMinutes: Int,
    val isEscalated: Boolean,
    val escalatedAtWallMs: Long
)

/**
 * Pure rolling-window evaluator for repeated Protective Shield activations.
 * Separated from Android SharedPreferences so unit tests can verify exact window semantics.
 */
class RollingTriggerWindowEvaluator(
    private val minDedupeIntervalMs: Long = 4_000L
) {
    private val timestamps = ArrayDeque<Long>()
    private var escalated: Boolean = false
    private var escalatedAtMs: Long = 0L
    private var lastRecordedMs: Long = Long.MIN_VALUE

    fun restore(savedTimestamps: List<Long>, isEscalated: Boolean, escalatedAt: Long) {
        timestamps.clear()
        savedTimestamps.sorted().forEach { timestamps.addLast(it) }
        escalated = isEscalated
        escalatedAtMs = escalatedAt
        lastRecordedMs = timestamps.lastOrNull() ?: Long.MIN_VALUE
    }

    fun prune(nowMs: Long, windowMs: Long) {
        val cutoff = nowMs - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
    }

    /**
     * Records a new Protective Shield activation (deduplicated within [minDedupeIntervalMs]).
     * Returns true if the activation was recorded, or false if deduplicated.
     */
    fun recordActivation(nowMs: Long, threshold: Int, windowMs: Long): Boolean {
        prune(nowMs, windowMs)
        if (lastRecordedMs != Long.MIN_VALUE && (nowMs - lastRecordedMs) in 0L until minDedupeIntervalMs) {
            return false
        }
        timestamps.addLast(nowMs)
        lastRecordedMs = nowMs
        if (timestamps.size >= threshold) {
            if (!escalated) {
                escalated = true
                escalatedAtMs = nowMs
            }
        }
        return true
    }

    fun isEscalated(): Boolean = escalated

    fun getEscalatedAtMs(): Long = escalatedAtMs

    fun getRecentTimestamps(nowMs: Long, windowMs: Long): List<Long> {
        prune(nowMs, windowMs)
        return timestamps.toList()
    }

    fun clearEscalationAndReset() {
        escalated = false
        escalatedAtMs = 0L
        timestamps.clear()
        lastRecordedMs = Long.MIN_VALUE
    }
}

/**
 * Persistent repeated-trigger tracker for Protective Shield activations.
 *
 * Reliability guarantees:
 * - Persists activation timestamps and escalation state to SharedPreferences immediately.
 * - Uses monotonic elapsed time during a boot session, with wall-clock fallback across reboots
 *   so an active escalation state is NEVER lost on Activity recreation, app switching,
 *   AccessibilityService restart, or device reboot.
 * - Escalation is ONLY cleared when [onCoolOffSessionCompleted] is called after a validated
 *   Cool-Off session completes.
 */
object TriggerFrequencyTracker {
    private const val PREFS_NAME = "screenguard_cooloff_store"
    private const val KEY_TRACKER_STATE = "trigger_frequency_state_v1"

    private val lock = Any()
    private val evaluator = RollingTriggerWindowEvaluator()

    @Volatile
    private var initialized = false

    private fun initLocked(context: Context) {
        if (initialized) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_TRACKER_STATE, null)
        if (!raw.isNullOrBlank()) {
            try {
                val obj = JSONObject(raw)
                val isEsc = obj.optBoolean("isEscalated", false)
                val escAt = obj.optLong("escalatedAtWallMs", 0L)
                val savedBootId = obj.optLong("bootBaselineWallMs", 0L)
                val currentBootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                val sameBoot = kotlin.math.abs(currentBootWallMs - savedBootId) < 120_000L

                val list = mutableListOf<Long>()
                val arr = obj.optJSONArray("timestamps")
                if (arr != null && sameBoot) {
                    for (i in 0 until arr.length()) {
                        val ts = arr.optLong(i, -1L)
                        if (ts >= 0L) list.add(ts)
                    }
                }
                evaluator.restore(list, isEsc, escAt)
            } catch (_: Exception) {
            }
        }
        initialized = true
    }

    private fun persistLocked(context: Context, nowMonoMs: Long, windowMs: Long) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recent = evaluator.getRecentTimestamps(nowMonoMs, windowMs)
        val arr = JSONArray()
        recent.forEach { arr.put(it) }
        val bootBaselineWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val obj = JSONObject().apply {
            put("isEscalated", evaluator.isEscalated())
            put("escalatedAtWallMs", evaluator.getEscalatedAtMs())
            put("bootBaselineWallMs", bootBaselineWallMs)
            put("timestamps", arr)
        }
        sp.edit().putString(KEY_TRACKER_STATE, obj.toString()).apply()
    }

    fun recordShieldActivation(context: Context): TriggerFrequencyState {
        val config = CoolOffConfigStore.load(context)
        val nowMono = SystemClock.elapsedRealtime()
        synchronized(lock) {
            initLocked(context)
            val wasEscalated = evaluator.isEscalated()
            evaluator.recordActivation(
                nowMs = nowMono,
                threshold = config.safeThreshold,
                windowMs = config.detectionWindowMs
            )
            // Keep wall-clock timestamp for escalatedAt when first transitioning
            val escalatedAtWall = if (!wasEscalated && evaluator.isEscalated()) {
                System.currentTimeMillis()
            } else {
                evaluator.getEscalatedAtMs()
            }
            if (!wasEscalated && evaluator.isEscalated()) {
                evaluator.restore(
                    evaluator.getRecentTimestamps(nowMono, config.detectionWindowMs),
                    true,
                    escalatedAtWall
                )
            }
            persistLocked(context, nowMono, config.detectionWindowMs)
            return snapshotLocked(config, nowMono)
        }
    }

    fun isEscalated(context: Context): Boolean {
        synchronized(lock) {
            initLocked(context)
            return evaluator.isEscalated()
        }
    }

    fun getState(context: Context): TriggerFrequencyState {
        val config = CoolOffConfigStore.load(context)
        val nowMono = SystemClock.elapsedRealtime()
        synchronized(lock) {
            initLocked(context)
            return snapshotLocked(config, nowMono)
        }
    }

    /**
     * Clears the escalated Cool-Off-required state ONLY after a validated session completes.
     */
    fun onCoolOffSessionCompleted(context: Context): TriggerFrequencyState {
        val config = CoolOffConfigStore.load(context)
        val nowMono = SystemClock.elapsedRealtime()
        synchronized(lock) {
            initLocked(context)
            evaluator.clearEscalationAndReset()
            persistLocked(context, nowMono, config.detectionWindowMs)
            return snapshotLocked(config, nowMono)
        }
    }

    private fun snapshotLocked(
        config: CoolOffPolicyConfig,
        nowMono: Long
    ): TriggerFrequencyState {
        val recent = evaluator.getRecentTimestamps(nowMono, config.detectionWindowMs)
        return TriggerFrequencyState(
            recentActivationCount = recent.size,
            threshold = config.safeThreshold,
            windowMinutes = config.detectionWindowMinutes,
            isEscalated = evaluator.isEscalated(),
            escalatedAtWallMs = evaluator.getEscalatedAtMs()
        )
    }
}
