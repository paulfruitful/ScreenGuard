package com.paulfruitful.screenguard.classifier

/**
 * Milestone 2 final classification taxonomy:
 * - EXPLICIT: Explicit sexual activity or pornographic imagery (`porn` or `hentai` above explicit threshold).
 * - NUDITY: Visible intimate nudity.
 * - SUGGESTIVE: Sexualized but non-explicit imagery (`sexy` above suggestive threshold).
 * - NON_EXPLICIT: Ordinary skin exposure, swimming, fitness, beach photography, and similar non-explicit content.
 * - SAFE: Ordinary content (`neutral` or `drawings`).
 * - UNCERTAIN: Insufficient confidence or ambiguous probability margin.
 */
enum class ContentCategory {
    EXPLICIT,
    NUDITY,
    SUGGESTIVE,
    NON_EXPLICIT,
    SAFE,
    UNCERTAIN
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
    val suggestiveScore: Float = 0f,
    val safeScore: Float = 1f,
    val drawingsScore: Float = 0f,
    val hentaiScore: Float = 0f,
    val neutralScore: Float = 1f,
    val pornScore: Float = 0f,
    val sexyScore: Float = 0f,
    val consecutiveDetections: Int = 0,
    val confirmedIntervention: Boolean = false,
    val temporalState: String = "SAFE_STABLE",
    val smoothedExplicitScore: Float = 0f,
    val isDuplicateSkipped: Boolean = false,
    val frameHash: String = "",
    val delegateUsed: String = "CPU_XNNPACK_4T",
    val thresholdUsed: Float = 0.72f,
    val modelId: String = "nsfw-mobilenet-v2-140-224",
    val isMockModel: Boolean = false,
    val frameWidth: Int = 224,
    val frameHeight: Int = 224,
    val packageName: String = "none",
    val windowId: Int = -1,
    val timestamp: Long = System.currentTimeMillis()
)
