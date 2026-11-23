package com.paulfruitful.screenguard.classifier

import android.graphics.Bitmap

/**
 * Computes a fast 64-bit difference perceptual hash (dHash) over an 9x8 grid sampled
 * directly from the 224x224 downsampled [Bitmap] in < 0.15 ms.
 *
 * Used for duplicate-frame detection so static screens within the same foreground
 * application do not trigger redundant neural network inference.
 */
object PerceptualFrameHasher {

    fun computeDHash64(bitmap: Bitmap): Long {
        if (bitmap.isRecycled || bitmap.width < 9 || bitmap.height < 8) {
            return 0L
        }
        val w = bitmap.width
        val h = bitmap.height
        val lumaGrid = IntArray(9 * 8)

        for (row in 0 until 8) {
            val y = ((row + 0.5f) * h / 8f).toInt().coerceIn(0, h - 1)
            for (col in 0 until 9) {
                val x = ((col + 0.5f) * w / 9f).toInt().coerceIn(0, w - 1)
                val argb = bitmap.getPixel(x, y)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                lumaGrid[row * 9 + col] = (299 * r + 587 * g + 114 * b) / 1000
            }
        }

        var hash = 0L
        var bitIndex = 0
        for (row in 0 until 8) {
            val rowOffset = row * 9
            for (col in 0 until 8) {
                if (lumaGrid[rowOffset + col] > lumaGrid[rowOffset + col + 1]) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return hash
    }

    fun hammingDistance(hashA: Long, hashB: Long): Int {
        return java.lang.Long.bitCount(hashA xor hashB)
    }

    fun formatHex(hash: Long): String {
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }
}
