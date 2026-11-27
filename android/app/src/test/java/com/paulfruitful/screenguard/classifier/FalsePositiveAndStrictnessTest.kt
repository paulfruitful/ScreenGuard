package com.paulfruitful.screenguard.classifier

import com.paulfruitful.screenguard.accessibility.ScreenTextContextAnalyzer
import com.paulfruitful.screenguard.multimodal.AdultAccountDirectory
import com.paulfruitful.screenguard.multimodal.AudioPlaybackEvidence
import com.paulfruitful.screenguard.multimodal.CaptionAndOcrAnalyzer
import com.paulfruitful.screenguard.multimodal.ContextVerificationEvidence
import com.paulfruitful.screenguard.multimodal.MultimodalEvidenceBundle
import com.paulfruitful.screenguard.multimodal.MultimodalFusionEngine
import com.paulfruitful.screenguard.multimodal.NonSexualSceneEvidence
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
            "wataa goon 69" to "EXPLICIT_SEXUAL",
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
    fun splitSecondDirectory_blocksAllPriorityXAccountsAndKeywords_withoutFalsePositives() {
        val mustBlockImmediately = listOf(
            "TheLionsDens28 posted a video",
            "ShmoodNasty @shmoodnasty",
            "699lbs video",
            "shmdtv exclusive",
            "heads and backshots",
            "deznut",
            "baddieX",
            "freaksstown",
            "ebonyxvids",
            "callingbooty",
            "dopeassxxxvids",
            "freakmajor",
            "umdavazo",
            "pozzyanach",
            "Driler",
            "WackSackXXX",
            "snowworshipper",
            "blackwomenfuck",
            "thethroatnurse",
            "throat goat",
            "toptiertits",
            "goodgoodgoons",
            "ebornyporn",
            "acervodasputas",
            "Big Thicc",
            "chopdckkk",
            "_shmoodtv",
            "whosbonnieandclyde",
            "SLUT MAKER",
            "flix_nut",
            "NikoNasty",
            "nuttyvidspage",
            "Herfave_page",
            "BaddiesHub",
            "DrillPink",
            "shmoodfreakz",
            "HunnyD",
            "sluttymommon",
            "18+ video only",
            "blackxxfreaks",
            "EbonyCct",
            "EbonyLeakz",
            "Mariannamuse",
            "AwfulLotta",
            "willow rider",
            "princess emily",
            "troy franciso",
            "ricky's room",
            "medusa",
            "BlowMyfrank",
            "@CaptionTV",
            "BigStroke69",
            "slvttyvisions",
            "Ebony Light Skin",
            "toxicbbyonx",
            "beach sex",
            "petiteebonyx",
            "booty clapper 187",
            "make you nut 💦",
            "Ms Nasty",
            "baddiexxx",
            "wetwrldx",
            "phat ass",
            "nutty clips",
            "fvckingpalace",
            "VidVaultXShadyTaboo",
            "she can moan so loud",
            "BBC and PAWG action"
        )
        for (sample in mustBlockImmediately) {
            val match = AdultAccountDirectory.inspect(sample, isSocialApp = true)
            assertTrue("Expected split-second block for '$sample'", match.shouldBlockImmediately)
        }

        // Critical false-positive safety checks (normal news, class, peanut butter, mythology, bible)
        val mustNotBlock = listOf(
            "BBC News breaking world report",
            "I was in class passing notes by the glass window",
            "Peanut butter and donut recipe in 5 minutes",
            "Greek mythology statue of Medusa in the museum",
            "Sunday church sermon and Bible scripture"
        )
        for (safe in mustNotBlock) {
            val match = AdultAccountDirectory.inspect(safe, isSocialApp = true)
            assertFalse("Must NOT false-positive on '$safe' (tags=${match.matchedTags})", match.shouldBlockImmediately)
        }
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

    @Test
    fun clothedThirstTrapAndTwerking_blocksWithinTwoSeconds_whileSafeAnimeAndGymStayAllowed() {
        val engine = ProtectionDecisionEngine()
        val policy = strictPolicy()

        // 1. Clothed thirst trap with suggestive caption ("Someone clapping behind tbh"):
        // Stage 1 crop sees strong suggestive/sexualized pose, Stage 2 OpenNSFW is 0.05 (clothed)
        val thirstTrapVisual = VisualEvidence(
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.86f,
            pornScore = 0.64f,
            sexyScore = 0.36f,
            stage2Triggered = true,
            stage2NsfwScore = 0.05f,
            neutralScore = 0.00f
        )
        val fusedTrap = fuse(thirstTrapVisual, "Someone clapping behind tbh")
        assertTrue("Clothed thirst trap should qualify for intervention", fusedTrap.shouldIntervene)
        assertEquals(ContentCategory.SUGGESTIVE.name, fusedTrap.finalCategory)

        val trapFrame = ClassificationResult(
            scores = mapOf("sexy" to fusedTrap.finalConfidence),
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = fusedTrap.finalConfidence,
            sexyScore = fusedTrap.finalConfidence,
            suggestiveScore = fusedTrap.finalConfidence,
            processingDurationMs = 20L,
            modelVersion = "test",
            successful = true
        )
        // Frame 1 enters PENDING (~0s), Frame 2 confirms (~1.1s) because confidence >= 0.86 (accum >= 1.72)
        assertFalse(engine.evaluate(trapFrame, "com.twitter.android", true, false, policy).shouldShowOverlay)
        assertTrue(
            "High-confidence clothed thirst trap should block within 2 frames (~1.1s)",
            engine.evaluate(trapFrame, "com.twitter.android", true, false, policy).shouldShowOverlay
        )

        // 2. Clothed suggestive squat (moderate ~0.70 confidence) confirms in 3 frames (~2.1s)
        val squatEngine = ProtectionDecisionEngine()
        val squatFrame = ClassificationResult(
            scores = mapOf("sexy" to 0.70f),
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.70f,
            sexyScore = 0.70f,
            suggestiveScore = 0.70f,
            processingDurationMs = 20L,
            modelVersion = "test",
            successful = true
        )
        assertFalse(squatEngine.evaluate(squatFrame, "com.twitter.android", true, false, policy).shouldShowOverlay)
        assertFalse(squatEngine.evaluate(squatFrame, "com.twitter.android", true, false, policy).shouldShowOverlay)
        assertTrue(
            "Moderate clothed suggestive pose should confirm on frame 3 (~2.1s)",
            squatEngine.evaluate(squatFrame, "com.twitter.android", true, false, policy).shouldShowOverlay
        )

        // 3. Explicit animated porn / hentai must NOT be vetoed even if ML Kit sees a drawn face
        val hentaiVisual = VisualEvidence(
            predictedCategory = ContentCategory.EXPLICIT.name,
            confidence = 0.72f,
            hentaiScore = 0.72f,
            drawingsScore = 0.25f,
            explicitScore = 0.72f,
            stage2Triggered = true,
            stage2NsfwScore = 0.04f,
            neutralScore = 0.03f
        )
        val drawnFaceCtx = ContextVerificationEvidence(
            executed = true,
            faceCount = 1,
            largestFaceAreaRatio = 0.18f,
            isTalkingHeadPortrait = true,
            nlpScore = OnDeviceNlpContextClassifier.classify("anime clip")
        )
        val fusedHentai = fuse(hentaiVisual, "anime clip", drawnFaceCtx)
        assertTrue("Explicit hentai/animated porn must NOT be vetoed by face or Stage 2", fusedHentai.shouldIntervene)
        assertEquals(ContentCategory.EXPLICIT.name, fusedHentai.finalCategory)

        // 4. Ordinary anime drawing stays NON_EXPLICIT
        val safeAnime = VisualEvidence(
            predictedCategory = ContentCategory.NON_EXPLICIT.name,
            confidence = 0.98f,
            drawingsScore = 0.98f,
            hentaiScore = 0.02f,
            neutralScore = 0.00f
        )
        val fusedSafeAnime = fuse(safeAnime, "New anime episode review")
        assertFalse("Safe anime must stay allowed", fusedSafeAnime.shouldIntervene)
    }

    @Test
    fun lyingDownNeckTraining_stage1HallucinationWithSafeStage2_isSuppressedByFaceOrFitnessContext() {
        // Real TikTok false-positive case: close-up man lying horizontally on peach bedsheet
        // doing neck training ("Day 31 of training neck to see if it helps me ascend").
        // Stage 1 hallucinates Porn=0.98, Sexy=0.00, while Stage 2 OpenNSFW confirms safe (0.05).
        val hallucinatedVisual = VisualEvidence(
            predictedCategory = ContentCategory.SUGGESTIVE.name,
            confidence = 0.88f,
            pornScore = 0.98f,
            sexyScore = 0.00f,
            stage2Triggered = true,
            stage2NsfwScore = 0.05f,
            neutralScore = 0.02f
        )

        // Case A: Caption OCR detects benign fitness/training text -> suppressed by NLP_NON_SEXUAL_VETO
        val fitnessCtx = ContextVerificationEvidence(
            executed = true,
            faceCount = 0,
            ocrTextFound = true,
            nlpScore = OnDeviceNlpContextClassifier.classify(
                "Day 31 of training neck to see if it helps me ascend"
            )
        )
        val fusedByCaption = fuse(
            hallucinatedVisual,
            "Day 31 of training neck to see if it helps me ascend",
            fitnessCtx
        )
        assertFalse("Benign neck-training caption with safe Stage 2 must stay allowed", fusedByCaption.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fusedByCaption.finalCategory)

        // Case B: Rotated 90°/270° lying-down close-up face detected (even with no caption visible)
        val rotatedFaceCtx = ContextVerificationEvidence(
            executed = true,
            faceCount = 1,
            largestFaceAreaRatio = 0.22f,
            isTalkingHeadPortrait = true,
            nlpScore = OnDeviceNlpContextClassifier.classify("")
        )
        val fusedByFace = fuse(hallucinatedVisual, "", rotatedFaceCtx)
        assertFalse("Lying-down close-up face portrait with safe Stage 2 must stay allowed", fusedByFace.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fusedByFace.finalCategory)
    }

    @Test
    fun catAndPitbullWrestling_normalContentVerifierVetoesBothStage1AndStage2FalseAlarms() {
        // Real live false-positive case: a cat and a tan pitbull wrestling caused BOTH
        // Stage 1 (porn=0.99) and Stage 2 Yahoo OpenNSFW (s2=0.87) to misfire.
        val animalFalsePositiveVisual = VisualEvidence(
            predictedCategory = ContentCategory.EXPLICIT.name,
            confidence = 0.99f,
            pornScore = 0.99f,
            explicitScore = 0.99f,
            sexyScore = 0.01f,
            stage2Triggered = true,
            stage2NsfwScore = 0.87f,
            neutralScore = 0.01f
        )

        val animalCtx = ContextVerificationEvidence(
            executed = true,
            faceCount = 0,
            ocrTextFound = true,
            nlpScore = OnDeviceNlpContextClassifier.classify("No be small fight lol #dog #viral #pitbull"),
            nonSexualScene = NonSexualSceneEvidence(
                executed = true,
                isConfirmedNonSexualScene = true,
                sceneCategory = "ANIMAL",
                confidence = 0.78f,
                topAnimalProb = 0.78f,
                topPetProb = 0.78f,
                petSum = 0.91f
            )
        )

        val fused = fuse(animalFalsePositiveVisual, "#dog #pitbull", animalCtx)
        assertFalse("Confirmed ANIMAL scene must veto false Stage 1 + Stage 2 nudity alarms", fused.shouldIntervene)
        assertEquals(ContentCategory.NON_EXPLICIT.name, fused.finalCategory)
        assertTrue(fused.corroboratingModalities.contains("NON_SEXUAL_SCENE_VETO:ANIMAL"))

        // Guardrail: real human explicit porn where EfficientNet does NOT confirm an animal/food scene still blocks
        val realHumanCtx = ContextVerificationEvidence(
            executed = true,
            nlpScore = OnDeviceNlpContextClassifier.classify(""),
            nonSexualScene = NonSexualSceneEvidence(
                executed = true,
                isConfirmedNonSexualScene = false,
                sceneCategory = "NONE",
                confidence = 0.14f,
                topAnimalProb = 0.14f
            )
        )
        val fusedReal = fuse(animalFalsePositiveVisual, "", realHumanCtx)
        assertTrue("Real human explicit content must still block", fusedReal.shouldIntervene)
        assertEquals(ContentCategory.EXPLICIT.name, fusedReal.finalCategory)
    }

    @Test
    fun customUserBlockedPages_handlesNamesAndUrlsBlockImmediatelyAcrossAllApps() {
        try {
            AdultAccountDirectory.setCustomBlockedEntriesForTests(
                listOf("https://x.com/CustomOnlyFansGirl", "Spicy Creator Name", "@badpage99")
            )

            val byHandle = AdultAccountDirectory.inspect("Profile: @customonlyfansgirl · 12K followers", isSocialApp = false)
            assertTrue("Custom handle pasted as x.com URL must block immediately on any app", byHandle.shouldBlockImmediately)
            assertTrue(byHandle.matchedTags.any { it.contains("customonlyfansgirl") })

            val byName = AdultAccountDirectory.inspect("Watching Spicy Creator Name live", isSocialApp = false)
            assertTrue("Custom creator display name must block immediately", byName.shouldBlockImmediately)

            val bySecondHandle = AdultAccountDirectory.inspect("Resumed window showing @badpage99", isSocialApp = true)
            assertTrue("Custom @handle must block immediately on resume", bySecondHandle.shouldBlockImmediately)

            val unrelated = AdultAccountDirectory.inspect("Normal news article about football", isSocialApp = true)
            assertFalse("Unrelated safe text must not match custom blocklist", unrelated.shouldBlockImmediately)
        } finally {
            AdultAccountDirectory.setCustomBlockedEntriesForTests(emptyList())
        }
    }
}
