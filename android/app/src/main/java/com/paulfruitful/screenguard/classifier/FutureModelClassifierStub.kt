package com.paulfruitful.screenguard.classifier

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Base template for future ONNX Runtime Mobile or secondary model adapters.
 */
abstract class FutureModelClassifierStub(
    override val modelId: String = "onnx-nsfw-adapter",
    override val modelVersion: String = "1.0.0-onnx",
    private val inputWidth: Int = 224,
    private val inputHeight: Int = 224
) : ContentClassifier {

    override val isMockModel: Boolean = false
    override fun getInputTensorSpec(): String = "[1, $inputWidth, $inputHeight, 3] FLOAT32"
    override fun getOutputTensorSpec(): String = "[1, 5] FLOAT32"

    private val tensorLock = Any()
    private val inputTensorBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * inputWidth * inputHeight * 3)
        .order(ByteOrder.nativeOrder())
    private val pixelScratch = IntArray(inputWidth * inputHeight)

    protected fun populateNormalizedInputTensor(bitmap: Bitmap): ByteBuffer {
        synchronized(tensorLock) {
            inputTensorBuffer.rewind()
            bitmap.getPixels(
                pixelScratch,
                0,
                inputWidth,
                0,
                0,
                minOf(bitmap.width, inputWidth),
                minOf(bitmap.height, inputHeight)
            )
            for (argb in pixelScratch) {
                val r = ((argb shr 16) and 0xFF) / 255.0f
                val g = ((argb shr 8) and 0xFF) / 255.0f
                val b = (argb and 0xFF) / 255.0f
                inputTensorBuffer.putFloat(r)
                inputTensorBuffer.putFloat(g)
                inputTensorBuffer.putFloat(b)
            }
            inputTensorBuffer.rewind()
            return inputTensorBuffer
        }
    }
}
