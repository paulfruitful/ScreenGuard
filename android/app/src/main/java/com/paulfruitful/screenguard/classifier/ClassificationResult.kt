package com.paulfruitful.screenguard.classifier

/**
 * Milestone 2.1 classification taxonomy:
 * - EXPLICIT: Explicit sexual activity or pornographic imagery.
 * - NUDITY: Visible intimate nudity.
 * - SEXUAL_ACTIVITY: Sexual activity without complete nudity, partially clothed sexual content,
 *   or sexualized interactions/poses corroborated across stages.
 * - SUGGESTIVE: Sexualized but non-explicit imagery.
 * - NON_EXPLICIT: Ordinary content, ordinary dark scenes, swimming, fitness, beach, fashion/underwear ads, portraits.
 * - UNCERTAIN: Insufficient confidence or unresolvable inter-stage ambiguity.
 * - SAFE: Retained as backwards-compatible alias for ordinary safe content.
 */
enum class ContentCategory {
    EXPLICIT,
    NUDITY,
    SEXUAL_ACTIVITY,
    SUGGESTIVE,
    NON_EXPLICIT,
    UNCERTAIN,
    SAFE
}

data class ClassificationResult(
    val scores: Map<String, Float>,
    val predictedCategory: String,
    val confidence: Float,
    val processingDurationMs: Long,
    val modelVersion: String,
    val successful: Boolean,
    val error: String? = null,
    val isExplicit: Boolean = false,
    val nudityScore: Float = 0f,
    val explicitScore: Float = 0f,
    val sexualActivityScore: Float = 0f,
    val suggestiveScore: Float = 0f,
    val safeScore: Float = 1f,
    val drawingsScore: Float = 0f,
    val hentaiScore: Float = 0f,
    val neutralScore: Float = 1f,
    val pornScore: Float = 0f,
    val sexyScore: Float = 0f,
    val stage2Triggered: Boolean = false,
    val stage2NsfwScore: Float = 0f,
    val stage2SfwScore: Float = 1f,
    val stage2DurationMs: Long = 0L,
    val preprocessingApplied: String = "ORIGINAL",
    val isLowLightFrame: Boolean = false,
    val meanLuminance: Float = 128f,
    val originalCategory: String = "NON_EXPLICIT",
    val originalConfidence: Float = 1f,
    val consecutiveDetections: Int = 0,
    val confirmedIntervention: Boolean = false,
    val temporalState: String = "SAFE_STABLE",
    val smoothedExplicitScore: Float = 0f,
    val isDuplicateSkipped: Boolean = false,
    val frameHash: String = "",
    val delegateUsed: String = "CPU_XNNPACK_4T",
    val thresholdUsed: Float = 0.72f,
    val modelId: String = "nsfw-mobilenet-v2-140-224+open-nsfw-resnet50",
    val isMockModel: Boolean = false,
    val frameWidth: Int = 224,
    val frameHeight: Int = 224,
    val packageName: String = "none",
    val windowId: Int = -1,
    val timestamp: Long = System.currentTimeMillis()
)
