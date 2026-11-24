package com.paulfruitful.screenguard.multimodal

import android.graphics.Bitmap
import android.os.SystemClock
import com.paulfruitful.screenguard.accessibility.ScreenTextContextAnalyzer
import com.paulfruitful.screenguard.accessibility.ScreenTextSignal
import kotlin.math.abs

/**
 * Converts on-screen Accessibility caption/node signals and fast subtitle-band visual text density
 * into separate [CaptionNodeEvidence] and [OcrFrameEvidence] records (Phase C).
 *
 * Enforces two critical rules:
 * 1. Do not assume every application exposes captions (marks `available = false` when no text nodes exist).
 * 2. Do not mistake sexual vocabulary in dialogue, health/medical articles, or news for explicit visual content.
 */
object CaptionAndOcrAnalyzer {

    fun buildCaptionEvidence(signal: ScreenTextSignal): CaptionNodeEvidence {
        if (!signal.hasVisibleText) {
            return CaptionNodeEvidence(
                available = false,
                sourceType = CaptionSourceType.NONE,
                durationMs = signal.scanDurationMs
            )
        }

        val srcType = when {
            signal.isExplicitPornDomain -> CaptionSourceType.BROWSER_URL_BAR
            signal.fromSubtitleNode -> CaptionSourceType.SUBTITLE_VIEW_NODE
            else -> CaptionSourceType.ACCESSIBILITY_UI_TEXT
        }

        return CaptionNodeEvidence(
            available = true,
            sourceType = srcType,
            explicitPornDomainDetected = signal.isExplicitPornDomain,
            hardcoreKeywordScore = if (signal.isExplicitPornDomain) 0.96f else signal.hardcoreScore,
            contextualAdultScore = signal.adultScore,
            benignDialogueOrEducationalContext = signal.isBenignEducationalOrDialogue,
            matchedCategoryCount = signal.matchedIndicators.size,
            durationMs = signal.scanDurationMs
        )
    }

    /**
     * Fast on-device visual text / subtitle band detector on the 224x224 frame (< 0.6 ms).
     * Measures high-frequency horizontal character-stroke transitions in the lower subtitle band
     * (`y: 72%..90%`) to detect burned-in video captions even when apps do not expose accessibility
     * subtitle nodes.
     */
    fun analyzeSubtitleBandOcr(
        bitmap224: Bitmap,
        pixelsScratch: IntArray? = null
    ): OcrFrameEvidence {
        val t0 = SystemClock.elapsedRealtimeNanos()
        if (bitmap224.isRecycled || bitmap224.width < 64 || bitmap224.height < 64) {
            return OcrFrameEvidence(available = false)
        }
        val w = bitmap224.width
        val h = bitmap224.height
        val yStart = (h * 0.72f).toInt()
        val yEnd = (h * 0.90f).toInt()
        val xStart = (w * 0.12f).toInt()
        val xEnd = (w * 0.88f).toInt()

        var highContrastTransitions = 0
        var totalSamples = 0

        for (y in yStart until yEnd step 2) {
            var prevLuma = -1
            for (x in xStart until xEnd step 2) {
                val px = bitmap224.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val luma = (299 * r + 587 * g + 114 * b) / 1000
                if (prevLuma >= 0) {
                    if (abs(luma - prevLuma) >= 85) {
                        highContrastTransitions++
                    }
                    totalSamples++
                }
                prevLuma = luma
            }
        }

        val density = if (totalSamples > 0) {
            highContrastTransitions.toFloat() / totalSamples.toFloat()
        } else {
            0f
        }
        val subtitleDetected = density in 0.14f..0.48f
        val dur = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L)

        return OcrFrameEvidence(
            available = true,
            subtitleBandDetected = subtitleDetected,
            glyphEdgeDensity = density,
            visualTextConfidenceBoost = 0f, // Never boosts NSFW score from glyph presence alone
            durationMs = dur
        )
    }
}
