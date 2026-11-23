package com.paulfruitful.screenguard.classifier

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Fallback test stub classifier (retained only for isolated unit tests).
 * Production pipeline uses [LiteRtNsfwClassifier].
 */
class MockContentClassifier : ContentClassifier {
    override val modelId: String = "mock-fallback-stub"
    override val modelVersion: String = "1.0.0-stub"
    override val isMockModel: Boolean = true

    override fun isModelLoaded(): Boolean = true
    override fun getInitializationDurationMs(): Long = 1L
    override fun getActiveDelegateName(): String = "STUB"
    override fun getModelSizeBytes(): Long = 0L
    override fun getInputTensorSpec(): String = "[1, 224, 224, 3] FLOAT32"
    override fun getOutputTensorSpec(): String = "[1, 5] FLOAT32"

    override suspend fun classify(bitmap: Bitmap): ClassificationResult = withContext(Dispatchers.Default) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val durationMs = max(1L, (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L)
        val scores = mapOf(
            "drawings" to 0.02f,
            "hentai" to 0.01f,
            "neutral" to 0.94f,
            "porn" to 0.01f,
            "sexy" to 0.02f
        )
        ClassificationResult(
            scores = scores,
            predictedCategory = ContentCategory.SAFE.name,
            confidence = 0.96f,
            processingDurationMs = durationMs,
            modelVersion = modelVersion,
            successful = true,
            error = null,
            isExplicit = false,
            nudityScore = 0.02f,
            explicitScore = 0.02f,
            suggestiveScore = 0.02f,
            safeScore = 0.96f,
            drawingsScore = 0.02f,
            hentaiScore = 0.01f,
            neutralScore = 0.94f,
            pornScore = 0.01f,
            sexyScore = 0.02f,
            modelId = modelId,
            isMockModel = true,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height
        )
    }
}
