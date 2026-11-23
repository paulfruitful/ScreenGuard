package com.paulfruitful.screenguard.classifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Base64
import com.paulfruitful.screenguard.storage.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class EvalSampleMetadata(
    val id: String,
    val fileName: String,
    val split: String,
    val expectedGroup: String,
    val isPositiveGroundTruth: Boolean,
    val categoryLabel: String,
    val description: String
)

data class SingleSampleEvalResult(
    val sample: EvalSampleMetadata,
    val classification: ClassificationResult,
    val predictedPositive: Boolean,
    val isCorrectBinary: Boolean,
    val isFalsePositive: Boolean,
    val isFalseNegative: Boolean,
    val previewBase64: String?
)

data class SplitEvaluationMetrics(
    val splitName: String,
    val totalSamples: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val trueNegatives: Int,
    val falseNegatives: Int,
    val precision: Float,
    val recall: Float,
    val falsePositiveRate: Float,
    val falseNegativeRate: Float,
    val f1Score: Float,
    val accuracy: Float
)

data class FullEvaluationReport(
    val modelId: String,
    val modelVersion: String,
    val modelSizeBytes: Long,
    val initDurationMs: Long,
    val inputTensorSpec: String,
    val outputTensorSpec: String,
    val activeDelegate: String,
    val thresholdUsed: Float,
    val testSplitMetrics: SplitEvaluationMetrics,
    val validationSplitMetrics: SplitEvaluationMetrics,
    val overallMetrics: SplitEvaluationMetrics,
    val avgLatencyMs: Float,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val nativeHeapAllocatedKb: Long,
    val javaHeapAllocatedKb: Long,
    val cpuBenchmark: DelegateBenchmarkResult?,
    val nnapiBenchmark: DelegateBenchmarkResult?,
    val sampleResults: List<SingleSampleEvalResult>,
    val timestamp: Long = System.currentTimeMillis()
)

object ClassifierEvaluator {
    private const val EVAL_DIR = "eval_suite"
    private const val MANIFEST_PATH = "$EVAL_DIR/manifest.json"

    @Volatile
    private var cachedLastReport: FullEvaluationReport? = null

    fun getLastEvaluationReport(): FullEvaluationReport? = cachedLastReport

    fun listEvaluationSamples(context: Context): List<EvalSampleMetadata> {
        return try {
            val jsonText = context.assets.open(MANIFEST_PATH).bufferedReader().use { it.readText() }
            val arr = JSONArray(jsonText)
            val list = ArrayList<EvalSampleMetadata>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    EvalSampleMetadata(
                        id = obj.optString("id"),
                        fileName = obj.optString("fileName"),
                        split = obj.optString("split", "test"),
                        expectedGroup = obj.optString("expectedGroup", "SAFE"),
                        isPositiveGroundTruth = obj.optBoolean("isPositiveGroundTruth", false),
                        categoryLabel = obj.optString("categoryLabel", ""),
                        description = obj.optString("description", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun classifySingleSample(
        context: Context,
        sampleId: String,
        includePreviewBase64: Boolean = true
    ): SingleSampleEvalResult? = withContext(Dispatchers.Default) {
        DetectionEngine.ensureModelLoaded(context)
        val classifier = DetectionEngine.getActiveClassifier()
        val samples = listEvaluationSamples(context)
        val meta = samples.firstOrNull { it.id == sampleId } ?: return@withContext null

        var bitmap: Bitmap? = null
        try {
            val assetPath = "$EVAL_DIR/${meta.fileName}"
            bitmap = context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext null

            val result = classifier.classify(bitmap)
            val predictedPos = result.predictedCategory == ContentCategory.EXPLICIT.name ||
                result.predictedCategory == ContentCategory.NUDITY.name
            val gtPos = meta.isPositiveGroundTruth

            val isFp = !gtPos && predictedPos
            val isFn = gtPos && !predictedPos
            val isCorrect = (gtPos == predictedPos)

            val preview = if (includePreviewBase64) {
                val out = ByteArrayOutputStream(8192)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } else {
                null
            }

            SingleSampleEvalResult(
                sample = meta,
                classification = result,
                predictedPositive = predictedPos,
                isCorrectBinary = isCorrect,
                isFalsePositive = isFp,
                isFalseNegative = isFn,
                previewBase64 = preview
            )
        } finally {
            bitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    suspend fun runFullEvaluationSuite(
        context: Context,
        includeDelegateBenchmark: Boolean = true
    ): FullEvaluationReport = withContext(Dispatchers.Default) {
        DetectionEngine.ensureModelLoaded(context)
        val classifier = DetectionEngine.getActiveClassifier()
        val samples = listEvaluationSamples(context)
        val results = ArrayList<SingleSampleEvalResult>(samples.size)

        var benchmarkSampleBitmap: Bitmap? = null
        try {
            for ((idx, sample) in samples.withIndex()) {
                val assetPath = "$EVAL_DIR/${sample.fileName}"
                val bmp = context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: continue

                if (idx == 0) {
                    if (includeDelegateBenchmark) {
                        benchmarkSampleBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    // Execute 1 warmup pass so one-time XNNPACK arena allocation is not attributed to sample 0
                    classifier.classify(bmp)
                }

                try {
                    val classification = classifier.classify(bmp)
                    val predictedPos =
                        classification.predictedCategory == ContentCategory.EXPLICIT.name ||
                            classification.predictedCategory == ContentCategory.NUDITY.name
                    val gtPos = sample.isPositiveGroundTruth

                    results.add(
                        SingleSampleEvalResult(
                            sample = sample,
                            classification = classification,
                            predictedPositive = predictedPos,
                            isCorrectBinary = (gtPos == predictedPos),
                            isFalsePositive = (!gtPos && predictedPos),
                            isFalseNegative = (gtPos && !predictedPos),
                            previewBase64 = null
                        )
                    )
                } finally {
                    if (!bmp.isRecycled) {
                        bmp.recycle()
                    }
                }
            }

            val testResults = results.filter { it.sample.split == "test" }
            val valResults = results.filter { it.sample.split == "validation" }

            val testMetrics = computeMetrics("test", testResults)
            val valMetrics = computeMetrics("validation", valResults)
            val overallMetrics = computeMetrics("all", results)

            val latencies = results
                .map { it.classification.processingDurationMs }
                .filter { it > 0L }
                .sorted()

            val avgLatency = if (latencies.isNotEmpty()) latencies.average().toFloat() else 0f
            val p50 = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else 0L
            val p95 = if (latencies.isNotEmpty()) {
                val i95 = ((latencies.size - 1) * 0.95).toInt().coerceIn(0, latencies.size - 1)
                latencies[i95]
            } else 0L
            val minLat = latencies.firstOrNull() ?: 0L
            val maxLat = latencies.lastOrNull() ?: 0L

            var cpuBench: DelegateBenchmarkResult? = null
            var nnapiBench: DelegateBenchmarkResult? = null
            if (includeDelegateBenchmark && classifier is LiteRtNsfwClassifier && benchmarkSampleBitmap != null) {
                cpuBench = classifier.benchmarkDelegate(benchmarkSampleBitmap!!, testNnapi = false, iterations = 10)
                nnapiBench = classifier.benchmarkDelegate(benchmarkSampleBitmap!!, testNnapi = true, iterations = 10)
            }

            val runtime = Runtime.getRuntime()
            val javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
            val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L

            val thresholdUsed = results.firstOrNull()?.classification?.thresholdUsed ?: 0.75f

            val report = FullEvaluationReport(
                modelId = classifier.modelId,
                modelVersion = classifier.modelVersion,
                modelSizeBytes = classifier.getModelSizeBytes(),
                initDurationMs = classifier.getInitializationDurationMs(),
                inputTensorSpec = classifier.getInputTensorSpec(),
                outputTensorSpec = classifier.getOutputTensorSpec(),
                activeDelegate = classifier.getActiveDelegateName(),
                thresholdUsed = thresholdUsed,
                testSplitMetrics = testMetrics,
                validationSplitMetrics = valMetrics,
                overallMetrics = overallMetrics,
                avgLatencyMs = avgLatency,
                p50LatencyMs = p50,
                p95LatencyMs = p95,
                minLatencyMs = minLat,
                maxLatencyMs = maxLat,
                nativeHeapAllocatedKb = nativeHeapKb,
                javaHeapAllocatedKb = javaHeapKb,
                cpuBenchmark = cpuBench,
                nnapiBenchmark = nnapiBench,
                sampleResults = results
            )
            cachedLastReport = report
            try {
                LocalStorage.saveEvaluationReportJson(context, reportToJson(report).toString())
            } catch (_: Exception) {
            }
            return@withContext report
        } finally {
            benchmarkSampleBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    private fun reportToJson(r: FullEvaluationReport): JSONObject {
        fun splitToJson(m: SplitEvaluationMetrics) = JSONObject().apply {
            put("splitName", m.splitName)
            put("totalSamples", m.totalSamples)
            put("truePositives", m.truePositives)
            put("falsePositives", m.falsePositives)
            put("trueNegatives", m.trueNegatives)
            put("falseNegatives", m.falseNegatives)
            put("precision", m.precision.toDouble())
            put("recall", m.recall.toDouble())
            put("falsePositiveRate", m.falsePositiveRate.toDouble())
            put("falseNegativeRate", m.falseNegativeRate.toDouble())
            put("f1Score", m.f1Score.toDouble())
            put("accuracy", m.accuracy.toDouble())
        }
        fun benchToJson(b: DelegateBenchmarkResult?) = if (b == null) JSONObject.NULL else JSONObject().apply {
            put("delegateName", b.delegateName)
            put("supported", b.supported)
            put("initDurationMs", b.initDurationMs)
            put("avgLatencyMs", b.avgLatencyMs.toDouble())
            put("p50LatencyMs", b.p50LatencyMs)
            put("p95LatencyMs", b.p95LatencyMs)
            put("minLatencyMs", b.minLatencyMs)
            put("maxLatencyMs", b.maxLatencyMs)
            put("error", b.error ?: JSONObject.NULL)
        }
        val samplesArr = JSONArray()
        for (s in r.sampleResults) {
            val scoresObj = JSONObject()
            s.classification.scores.forEach { (k, v) -> scoresObj.put(k, v.toDouble()) }
            samplesArr.put(
                JSONObject().apply {
                    put("id", s.sample.id)
                    put("split", s.sample.split)
                    put("categoryLabel", s.sample.categoryLabel)
                    put("expectedGroup", s.sample.expectedGroup)
                    put("isPositiveGroundTruth", s.sample.isPositiveGroundTruth)
                    put("predictedCategory", s.classification.predictedCategory)
                    put("confidence", s.classification.confidence.toDouble())
                    put("durationMs", s.classification.processingDurationMs)
                    put("predictedPositive", s.predictedPositive)
                    put("isCorrectBinary", s.isCorrectBinary)
                    put("isFalsePositive", s.isFalsePositive)
                    put("isFalseNegative", s.isFalseNegative)
                    put("scores", scoresObj)
                }
            )
        }
        return JSONObject().apply {
            put("modelId", r.modelId)
            put("modelVersion", r.modelVersion)
            put("modelSizeBytes", r.modelSizeBytes)
            put("initDurationMs", r.initDurationMs)
            put("inputTensorSpec", r.inputTensorSpec)
            put("outputTensorSpec", r.outputTensorSpec)
            put("activeDelegate", r.activeDelegate)
            put("thresholdUsed", r.thresholdUsed.toDouble())
            put("testSplitMetrics", splitToJson(r.testSplitMetrics))
            put("validationSplitMetrics", splitToJson(r.validationSplitMetrics))
            put("overallMetrics", splitToJson(r.overallMetrics))
            put("avgLatencyMs", r.avgLatencyMs.toDouble())
            put("p50LatencyMs", r.p50LatencyMs)
            put("p95LatencyMs", r.p95LatencyMs)
            put("minLatencyMs", r.minLatencyMs)
            put("maxLatencyMs", r.maxLatencyMs)
            put("nativeHeapAllocatedKb", r.nativeHeapAllocatedKb)
            put("javaHeapAllocatedKb", r.javaHeapAllocatedKb)
            put("cpuBenchmark", benchToJson(r.cpuBenchmark))
            put("nnapiBenchmark", benchToJson(r.nnapiBenchmark))
            put("samples", samplesArr)
            put("timestamp", r.timestamp)
        }
    }

    private fun computeMetrics(
        splitName: String,
        items: List<SingleSampleEvalResult>
    ): SplitEvaluationMetrics {
        var tp = 0
        var fp = 0
        var tn = 0
        var fn = 0

        for (item in items) {
            val gt = item.sample.isPositiveGroundTruth
            val pred = item.predictedPositive
            if (gt && pred) tp++
            else if (!gt && pred) fp++
            else if (!gt && !pred) tn++
            else if (gt && !pred) fn++
        }

        val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp).toFloat() else 1.0f
        val recall = if (tp + fn > 0) tp.toFloat() / (tp + fn).toFloat() else 1.0f
        val fpr = if (fp + tn > 0) fp.toFloat() / (fp + tn).toFloat() else 0.0f
        val fnr = if (fn + tp > 0) fn.toFloat() / (fn + tp).toFloat() else 0.0f
        val f1 = if (precision + recall > 0f) {
            2f * (precision * recall) / (precision + recall)
        } else 0f
        val acc = if (items.isNotEmpty()) {
            (tp + tn).toFloat() / items.size.toFloat()
        } else 1.0f

        return SplitEvaluationMetrics(
            splitName = splitName,
            totalSamples = items.size,
            truePositives = tp,
            falsePositives = fp,
            trueNegatives = tn,
            falseNegatives = fn,
            precision = precision,
            recall = recall,
            falsePositiveRate = fpr,
            falseNegativeRate = fnr,
            f1Score = f1,
            accuracy = acc
        )
    }
}
