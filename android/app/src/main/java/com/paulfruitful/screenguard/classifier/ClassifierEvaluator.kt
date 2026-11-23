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
    val baselineClassification: ClassificationResult?,
    val predictedPositive: Boolean,
    val baselinePredictedPositive: Boolean,
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
    val uncertainCount: Int,
    val uncertainRate: Float,
    val precision: Float,
    val recall: Float,
    val falsePositiveRate: Float,
    val falseNegativeRate: Float,
    val f1Score: Float,
    val accuracy: Float
)

data class PreprocessingModeBenchmark(
    val modeName: String,
    val lowLightSamplesEvaluated: Int,
    val recallOnLowLightPositives: Float,
    val fprOnOrdinaryDarkScenes: Float,
    val uncertainCount: Int,
    val avgLatencyMs: Float
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
    val baselineTestSplitMetrics: SplitEvaluationMetrics,
    val validationSplitMetrics: SplitEvaluationMetrics,
    val baselineValidationSplitMetrics: SplitEvaluationMetrics,
    val overallMetrics: SplitEvaluationMetrics,
    val baselineOverallMetrics: SplitEvaluationMetrics,
    val preprocessingBenchmarks: List<PreprocessingModeBenchmark>,
    val perCategoryMatrix: Map<String, Map<String, Int>>,
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
                        expectedGroup = obj.optString("expectedGroup", "NON_EXPLICIT"),
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

    private fun isPositiveCategory(category: String): Boolean {
        return category == ContentCategory.EXPLICIT.name ||
            category == ContentCategory.NUDITY.name ||
            category == ContentCategory.SEXUAL_ACTIVITY.name
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

            val baseline = if (classifier is LiteRtNsfwClassifier) {
                classifier.classifyBaselineSingleStage(bitmap)
            } else null

            val result = classifier.classify(bitmap)
            val predictedPos = isPositiveCategory(result.predictedCategory)
            val baselinePos = baseline != null && isPositiveCategory(baseline.predictedCategory)
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
                baselineClassification = baseline,
                predictedPositive = predictedPos,
                baselinePredictedPositive = baselinePos,
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
                    // Warm up both Stage 1 and Stage 2 interpreters before timing sample 0
                    classifier.classify(bmp)
                }

                try {
                    val baseline = if (classifier is LiteRtNsfwClassifier) {
                        classifier.classifyBaselineSingleStage(bmp)
                    } else null

                    val classification = classifier.classify(bmp)
                    val predictedPos = isPositiveCategory(classification.predictedCategory)
                    val baselinePos = baseline != null && isPositiveCategory(baseline.predictedPositiveCategory())
                    val gtPos = sample.isPositiveGroundTruth

                    results.add(
                        SingleSampleEvalResult(
                            sample = sample,
                            classification = classification,
                            baselineClassification = baseline,
                            predictedPositive = predictedPos,
                            baselinePredictedPositive = baselinePos,
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

            // Benchmark the 4 preprocessing modes independently on the low-light & dark-scene subset
            val preprocBenchmarks = ArrayList<PreprocessingModeBenchmark>(4)
            if (classifier is LiteRtNsfwClassifier) {
                val lowLightSamples = samples.filter {
                    it.id.contains("lowlight") ||
                        it.id.contains("ordinary_dark") ||
                        it.id == "val_erotic_art_01" ||
                        it.id == "test_nude_study_02"
                }
                for (mode in PreprocessingMode.values()) {
                    var posTotal = 0
                    var posDetected = 0
                    var negTotal = 0
                    var negFp = 0
                    var uncCount = 0
                    var totalLat = 0L
                    var evalCount = 0

                    for (ls in lowLightSamples) {
                        val bmp = context.assets.open("$EVAL_DIR/${ls.fileName}").use {
                            BitmapFactory.decodeStream(it)
                        } ?: continue
                        try {
                            val res = classifier.classifyWithFixedPreprocessing(
                                bitmap = bmp,
                                mode = mode,
                                enableStage2 = true
                            )
                            evalCount++
                            totalLat += res.processingDurationMs
                            if (res.predictedCategory == ContentCategory.UNCERTAIN.name) {
                                uncCount++
                            }
                            val predPos = isPositiveCategory(res.predictedCategory)
                            if (ls.isPositiveGroundTruth) {
                                posTotal++
                                if (predPos) posDetected++
                            } else {
                                negTotal++
                                if (predPos) negFp++
                            }
                        } finally {
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }

                    preprocBenchmarks.add(
                        PreprocessingModeBenchmark(
                            modeName = mode.name,
                            lowLightSamplesEvaluated = evalCount,
                            recallOnLowLightPositives = if (posTotal > 0) posDetected.toFloat() / posTotal else 1f,
                            fprOnOrdinaryDarkScenes = if (negTotal > 0) negFp.toFloat() / negTotal else 0f,
                            uncertainCount = uncCount,
                            avgLatencyMs = if (evalCount > 0) totalLat.toFloat() / evalCount else 0f
                        )
                    )
                }
            }

            val testResults = results.filter { it.sample.split == "test" }
            val valResults = results.filter { it.sample.split == "validation" }

            val testMetrics = computeMetrics("test", testResults, useBaseline = false)
            val baselineTestMetrics = computeMetrics("test_baseline", testResults, useBaseline = true)
            val valMetrics = computeMetrics("validation", valResults, useBaseline = false)
            val baselineValMetrics = computeMetrics("validation_baseline", valResults, useBaseline = true)
            val overallMetrics = computeMetrics("all", results, useBaseline = false)
            val baselineOverallMetrics = computeMetrics("all_baseline", results, useBaseline = true)

            // Build per-category confusion matrix (groundTruthExpectedGroup -> predictedCategory -> count)
            val perCategoryMatrix = LinkedHashMap<String, MutableMap<String, Int>>()
            for (r in results) {
                val gtGroup = r.sample.expectedGroup
                val predCat = r.classification.predictedCategory
                val row = perCategoryMatrix.getOrPut(gtGroup) { LinkedHashMap() }
                row[predCat] = (row[predCat] ?: 0) + 1
            }

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
                baselineTestSplitMetrics = baselineTestMetrics,
                validationSplitMetrics = valMetrics,
                baselineValidationSplitMetrics = baselineValMetrics,
                overallMetrics = overallMetrics,
                baselineOverallMetrics = baselineOverallMetrics,
                preprocessingBenchmarks = preprocBenchmarks,
                perCategoryMatrix = perCategoryMatrix,
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

    private fun ClassificationResult.predictedPositiveCategory(): String = this.predictedCategory

    private fun computeMetrics(
        splitName: String,
        items: List<SingleSampleEvalResult>,
        useBaseline: Boolean
    ): SplitEvaluationMetrics {
        var tp = 0
        var fp = 0
        var tn = 0
        var fn = 0
        var uncertain = 0

        for (item in items) {
            val gt = item.sample.isPositiveGroundTruth
            val pred = if (useBaseline) item.baselinePredictedPositive else item.predictedPositive
            val cat = if (useBaseline) {
                item.baselineClassification?.predictedCategory ?: item.classification.originalCategory
            } else {
                item.classification.predictedCategory
            }
            if (cat == ContentCategory.UNCERTAIN.name) {
                uncertain++
            }
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
        val uncRate = if (items.isNotEmpty()) {
            uncertain.toFloat() / items.size.toFloat()
        } else 0f

        return SplitEvaluationMetrics(
            splitName = splitName,
            totalSamples = items.size,
            truePositives = tp,
            falsePositives = fp,
            trueNegatives = tn,
            falseNegatives = fn,
            uncertainCount = uncertain,
            uncertainRate = uncRate,
            precision = precision,
            recall = recall,
            falsePositiveRate = fpr,
            falseNegativeRate = fnr,
            f1Score = f1,
            accuracy = acc
        )
    }

    private fun reportToJson(r: FullEvaluationReport): JSONObject {
        fun splitToJson(m: SplitEvaluationMetrics) = JSONObject().apply {
            put("splitName", m.splitName)
            put("totalSamples", m.totalSamples)
            put("truePositives", m.truePositives)
            put("falsePositives", m.falsePositives)
            put("trueNegatives", m.trueNegatives)
            put("falseNegatives", m.falseNegatives)
            put("uncertainCount", m.uncertainCount)
            put("uncertainRate", m.uncertainRate.toDouble())
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
        val preprocArr = JSONArray()
        for (pb in r.preprocessingBenchmarks) {
            preprocArr.put(
                JSONObject().apply {
                    put("modeName", pb.modeName)
                    put("lowLightSamplesEvaluated", pb.lowLightSamplesEvaluated)
                    put("recallOnLowLightPositives", pb.recallOnLowLightPositives.toDouble())
                    put("fprOnOrdinaryDarkScenes", pb.fprOnOrdinaryDarkScenes.toDouble())
                    put("uncertainCount", pb.uncertainCount)
                    put("avgLatencyMs", pb.avgLatencyMs.toDouble())
                }
            )
        }
        val matrixObj = JSONObject()
        r.perCategoryMatrix.forEach { (gt, row) ->
            val rObj = JSONObject()
            row.forEach { (pred, cnt) -> rObj.put(pred, cnt) }
            matrixObj.put(gt, rObj)
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
                    put("baselineCategory", s.baselineClassification?.predictedCategory ?: s.classification.originalCategory)
                    put("baselineConfidence", (s.baselineClassification?.confidence ?: s.classification.originalConfidence).toDouble())
                    put("predictedCategory", s.classification.predictedCategory)
                    put("confidence", s.classification.confidence.toDouble())
                    put("durationMs", s.classification.processingDurationMs)
                    put("stage2Triggered", s.classification.stage2Triggered)
                    put("stage2NsfwScore", s.classification.stage2NsfwScore.toDouble())
                    put("preprocessingApplied", s.classification.preprocessingApplied)
                    put("isLowLightFrame", s.classification.isLowLightFrame)
                    put("meanLuminance", s.classification.meanLuminance.toDouble())
                    put("predictedPositive", s.predictedPositive)
                    put("baselinePredictedPositive", s.baselinePredictedPositive)
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
            put("baselineTestSplitMetrics", splitToJson(r.baselineTestSplitMetrics))
            put("validationSplitMetrics", splitToJson(r.validationSplitMetrics))
            put("baselineValidationSplitMetrics", splitToJson(r.baselineValidationSplitMetrics))
            put("overallMetrics", splitToJson(r.overallMetrics))
            put("baselineOverallMetrics", splitToJson(r.baselineOverallMetrics))
            put("preprocessingBenchmarks", preprocArr)
            put("perCategoryMatrix", matrixObj)
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
}
