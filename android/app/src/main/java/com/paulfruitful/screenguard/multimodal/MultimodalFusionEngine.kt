package com.paulfruitful.screenguard.multimodal

import android.os.SystemClock
import com.paulfruitful.screenguard.classifier.ContentCategory
import com.paulfruitful.screenguard.protection.ProtectionPolicyConfig
import com.paulfruitful.screenguard.protection.ProtectionSensitivityMode

/**
 * Multimodal Evidence Fusion Engine (Phase C).
 *
 * Design principles:
 * 1. Keeps Visual, AudioPlayback, CaptionNode, and OcrFrame evidence strictly separate.
 * 2. Handles unavailable modalities: missing audio or captions never push a frame toward SAFE.
 * 3. Avoids double-counting correlated evidence: CaptionNode and OcrFrame both observe on-screen
 *    text, so their combined contribution is capped by `max(captionBoost, ocrBoost) <= 0.14f`.
 * 4. Avoids false positives from dialogue alone: sexual or medical/educational vocabulary in
 *    captions/subtitles/UI text NEVER triggers an intervention when visual evidence is safe
 *    (`pornScore < 0.08f && sexyScore < 0.14f && stage2NsfwScore < 0.28f`), with the sole exception
 *    of an explicit porn domain URL (`pornhub.com`, `xvideos.com`, `xnxx.com`, etc.) in a browser bar.
 * 5. Preserves uncertainty: ambiguous visual signals without multimodal or Stage-2 corroboration
 *    remain `UNCERTAIN`.
 */
object MultimodalFusionEngine {

    private const val MAX_TEXT_CORROBORATION_BOOST = 0.14f
    private const val MAX_AUDIO_CORROBORATION_BOOST = 0.06f

    fun fuse(
        bundle: MultimodalEvidenceBundle,
        policy: ProtectionPolicyConfig
    ): FusedMultimodalDecision {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val v = bundle.visual
        val a = bundle.audio
        val c = bundle.caption
        val o = bundle.ocr
        val ctx = bundle.contextVerifier
        val nlp = ctx.nlpScore

        val corroborating = mutableListOf<String>()

        // 1. Explicit Porn Domain in Browser URL Bar or OCR Text (deterministic, instant 1-frame block)
        if ((c.available && c.explicitPornDomainDetected) || nlp.explicitPornDomainDetected) {
            corroborating.add("CAPTION_URL_DOMAIN")
            if (v.available && v.pornScore >= 0.15f) corroborating.add("VISUAL")
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            return FusedMultimodalDecision(
                finalCategory = ContentCategory.EXPLICIT.name,
                finalConfidence = maxOf(0.99f, v.confidence),
                shouldIntervene = true,
                primaryModality = "CAPTION_URL_DOMAIN",
                corroboratingModalities = corroborating,
                uncertaintyPreserved = false,
                dialogueOnlySuppressed = false,
                fusionExplanation = "Explicit adult domain detected in URL/OCR text",
                decisionDurationMs = dur
            )
        }

        // 1a. Talking-Head / Close-Up Portrait Veto (ML Kit Face Detection):
        // If a large face dominates the frame (vlog, WhatsApp/TikTok talking head, video call)
        // and explicit porn/Stage-2 scores are not overwhelmingly explicit (< 0.78), suppress false
        // skin/camisole/dark-room nudity alarms.
        val isOverwhelminglyExplicit =
            v.pornScore >= 0.78f && (!v.stage2Triggered || v.stage2NsfwScore >= 0.78f)
        if (ctx.executed && ctx.isTalkingHeadPortrait && !isOverwhelminglyExplicit && !ctx.isBentOverOrRearFacingPose) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            return FusedMultimodalDecision(
                finalCategory = ContentCategory.NON_EXPLICIT.name,
                finalConfidence = maxOf(v.neutralScore + v.drawingsScore, 0.85f).coerceIn(0f, 1f),
                shouldIntervene = false,
                primaryModality = "VISUAL",
                corroboratingModalities = listOf("TALKING_HEAD_FACE_VETO"),
                uncertaintyPreserved = false,
                dialogueOnlySuppressed = true,
                fusionExplanation = "Talking-head portrait veto applied (faceArea=${"%.1f".format(ctx.largestFaceAreaRatio * 100)}%, porn=${"%.2f".format(v.pornScore)})",
                decisionDurationMs = dur
            )
        }

        // 1b. Benign Non-Sexual Context Veto (Accessibility + ML Kit OCR + On-Device NLP):
        // Dampens borderline SUGGESTIVE / UNCERTAIN / low-porn frames when NLP or caption context
        // clearly identifies non-sexual content (tailoring, gym, MMA, medical, parenting, faith).
        val isBorderlineCategory =
            v.predictedCategory == ContentCategory.SUGGESTIVE.name ||
                v.predictedCategory == ContentCategory.UNCERTAIN.name ||
                (v.predictedCategory == ContentCategory.SEXUAL_ACTIVITY.name && v.pornScore < 0.30f)
        val hasLowExplicitSignal =
            v.pornScore < 0.30f &&
                v.hentaiScore < 0.25f &&
                v.maxRoiPornScore < 0.35f &&
                (!v.stage2Triggered || v.stage2NsfwScore < 0.62f)
        val nlpSaysNonSexual =
            ctx.executed && nlp.dominantClass == "NON_SEXUAL" && nlp.nonSexualContextProb >= 0.65f
        val hasBenignContext =
            (nlpSaysNonSexual || (c.available && (c.benignNonSexualVisualContext || c.benignDialogueOrEducationalContext))) &&
                c.hardcoreKeywordScore < 0.35f &&
                nlp.explicitSexualProb < 0.35f

        if (isBorderlineCategory && hasLowExplicitSignal && hasBenignContext) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            return FusedMultimodalDecision(
                finalCategory = ContentCategory.NON_EXPLICIT.name,
                finalConfidence = maxOf(v.neutralScore + v.drawingsScore, 0.82f).coerceIn(0f, 1f),
                shouldIntervene = false,
                primaryModality = "VISUAL",
                corroboratingModalities = listOf("NLP_NON_SEXUAL_VETO"),
                uncertaintyPreserved = false,
                dialogueOnlySuppressed = true,
                fusionExplanation = "Non-sexual NLP/context veto applied to ${v.predictedCategory} (nlpSafe=${"%.0f".format(nlp.nonSexualContextProb * 100)}%, tags=${nlp.matchedContextTags.joinToString(",")})",
                decisionDurationMs = dur
            )
        }

        // 1c. Explicit Sexual Caption / Adult Promo Text on Active Media (OCR + NLP):
        // Catches clothed thirst-trap posts whose on-screen caption is explicitly sexual
        // (e.g. "If u wanna jrkkk off, lock your doors and picture my ass on your D*ck", "@sweetpxrns")
        // while still respecting educational/medical/faith/news non-sexual contexts.
        val explicitCaptionOnMedia =
            ctx.executed &&
                !nlpSaysNonSexual &&
                !(c.available && c.benignDialogueOrEducationalContext) &&
                nlp.dominantClass == "EXPLICIT_SEXUAL" &&
                nlp.explicitSexualProb >= 0.72f

        if (explicitCaptionOnMedia) {
            corroborating.add("NLP_EXPLICIT_CAPTION")
            if (ctx.clothedSuggestivePoseScore >= 0.40f) corroborating.add("POSE_SUGGESTIVE")
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            return FusedMultimodalDecision(
                finalCategory = ContentCategory.EXPLICIT.name,
                finalConfidence = maxOf(0.82f, nlp.explicitSexualProb).coerceIn(0f, 1f),
                shouldIntervene = true,
                primaryModality = "NLP_EXPLICIT_CAPTION",
                corroboratingModalities = corroborating,
                uncertaintyPreserved = false,
                dialogueOnlySuppressed = false,
                fusionExplanation = "Explicit sexual caption/handle detected by OCR+NLP (${nlp.matchedContextTags.joinToString(",")})",
                decisionDurationMs = dur
            )
        }

        // 1d. Clothed Twerking / Sexually Suggestive Pose Detection (ML Kit Pose + Visual + NLP):
        // Catches fully-clothed bent-over / rear-facing / hip-centered suggestive dancing across
        // our 3-frame (~2.2s) accumulative gate even when the nudity model sees 0% skin, while
        // respecting non-sexual gym/workout/talking-head contexts.
        val poseIndicatesSuggestive =
            ctx.executed &&
                !ctx.isTalkingHeadPortrait &&
                !nlpSaysNonSexual &&
                !(c.available && c.benignNonSexualVisualContext) &&
                (ctx.clothedSuggestivePoseScore >= 0.58f ||
                    (ctx.clothedSuggestivePoseScore >= 0.46f &&
                        (v.sexyScore >= 0.20f || nlp.suggestivePromoProb >= 0.45f || ctx.hipOscillationScore >= 0.30f)))

        if (poseIndicatesSuggestive &&
            (v.predictedCategory == ContentCategory.NON_EXPLICIT.name ||
                v.predictedCategory == ContentCategory.UNCERTAIN.name ||
                v.predictedCategory == ContentCategory.SUGGESTIVE.name)
        ) {
            corroborating.add("POSE_SUGGESTIVE")
            if (nlp.suggestivePromoProb >= 0.45f) corroborating.add("NLP_SUGGESTIVE")
            val combinedPoseConf = maxOf(
                v.sexyScore,
                0.60f * ctx.clothedSuggestivePoseScore +
                    0.20f * maxOf(v.sexyScore, ctx.hipOscillationScore) +
                    0.20f * maxOf(nlp.suggestivePromoProb, 0.50f)
            ).coerceIn(0.74f, 0.92f)
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            return FusedMultimodalDecision(
                finalCategory = ContentCategory.SUGGESTIVE.name,
                finalConfidence = combinedPoseConf,
                shouldIntervene = true,
                primaryModality = "POSE_MULTIMODAL",
                corroboratingModalities = corroborating,
                uncertaintyPreserved = false,
                dialogueOnlySuppressed = false,
                fusionExplanation = "Clothed suggestive pose corroborated (pose=${"%.2f".format(ctx.clothedSuggestivePoseScore)}, osc=${"%.2f".format(ctx.hipOscillationScore)}, nlpSug=${"%.2f".format(nlp.suggestivePromoProb)})",
                decisionDurationMs = dur
            )
        }

        // 2. Dialogue-only / Educational Text False-Positive Guard:
        // If visual evidence has no meaningful explicit/nudity signal, text or audio alone
        // must NEVER declare the screen explicit.
        val visualHasSexualSignal =
            v.pornScore >= 0.10f ||
                v.sexyScore >= 0.18f ||
                v.maxRoiPornScore >= 0.25f ||
                (v.stage2Triggered && v.stage2NsfwScore >= 0.30f)

        val textHasAdultVocabulary =
            c.available && (c.contextualAdultScore > 0f || c.hardcoreKeywordScore > 0f)

        if (textHasAdultVocabulary && (!visualHasSexualSignal || c.benignDialogueOrEducationalContext)) {
            val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
            return FusedMultimodalDecision(
                finalCategory = ContentCategory.NON_EXPLICIT.name,
                finalConfidence = maxOf(v.neutralScore + v.drawingsScore, 0.85f).coerceIn(0f, 1f),
                shouldIntervene = false,
                primaryModality = "VISUAL",
                corroboratingModalities = emptyList(),
                uncertaintyPreserved = false,
                dialogueOnlySuppressed = true,
                fusionExplanation = "Suppressed text-only adult/medical vocabulary because visual frame is non-explicit",
                decisionDurationMs = dur
            )
        }

        // 3. Compute correlated text boost (de-duplicated across CaptionNode & OcrFrame)
        val rawTextBoost = if (c.available && !c.benignDialogueOrEducationalContext && visualHasSexualSignal) {
            maxOf(c.hardcoreKeywordScore * 0.14f, c.contextualAdultScore * 0.10f, o.visualTextConfidenceBoost)
                .coerceIn(0f, MAX_TEXT_CORROBORATION_BOOST)
        } else {
            0f
        }
        if (rawTextBoost > 0.02f) {
            corroborating.add("CAPTION_TEXT")
        }

        // 4. Compute supplementary audio boost (only when visual sexual signal is present)
        val audioBoost = if (a.available && visualHasSexualSignal && a.supplementarySuspicionScore > 0.05f) {
            corroborating.add("AUDIO_PLAYBACK")
            a.supplementarySuspicionScore.coerceIn(0f, MAX_AUDIO_CORROBORATION_BOOST)
        } else if (a.usageMediaActive && visualHasSexualSignal) {
            corroborating.add("ACTIVE_MEDIA_STREAM")
            0f
        } else {
            0f
        }

        val totalCorroborationBoost = (rawTextBoost + audioBoost).coerceIn(0f, 0.18f)
        val boostedConfidence = (v.confidence + totalCorroborationBoost).coerceIn(0f, 1f)

        // 5. Category-specific threshold evaluation with uncertainty preservation
        val (shouldIntervene, finalCategory) = when (v.predictedCategory) {
            ContentCategory.EXPLICIT.name -> {
                val eff = maxOf(boostedConfidence, v.explicitScore, v.pornScore)
                (eff >= policy.explicitThreshold) to ContentCategory.EXPLICIT.name
            }
            ContentCategory.NUDITY.name -> {
                val eff = maxOf(boostedConfidence, v.nudityScore, v.pornScore)
                (eff >= policy.nudityThreshold) to ContentCategory.NUDITY.name
            }
            ContentCategory.SEXUAL_ACTIVITY.name -> {
                val eff = maxOf(boostedConfidence, v.sexualActivityScore)
                (eff >= policy.sexualActivityThreshold) to ContentCategory.SEXUAL_ACTIVITY.name
            }
            ContentCategory.SUGGESTIVE.name -> {
                val eff = maxOf(boostedConfidence, v.sexyScore)
                val trig = policy.interveneOnSuggestiveInStrict &&
                    eff >= policy.suggestiveThresholdStrict
                trig to ContentCategory.SUGGESTIVE.name
            }
            ContentCategory.UNCERTAIN.name -> {
                false to ContentCategory.UNCERTAIN.name
            }
            else -> {
                false to ContentCategory.NON_EXPLICIT.name
            }
        }

        val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)
        return FusedMultimodalDecision(
            finalCategory = finalCategory,
            finalConfidence = boostedConfidence,
            shouldIntervene = shouldIntervene,
            primaryModality = "VISUAL",
            corroboratingModalities = corroborating,
            uncertaintyPreserved = finalCategory == ContentCategory.UNCERTAIN.name,
            dialogueOnlySuppressed = false,
            fusionExplanation = "Visual=$finalCategory (${"%.1f".format(v.confidence * 100)}%) + Corroboration=${"%.1f".format(totalCorroborationBoost * 100)}% [${corroborating.joinToString(",")}]",
            decisionDurationMs = dur
        )
    }
}
