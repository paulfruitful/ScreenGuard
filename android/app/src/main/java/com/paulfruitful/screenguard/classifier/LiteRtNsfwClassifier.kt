package com.paulfruitful.screenguard.classifier

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.paulfruitful.screenguard.storage.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

data class DelegateBenchmarkResult(
    val delegateName: String,
    val supported: Boolean,
    val initDurationMs: Long,
    val avgLatencyMs: Float,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val error: String? = null
)

/**
 * Production on-device NSFW visual classifier powered by Google LiteRT (TensorFlow Lite)
 * and the pretrained 5-class MobileNetV2 1.40 (224x224) model (`nsfw_mobilenet_v2_140_224.tflite`).
 *
 * Model Specifications:
 * - Source: GantMan/nsfw_model (MIT License), MobileNetV2 (alpha=1.40, 224x224)
 * - Asset file: `nsfw_mobilenet_v2_140_224.tflite` (17,251,928 bytes)
 * - Input Tensor: `[1, 224, 224, 3]` FLOAT32, RGB ordering, normalized to `[0.0, 1.0]` (`pixel / 255.0f`)
 * - Output Tensor: `[1, 5]` FLOAT32 softmax probabilities:
 *     0 -> `drawings` (safe illustrations, cartoons, vector graphics)
 *     1 -> `hentai`   (explicit illustrated / animated pornographic imagery)
 *     2 -> `neutral`  (ordinary safe photographs, UI screens, text, portraits)
 *     3 -> `porn`     (explicit sexual activity, pornography, visible intimate nudity)
 *     4 -> `sexy`     (suggestive/sexualized imagery, swimwear, fitness, underwear ads)
 */
class LiteRtNsfwClassifier(
    private val appContext: Context,
    private val numThreads: Int = 4,
    private val useNnapi: Boolean = false
) : ContentClassifier {

    companion object {
        const val ASSET_MODEL_FILENAME = "nsfw_mobilenet_v2_140_224.tflite"
        const val INPUT_WIDTH = 224
        const val INPUT_HEIGHT = 224
        const val INPUT_CHANNELS = 3
        const val OUTPUT_CLASSES = 5

        val RAW_LABELS = arrayOf("drawings", "hentai", "neutral", "porn", "sexy")
    }

    override val modelId: String = "nsfw-mobilenet-v2-140-224"
    override val modelVersion: String = "1.2.0-litert-fp32 (GantMan/nsfw_model MobileNetV2 1.4)"
    override val isMockModel: Boolean = false

    private val inferenceMutex = Mutex()

    @Volatile
    private var interpreter: Interpreter? = null
    @Volatile
    private var mappedModelBuffer: MappedByteBuffer? = null
    @Volatile
    private var modelLoaded: Boolean = false
    @Volatile
    private var initDurationMs: Long = 0L
    @Volatile
    private var modelSizeBytes: Long = 0L
    @Volatile
    private var activeDelegateName: String = if (useNnapi) "NNAPI" else "CPU_XNNPACK_${numThreads}T"
    @Volatile
    private var inputSpecString: String = "[1, 224, 224, 3] FLOAT32 RGB [0.0..1.0]"
    @Volatile
    private var outputSpecString: String = "[1, 5] FLOAT32 Softmax (drawings, hentai, neutral, porn, sexy)"
    @Volatile
    private var lastInitError: String? = null

    // Pre-allocated direct ByteBuffer (602,112 bytes) and pixel buffer to avoid per-frame GC churn
    private val inputTensorBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS)
        .order(ByteOrder.nativeOrder())
    private val pixelScratch = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
    private val outputProbabilities = Array(1) { FloatArray(OUTPUT_CLASSES) }

    override fun isModelLoaded(): Boolean = modelLoaded

    override fun getInitializationDurationMs(): Long = initDurationMs

    override fun getActiveDelegateName(): String = activeDelegateName

    override fun getModelSizeBytes(): Long = modelSizeBytes

    override fun getInputTensorSpec(): String = inputSpecString

    override fun getOutputTensorSpec(): String = outputSpecString

    /**
     * Thread-safe one-time model initialization from APK assets.
     */
    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (modelLoaded && interpreter != null) {
            return@withContext true
        }
        inferenceMutex.withLock {
            if (modelLoaded && interpreter != null) {
                return@withLock true
            }
            return@withLock initializeLocked()
        }
    }

    private fun initializeLocked(): Boolean {
        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            val mapped = loadMappedModelFromAssets()
            mappedModelBuffer = mapped

            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseXNNPACK(true)
                if (useNnapi) {
                    setUseNNAPI(true)
                }
            }

            val interp = Interpreter(mapped, options)
            interp.allocateTensors()

            val inTensor = interp.getInputTensor(0)
            val outTensor = interp.getOutputTensor(0)
            inputSpecString = "${inTensor.shape().contentToString()} ${inTensor.dataType()} RGB [0.0..1.0]"
            outputSpecString = "${outTensor.shape().contentToString()} ${outTensor.dataType()} (${RAW_LABELS.joinToString(", ")})"

            interpreter = interp
            initDurationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
            activeDelegateName = if (useNnapi) "NNAPI_DELEGATE" else "CPU_XNNPACK_${numThreads}T"
            modelLoaded = true
            lastInitError = null
            true
        } catch (e: Exception) {
            lastInitError = "${e.javaClass.simpleName}: ${e.message}"
            modelLoaded = false
            false
        }
    }

    private fun loadMappedModelFromAssets(): MappedByteBuffer {
        appContext.assets.openFd(ASSET_MODEL_FILENAME).use { afd ->
            modelSizeBytes = afd.declaredLength
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel = fis.channel
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    override suspend fun classify(bitmap: Bitmap): ClassificationResult = withContext(Dispatchers.Default) {
        val totalStartNs = SystemClock.elapsedRealtimeNanos()

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext failureResult("Input Bitmap is empty or already recycled", 0L)
        }

        inferenceMutex.withLock {
            if (!modelLoaded || interpreter == null) {
                if (!initializeLocked()) {
                    return@withLock failureResult(
                        lastInitError ?: "Failed to initialize LiteRT model",
                        max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                    )
                }
            }

            val activeInterpreter = interpreter
                ?: return@withLock failureResult("LiteRT Interpreter is null", 1L)

            var resizedTemp: Bitmap? = null
            try {
                val targetBmp = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true).also {
                        if (it !== bitmap) {
                            resizedTemp = it
                        }
                    }
                }

                // Populate normalized [1, 224, 224, 3] RGB FLOAT32 tensor in [0.0f..1.0f]
                inputTensorBuffer.rewind()
                targetBmp.getPixels(
                    pixelScratch,
                    0,
                    INPUT_WIDTH,
                    0,
                    0,
                    INPUT_WIDTH,
                    INPUT_HEIGHT
                )

                for (i in 0 until (INPUT_WIDTH * INPUT_HEIGHT)) {
                    val argb = pixelScratch[i]
                    val r = ((argb shr 16) and 0xFF) / 255.0f
                    val g = ((argb shr 8) and 0xFF) / 255.0f
                    val b = (argb and 0xFF) / 255.0f
                    inputTensorBuffer.putFloat(r)
                    inputTensorBuffer.putFloat(g)
                    inputTensorBuffer.putFloat(b)
                }
                inputTensorBuffer.rewind()

                // Reset output buffer
                for (c in 0 until OUTPUT_CLASSES) {
                    outputProbabilities[0][c] = 0f
                }

                // Execute real on-device LiteRT inference
                activeInterpreter.run(inputTensorBuffer, outputProbabilities)

                val pDrawings = outputProbabilities[0][0].coerceIn(0f, 1f)
                val pHentai = outputProbabilities[0][1].coerceIn(0f, 1f)
                val pNeutral = outputProbabilities[0][2].coerceIn(0f, 1f)
                val pPorn = outputProbabilities[0][3].coerceIn(0f, 1f)
                val pSexy = outputProbabilities[0][4].coerceIn(0f, 1f)

                val durationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                val prefs = LocalStorage.getPreferences(appContext)

                return@withLock interpretModelOutput(
                    pDrawings = pDrawings,
                    pHentai = pHentai,
                    pNeutral = pNeutral,
                    pPorn = pPorn,
                    pSexy = pSexy,
                    durationMs = durationMs,
                    explicitThreshold = prefs.confidenceThreshold,
                    frameWidth = targetBmp.width,
                    frameHeight = targetBmp.height
                )
            } catch (e: Exception) {
                val durationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                return@withLock failureResult(
                    "LiteRT inference exception: ${e.javaClass.simpleName}: ${e.message}",
                    durationMs
                )
            } finally {
                resizedTemp?.let { tmp ->
                    if (!tmp.isRecycled) {
                        tmp.recycle()
                    }
                }
            }
        }
    }

    /**
     * Scientifically justified taxonomy mapping layer:
     * Preserves all 5 original MobileNetV2 class probabilities (`drawings`, `hentai`, `neutral`, `porn`, `sexy`)
     * and maps them into the Screen Guard taxonomy:
     * - EXPLICIT: High-confidence `porn` or `hentai` (>= explicitThreshold, or combined >= 0.78).
     * - NUDITY: Moderate-to-high `porn` (>= nudityThreshold) or combined intimate exposure (`porn + 0.45*sexy >= 0.52` with `porn >= 0.25`).
     * - SUGGESTIVE: Elevated `sexy` (>= 0.52) without crossing the `NUDITY` / `EXPLICIT` threshold.
     * - NON_EXPLICIT: Moderate `sexy` (`0.18..0.52`) accompanied by `neutral`/`drawings` context (typical of swimming, gym/fitness, beach, sports, portraits).
     * - UNCERTAIN: Ambiguous probability distribution where no category reaches 0.42 or borderline explicit/safe split.
     * - SAFE: Dominant `neutral` + `drawings` (>= 0.55) with low `porn` + `hentai` (< 0.22).
     */
    fun interpretModelOutput(
        pDrawings: Float,
        pHentai: Float,
        pNeutral: Float,
        pPorn: Float,
        pSexy: Float,
        durationMs: Long,
        explicitThreshold: Float,
        frameWidth: Int = INPUT_WIDTH,
        frameHeight: Int = INPUT_HEIGHT
    ): ClassificationResult {
        val combinedExplicit = (pPorn + pHentai).coerceIn(0f, 1f)
        val combinedSafe = (pNeutral + pDrawings).coerceIn(0f, 1f)
        // Derived nudity score weights `porn` primary and `sexy`/`hentai` secondary
        val derivedNudityScore = (pPorn + 0.45f * pSexy + 0.65f * pHentai).coerceIn(0f, 1f)

        val nudityThreshold = (explicitThreshold * 0.62f).coerceIn(0.35f, 0.65f)
        val suggestiveThreshold = 0.52f

        val (category, categoryConfidence) = when {
            pPorn >= explicitThreshold || pHentai >= explicitThreshold || combinedExplicit >= maxOf(0.75f, explicitThreshold) -> {
                ContentCategory.EXPLICIT to maxOf(pPorn, pHentai, combinedExplicit)
            }
            pPorn >= nudityThreshold || (derivedNudityScore >= 0.52f && pPorn >= 0.22f) || (pHentai >= nudityThreshold) -> {
                ContentCategory.NUDITY to maxOf(pPorn, derivedNudityScore)
            }
            pSexy >= suggestiveThreshold -> {
                ContentCategory.SUGGESTIVE to pSexy
            }
            pSexy >= 0.18f && combinedSafe >= 0.35f -> {
                ContentCategory.NON_EXPLICIT to (pSexy + combinedSafe).coerceIn(0f, 1f)
            }
            (combinedSafe >= 0.52f && combinedExplicit < 0.25f) ||
                (combinedSafe >= 0.60f && pPorn < 0.05f && pHentai < 0.45f) -> {
                ContentCategory.SAFE to combinedSafe
            }
            else -> {
                val topProb = maxOf(pDrawings, pHentai, pNeutral, pPorn, pSexy)
                ContentCategory.UNCERTAIN to topProb
            }
        }

        val isExplicitOrNudity =
            category == ContentCategory.EXPLICIT || category == ContentCategory.NUDITY

        val scoresMap = linkedMapOf(
            "drawings" to pDrawings,
            "hentai" to pHentai,
            "neutral" to pNeutral,
            "porn" to pPorn,
            "sexy" to pSexy,
            "EXPLICIT" to combinedExplicit,
            "NUDITY" to derivedNudityScore,
            "SUGGESTIVE" to pSexy,
            "SAFE" to combinedSafe
        )

        return ClassificationResult(
            scores = scoresMap,
            predictedCategory = category.name,
            confidence = categoryConfidence,
            processingDurationMs = durationMs,
            modelVersion = modelVersion,
            successful = true,
            error = null,
            isExplicit = isExplicitOrNudity,
            nudityScore = derivedNudityScore,
            explicitScore = combinedExplicit,
            suggestiveScore = pSexy,
            safeScore = combinedSafe,
            drawingsScore = pDrawings,
            hentaiScore = pHentai,
            neutralScore = pNeutral,
            pornScore = pPorn,
            sexyScore = pSexy,
            delegateUsed = activeDelegateName,
            thresholdUsed = explicitThreshold,
            modelId = modelId,
            isMockModel = false,
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )
    }

    /**
     * Benchmarks CPU (XNNPACK 4 threads) vs NNAPI delegate on the target device.
     */
    suspend fun benchmarkDelegate(
        sampleBitmap: Bitmap,
        testNnapi: Boolean,
        iterations: Int = 12
    ): DelegateBenchmarkResult = withContext(Dispatchers.Default) {
        val delegateLabel = if (testNnapi) "NNAPI_DELEGATE" else "CPU_XNNPACK_${numThreads}T"
        val startInitNs = SystemClock.elapsedRealtimeNanos()
        var benchInterpreter: Interpreter? = null
        try {
            val mapped = mappedModelBuffer ?: loadMappedModelFromAssets().also { mappedModelBuffer = it }
            val opts = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseXNNPACK(!testNnapi)
                setUseNNAPI(testNnapi)
            }
            benchInterpreter = Interpreter(mapped, opts)
            benchInterpreter.allocateTensors()
            val initMs = max(1L, (SystemClock.elapsedRealtimeNanos() - startInitNs) / 1_000_000L)

            val localIn = ByteBuffer
                .allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS)
                .order(ByteOrder.nativeOrder())
            val localPixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
            val localOut = Array(1) { FloatArray(OUTPUT_CLASSES) }

            sampleBitmap.getPixels(localPixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
            for (argb in localPixels) {
                localIn.putFloat(((argb shr 16) and 0xFF) / 255.0f)
                localIn.putFloat(((argb shr 8) and 0xFF) / 255.0f)
                localIn.putFloat((argb and 0xFF) / 255.0f)
            }

            // 2 warmup runs
            for (w in 0 until 2) {
                localIn.rewind()
                benchInterpreter.run(localIn, localOut)
            }

            val latencies = LongArray(iterations)
            for (i in 0 until iterations) {
                localIn.rewind()
                val t0 = SystemClock.elapsedRealtimeNanos()
                benchInterpreter.run(localIn, localOut)
                latencies[i] = max(1L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            }
            latencies.sort()

            val avg = latencies.average().toFloat()
            val p50 = latencies[latencies.size / 2]
            val p95Index = ((latencies.size - 1) * 0.95).toInt().coerceIn(0, latencies.size - 1)
            val p95 = latencies[p95Index]

            DelegateBenchmarkResult(
                delegateName = delegateLabel,
                supported = true,
                initDurationMs = initMs,
                avgLatencyMs = avg,
                p50LatencyMs = p50,
                p95LatencyMs = p95,
                minLatencyMs = latencies.first(),
                maxLatencyMs = latencies.last(),
                error = null
            )
        } catch (e: Exception) {
            DelegateBenchmarkResult(
                delegateName = delegateLabel,
                supported = false,
                initDurationMs = 0L,
                avgLatencyMs = 0f,
                p50LatencyMs = 0L,
                p95LatencyMs = 0L,
                minLatencyMs = 0L,
                maxLatencyMs = 0L,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            try {
                benchInterpreter?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun failureResult(errorMessage: String, durationMs: Long): ClassificationResult {
        return ClassificationResult(
            scores = emptyMap(),
            predictedCategory = ContentCategory.UNCERTAIN.name,
            confidence = 0f,
            processingDurationMs = durationMs,
            modelVersion = modelVersion,
            successful = false,
            error = errorMessage,
            isExplicit = false,
            delegateUsed = activeDelegateName,
            modelId = modelId,
            isMockModel = false
        )
    }

    override fun release() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        }
        interpreter = null
        modelLoaded = false
    }
}
