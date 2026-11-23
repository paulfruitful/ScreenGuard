package com.paulfruitful.screenguard.classifier

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class PreprocessingMode {
    ORIGINAL,
    GAMMA_CORRECTION,
    CLAHE_LOCAL_CONTRAST,
    BRIGHTNESS_CONTRAST_NORM
}

data class IlluminationMetrics(
    val meanLuminance: Float,
    val stdDevLuminance: Float,
    val darkPixelRatio: Float,
    val brightPixelRatio: Float,
    val p5Luminance: Int,
    val p95Luminance: Int,
    val isLowLight: Boolean,
    val isLowContrast: Boolean
)

/**
 * Low-light analysis and contrast-enhancement pipeline for Milestone 2.1.
 *
 * Supports:
 * 1. [PreprocessingMode.ORIGINAL]: Unmodified 224x224 frame.
 * 2. [PreprocessingMode.GAMMA_CORRECTION]: Adaptive power-law luminance gamma correction
 *    with shadow-noise floor damping so dark noise is not amplified into false chroma artifacts.
 * 3. [PreprocessingMode.CLAHE_LOCAL_CONTRAST]: Contrast-Limited Adaptive Histogram Equalization
 *    over an 8x8 tile grid (28x28 pixels per tile) with bilinear inter-tile CDF interpolation.
 * 4. [PreprocessingMode.BRIGHTNESS_CONTRAST_NORM]: 5th-to-95th percentile dynamic range stretching
 *    with a bounded gain cap (maxGain = 2.25x) to avoid over-amplifying uniform dark backgrounds.
 */
object LowLightPreprocessor {

    fun analyzeIllumination(bitmap: Bitmap): IlluminationMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h
        if (total <= 0 || bitmap.isRecycled) {
            return IlluminationMetrics(128f, 40f, 0f, 0f, 20, 230, false, false)
        }

        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sum = 0.0
        var sumSq = 0.0
        var darkCount = 0
        var brightCount = 0

        for (i in 0 until total) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val luma = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
            hist[luma]++
            sum += luma
            sumSq += (luma.toDouble() * luma.toDouble())
            if (luma < 60) darkCount++
            if (luma > 210) brightCount++
        }

        val mean = (sum / total).toFloat()
        val variance = max(0.0, (sumSq / total) - (mean.toDouble() * mean.toDouble()))
        val stdDev = sqrt(variance).toFloat()
        val darkRatio = darkCount.toFloat() / total.toFloat()
        val brightRatio = brightCount.toFloat() / total.toFloat()

        val targetP5 = (total * 0.05f).toInt()
        val targetP95 = (total * 0.95f).toInt()
        var cumulative = 0
        var p5 = 0
        var p95 = 255
        var foundP5 = false
        for (v in 0..255) {
            cumulative += hist[v]
            if (!foundP5 && cumulative >= targetP5) {
                p5 = v
                foundP5 = true
            }
            if (cumulative >= targetP95) {
                p95 = v
                break
            }
        }

        val isLowLight = mean < 82f || (mean < 102f && darkRatio > 0.52f)
        val isLowContrast = (p95 - p5) < 95 || stdDev < 34f

        return IlluminationMetrics(
            meanLuminance = mean,
            stdDevLuminance = stdDev,
            darkPixelRatio = darkRatio,
            brightPixelRatio = brightRatio,
            p5Luminance = p5,
            p95Luminance = p95,
            isLowLight = isLowLight,
            isLowContrast = isLowContrast
        )
    }

    fun applyEnhancement(
        source: Bitmap,
        mode: PreprocessingMode,
        metrics: IlluminationMetrics? = null
    ): Bitmap {
        return when (mode) {
            PreprocessingMode.ORIGINAL -> source.copy(Bitmap.Config.ARGB_8888, false)
            PreprocessingMode.GAMMA_CORRECTION -> applyGammaCorrection(source, metrics)
            PreprocessingMode.CLAHE_LOCAL_CONTRAST -> applyClaheLocalContrast(source)
            PreprocessingMode.BRIGHTNESS_CONTRAST_NORM -> applyBrightnessContrastNorm(source, metrics)
        }
    }

    /**
     * Adaptive power-law gamma correction on luminance with shadow-noise suppression.
     */
    fun applyGammaCorrection(
        source: Bitmap,
        metrics: IlluminationMetrics? = null
    ): Bitmap {
        val m = metrics ?: analyzeIllumination(source)
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Compute adaptive gamma in [0.48, 0.72] targeting mid-tone luminance ~ 115/255
        val normalizedMean = (m.meanLuminance / 255f).coerceIn(0.08f, 0.85f)
        val gamma = if (normalizedMean < 0.45f) {
            (ln(0.45) / ln(normalizedMean.toDouble())).toFloat().coerceIn(0.48f, 0.75f)
        } else {
            0.75f
        }

        val lut = IntArray(256)
        for (v in 0..255) {
            val norm = v / 255.0
            val lifted = (norm.pow(gamma.toDouble()) * 255.0).toInt().coerceIn(0, 255)
            // Soft blend in deep shadow floor (v < 14) so dark sensor noise isn't over-boosted
            lut[v] = if (v < 14) {
                ((v * (14 - v) + lifted * v) / 14).coerceIn(0, 255)
            } else {
                lifted
            }
        }

        for (i in 0 until total) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val luma = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(1, 255)
            val newLuma = lut[luma]
            // Cap per-pixel chromaticity gain to 2.3x to prevent false color artifacts
            val gain = min(2.3f, newLuma.toFloat() / luma.toFloat())
            val nr = (r * gain).toInt().coerceIn(0, 255)
            val ng = (g * gain).toInt().coerceIn(0, 255)
            val nb = (b * gain).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Contrast-Limited Adaptive Histogram Equalization (CLAHE) on luminance channel
     * over an 8x8 tile grid with bilinear interpolation and shadow-noise damping.
     */
    fun applyClaheLocalContrast(
        source: Bitmap,
        tilesX: Int = 8,
        tilesY: Int = 8,
        clipLimitFactor: Float = 2.0f
    ): Bitmap {
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val lumaPlane = IntArray(total)
        for (i in 0 until total) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            lumaPlane[i] = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
        }

        val tileW = max(1, w / tilesX)
        val tileH = max(1, h / tilesY)
        // cdfs[ty][tx][luma] -> mapped value 0..255
        val tileCdfs = Array(tilesY) { Array(tilesX) { IntArray(256) } }

        for (ty in 0 until tilesY) {
            val y0 = ty * tileH
            val y1 = if (ty == tilesY - 1) h else min(h, (ty + 1) * tileH)
            for (tx in 0 until tilesX) {
                val x0 = tx * tileW
                val x1 = if (tx == tilesX - 1) w else min(w, (tx + 1) * tileW)
                val area = max(1, (y1 - y0) * (x1 - x0))
                val hist = IntArray(256)

                for (y in y0 until y1) {
                    val rowOff = y * w
                    for (x in x0 until x1) {
                        hist[lumaPlane[rowOff + x]]++
                    }
                }

                val clipLimit = max(2, ((clipLimitFactor * area) / 256f).toInt())
                var excess = 0
                for (v in 0..255) {
                    if (hist[v] > clipLimit) {
                        excess += (hist[v] - clipLimit)
                        hist[v] = clipLimit
                    }
                }
                val redistrib = excess / 256
                val remainder = excess % 256
                for (v in 0..255) {
                    hist[v] += redistrib + if (v < remainder) 1 else 0
                }

                var cum = 0
                val cdf = tileCdfs[ty][tx]
                for (v in 0..255) {
                    cum += hist[v]
                    cdf[v] = ((cum * 255f) / area.toFloat()).toInt().coerceIn(0, 255)
                }
            }
        }

        for (y in 0 until h) {
            val fy = ((y.toFloat() - tileH / 2f) / tileH.toFloat())
            val ty0 = fy.toInt().coerceIn(0, tilesY - 1)
            val ty1 = (ty0 + 1).coerceIn(0, tilesY - 1)
            val wy = (fy - ty0).coerceIn(0f, 1f)
            val rowOff = y * w

            for (x in 0 until w) {
                val fx = ((x.toFloat() - tileW / 2f) / tileW.toFloat())
                val tx0 = fx.toInt().coerceIn(0, tilesX - 1)
                val tx1 = (tx0 + 1).coerceIn(0, tilesX - 1)
                val wx = (fx - tx0).coerceIn(0f, 1f)

                val idx = rowOff + x
                val luma = lumaPlane[idx]

                val c00 = tileCdfs[ty0][tx0][luma]
                val c10 = tileCdfs[ty0][tx1][luma]
                val c01 = tileCdfs[ty1][tx0][luma]
                val c11 = tileCdfs[ty1][tx1][luma]

                val top = c00 * (1f - wx) + c10 * wx
                val bot = c01 * (1f - wx) + c11 * wx
                val claheLuma = (top * (1f - wy) + bot * wy).toInt().coerceIn(0, 255)

                // Blend 70% CLAHE + 30% original luminance, with deep-shadow noise damping (luma < 15)
                val blendedLuma = if (luma < 15) {
                    ((luma * 2 + claheLuma) / 3).coerceIn(0, 255)
                } else {
                    ((claheLuma * 7 + luma * 3) / 10).coerceIn(0, 255)
                }

                val safeDenom = max(1, luma)
                val gain = min(2.25f, blendedLuma.toFloat() / safeDenom.toFloat())
                val argb = pixels[idx]
                val r = (((argb shr 16) and 0xFF) * gain).toInt().coerceIn(0, 255)
                val g = (((argb shr 8) and 0xFF) * gain).toInt().coerceIn(0, 255)
                val b = ((argb and 0xFF) * gain).toInt().coerceIn(0, 255)
                pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Brightness and contrast percentile normalization ([P5, P95] -> [18, 235]) with capped gain.
     */
    fun applyBrightnessContrastNorm(
        source: Bitmap,
        metrics: IlluminationMetrics? = null
    ): Bitmap {
        val m = metrics ?: analyzeIllumination(source)
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val span = max(25, m.p95Luminance - m.p5Luminance)
        val scale = min(2.25f, 217f / span.toFloat())
        val offset = 18f - (m.p5Luminance * scale)

        for (i in 0 until total) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val nr = (r * scale + offset).toInt().coerceIn(0, 255)
            val ng = (g * scale + offset).toInt().coerceIn(0, 255)
            val nb = (b * scale + offset).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
