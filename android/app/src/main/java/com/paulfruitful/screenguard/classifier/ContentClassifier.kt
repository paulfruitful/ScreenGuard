package com.paulfruitful.screenguard.classifier

import android.graphics.Bitmap

/**
 * Replaceable on-device visual content classifier interface.
 *
 * Production implementation: [LiteRtNsfwClassifier] using Google LiteRT (TensorFlow Lite)
 * and the pretrained 5-class `nsfw_mobilenet_v2_140_224.tflite` model.
 */
interface ContentClassifier {
    val modelId: String
    val modelVersion: String
    val isMockModel: Boolean

    fun isModelLoaded(): Boolean
    fun getInitializationDurationMs(): Long
    fun getActiveDelegateName(): String
    fun getModelSizeBytes(): Long
    fun getInputTensorSpec(): String
    fun getOutputTensorSpec(): String

    suspend fun classify(bitmap: Bitmap): ClassificationResult

    fun release() {}
}
