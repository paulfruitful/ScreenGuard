package com.paulfruitful.screenguard.pipeline

import android.os.SystemClock
import android.util.Log

/**
 * One end-to-end frame measurement. Contains ONLY durations and boolean flags — never pixels,
 * text, package names or any other content-bearing data (privacy requirement).
 */
data class FrameTrace(
    val eventToCaptureStartMs: Long,
    val captureMs: Long,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val stage1Ms: Long,
    val decisionMs: Long,
    val captureToPredictionMs: Long,
    val eventToDecisionMs: Long,
    val usedRoiScan: Boolean,
    val usedStage2: Boolean,
    val producedProtection: Boolean
)

data class StageStats(val count: Int, val p50: Long, val p95: Long, val max: Long)

data class TelemetrySummary(
    val frames: Int,
    val eventToCaptureStart: StageStats,
    val capture: StageStats,
    val preprocess: StageStats,
    val inference: StageStats,
    val stage1: StageStats,
    val decision: StageStats,
    val captureToPrediction: StageStats,
    val eventToDecision: StageStats,
    val overlayAttach: StageStats,
    val droppedObsoleteTriggers: Long,
    val roiScanRate: Float,
    val stage2Rate: Float
)

/**
 * Bounded ring buffer of aggregate frame timings used to profile the complete pipeline:
 * accessibility event -> capture -> preprocessing -> inference -> decision (-> overlay attach).
 */
object PipelineTelemetry {
    private const val TAG = "SGTelemetry"
    private const val CAPACITY = 256
    private const val LOG_EVERY_N_FRAMES = 25

    private val lock = Any()
    private val traces = arrayOfNulls<FrameTrace>(CAPACITY)
    private var writeIndex = 0
    private var total = 0
    private val overlayAttachMs = LongArray(64)
    private var overlayWrite = 0
    private var overlayTotal = 0

    @Volatile
    var droppedObsoleteTriggers: Long = 0L
        private set

    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    fun msBetween(startNs: Long, endNs: Long): Long =
        maxOf(0L, (endNs - startNs) / 1_000_000L)

    fun recordObsoleteTriggerDropped() {
        droppedObsoleteTriggers++
    }

    fun recordOverlayAttach(ms: Long) {
        synchronized(lock) {
            overlayAttachMs[overlayWrite] = ms
            overlayWrite = (overlayWrite + 1) % overlayAttachMs.size
            overlayTotal++
        }
    }

    fun record(trace: FrameTrace) {
        var shouldLog = false
        synchronized(lock) {
            traces[writeIndex] = trace
            writeIndex = (writeIndex + 1) % CAPACITY
            total++
            shouldLog = total % LOG_EVERY_N_FRAMES == 0
        }
        if (shouldLog) {
            val s = snapshot()
            Log.i(
                TAG,
                "frames=${s.frames} capture p50/p95=${s.capture.p50}/${s.capture.p95}ms " +
                    "preprocess=${s.preprocess.p50}/${s.preprocess.p95}ms " +
                    "inference=${s.inference.p50}/${s.inference.p95}ms " +
                    "stage1=${s.stage1.p50}/${s.stage1.p95}ms " +
                    "decision=${s.decision.p50}/${s.decision.p95}ms " +
                    "capture->prediction=${s.captureToPrediction.p50}/${s.captureToPrediction.p95}ms " +
                    "event->decision=${s.eventToDecision.p50}/${s.eventToDecision.p95}ms " +
                    "roi=${"%.0f".format(s.roiScanRate * 100)}% stage2=${"%.0f".format(s.stage2Rate * 100)}% " +
                    "droppedTriggers=${s.droppedObsoleteTriggers}"
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            for (i in traces.indices) traces[i] = null
            writeIndex = 0
            total = 0
            overlayWrite = 0
            overlayTotal = 0
        }
    }

    fun snapshot(): TelemetrySummary {
        val list: List<FrameTrace>
        val overlay: List<Long>
        synchronized(lock) {
            list = traces.filterNotNull()
            overlay = overlayAttachMs.take(minOf(overlayTotal, overlayAttachMs.size))
        }
        fun stats(values: List<Long>): StageStats {
            if (values.isEmpty()) return StageStats(0, 0, 0, 0)
            val sorted = values.sorted()
            fun pct(p: Double): Long = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
            return StageStats(sorted.size, pct(0.50), pct(0.95), sorted.last())
        }
        val n = list.size.coerceAtLeast(1).toFloat()
        return TelemetrySummary(
            frames = list.size,
            eventToCaptureStart = stats(list.map { it.eventToCaptureStartMs }.filter { it >= 0 }),
            capture = stats(list.map { it.captureMs }),
            preprocess = stats(list.map { it.preprocessMs }),
            inference = stats(list.map { it.inferenceMs }),
            stage1 = stats(list.map { it.stage1Ms }),
            decision = stats(list.map { it.decisionMs }),
            captureToPrediction = stats(list.map { it.captureToPredictionMs }),
            eventToDecision = stats(list.map { it.eventToDecisionMs }.filter { it >= 0 }),
            overlayAttach = stats(overlay),
            droppedObsoleteTriggers = droppedObsoleteTriggers,
            roiScanRate = list.count { it.usedRoiScan } / n,
            stage2Rate = list.count { it.usedStage2 } / n
        )
    }
}
