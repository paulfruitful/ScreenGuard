package com.paulfruitful.screenguard.classifier

import com.paulfruitful.screenguard.accessibility.ScreenTextContextAnalyzer
import com.paulfruitful.screenguard.multimodal.AudioPlaybackEvidence
import com.paulfruitful.screenguard.multimodal.CaptionAndOcrAnalyzer
import com.paulfruitful.screenguard.multimodal.ContextVerificationEvidence
import com.paulfruitful.screenguard.multimodal.MultimodalEvidenceBundle
import com.paulfruitful.screenguard.multimodal.MultimodalFusionEngine
import com.paulfruitful.screenguard.multimodal.OcrFrameEvidence
import com.paulfruitful.screenguard.multimodal.OnDeviceNlpContextClassifier
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
 * Hard-negative, multi-modal verification & 2–3 frame accumulative gate regression tests.
 */
class FalsePositiveAndStrictnessTest {

    private fun strictPolicy(): ProtectionPolicyConfig =
        ProtectionPolicyConfig.forMode(
            mode = ProtectionSensitivityMode.STRICT,
            baseConfidenceThreshold = 0.70f,
            confirmationFrames = 2,
            allowDismiss = false
        )

    private fun fuse(
        visual: VisualEvidence,
        onScreenText: String,
        contextVerifier: ContextVerificationEvidence? = null
    ) = MultimodalFusionEngine.fuse(
        bundle = MultimodalEvidenceBundle(
            visual = visual,
            audio = AudioPlaybackEvidence(),
            caption = CaptionAndOcrAnalyzer.buildCaptionEvidence(
                ScreenTextContextAnalyzer.scoreText(onScreenText.lowercase(), false, 1L)
            ),
            ocr = OcrFrameEvidence(available = onScreenText.isNotBlank()),
            contextVerifier = contextVerifier ?: ContextVerificationEvidence(
                executed = onScreenText.isNotBlank(),
                ocrTextFound = onScreenText.isNotBlank(),
                nlpScore = OnDeviceNlpContextClassifier.classify(onScreenText)
            )
        ),
        policy = strictPolicy()
    )

    @Test
    fun policyFloors_arePrecisionFirst_not24PercentHairTrigger() {
        val policy = strictPolicy()
        assertTrue("explicitThreshold must be >= 0.65", policy.explicitThreshold >= 0.65f)
        assertTrue("nudityThreshold must be >= 0.62", policy.nudityThreshold >= 0.62f)
        assertTrue("sexualActivityThreshold must be >= 0.64", policy.sexualActivityThreshold >= 0.64f)
        assertTrue("confirmationFramesRequired must be >= 2", policy.confirmationFramesRequired >= 2)
    }

    @Test
    fun nlpClassifier_understandsBenignSuggestiveAndObfuscatedText() {
        val tailoring = OnDeviceNlpContextClassifier.classify(
            "Hi guys I am a university student and I run a garment alteration tailoring service"
        )
        assertEquals("NON_SEXUAL", tailoring.dominantClass)

        val twerkPromo = OnDeviceNlpContextClassifier.classify(
            "Twerking challenge buss it spicy content link in bio"
        )
        assertEquals("SUGGESTIVE_PROMO", twerkPromo.dominantClass)

        val obfuscated = OnDeviceNlpContextClassifier.classify(
            "Watch full h@rdc0r3 p0rn s3x t@p3 on p0rnhub"
        )
        assertEquals("EXPLICIT_SEXUAL", obfuscated.dominantClass)

        val censoredTwitter = OnDeviceNlpContextClassifier.classify(
            "If u wanna jrkkk off, lock your doors and picture my ass on your D*ck"
        )
        assertEquals("EXPLICIT_SEXUAL", censoredTwitter.dominantClass)
        assertTrue(censoredTwitter.explicitSexualProb >= 0.72f)

        val onlylinks = OnDeviceNlpContextClassifier.classify("Join now onlylinks.com/abbybb")
        assertTrue(onlylinks.explicitPornDomainDetected)

        val pxrnsHandle = OnDeviceNlpContextClassifier.classify("Lara Hidalgo @sweetpxrns")
        assertEquals("EXPLICIT_SEXUAL", pxrnsHandle.dominantClass)

        val slangBatch = listOf(
            "chudai video" to "EXPLICIT_SEXUAL",
            "goonette reversecowgirl" to "EXPLICIT_SEXUAL",
            "doggy and cowgirl" to "EXPLICIT_SEXUAL",
            "wataa goon 69" to "SUGGESTIVE_PROMO",
            "Check out @shmoods #nsfw" to "EXPLICIT_SEXUAL",
            "Follow @abella_danger on X" to "EXPLICIT_SEXUAL",
            "New video from @moodafxxx" to "EXPLICIT_SEXUAL",
            "Watch Brazzers exclusive" to "EXPLICIT_SEXUAL"
        )
        for ((sample, expected) in slangBatch) {
            assertEquals("Expected $expected for '$sample'", expected, OnDeviceNlpContextClassifier.classify(sample).dominantClass)
        }
        assertTrue(OnDeviceNlpContextClassifier.classify("Follow @shmoods").explicitPornDomainDetected)
        assertTrue(OnDeviceNlpContextClassifier.classify("Video #nsfw").dominantClass == "EXPLICIT_SEXUAL")
    }

    @Test
    fun fullyClothedTwerking_zeroSkinVisualScore_caughtByPoseAcross3Frames() {
        val zeroSkinVisual = VisualEvidence(
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.94f,
            sexyScore = 0.02f,
            pornScore = 0.01f,
            neutralScore = 0.94f
        )
        val clothedPoseCtx = ContextVerificationEvidence(
            executed = true,
            poseDetected = true,
            isBentOverOrRearFacingPose = true,
            hipProminenceScore = 0.72f,
            hipOscillationScore = 0.45f,
            clothedSuggestivePoseScore = 0.68f,
            nlpScore = OnDeviceNlpContextClassifier.classify("Weekend vibe")
        )
        val fused = fuse(zeroSkinVisual, "Weekend vibe", clothedPoseCtx)
        assertTrue("Fully clothed twerking pose should flag SUGGESTIVE even with 0% skin", fused.shouldIntervene)
        assertEquals(ContentCategory.SUGGESTIVE.name, fused.finalCategory)
    }

    @Test
    fun explicitPorn_blocksOnFrame2AccumulativeGate_evenWithDeceptiveGymCaption() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.EXPLICIT.name,
            confidence = 0.86f,
            pornScore = 0.86f,
            explicitScore = 0.86f,
            stage2Triggered = true,
            stage2NsfwScore = 0.84f,
            neutralScore = 0.10f
        )
        val fused = fuse(visual, "UFC gym workout fitness bodybuilding medical")
        assertTrue("Explicit porn must NEVER be vetoed by benign keywords", fused.shouldIntervene)
        assertEquals(ContentCategory.EXPLICIT.name, fused.finalCategory)

        val engine = ProtectionDecisionEngine()
        val frame = ClassificationResult(
            scores = mapOf("porn" to 0.86f),
            predictedCategory = fused.finalCategory,
            confidence = fused.finalConfidence,
            pornScore = 0.86f,
            explicitScore = 0.86f,
            isExplicit = true,
            processingDurationMs = 15L,
            modelVersion = "test",
            successful = true
        )

        val first = engine.evaluate(frame, "com.twitter.android", true, false, strictPolicy())
        assertFalse("Frame 1 enters PENDING to prevent single-frame false spikes", first.shouldShowOverlay)
        assertEquals(OverlayState.PENDING, first.targetOverlayState)

        val second = engine.evaluate(frame, "com.twitter.android", true, false, strictPolicy())
        assertTrue("Frame 2 (~1.2s) confirms explicit porn", second.shouldShowOverlay)
        assertEquals(DecisionDirective.TRIGGER_PROTECTION, second.directive)
    }

    @Test
    fun explicitPornDomain_blocksImmediatelyOnFrame1() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.90f,
            neutralScore = 0.90f
        )
        val fused = fuse(visual, "https://pornhub.com medical anatomy health")
        assertTrue(fused.shouldIntervene)
        assertEquals("CAPTION_URL_DOMAIN", fused.primaryModality)

        val engine = ProtectionDecisionEngine()
        val decision = engine.evaluate(
            result = ClassificationResult(
                scores = emptyMap(),
                predictedCategory = ContentCategory.EXPLICIT.name,
                confidence = 0.99f,
                isExplicit = true,
                processingDurationMs = 5L,
                modelVersion = ProtectionDecisionEngine.DETERMINISTIC_URL_MODEL_VERSION,
                successful = true
            ),
            packageName = "com.android.chrome",
            protectionEnabled = true,
            isExcludedPackage = false,
            policy = strictPolicy()
        )
        assertTrue("Deterministic porn URL blocks immediately on frame 1", decision.shouldShowOverlay)
    }

    @Test
    fun talkingHeadPortrait_mlKitFaceVeto_stopsCamisoleAndDarkFaceFalsePositives() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.NUDITY.name,
            confidence = 0.66f,
            pornScore = 0.32f,
            nudityScore = 0.66f,
            stage2Triggered = true,
            stage2NsfwScore = 0.62f,
            neutralScore = 0.52f
        )
        val faceCtx = ContextVerificationEvidence(
            executed = true,
            faceCount = 1,
            largestFaceAreaRatio = 0.22f,
            isTalkingHeadPortrait = true,
            nlpScore = OnDeviceNlpContextClassifier.classify("garment alteration service")
        )
        val fused = fuse(visual, "garment alteration service", faceCtx)
        assertFalse("Close-up talking head portrait must not be blocked", fused.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fused.finalCategory)
        assertTrue(fused.corroboratingModalities.contains("TALKING_HEAD_FACE_VETO"))
    }

    @Test
    fun clothedTwerkingPose_blocksAfter3ConsecutiveFrames_whileGymDeadliftIsVetoed() {
        val visual = VisualEvidence(
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.70f,
            sexyScore = 0.30f,
            pornScore = 0.04f,
            neutralScore = 0.66f
        )
        val twerkCtx = ContextVerificationEvidence(
            executed = true,
            poseDetected = true,
            isBentOverOrRearFacingPose = true,
            hipProminenceScore = 0.78f,
            hipOscillationScore = 0.60f,
            clothedSuggestivePoseScore = 0.75f,
            nlpScore = OnDeviceNlpContextClassifier.classify("twerking challenge buss it")
        )
        val fusedTwerk = fuse(visual, "twerking challenge buss it", twerkCtx)
        assertTrue("Clothed twerking pose + suggestive NLP should flag SUGGESTIVE", fusedTwerk.shouldIntervene)
        assertEquals(ContentCategory.SUGGESTIVE.name, fusedTwerk.finalCategory)

        val engine = ProtectionDecisionEngine()
        val policy = strictPolicy()
        val twerkFrame = ClassificationResult(
            scores = mapOf("sexy" to fusedTwerk.finalConfidence),
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = fusedTwerk.finalConfidence,
            sexyScore = fusedTwerk.finalConfidence,
            suggestiveScore = fusedTwerk.finalConfidence,
            processingDurationMs = 20L,
            modelVersion = "test",
            successful = true
        )
        assertFalse(engine.evaluate(twerkFrame, "com.zhiliaoapp.musically", true, false, policy).shouldShowOverlay)
        assertFalse(engine.evaluate(twerkFrame, "com.zhiliaoapp.musically", true, false, policy).shouldShowOverlay)
        assertTrue(
            "Clothed suggestive twerking confirms on frame 3 (~2.2s)",
            engine.evaluate(twerkFrame, "com.zhiliaoapp.musically", true, false, policy).shouldShowOverlay
        )

        // Same bent-over pose in a gym deadlift context must NOT be blocked
        val gymCtx = twerkCtx.copy(
            nlpScore = OnDeviceNlpContextClassifier.classify("Morning gym deadlift workout fitness")
        )
        val fusedGym = fuse(visual, "Morning gym deadlift workout fitness", gymCtx)
        assertFalse("Gym deadlift pose must be allowed", fusedGym.shouldIntervene)
    }

    @Test
    fun singleFrameSpike_resetsCleanlyWithZeroFalseOverlays() {
        val engine = ProtectionDecisionEngine()
        val policy = strictPolicy()
        val spike = ClassificationResult(
            scores = mapOf("porn" to 0.72f),
            predictedCategory = ContentCategory.EXPLICIT.name,
            confidence = 0.72f,
            pornScore = 0.72f,
            explicitScore = 0.72f,
            processingDurationMs = 15L,
            modelVersion = "test",
            successful = true
        )
        val safe = ClassificationResult(
            scores = mapOf("neutral" to 0.94f),
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.94f,
            safeScore = 0.94f,
            processingDurationMs = 15L,
            modelVersion = "test",
            successful = true
        )
        assertFalse(engine.evaluate(spike, "com.instagram.android", true, false, policy).shouldShowOverlay)
        val cleared = engine.evaluate(safe, "com.instagram.android", true, false, policy)
        assertFalse(cleared.shouldShowOverlay)
        assertEquals(OverlayState.HIDDEN, cleared.targetOverlayState)
    }
}
