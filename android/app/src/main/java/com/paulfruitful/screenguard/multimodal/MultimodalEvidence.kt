package com.paulfruitful.screenguard.multimodal

/**
 * Clean, separated multimodal evidence representations (Phase C).
 *
 * Each modality (Visual, AudioPlayback, CaptionNode, OcrFrame) reports its own availability,
 * non-content-bearing confidence metrics, and latency so the fusion engine can handle
 * missing modalities, avoid double-counting correlated text evidence, and prevent
 * dialogue-only false positives.
 */
data class VisualEvidence(
    val available: Boolean = true,
    val predictedCategory: String = "NON_EXPLICIT",
    val confidence: Float = 0f,
    val pornScore: Float = 0f,
    val sexyScore: Float = 0f,
    val hentaiScore: Float = 0f,
    val neutralScore: Float = 1f,
    val drawingsScore: Float = 0f,
    val explicitScore: Float = 0f,
    val nudityScore: Float = 0f,
    val sexualActivityScore: Float = 0f,
    val stage2Triggered: Boolean = false,
    val stage2NsfwScore: Float = 0f,
    val roiScanTriggered: Boolean = false,
    val maxRoiPornScore: Float = 0f,
    val isLowLight: Boolean = false,
    val meanLuminance: Float = 128f,
    val stage1DurationMs: Long = 0L,
    val totalVisualDurationMs: Long = 0L
)

enum class AudioUnavailableReason {
    AVAILABLE_AND_SAMPLED,
    NO_MEDIA_PROJECTION_CONSENT,
    NO_ACTIVE_MEDIA_PLAYBACK,
    APP_OPTED_OUT_OR_DRM,
    AUDIO_CAPTURE_ERROR
}

data class AudioPlaybackEvidence(
    val available: Boolean = false,
    val unavailableReason: AudioUnavailableReason = AudioUnavailableReason.NO_MEDIA_PROJECTION_CONSENT,
    val targetPackageEligible: Boolean = false,
    val activePlaybackCount: Int = 0,
    val usageMediaActive: Boolean = false,
    val speechBandEnergyRatio: Float = 0f,
    val rhythmicModulationScore: Float = 0f,
    val supplementarySuspicionScore: Float = 0f,
    val durationMs: Long = 0L
)

enum class CaptionSourceType {
    NONE,
    BROWSER_URL_BAR,
    SUBTITLE_VIEW_NODE,
    ACCESSIBILITY_UI_TEXT
}

data class CaptionNodeEvidence(
    val available: Boolean = false,
    val sourceType: CaptionSourceType = CaptionSourceType.NONE,
    val explicitPornDomainDetected: Boolean = false,
    val hardcoreKeywordScore: Float = 0f,
    val contextualAdultScore: Float = 0f,
    val benignDialogueOrEducationalContext: Boolean = false,
    val benignNonSexualVisualContext: Boolean = false,
    val matchedCategoryCount: Int = 0,
    val durationMs: Long = 0L
)

data class OcrFrameEvidence(
    val available: Boolean = false,
    val subtitleBandDetected: Boolean = false,
    val glyphEdgeDensity: Float = 0f,
    val visualTextConfidenceBoost: Float = 0f,
    val durationMs: Long = 0L
)

data class MultimodalEvidenceBundle(
    val visual: VisualEvidence,
    val audio: AudioPlaybackEvidence,
    val caption: CaptionNodeEvidence,
    val ocr: OcrFrameEvidence
)

data class FusedMultimodalDecision(
    val finalCategory: String,
    val finalConfidence: Float,
    val shouldIntervene: Boolean,
    val primaryModality: String,
    val corroboratingModalities: List<String>,
    val uncertaintyPreserved: Boolean,
    val dialogueOnlySuppressed: Boolean,
    val fusionExplanation: String,
    val decisionDurationMs: Long
)
