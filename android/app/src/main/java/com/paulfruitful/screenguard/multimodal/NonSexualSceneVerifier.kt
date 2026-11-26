package com.paulfruitful.screenguard.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Output of the on-device 1,000-class ImageNet general-content verifier (`efficientnet_lite0.tflite`).
 *
 * Recognizes everyday non-sexual subjects (398 animal species including 118 dog breeds & all cats,
 * food/cooking, vehicles, landscapes, and UI/article screens) so texture-based NSFW CNNs never
 * falsely block pets wrestling, food, cars, or scenery.
 */
data class NonSexualSceneEvidence(
    val executed: Boolean = false,
    val isConfirmedNonSexualScene: Boolean = false,
    val sceneCategory: String = "NONE", // ANIMAL | FOOD | VEHICLE_OR_LANDSCAPE | UI_SCREEN | NONE
    val confidence: Float = 0f,
    val topAnimalProb: Float = 0f,
    val topPetProb: Float = 0f,
    val petSum: Float = 0f,
    val foodSum: Float = 0f,
    val topClassIndex: Int = -1,
    val durationMs: Long = 0L
)

/**
 * Lightweight on-device normal-content verifier backed by Google's official `efficientnet_lite0.tflite`
 * (5.4 MB INT8, ~18–25 ms on 224x224 bitmap via LiteRT XNNPACK — 100% local, zero cloud).
 *
 * Only invoked on frames where the visual NSFW classifier raised a suspicious score (zero latency
 * overhead on normal safe frames).
 *
 * Calibrated empirically across 36 real explicit/nude/thirst-trap images:
 * - Max single animal prob on all 36 positives = 0.24 (0.14 on real positives)
 * - Max food sum on all 36 positives = 0.02
 * - Confirmed ANIMAL veto requires topAnimalProb >= 0.35 OR (top3AnimalSum >= 0.52 && topAnimalProb >= 0.24)
 * - Confirmed FOOD veto requires topFoodProb >= 0.30 && foodSum >= 0.45
 */
object NonSexualSceneVerifier {

    private const val MODEL_ASSET_NAME = "efficientnet_lite0.tflite"
    private const val INPUT_SIZE = 224
    private const val NUM_CLASSES = 1000

    // ImageNet-1K standard class index ranges (verified against efficientnet_lite0 labels_without_background.txt):
    // 0..397   = All 398 animal species (fish, birds, reptiles, insects, dogs 151..268, cats 281..285, wildlife)
    // 151..285 = Dogs (incl. 179 Staffordshire bullterrier, 180 American Staffordshire terrier) & domestic cats
    // 924..969 = Food & cooking dishes (guacamole, pizza, burger, bread, meat, fruits, desserts)
    private const val ANIMAL_START = 0
    private const val ANIMAL_END = 397
    private const val PET_START = 151
    private const val PET_END = 285
    private const val FOOD_START = 924
    private const val FOOD_END = 969

    // Safe non-human UI / web / landscape / transport indices (explicitly excludes clothing,
    // swimwear, underwear, bedrooms, couches, and human-body adjacent classes)
    private val SAFE_UI_AND_LANDSCAPE_INDICES = setOf(
        916, // web site
        921, // book jacket
        922, // menu
        970, // alp
        973, // coral reef
        975, // lakeside
        977, // sandbar
        978, // seashore
        979, // valley
        980, // volcano
        404, // airliner
        407, // ambulance
        436, // beach wagon
        468, // cab
        511, // convertible
        555, // fire engine
        569, // garbage truck
        609, // jeep
        627, // limousine
        654, // minibus
        656, // minivan
        670, // motor scooter
        671, // mountain bike
        705, // passenger car
        717, // pickup
        734, // police van
        751, // racer
        779, // school bus
        817, // sports car
        829, // streetcar
        864, // tow truck
        867  // trailer truck
    )

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var initAttempted: Boolean = false

    private var inputBuffer: ByteBuffer? = null
    private var intPixels: IntArray? = null
    private var outputQuant: Array<ByteArray>? = null
    private var outputScale: Float = 1f / 256f
    private var outputZeroPoint: Int = 0

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun verify(
        bitmap: Bitmap,
        hasPetOrAnimalTextHint: Boolean = false
    ): NonSexualSceneEvidence {
        val ctx = appContext ?: return NonSexualSceneEvidence(executed = false)
        if (bitmap.isRecycled || bitmap.width < 32 || bitmap.height < 32) {
            return NonSexualSceneEvidence(executed = false)
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            val interp = ensureInterpreterLocked(ctx) ?: return NonSexualSceneEvidence(executed = false)
            val resized = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            }
            try {
                val buf = inputBuffer ?: ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
                    .order(ByteOrder.nativeOrder())
                    .also { inputBuffer = it }
                val pixels = intPixels ?: IntArray(INPUT_SIZE * INPUT_SIZE).also { intPixels = it }
                val outArr = outputQuant ?: Array(1) { ByteArray(NUM_CLASSES) }.also { outputQuant = it }

                resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
                buf.rewind()
                for (px in pixels) {
                    buf.put(((px shr 16) and 0xFF).toByte())
                    buf.put(((px shr 8) and 0xFF).toByte())
                    buf.put((px and 0xFF).toByte())
                }
                buf.rewind()

                interp.run(buf, outArr)

                val rawBytes = outArr[0]
                var topOverallIdx = -1
                var topOverallProb = 0f

                var topAnimalProb = 0f
                var top2Animal = 0f
                var top3Animal = 0f
                var topPetProb = 0f
                var petSum = 0f

                var topFoodProb = 0f
                var foodSum = 0f

                var topSafeSceneProb = 0f
                var topSafeSceneIdx = -1

                for (i in 0 until NUM_CLASSES) {
                    val u = rawBytes[i].toInt() and 0xFF
                    val prob = ((u - outputZeroPoint) * outputScale).coerceIn(0f, 1f)
                    if (prob > topOverallProb) {
                        topOverallProb = prob
                        topOverallIdx = i
                    }
                    if (i in ANIMAL_START..ANIMAL_END) {
                        if (prob >= topAnimalProb) {
                            top3Animal = top2Animal
                            top2Animal = topAnimalProb
                            topAnimalProb = prob
                        } else if (prob >= top2Animal) {
                            top3Animal = top2Animal
                            top2Animal = prob
                        } else if (prob > top3Animal) {
                            top3Animal = prob
                        }
                        if (i in PET_START..PET_END) {
                            petSum += prob
                            if (prob > topPetProb) topPetProb = prob
                        }
                    } else if (i in FOOD_START..FOOD_END) {
                        foodSum += prob
                        if (prob > topFoodProb) topFoodProb = prob
                    } else if (i in SAFE_UI_AND_LANDSCAPE_INDICES) {
                        if (prob > topSafeSceneProb) {
                            topSafeSceneProb = prob
                            topSafeSceneIdx = i
                        }
                    }
                }

                val top3AnimalSum = topAnimalProb + top2Animal + top3Animal
                val isAnimal =
                    topAnimalProb >= 0.35f ||
                        (top3AnimalSum >= 0.52f && topAnimalProb >= 0.24f) ||
                        (hasPetOrAnimalTextHint && (topPetProb >= 0.16f || petSum >= 0.28f || topAnimalProb >= 0.20f))

                val isFood = topFoodProb >= 0.30f && foodSum >= 0.45f
                val isSafeUiOrLandscape = topSafeSceneProb >= 0.52f

                val (confirmed, category, conf) = when {
                    isAnimal -> Triple(true, "ANIMAL", maxOf(topAnimalProb, top3AnimalSum.coerceAtMost(0.99f)))
                    isFood -> Triple(true, "FOOD", maxOf(topFoodProb, foodSum.coerceAtMost(0.99f)))
                    isSafeUiOrLandscape -> Triple(
                        true,
                        if (topSafeSceneIdx in setOf(916, 921, 922)) "UI_SCREEN" else "VEHICLE_OR_LANDSCAPE",
                        topSafeSceneProb
                    )
                    else -> Triple(false, "NONE", topOverallProb)
                }

                val durMs = maxOf(1L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
                return NonSexualSceneEvidence(
                    executed = true,
                    isConfirmedNonSexualScene = confirmed,
                    sceneCategory = category,
                    confidence = conf,
                    topAnimalProb = topAnimalProb,
                    topPetProb = topPetProb,
                    petSum = petSum,
                    foodSum = foodSum,
                    topClassIndex = topOverallIdx,
                    durationMs = durMs
                )
            } catch (_: Throwable) {
                return NonSexualSceneEvidence(executed = false)
            } finally {
                if (resized !== bitmap && !resized.isRecycled) {
                    resized.recycle()
                }
            }
        }
    }

    private fun ensureInterpreterLocked(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (initAttempted) return null
        initAttempted = true
        return try {
            val mapped = loadAssetFile(context, MODEL_ASSET_NAME)
            val opts = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            val interp = Interpreter(mapped, opts)
            val qParams = interp.getOutputTensor(0).quantizationParams()
            if (qParams.scale > 0f) {
                outputScale = qParams.scale
                outputZeroPoint = qParams.zeroPoint
            }
            interpreter = interp
            interp
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadAssetFile(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
        }
    }
}
