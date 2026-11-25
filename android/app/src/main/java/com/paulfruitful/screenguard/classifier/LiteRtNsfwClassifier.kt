package com.paulfruitful.screenguard.classifier

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.paulfruitful.screenguard.accessibility.ScreenTextContextAnalyzer
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

data class RawStage1Scores(
    val drawings: Float,
    val hentai: Float,
    val neutral: Float,
    val porn: Float,
    val sexy: Float,
    val durationMs: Long
)

/**
 * Milestone 2.1 Two-Stage On-Device Visual Content Classifier:
 *
 * - Stage 1 (Primary): `nsfw_mobilenet_v2_140_224.tflite` (GantMan/nsfw_model MobileNetV2 1.4,
 *   MIT License, 17.25 MB, 5 classes: `drawings`, `hentai`, `neutral`, `porn`, `sexy`,
 *   input `[1, 224, 224, 3]` FLOAT32 RGB `[0.0..1.0]`).
 * - Stage 2 (Selective Verifier): [OpenNsfwResNetClassifier] (`open_nsfw_resnet50.tflite`,
 *   Yahoo OpenNSFW ResNet-50, BSD-2-Clause License, 5.96 MB, 2 classes: `sfw`, `nsfw`,
 *   input `[1, 224, 224, 3]` FLOAT32 BGR mean-subtracted `[B-104, G-117, R-123]`).
 * - Low-Light Preprocessing ([LowLightPreprocessor]): Supports `ORIGINAL`, `GAMMA_CORRECTION`,
 *   `CLAHE_LOCAL_CONTRAST`, and `BRIGHTNESS_CONTRAST_NORM`. Preserves the original unenhanced
 *   prediction and requires corroboration so enhancement noise never manufactures false positives.
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

    override val modelId: String = "nsfw-mobilenetv2-140+open-nsfw-resnet50"
    override val modelVersion: String =
        "2.1.0-cascade (MobileNetV2 1.4 MIT + OpenNSFW ResNet-50 BSD-2)"
    override val isMockModel: Boolean = false

    private val inferenceMutex = Mutex()
    val secondStageClassifier = OpenNsfwResNetClassifier(appContext, numThreads)

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
    private var inputSpecString: String =
        "S1:[1,224,224,3] RGB [0..1] + S2:[1,224,224,3] BGR MeanSub"
    @Volatile
    private var outputSpecString: String =
        "S1:[1,5] (drawings,hentai,neutral,porn,sexy) + S2:[1,2] (sfw,nsfw)"
    @Volatile
    private var lastInitError: String? = null

    private val inputTensorBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS)
        .order(ByteOrder.nativeOrder())
    private val pixelScratch = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
    private val outputProbabilities = Array(1) { FloatArray(OUTPUT_CLASSES) }

    override fun isModelLoaded(): Boolean = modelLoaded && secondStageClassifier.isModelLoaded()

    override fun getInitializationDurationMs(): Long =
        initDurationMs + secondStageClassifier.getInitDurationMs()

    override fun getActiveDelegateName(): String = activeDelegateName

    override fun getModelSizeBytes(): Long =
        modelSizeBytes + secondStageClassifier.getModelSizeBytes()

    override fun getInputTensorSpec(): String = inputSpecString

    override fun getOutputTensorSpec(): String = outputSpecString

    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Default) {
        val s2Ready = secondStageClassifier.ensureInitialized()
        if (modelLoaded && interpreter != null) {
            return@withContext s2Ready
        }
        val s1Ready = inferenceMutex.withLock {
            if (modelLoaded && interpreter != null) {
                return@withLock true
            }
            initializeLocked()
        }
        return@withContext s1Ready && s2Ready
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
            inputSpecString =
                "S1:${inTensor.shape().contentToString()} RGB [0..1] | S2:[1,224,224,3] BGR [-104,-117,-123]"
            outputSpecString =
                "S1:${outTensor.shape().contentToString()} (${RAW_LABELS.joinToString(",")}) | S2:[1,2] (sfw,nsfw)"

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
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength
                )
            }
        }
    }

    private fun runStage1Locked(targetBmp: Bitmap): RawStage1Scores {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val activeInterpreter = interpreter
            ?: throw IllegalStateException("Stage 1 LiteRT interpreter not initialized")

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

        for (c in 0 until OUTPUT_CLASSES) {
            outputProbabilities[0][c] = 0f
        }

        activeInterpreter.run(inputTensorBuffer, outputProbabilities)
        val dur = max(1L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)

        return RawStage1Scores(
            drawings = outputProbabilities[0][0].coerceIn(0f, 1f),
            hentai = outputProbabilities[0][1].coerceIn(0f, 1f),
            neutral = outputProbabilities[0][2].coerceIn(0f, 1f),
            porn = outputProbabilities[0][3].coerceIn(0f, 1f),
            sexy = outputProbabilities[0][4].coerceIn(0f, 1f),
            durationMs = dur
        )
    }

    /**
     * Executes the exact Milestone 2 single-stage baseline (unenhanced original frame, Stage 1 only)
     * for side-by-side Before vs After benchmarking.
     */
    suspend fun classifyBaselineSingleStage(bitmap: Bitmap): ClassificationResult =
        withContext(Dispatchers.Default) {
            val totalStartNs = SystemClock.elapsedRealtimeNanos()
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return@withContext failureResult("Recycled bitmap", 0L)
            }
            val prefs = LocalStorage.getPreferences(appContext)
            var resizedTemp: Bitmap? = null
            try {
                val targetBmp = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true).also {
                        if (it !== bitmap) resizedTemp = it
                    }
                }
                val illum = LowLightPreprocessor.analyzeIllumination(targetBmp)
                val s1 = inferenceMutex.withLock {
                    if (!modelLoaded || interpreter == null) {
                        if (!initializeLocked()) {
                            return@withLock null
                        }
                    }
                    runStage1Locked(targetBmp)
                } ?: return@withContext failureResult("Stage 1 init failed", 1L)

                val totalMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                return@withContext interpretBaselineSingleStage(
                    s1 = s1,
                    durationMs = totalMs,
                    explicitThreshold = prefs.confidenceThreshold,
                    illum = illum
                )
            } finally {
                resizedTemp?.let { if (!it.isRecycled) it.recycle() }
            }
        }

    /**
     * Benchmarks a specific [PreprocessingMode] independently on Stage 1 + Stage 2.
     */
    suspend fun classifyWithFixedPreprocessing(
        bitmap: Bitmap,
        mode: PreprocessingMode,
        enableStage2: Boolean = true
    ): ClassificationResult = withContext(Dispatchers.Default) {
        val totalStartNs = SystemClock.elapsedRealtimeNanos()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext failureResult("Recycled bitmap", 0L)
        }
        val prefs = LocalStorage.getPreferences(appContext)
        var resizedTemp: Bitmap? = null
        var enhancedTemp: Bitmap? = null
        try {
            val targetBmp = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true).also {
                    if (it !== bitmap) resizedTemp = it
                }
            }
            val illum = LowLightPreprocessor.analyzeIllumination(targetBmp)
            val evalBmp = if (mode == PreprocessingMode.ORIGINAL) {
                targetBmp
            } else {
                LowLightPreprocessor.applyEnhancement(targetBmp, mode, illum).also {
                    enhancedTemp = it
                }
            }

            val s1 = inferenceMutex.withLock {
                if (!modelLoaded || interpreter == null) {
                    if (!initializeLocked()) return@withLock null
                }
                runStage1Locked(evalBmp)
            } ?: return@withContext failureResult("Stage 1 init failed", 1L)

            val s2 = if (enableStage2) {
                secondStageClassifier.classify(evalBmp)
            } else {
                null
            }

            val totalMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
            return@withContext resolveTwoStageTaxonomy(
                origS1 = s1,
                effectiveS1 = s1,
                s2 = s2,
                illum = illum,
                preprocessingMode = mode,
                baselineCategory = ContentCategory.NON_EXPLICIT.name,
                baselineConfidence = s1.neutral + s1.drawings,
                durationMs = totalMs,
                explicitThreshold = prefs.confidenceThreshold
            )
        } finally {
            enhancedTemp?.let { if (!it.isRecycled) it.recycle() }
            resizedTemp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Production Milestone 2.1 inference pipeline:
     * 1. Runs Stage 1 on the original 224x224 frame and records the baseline prediction.
     * 2. Analyzes frame illumination (`meanLuminance`, `darkPixelRatio`, `isLowLight`).
     * 3. If the frame is low-light and not overwhelmingly safe, applies CLAHE local contrast
     *    and Gamma correction to recover shadow-compressed features while guarding against noise.
     * 4. Selectively invokes Stage 2 (`open_nsfw_resnet50.tflite`) when Stage 1 is uncertain,
     *    borderline suspicious, or shows partially clothed (`porn + sexy`) or low-light evidence.
     */
    override suspend fun classify(bitmap: Bitmap): ClassificationResult =
        classifyWithNativeCrops(bitmap, emptyList())

    suspend fun classifyWithNativeCrops(
        bitmap: Bitmap,
        nativeRoiCrops: List<Bitmap> = emptyList()
    ): ClassificationResult =
        withContext(Dispatchers.Default) {
            val totalStartNs = SystemClock.elapsedRealtimeNanos()
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return@withContext failureResult("Input Bitmap is empty or already recycled", 0L)
            }

            val prefs = LocalStorage.getPreferences(appContext)
            var resizedTemp: Bitmap? = null
            var claheBmp: Bitmap? = null
            var gammaBmp: Bitmap? = null

            try {
                val targetBmp = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true).also {
                        if (it !== bitmap) resizedTemp = it
                    }
                }

                val illum = LowLightPreprocessor.analyzeIllumination(targetBmp)

                // 1. Always evaluate the unenhanced original frame first and preserve its baseline result
                val origS1 = inferenceMutex.withLock {
                    if (!modelLoaded || interpreter == null) {
                        if (!initializeLocked()) return@withLock null
                    }
                    runStage1Locked(targetBmp)
                } ?: return@withContext failureResult(
                    lastInitError ?: "Failed to initialize LiteRT model",
                    max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                )

                val baselineResult = interpretBaselineSingleStage(
                    s1 = origS1,
                    durationMs = origS1.durationMs,
                    explicitThreshold = prefs.confidenceThreshold,
                    illum = illum
                )

                var effectiveS1 = origS1
                var appliedMode = PreprocessingMode.ORIGINAL
                var bestNativeCropBmp: Bitmap? = null
                val textSignal = ScreenTextContextAnalyzer.getLatestSignal()

                // 2. Native-Resolution Social Media Feed/Video ROI Scan (when live HardwareBuffer crops exist):
                // On X/Twitter, Instagram, TikTok, Facebook, Reddit, and Chrome, an in-feed video often
                // occupies 35-50% of the vertical screen height while surrounding UI chrome makes the
                // full-screen 224x224 look neutral. Evaluating the native-resolution 1:1 center video crop
                // catches explicit in-feed videos on the very first frame.
                val validNativeCrops = nativeRoiCrops.filter { !it.isRecycled && it.width == INPUT_WIDTH && it.height == INPUT_HEIGHT }
                if (validNativeCrops.isNotEmpty() && origS1.drawings < 0.82f) {
                    val centerCropBmp = validNativeCrops[0]
                    val centerS1 = inferenceMutex.withLock { runStage1Locked(centerCropBmp) }

                    val shouldCheckActionCrop = validNativeCrops.size > 1 && (
                        centerS1.porn >= 0.04f ||
                            centerS1.sexy >= 0.08f ||
                            origS1.porn >= 0.03f ||
                            origS1.sexy >= 0.06f ||
                            textSignal.adultScore >= 0.25f
                        )
                    val actionCropBmp = if (shouldCheckActionCrop) validNativeCrops[1] else centerCropBmp
                    val actionS1 = if (shouldCheckActionCrop) {
                        inferenceMutex.withLock { runStage1Locked(actionCropBmp) }
                    } else {
                        centerS1
                    }

                    val maxCropPorn = maxOf(centerS1.porn, actionS1.porn)
                    val minCropPorn = minOf(centerS1.porn, actionS1.porn)
                    val maxCropSexy = maxOf(centerS1.sexy, actionS1.sexy)

                    val pickAction = (actionS1.porn + 0.5f * actionS1.sexy) > (centerS1.porn + 0.5f * centerS1.sexy)
                    val candidateS1 = if (pickAction) actionS1 else centerS1
                    val candidateBmp = if (pickAction) actionCropBmp else centerCropBmp

                    // If the center/action crop shows substantial sexual signal, verify with Stage 2 on that crop
                    var s2CropNsfw = 0f
                    if (maxCropPorn >= 0.14f || (maxCropPorn + maxCropSexy) >= 0.28f) {
                        bestNativeCropBmp = candidateBmp
                        val s2Crop = secondStageClassifier.classify(candidateBmp)
                        if (s2Crop.successful) {
                            s2CropNsfw = s2Crop.nsfwScore
                        }
                    }

                    val nativeRoiConfirmed =
                        (centerS1.porn >= 0.42f && actionS1.porn >= 0.65f && (s2CropNsfw >= 0.16f || origS1.porn >= 0.08f)) ||
                            (maxCropPorn >= 0.72f && s2CropNsfw >= 0.20f) ||
                            (maxCropPorn >= 0.28f && s2CropNsfw >= 0.46f) ||
                            ((maxCropPorn + maxCropSexy) >= 0.52f && maxCropPorn >= 0.18f && s2CropNsfw >= 0.58f) ||
                            (textSignal.adultScore >= 0.35f && (maxCropPorn >= 0.24f || s2CropNsfw >= 0.38f)) ||
                            (textSignal.isExplicitPornDomain && maxCropPorn >= 0.12f)

                    if (nativeRoiConfirmed && (candidateS1.porn + 0.5f * candidateS1.sexy) > (effectiveS1.porn + 0.5f * effectiveS1.sexy)) {
                        effectiveS1 = candidateS1
                    }
                } else {
                    // Fast Safe Short-Circuit (Phase B < 50 ms target when no native ROI crop overrides):
                    val clearlySafeStage1 =
                        !illum.isLowLight &&
                            (origS1.neutral + origS1.drawings) >= 0.85f &&
                            origS1.porn < 0.055f &&
                            origS1.sexy < 0.14f &&
                            textSignal.adultScore < 0.30f &&
                            !textSignal.isExplicitPornDomain

                    if (clearlySafeStage1) {
                        val fastMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                        return@withContext resolveTwoStageTaxonomy(
                            origS1 = origS1,
                            effectiveS1 = origS1,
                            s2 = null,
                            illum = illum,
                            preprocessingMode = PreprocessingMode.ORIGINAL,
                            baselineCategory = baselineResult.predictedCategory,
                            baselineConfidence = baselineResult.confidence,
                            durationMs = fastMs,
                            explicitThreshold = prefs.confidenceThreshold
                        )
                    }

                    // Fallback Sub-Crop ROI Visual Scan (for benchmark/static images without HardwareBuffer crops):
                    if ((origS1.porn >= 0.06f && (origS1.sexy >= 0.018f || origS1.porn >= 0.35f)) ||
                        textSignal.adultScore >= 0.35f
                    ) {
                        val cropAction = extractSubCrop224(targetBmp, 0.10f, 0.38f, 0.90f, 0.76f)
                        val cropCenter = extractSubCrop224(targetBmp, 0.0f, 0.18f, 1.0f, 0.78f)
                        var cropLower: Bitmap? = null
                        try {
                            val actionS1 = inferenceMutex.withLock { runStage1Locked(cropAction) }
                            val centerS1 = inferenceMutex.withLock { runStage1Locked(cropCenter) }

                            val lowerS1 = if (centerS1.porn >= 0.38f && actionS1.porn >= 0.72f) {
                                actionS1
                            } else {
                                cropLower = extractSubCrop224(targetBmp, 0.15f, 0.45f, 0.85f, 0.82f)
                                inferenceMutex.withLock { runStage1Locked(cropLower!!) }
                            }

                            val maxActionPorn = maxOf(actionS1.porn, lowerS1.porn)
                            val maxAllPorn = maxOf(centerS1.porn, actionS1.porn, lowerS1.porn)
                            val roiConfirmed =
                                (centerS1.porn >= 0.38f && maxActionPorn >= 0.72f) ||
                                    (textSignal.adultScore >= 0.45f && maxAllPorn >= 0.36f) ||
                                    (textSignal.isExplicitPornDomain && maxAllPorn >= 0.15f)

                            if (roiConfirmed) {
                                val bestCropS1 = listOf(centerS1, actionS1, lowerS1)
                                    .maxByOrNull { it.porn + 0.5f * it.sexy } ?: actionS1
                                if (bestCropS1.porn > effectiveS1.porn) {
                                    effectiveS1 = bestCropS1
                                }
                            }
                        } finally {
                            if (!cropCenter.isRecycled) cropCenter.recycle()
                            if (!cropAction.isRecycled) cropAction.recycle()
                            cropLower?.let { if (!it.isRecycled) it.recycle() }
                        }
                    }
                }

                // 2b. Low-light enhancement path: only evaluate enhanced frames when the scene is
                // genuinely low-light (`illum.isLowLight`) and not already confirmed explicit.
                if (illum.isLowLight && origS1.drawings < 0.80f && effectiveS1.porn < 0.72f) {
                    claheBmp = LowLightPreprocessor.applyClaheLocalContrast(targetBmp)
                    gammaBmp = LowLightPreprocessor.applyGammaCorrection(targetBmp, illum)

                    val claheS1 = inferenceMutex.withLock { runStage1Locked(claheBmp!!) }
                    val gammaS1 = inferenceMutex.withLock { runStage1Locked(gammaBmp!!) }

                    val bestEnhanced = if ((claheS1.porn + 0.6f * claheS1.sexy) >=
                        (gammaS1.porn + 0.6f * gammaS1.sexy)
                    ) {
                        claheS1 to PreprocessingMode.CLAHE_LOCAL_CONTRAST
                    } else {
                        gammaS1 to PreprocessingMode.GAMMA_CORRECTION
                    }

                    // Noise-artifact guard: Only adopt enhanced Stage 1 scores if the original
                    // unenhanced frame had baseline non-zero sexual signal OR the enhanced frame
                    // shows strong structural confidence (> 0.25 porn+sexy) confirmed by Stage 2.
                    val origSignal = origS1.porn + origS1.sexy + origS1.hentai
                    val enhSignal = bestEnhanced.first.porn + bestEnhanced.first.sexy
                    if (origSignal >= 0.015f && enhSignal > (origSignal + 0.04f)) {
                        effectiveS1 = bestEnhanced.first
                        appliedMode = bestEnhanced.second
                    }
                }

                // 3. Selective Second-Stage Verification (OpenNSFW ResNet-50)
                // Triggered when:
                // - Baseline Stage 1 was UNCERTAIN
                // - Stage 1 has intermediate porn/hentai (`0.04..0.52`) or suggestive/partial clothing (`sexy >= 0.16`)
                // - Frame is low-light (`illum.isLowLight`) and not a pure UI/drawing screen
                val shouldRunStage2 =
                    baselineResult.predictedCategory == ContentCategory.UNCERTAIN.name ||
                        (effectiveS1.porn in 0.04f..0.55f) ||
                        (effectiveS1.sexy >= 0.16f) ||
                        (illum.isLowLight && origS1.drawings < 0.70f)

                var stage2Res: SecondStageResult? = null
                if (shouldRunStage2) {
                    val s2Orig = secondStageClassifier.classify(targetBmp)
                    stage2Res = s2Orig

                    val cropBmp = bestNativeCropBmp
                    if (cropBmp != null && !cropBmp.isRecycled && effectiveS1 !== origS1) {
                        val s2Crop = secondStageClassifier.classify(cropBmp)
                        if (s2Crop.successful && s2Crop.nsfwScore > s2Orig.nsfwScore) {
                            stage2Res = s2Crop.copy(durationMs = s2Orig.durationMs + s2Crop.durationMs)
                        }
                    }

                    // If low-light enhancement was applied, also evaluate Stage 2 on the enhanced frame
                    // and require both original and enhanced views to prevent noise-induced false positives.
                    val enhTarget = when (appliedMode) {
                        PreprocessingMode.CLAHE_LOCAL_CONTRAST -> claheBmp
                        PreprocessingMode.GAMMA_CORRECTION -> gammaBmp
                        else -> gammaBmp ?: claheBmp
                    }
                    if (illum.isLowLight && enhTarget != null) {
                        val s2Enh = secondStageClassifier.classify(enhTarget)
                        // Conservative blend: weight original 45% + enhanced 55% only if original s2Orig.nsfw >= 0.08
                        // or origS1 had porn+sexy >= 0.05
                        if (s2Orig.nsfwScore >= 0.08f || (origS1.porn + origS1.sexy) >= 0.05f) {
                            val blendedNsfw = maxOf(
                                s2Orig.nsfwScore,
                                0.45f * s2Orig.nsfwScore + 0.55f * s2Enh.nsfwScore
                            ).coerceIn(0f, 1f)
                            stage2Res = s2Orig.copy(
                                nsfwScore = blendedNsfw,
                                sfwScore = (1f - blendedNsfw).coerceIn(0f, 1f),
                                durationMs = s2Orig.durationMs + s2Enh.durationMs
                            )
                            if (appliedMode == PreprocessingMode.ORIGINAL && s2Enh.nsfwScore > s2Orig.nsfwScore + 0.10f) {
                                appliedMode = PreprocessingMode.CLAHE_LOCAL_CONTRAST
                            }
                        }
                    }
                }

                val totalMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                return@withContext resolveTwoStageTaxonomy(
                    origS1 = origS1,
                    effectiveS1 = effectiveS1,
                    s2 = stage2Res,
                    illum = illum,
                    preprocessingMode = appliedMode,
                    baselineCategory = baselineResult.predictedCategory,
                    baselineConfidence = baselineResult.confidence,
                    durationMs = totalMs,
                    explicitThreshold = prefs.confidenceThreshold
                )
            } catch (e: Exception) {
                val durationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L)
                return@withContext failureResult(
                    "LiteRT inference exception: ${e.javaClass.simpleName}: ${e.message}",
                    durationMs
                )
            } finally {
                claheBmp?.let { if (!it.isRecycled) it.recycle() }
                gammaBmp?.let { if (!it.isRecycled) it.recycle() }
                resizedTemp?.let { if (!it.isRecycled) it.recycle() }
            }
        }

    private fun extractSubCrop224(
        src: Bitmap,
        x0Frac: Float,
        y0Frac: Float,
        x1Frac: Float,
        y1Frac: Float
    ): Bitmap {
        val x0 = (src.width * x0Frac).toInt().coerceIn(0, src.width - 2)
        val y0 = (src.height * y0Frac).toInt().coerceIn(0, src.height - 2)
        val x1 = (src.width * x1Frac).toInt().coerceIn(x0 + 1, src.width)
        val y1 = (src.height * y1Frac).toInt().coerceIn(y0 + 1, src.height)
        val sub = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        if (sub.width == INPUT_WIDTH && sub.height == INPUT_HEIGHT) {
            return sub
        }
        val scaled = Bitmap.createScaledBitmap(sub, INPUT_WIDTH, INPUT_HEIGHT, true)
        if (scaled !== sub && !sub.isRecycled) {
            sub.recycle()
        }
        return scaled
    }

    private fun interpretBaselineSingleStage(
        s1: RawStage1Scores,
        durationMs: Long,
        explicitThreshold: Float,
        illum: IlluminationMetrics
    ): ClassificationResult {
        val combinedExplicit = (s1.porn + s1.hentai).coerceIn(0f, 1f)
        val combinedSafe = (s1.neutral + s1.drawings).coerceIn(0f, 1f)
        val derivedNudityScore = (s1.porn + 0.45f * s1.sexy + 0.65f * s1.hentai).coerceIn(0f, 1f)
        val nudityThreshold = (explicitThreshold * 0.62f).coerceIn(0.35f, 0.65f)
        val suggestiveThreshold = 0.52f

        val (category, categoryConfidence) = when {
            s1.porn >= explicitThreshold || s1.hentai >= explicitThreshold || combinedExplicit >= maxOf(0.75f, explicitThreshold) -> {
                ContentCategory.EXPLICIT to maxOf(s1.porn, s1.hentai, combinedExplicit)
            }
            s1.porn >= nudityThreshold || (derivedNudityScore >= 0.52f && s1.porn >= 0.22f) || (s1.hentai >= nudityThreshold) -> {
                ContentCategory.NUDITY to maxOf(s1.porn, derivedNudityScore)
            }
            s1.sexy >= suggestiveThreshold -> {
                ContentCategory.SUGGESTIVE to s1.sexy
            }
            s1.sexy >= 0.18f && combinedSafe >= 0.35f -> {
                ContentCategory.NON_EXPLICIT to (s1.sexy + combinedSafe).coerceIn(0f, 1f)
            }
            (combinedSafe >= 0.52f && combinedExplicit < 0.25f) ||
                (combinedSafe >= 0.60f && s1.porn < 0.05f && s1.hentai < 0.45f) -> {
                ContentCategory.NON_EXPLICIT to combinedSafe
            }
            else -> {
                val topProb = maxOf(s1.drawings, s1.hentai, s1.neutral, s1.porn, s1.sexy)
                ContentCategory.UNCERTAIN to topProb
            }
        }

        val isPos = category == ContentCategory.EXPLICIT || category == ContentCategory.NUDITY
        val scoresMap = linkedMapOf(
            "drawings" to s1.drawings,
            "hentai" to s1.hentai,
            "neutral" to s1.neutral,
            "porn" to s1.porn,
            "sexy" to s1.sexy,
            "EXPLICIT" to combinedExplicit,
            "NUDITY" to derivedNudityScore,
            "SEXUAL_ACTIVITY" to 0f,
            "SUGGESTIVE" to s1.sexy,
            "NON_EXPLICIT" to combinedSafe
        )

        return ClassificationResult(
            scores = scoresMap,
            predictedCategory = category.name,
            confidence = categoryConfidence,
            processingDurationMs = durationMs,
            modelVersion = "1.2.0-baseline-single-stage",
            successful = true,
            isExplicit = isPos,
            nudityScore = derivedNudityScore,
            explicitScore = combinedExplicit,
            sexualActivityScore = 0f,
            suggestiveScore = s1.sexy,
            safeScore = combinedSafe,
            drawingsScore = s1.drawings,
            hentaiScore = s1.hentai,
            neutralScore = s1.neutral,
            pornScore = s1.porn,
            sexyScore = s1.sexy,
            stage2Triggered = false,
            preprocessingApplied = PreprocessingMode.ORIGINAL.name,
            isLowLightFrame = illum.isLowLight,
            meanLuminance = illum.meanLuminance,
            originalCategory = category.name,
            originalConfidence = categoryConfidence,
            delegateUsed = activeDelegateName,
            thresholdUsed = explicitThreshold,
            modelId = "nsfw-mobilenet-v2-140-224"
        )
    }

    /**
     * Defensible two-stage taxonomy resolution combining Stage 1 (5-class MobileNetV2 1.4)
     * and Stage 2 (Yahoo OpenNSFW ResNet-50) with low-light false-positive protection.
     */
    private fun resolveTwoStageTaxonomy(
        origS1: RawStage1Scores,
        effectiveS1: RawStage1Scores,
        s2: SecondStageResult?,
        illum: IlluminationMetrics,
        preprocessingMode: PreprocessingMode,
        baselineCategory: String,
        baselineConfidence: Float,
        durationMs: Long,
        explicitThreshold: Float
    ): ClassificationResult {
        val pDrawings = effectiveS1.drawings
        val pHentai = effectiveS1.hentai
        val pNeutral = effectiveS1.neutral
        val pPorn = maxOf(origS1.porn, effectiveS1.porn)
        val pSexy = maxOf(origS1.sexy, effectiveS1.sexy)

        val s2Triggered = s2 != null && s2.successful
        val s2Nsfw = if (s2Triggered) s2!!.nsfwScore else 0f
        val s2Sfw = if (s2Triggered) s2!!.sfwScore else 1f

        val combinedSafe = (pNeutral + pDrawings).coerceIn(0f, 1f)
        val s1Explicit = (pPorn + pHentai).coerceIn(0f, 1f)
        val textSignal = ScreenTextContextAnalyzer.getLatestSignal()
        val hasExplicitTextContext =
            textSignal.isExplicitPornDomain ||
                (textSignal.adultScore >= 0.65f && (pPorn + pSexy >= 0.10f || s2Nsfw >= 0.22f)) ||
                (textSignal.adultScore >= 0.45f && (pPorn >= 0.22f || (pPorn + pSexy) >= 0.32f))

        // Breastfeeding / infant maternal care / musical instrument or bare-arm crop false-positive guard:
        // When the full-screen unenhanced frame has very low porn (< 0.12) and high neutral (>= 0.72),
        // AND Stage 2 ResNet-50 is strongly SFW (`s2Nsfw < 0.14`), never trigger a false positive.
        val isLikelyNonSexualSkinExposure =
            (!illum.isLowLight &&
                pNeutral >= 0.70f &&
                pSexy < 0.025f &&
                pPorn < 0.30f &&
                (!s2Triggered || s2Nsfw < 0.52f)) ||
                (s2Triggered &&
                    s2Nsfw < 0.14f &&
                    origS1.porn < 0.12f &&
                    origS1.sexy < 0.08f &&
                    pPorn < 0.85f &&
                    !hasExplicitTextContext)

        // Ordinary dark scene false-positive guard:
        // If the original unenhanced frame had near-zero porn (< 0.025) and near-zero sexy (< 0.04)
        // and Stage 2 nsfw < 0.38, never classify as sexual even if low-light enhancement lifted background contrast.
        val isOrdinaryDarkScene =
            illum.isLowLight &&
                origS1.porn < 0.025f &&
                origS1.sexy < 0.045f &&
                (!s2Triggered || s2Nsfw < 0.38f)

        val nudityThreshold = (explicitThreshold * 0.58f).coerceIn(0.35f, 0.60f)

        // Joint sexual activity score capturing partially clothed sexual activity and sexualized poses
        // where Stage 1 detects `porn + sexy` (or low-light compressed `porn`) and Stage 2 ResNet-50 corroborates `nsfw`.
        val sexualActivityScore = if (s2Triggered) {
            (0.45f * (pPorn + 0.75f * pSexy) + 0.55f * s2Nsfw).coerceIn(0f, 1f)
        } else {
            (0.70f * pPorn + 0.45f * pSexy).coerceIn(0f, 1f)
        }

        val derivedNudityScore = if (s2Triggered) {
            maxOf(
                pPorn,
                (0.60f * pPorn + 0.40f * s2Nsfw + 0.25f * pSexy).coerceIn(0f, 1f)
            )
        } else {
            (pPorn + 0.40f * pSexy + 0.55f * pHentai).coerceIn(0f, 1f)
        }

        val derivedExplicitScore = if (s2Triggered) {
            maxOf(s1Explicit, (0.65f * pPorn + 0.35f * s2Nsfw).coerceIn(0f, 1f))
        } else {
            s1Explicit
        }

        val (category, categoryConfidence) = when {
            textSignal.isExplicitPornDomain -> {
                ContentCategory.EXPLICIT to maxOf(0.95f, pPorn, derivedExplicitScore)
            }

            hasExplicitTextContext && pPorn >= 0.35f -> {
                ContentCategory.EXPLICIT to maxOf(0.85f, pPorn, derivedExplicitScore)
            }

            hasExplicitTextContext -> {
                ContentCategory.SEXUAL_ACTIVITY to maxOf(0.76f, sexualActivityScore, textSignal.adultScore)
            }

            isOrdinaryDarkScene || isLikelyNonSexualSkinExposure -> {
                ContentCategory.NON_EXPLICIT to maxOf(combinedSafe, s2Sfw)
            }

            // 1. EXPLICIT: High-confidence explicit sexual imagery
            pPorn >= explicitThreshold ||
                (pHentai >= explicitThreshold && pDrawings < 0.30f) ||
                (s2Triggered && pPorn >= 0.50f && s2Nsfw >= 0.68f) -> {
                ContentCategory.EXPLICIT to maxOf(pPorn, derivedExplicitScore)
            }

            // 2. SEXUAL_ACTIVITY: Requires genuine porn evidence (pPorn >= 0.16f) OR strong Stage 2
            // corroboration (s2Nsfw >= 0.72f with pPorn >= 0.10f) — never escalates everyday lace
            // camisoles/tank tops (pPorn < 0.12f) or dim-room face close-ups into SEXUAL_ACTIVITY.
            (pPorn in 0.22f..0.68f && pSexy >= 0.14f && (pPorn + pSexy) >= 0.48f) ||
                (s2Triggered && pPorn >= 0.16f && (pPorn + pSexy) >= 0.36f && s2Nsfw >= 0.54f) ||
                (s2Triggered && pSexy >= 0.52f && pPorn >= 0.10f && s2Nsfw >= 0.72f) ||
                (s2Triggered && illum.isLowLight && pPorn >= 0.14f && (pPorn + pSexy) >= 0.24f && s2Nsfw >= 0.62f) -> {
                ContentCategory.SEXUAL_ACTIVITY to maxOf(sexualActivityScore, s2Nsfw, (pPorn + 0.65f * pSexy).coerceIn(0f, 1f))
            }

            // 3. NUDITY: Visible intimate nudity (either direct high porn score, or low-light / shadowed
            // nudity where Stage 1 porn >= 0.18 is corroborated by Stage 2 OpenNSFW or enhanced Stage 1)
            pPorn >= nudityThreshold ||
                (s2Triggered && pPorn >= 0.20f && s2Nsfw >= 0.42f) ||
                (illum.isLowLight && pPorn >= 0.18f && (s2Nsfw >= 0.28f || pPorn >= 0.26f)) -> {
                ContentCategory.NUDITY to maxOf(pPorn, derivedNudityScore, s2Nsfw)
            }

            // 4. SUGGESTIVE: Strongly sexualized non-explicit imagery (pSexy >= 0.62f, low porn);
            // everyday camisoles/tank tops/bare shoulders (pSexy < 0.62f, pPorn < 0.14f) stay NON_EXPLICIT.
            (pSexy >= 0.62f && pPorn < 0.14f && (!s2Triggered || s2Nsfw < 0.72f)) -> {
                ContentCategory.SUGGESTIVE to pSexy
            }

            // 5. NON_EXPLICIT: Ordinary safe content, camisoles/tank tops, dim talking heads, fitness,
            // swimming, beach, fashion portraits, and normal app UI
            (combinedSafe >= 0.42f && pPorn < 0.16f) ||
                (pSexy in 0.12f..0.62f && pPorn < 0.14f && (!s2Triggered || s2Nsfw < 0.65f)) ||
                (s2Triggered && s2Sfw >= 0.62f && pPorn < 0.18f) ||
                (combinedSafe >= 0.60f && pPorn < 0.05f && pHentai < 0.45f) -> {
                ContentCategory.NON_EXPLICIT to maxOf(combinedSafe, s2Sfw)
            }

            // 6. UNCERTAIN: Genuinely ambiguous cases where signals conflict
            else -> {
                val topProb = maxOf(pDrawings, pHentai, pNeutral, pPorn, pSexy)
                ContentCategory.UNCERTAIN to topProb
            }
        }

        val isPositiveDetection =
            category == ContentCategory.EXPLICIT ||
                category == ContentCategory.NUDITY ||
                category == ContentCategory.SEXUAL_ACTIVITY

        val scoresMap = linkedMapOf(
            "drawings" to pDrawings,
            "hentai" to pHentai,
            "neutral" to pNeutral,
            "porn" to pPorn,
            "sexy" to pSexy,
            "stage2_open_nsfw" to s2Nsfw,
            "stage2_open_sfw" to s2Sfw,
            "EXPLICIT" to derivedExplicitScore,
            "NUDITY" to derivedNudityScore,
            "SEXUAL_ACTIVITY" to sexualActivityScore,
            "SUGGESTIVE" to pSexy,
            "NON_EXPLICIT" to combinedSafe
        )

        return ClassificationResult(
            scores = scoresMap,
            predictedCategory = category.name,
            confidence = categoryConfidence.coerceIn(0f, 1f),
            processingDurationMs = durationMs,
            modelVersion = modelVersion,
            successful = true,
            error = null,
            isExplicit = isPositiveDetection,
            nudityScore = derivedNudityScore,
            explicitScore = derivedExplicitScore,
            sexualActivityScore = sexualActivityScore,
            suggestiveScore = pSexy,
            safeScore = combinedSafe,
            drawingsScore = pDrawings,
            hentaiScore = pHentai,
            neutralScore = pNeutral,
            pornScore = pPorn,
            sexyScore = pSexy,
            stage2Triggered = s2Triggered,
            stage2NsfwScore = s2Nsfw,
            stage2SfwScore = s2Sfw,
            stage2DurationMs = s2?.durationMs ?: 0L,
            preprocessingApplied = preprocessingMode.name,
            isLowLightFrame = illum.isLowLight,
            meanLuminance = illum.meanLuminance,
            originalCategory = baselineCategory,
            originalConfidence = baselineConfidence,
            delegateUsed = activeDelegateName,
            thresholdUsed = explicitThreshold,
            modelId = modelId,
            isMockModel = false,
            frameWidth = INPUT_WIDTH,
            frameHeight = INPUT_HEIGHT
        )
    }

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
        secondStageClassifier.release()
    }
}
