package com.paulfruitful.screenguard.pipeline

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why a frame observation was requested. Each request carries the instant the originating
 * accessibility event was received so event->decision latency can be measured end to end.
 */
data class FrameTrigger(
    val eventAtNs: Long,
    val settleMs: Long,
    val minIntervalMs: Long,
    val isAppSwitch: Boolean = false
)

/**
 * Latest-frame-wins scheduler.
 *
 * - A single worker executes frames strictly one at a time (bounded concurrency = 1).
 * - Requests arriving while a frame is running or settling are CONFLATED: only the newest one is
 *   kept, so obsolete triggers can never accumulate (counted in [PipelineTelemetry]).
 * - The settle delay is applied once per frame and never restarted by newer events, so continuous
 *   animation/video cannot starve capture (non-starving debounce).
 * - Every frame run is bounded by [frameTimeoutMs]; a hung frame is abandoned, not awaited forever.
 */
class FrameScheduler(
    private val scope: CoroutineScope,
    private val frameTimeoutMs: Long = 6_000L,
    private val onFrameTimeout: () -> Unit = {},
    private val runFrame: suspend (FrameTrigger) -> Unit
) {
    private val requests = Channel<FrameTrigger>(Channel.CONFLATED)
    private val running = AtomicBoolean(false)
    private var lastFrameStartElapsedMs = 0L

    @Volatile
    private var started = false

    @Volatile
    var isFrameInFlight: Boolean = false
        private set

    @Volatile
    var lastFrameStartedAtElapsedMs: Long = 0L
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        started = true
        scope.launch {
            for (first in requests) {
                var trigger = first
                // 1. Settle (once). Newer requests simply replace the pending one in the channel.
                if (trigger.settleMs > 0) delay(trigger.settleMs)

                // 2. Take the newest request that arrived while settling (latest wins).
                while (true) {
                    val newer = requests.tryReceive().getOrNull() ?: break
                    PipelineTelemetry.recordObsoleteTriggerDropped()
                    trigger = FrameTrigger(
                        eventAtNs = minOf(trigger.eventAtNs, newer.eventAtNs),
                        settleMs = 0L,
                        minIntervalMs = minOf(trigger.minIntervalMs, newer.minIntervalMs),
                        isAppSwitch = trigger.isAppSwitch || newer.isAppSwitch
                    )
                }

                // 3. Respect the minimum interval between frame starts (Android enforces ~1s).
                val sinceLast = SystemClock.elapsedRealtime() - lastFrameStartElapsedMs
                if (sinceLast < trigger.minIntervalMs) delay(trigger.minIntervalMs - sinceLast)

                lastFrameStartElapsedMs = SystemClock.elapsedRealtime()
                lastFrameStartedAtElapsedMs = lastFrameStartElapsedMs
                isFrameInFlight = true
                try {
                    withTimeout(frameTimeoutMs) { runFrame(trigger) }
                } catch (_: TimeoutCancellationException) {
                    onFrameTimeout()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!running.get()) {
                        throw e
                    }
                    // Inner coroutine timeout or frame cancellation — never terminate the scheduler loop!
                    onFrameTimeout()
                } catch (_: Throwable) {
                    // A single failing frame must never kill the scheduler loop.
                } finally {
                    isFrameInFlight = false
                }
            }
        }
    }

    fun request(trigger: FrameTrigger) {
        if (!started) start()
        requests.trySend(trigger)
    }

    fun stop() {
        running.set(false)
        started = false
        // Drain any pending trigger.
        while (requests.tryReceive().getOrNull() != null) {
            // discard
        }
    }
}
