package com.paulfruitful.screenguard.classifier

import com.paulfruitful.screenguard.accessibility.ScreenTextContextAnalyzer
import com.paulfruitful.screenguard.multimodal.AudioPlaybackEvidence
import com.paulfruitful.screenguard.multimodal.CaptionAndOcrAnalyzer
import com.paulfruitful.screenguard.multimodal.MultimodalEvidenceBundle
import com.paulfruitful.screenguard.multimodal.MultimodalFusionEngine
import com.paulfruitful.screenguard.multimodal.OcrFrameEvidence
import com.paulfruitful.screenguard.multimodal.VisualEvidence
import com.paulfruitful.screenguard.protection.DecisionDirective
import com.paulfruitful.screenguard.protection.OverlayState
import com.paulfruitful.screenguard.protection.ProtectionDecisionEngine
import com.paulfruitful.screenguard.protection.ProtectionPolicyConfig
import com.paulfruitful.screenguard.protection.ProtectionSensitivityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard-negative & strictness regression tests verifying that:
 * 1. Explicit PORN / NUDITY / SEXUAL_ACTIVITY and porn domains remain immediate 1-frame blocks
 *    and can NEVER be bypassed by injecting benign keywords like "gym" or "medical".
 * 2. Non-sexual hard negatives (MMA/UFC, gym/bodybuilding, medical/dermatology, single-frame
 *    swimwear/scroll spikes) do NOT trigger false overlays.
 */
class FalsePositiveAndStrictnessTest {

    private fun strictPolicy(): ProtectionPolicyConfig =
        ProtectionPolicyConfig.forMode(
            mode = ProtectionSensitivityMode.STRICT,
            baseConfidenceThreshold = 0.70f,
            confirmationFrames = 1,
            allowDismiss = false
        ).copy(
            sensitivityMode = ProtectionSensitivityMode.STRICT,
            interveneOnSuggestiveInStrict = true,
            allowManualDismiss = false,
            dismissCooldownMs = 0L
        )

    private fun fuse(visual: VisualEvidence, onScreenText: String) =
        MultimodalFusionEngine.fuse(
            bundle = MultimodalEvidenceBundle(
                visual = visual,
                audio = AudioPlaybackEvidence(),
                caption = CaptionAndOcrAnalyzer.buildCaptionEvidence(
                    ScreenTextContextAnalyzer.scoreText(onScreenText.lowercase(), false, 1L)
                ),
                ocr = OcrFrameEvidence(available = false)
            ),
            policy = strictPolicy()
        )

    @Test
    fun explicitPorn_blocksImmediatelyOnFrame1_evenWithDeceptiveGymCaption() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.EXPLICIT.name,
            confidence = 0.86f,
            pornScore = 0.86f,
            explicitScore = 0.86f,
            neutralScore = 0.10f
        )
        val fused = fuse(visual, "UFC gym workout fitness bodybuilding medical")
        assertTrue("Explicit porn must NEVER be vetoed by benign keywords", fused.shouldIntervene)
        assertEquals(ContentCategory.EXPLICIT.name, fused.finalCategory)

        val engine = ProtectionDecisionEngine()
        val decision = engine.evaluate(
            result = ClassificationResult(
                scores = mapOf("porn" to 0.86f),
                predictedCategory = fused.finalCategory,
                confidence = fused.finalConfidence,
                pornScore = 0.86f,
                explicitScore = 0.86f,
                isExplicit = true,
                processingDurationMs = 15L,
                modelVersion = "test",
                successful = true
            ),
            packageName = "com.twitter.android",
            protectionEnabled = true,
            isExcludedPackage = false,
            policy = strictPolicy()
        )
        assertTrue("Explicit porn must show overlay on frame 1", decision.shouldShowOverlay)
        assertEquals(DecisionDirective.TRIGGER_PROTECTION, decision.directive)
    }

    @Test
    fun explicitPornDomain_blocksImmediately_evenWithMedicalKeywords() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.90f,
            neutralScore = 0.90f
        )
        val fused = fuse(visual, "https://pornhub.com medical anatomy health")
        assertTrue("Porn domain must always trigger immediate block", fused.shouldIntervene)
        assertEquals(ContentCategory.EXPLICIT.name, fused.finalCategory)
    }

    @Test
    fun hardNegative_mmaFightBorderlineSuggestive_isVetoed() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.64f,
            sexyScore = 0.64f,
            pornScore = 0.06f,
            neutralScore = 0.30f
        )
        val fused = fuse(visual, "UFC 312 Main Event MMA Wrestling Highlights")
        assertFalse("MMA/UFC borderline suggestive frame should be vetoed", fused.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fused.finalCategory)
        assertTrue(fused.dialogueOnlySuppressed)
    }

    @Test
    fun hardNegative_gymWorkoutBorderlineSuggestive_isVetoed() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.68f,
            sexyScore = 0.68f,
            pornScore = 0.04f,
            neutralScore = 0.28f
        )
        val fused = fuse(visual, "Morning gym deadlift workout physique check")
        assertFalse("Gym workout borderline suggestive frame should be vetoed", fused.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fused.finalCategory)
    }

    @Test
    fun hardNegative_medicalDermatologyBorderlineSuggestive_isVetoed() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.60f,
            sexyScore = 0.60f,
            pornScore = 0.03f,
            neutralScore = 0.37f
        )
        val fused = fuse(visual, "Dermatology skincare physical therapy guide")
        assertFalse("Medical/skincare borderline suggestive frame should be vetoed", fused.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fused.finalCategory)
    }

    @Test
    fun hardNegative_singleFrameSuggestiveSpike_doesNotFlashOverlay() {
        val engine = ProtectionDecisionEngine()
        val policy = strictPolicy()

        val spikeFrame = ClassificationResult(
            scores = mapOf("sexy" to 0.65f, "neutral" to 0.30f),
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.65f,
            sexyScore = 0.65f,
            suggestiveScore = 0.65f,
            safeScore = 0.30f,
            processingDurationMs = 15L,
            modelVersion = "test",
            successful = true
        )
        val firstDecision = engine.evaluate(
            result = spikeFrame,
            packageName = "com.instagram.android",
            protectionEnabled = true,
            isExcludedPackage = false,
            policy = policy
        )
        assertFalse("Single suggestive spike must wait for confirmation frame", firstDecision.shouldShowOverlay)
        assertEquals(OverlayState.PENDING, firstDecision.targetOverlayState)

        val nextSafeFrame = ClassificationResult(
            scores = mapOf("neutral" to 0.92f),
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.92f,
            safeScore = 0.92f,
            processingDurationMs = 15L,
            modelVersion = "test",
            successful = true
        )
        val secondDecision = engine.evaluate(
            result = nextSafeFrame,
            packageName = "com.instagram.android",
            protectionEnabled = true,
            isExcludedPackage = false,
            policy = policy
        )
        assertFalse("Returning to safe frame clears pending state cleanly", secondDecision.shouldShowOverlay)
        assertEquals(OverlayState.HIDDEN, secondDecision.targetOverlayState)
    }

    @Test
    fun sustainedSuggestive_twoConsecutiveFrames_stillBlocksStrictly() {
        val engine = ProtectionDecisionEngine()
        val policy = strictPolicy()

        val suggestiveFrame = ClassificationResult(
            scores = mapOf("sexy" to 0.72f, "neutral" to 0.22f),
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.72f,
            sexyScore = 0.72f,
            suggestiveScore = 0.72f,
            safeScore = 0.22f,
            processingDurationMs = 15L,
            modelVersion = "test",
            successful = true
        )

        val first = engine.evaluate(
            result = suggestiveFrame,
            packageName = "com.instagram.android",
            protectionEnabled = true,
            isExcludedPackage = false,
            policy = policy
        )
        assertFalse(first.shouldShowOverlay)
        assertEquals(OverlayState.PENDING, first.targetOverlayState)

        val second = engine.evaluate(
            result = suggestiveFrame,
            packageName = "com.instagram.android",
            protectionEnabled = true,
            isExcludedPackage = false,
            policy = policy
        )
        assertTrue("Sustained suggestive content must block on frame 2", second.shouldShowOverlay)
        assertEquals(DecisionDirective.TRIGGER_PROTECTION, second.directive)
    }
}
