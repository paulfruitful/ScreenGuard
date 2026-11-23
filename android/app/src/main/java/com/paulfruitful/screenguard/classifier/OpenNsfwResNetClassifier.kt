package com.paulfruitful.screenguard.classifier

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
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

data class SecondStageResult(
    val sfwScore: Float,
    val nsfwScore: Float,
    val durationMs: Long,
    val successful: Boolean,
    val error: String? = null
)

/**
 * Second-stage on-device sexual-content verifier powered by Yahoo's OpenNSFW ResNet-50 thin
 * architecture (`open_nsfw_resnet50.tflite`, 5,958,568 bytes, BSD-2-Clause License).
 *
 * Tensor Specifications:
 * - Asset: `open_nsfw_resnet50.tflite` (5.96 MB)
 * - Input Tensor: `[1, 224, 224, 3]` FLOAT32, **BGR channel order** with Caffe ImageNet
 *   mean subtraction: `B = blue - 104.0f`, `G = green - 117.0f`, `R = red - 123.0f`.
 * - Output Tensor: `[1, 2]` FLOAT32 softmax probabilities:
 *     0 -> `sfw`  (Safe For Work probability)
 *     1 -> `nsfw` (Sexual / explicit probability)
 *
 * Invoked selectively when the primary 5-class MobileNetV2 model encounters ambiguous,
 * low-light, or partially clothed sexual content.
 */
class OpenNsfwResNetClassifier(
    private val appContext: Context,
    private val numThreads: Int = 4
) {
    companion object {
        const val ASSET_MODEL_FILENAME = "open_nsfw_resnet50.tflite"
        const val INPUT_WIDTH = 224
        const val INPUT_HEIGHT = 224
        const val INPUT_CHANNELS = 3

        private const val MEAN_BLUE = 104.0f
        private const val MEAN_GREEN = 117.0f
        private const val MEAN_RED = 123.0f
    }

    val modelId: String = "open-nsfw-resnet50-224"
    val modelVersion: String = "1.0-open_nsfw-fp32 (Yahoo OpenNSFW ResNet-50 BSD-2)"

    private val mutex = Mutex()

    @Volatile
    private var interpreter: Interpreter? = null
    @Volatile
    private var mappedBuffer: MappedByteBuffer? = null
    @Volatile
    private var loaded: Boolean = false
    @Volatile
    private var initDurationMs: Long = 0L
    @Volatile
    private var modelSizeBytes: Long = 0L

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS)
        .order(ByteOrder.nativeOrder())
    private val pixelScratch = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
    private val outputProbs = Array(1) { FloatArray(2) }

    fun isModelLoaded(): Boolean = loaded
    fun getInitDurationMs(): Long = initDurationMs
    fun getModelSizeBytes(): Long = modelSizeBytes

    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (loaded && interpreter != null) return@withContext true
        mutex.withLock {
            if (loaded && interpreter != null) return@withLock true
            return@withLock initLocked()
        }
    }

    private fun initLocked(): Boolean {
        val t0 = SystemClock.elapsedRealtimeNanos()
        return try {
            val mapped = appContext.assets.openFd(ASSET_MODEL_FILENAME).use { afd ->
                modelSizeBytes = afd.declaredLength
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength
                    )
                }
            }
            mappedBuffer = mapped
            val opts = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseXNNPACK(true)
            }
            val interp = Interpreter(mapped, opts)
            interp.allocateTensors()
            interpreter = interp
            initDurationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            loaded = true
            true
        } catch (_: Exception) {
            loaded = false
            false
        }
    }

    suspend fun classify(bitmap: Bitmap): SecondStageResult = withContext(Dispatchers.Default) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext SecondStageResult(1f, 0f, 0L, false, "Recycled bitmap")
        }

        mutex.withLock {
            if (!loaded || interpreter == null) {
                if (!initLocked()) {
                    return@withLock SecondStageResult(1f, 0f, 1L, false, "Failed to load open_nsfw_resnet50.tflite")
                }
            }
            val interp = interpreter ?: return@withLock SecondStageResult(1f, 0f, 1L, false, "Null interpreter")

            var resizedTemp: Bitmap? = null
            try {
                val targetBmp = if (bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true).also {
                        if (it !== bitmap) resizedTemp = it
                    }
                }

                inputBuffer.rewind()
                targetBmp.getPixels(pixelScratch, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

                // BGR channel order with ImageNet mean subtraction: B - 104, G - 117, R - 123
                for (i in 0 until (INPUT_WIDTH * INPUT_HEIGHT)) {
                    val argb = pixelScratch[i]
                    val r = ((argb shr 16) and 0xFF) - MEAN_RED
                    val g = ((argb shr 8) and 0xFF) - MEAN_GREEN
                    val b = (argb and 0xFF) - MEAN_BLUE
                    inputBuffer.putFloat(b)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(r)
                }
                inputBuffer.rewind()

                outputProbs[0][0] = 0f
                outputProbs[0][1] = 0f

                interp.run(inputBuffer, outputProbs)

                val sfw = outputProbs[0][0].coerceIn(0f, 1f)
                val nsfw = outputProbs[0][1].coerceIn(0f, 1f)
                val durationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)

                SecondStageResult(
                    sfwScore = sfw,
                    nsfwScore = nsfw,
                    durationMs = durationMs,
                    successful = true,
                    error = null
                )
            } catch (e: Exception) {
                val durationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
                SecondStageResult(1f, 0f, durationMs, false, e.message)
            } finally {
                resizedTemp?.let { tmp ->
                    if (!tmp.isRecycled) tmp.recycle()
                }
            }
        }
    }

    fun release() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        }
        interpreter = null
        loaded = false
    }
}
