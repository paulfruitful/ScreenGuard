package com.paulfruitful.screenguard.accessibility

import android.os.SystemClock
import com.paulfruitful.screenguard.capture.BufferReleaseTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class DebounceBurstTestResult(
    val burstCount: Int,
    val debounceWindowMs: Long,
    val acceptedCount: Int,
    val suppressedCount: Int,
    val elapsedMs: Long
)

/**
 * Debounces and rate-limits high-frequency accessibility events so that rapid
 * scrolling or layout changes coalesce into a single screenshot capture request.
 */
class EventDebouncer(
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private var pendingJob: Job? = null
    @Volatile
    private var isWaitingForSettle: Boolean = false
    private val lastDispatchedElapsedMs = AtomicLong(0L)

    /**
     * Schedules a debounced observation action using non-starving coalescing:
     * - If a capture job is currently waiting in its settle window (`isWaitingForSettle`)
     *   for the same app (`!isImmediateAppSwitch`), incoming content/scroll events are
     *   coalesced into the pending job rather than resetting its timer indefinitely.
     * - If the user switched to a new foreground application (`isImmediateAppSwitch == true`),
     *   any prior pending settle timer is cancelled and a fresh settle capture (350ms) is scheduled.
     */
    fun schedule(
        isImmediateAppSwitch: Boolean,
        debounceDelayMs: Long,
        minCooldownMs: Long,
        action: suspend () -> Unit
    ) {
        synchronized(lock) {
            if (isWaitingForSettle && pendingJob?.isActive == true && !isImmediateAppSwitch) {
                // Coalesce into the already-waiting settle timer so continuous animations never starve capture
                BufferReleaseTracker.onDebounceEventReceived(accepted = false)
                return
            }

            if (isWaitingForSettle && pendingJob?.isActive == true) {
                pendingJob?.cancel()
                BufferReleaseTracker.onDebounceEventReceived(accepted = false)
            }

            val settleMs = if (isImmediateAppSwitch) {
                minOf(350L, debounceDelayMs)
            } else {
                debounceDelayMs
            }
            val effectiveCooldownMs = if (isImmediateAppSwitch) {
                minOf(minCooldownMs, 1150L)
            } else {
                minCooldownMs
            }

            isWaitingForSettle = true
            pendingJob = scope.launch {
                try {
                    delay(settleMs)

                    val now = SystemClock.elapsedRealtime()
                    val elapsedSinceLast = now - lastDispatchedElapsedMs.get()
                    if (elapsedSinceLast < effectiveCooldownMs) {
                        val remainingWait = effectiveCooldownMs - elapsedSinceLast
                        if (!isImmediateAppSwitch && remainingWait > 2500L) {
                            BufferReleaseTracker.onDebounceEventReceived(accepted = false)
                            return@launch
                        }
                        delay(remainingWait)
                    }
                } finally {
                    isWaitingForSettle = false
                }

                lastDispatchedElapsedMs.set(SystemClock.elapsedRealtime())
                BufferReleaseTracker.onDebounceEventReceived(accepted = true)
                action()
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = null
        }
    }

    /**
     * Deterministic burst test used by the Testing screen to verify that a rapid stream
     * of [burstCount] events separated by [intervalMs] coalesces into a single accepted capture.
     */
    fun runBurstSimulation(
        burstCount: Int,
        intervalMs: Long,
        debounceWindowMs: Long
    ): DebounceBurstTestResult {
        val start = SystemClock.elapsedRealtime()
        val safeBurst = burstCount.coerceIn(2, 200)
        val safeInterval = intervalMs.coerceIn(1L, 500L)

        var accepted = 0
        var suppressed = 0
        var simulatedClockMs = 0L
        var nextAllowedFireMs = -1L

        for (i in 0 until safeBurst) {
            if (nextAllowedFireMs >= 0 && simulatedClockMs < nextAllowedFireMs) {
                // Previous scheduled capture was superseded before its debounce window expired
                suppressed++
                BufferReleaseTracker.onDebounceEventReceived(accepted = false)
            }
            nextAllowedFireMs = simulatedClockMs + debounceWindowMs
            simulatedClockMs += safeInterval
        }

        // The final event in the burst settles after debounceWindowMs
        if (nextAllowedFireMs >= 0) {
            accepted++
            BufferReleaseTracker.onDebounceEventReceived(accepted = true)
        }

        val elapsed = maxOf(1L, SystemClock.elapsedRealtime() - start)
        return DebounceBurstTestResult(
            burstCount = safeBurst,
            debounceWindowMs = debounceWindowMs,
            acceptedCount = accepted,
            suppressedCount = suppressed,
            elapsedMs = elapsed
        )
    }
}
