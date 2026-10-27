package com.pdforge.app.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android bitmap-backed [ImageRecompressor] used on device.
 *
 * Decodes an embedded JPEG downsampled to the preset's target resolution, re-encodes
 * it at the preset's quality, and releases the bitmap immediately. This is what
 * makes "Recommended" and "Smallest" actually shrink image-heavy PDFs.
 */
internal class AndroidImageRecompressor : ImageRecompressor {

    override fun recompress(jpegBytes: ByteArray, quality: Int, maxLongEdgePx: Int): RecompressedImage? {
        if (jpegBytes.isEmpty() || maxLongEdgePx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = inSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdgePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } ?: return null

        val (targetWidth, targetHeight) = scaledDimensions(decoded.width, decoded.height, maxLongEdgePx)
        val scaled = if (targetWidth == decoded.width && targetHeight == decoded.height) {
            decoded
        } else {
            try {
                Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                    if (it != decoded) decoded.recycle()
                }
            } catch (_: OutOfMemoryError) {
                decoded
            }
        }

        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            RecompressedImage(out.toByteArray(), scaled.width, scaled.height)
        } catch (_: Exception) {
            null
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    }

    /** Power-of-two subsample factor that brings the image at or below [maxLongEdgePx]. */
    private fun inSampleSize(srcWidth: Int, srcHeight: Int, maxLongEdgePx: Int): Int {
        var sample = 1
        while (max(srcWidth, srcHeight) / (sample * 2) >= maxLongEdgePx) {
            sample *= 2
        }
        return sample
    }

    /** Final scaled size, preserving aspect ratio. */
    private fun scaledDimensions(srcWidth: Int, srcHeight: Int, maxLongEdgePx: Int): Pair<Int, Int> {
        val longest = max(srcWidth, srcHeight)
        if (longest <= maxLongEdgePx) return srcWidth to srcHeight
        val scale = maxLongEdgePx.toDouble() / longest
        return (srcWidth * scale).roundToInt().coerceAtLeast(1) to
            (srcHeight * scale).roundToInt().coerceAtLeast(1)
    }
}
