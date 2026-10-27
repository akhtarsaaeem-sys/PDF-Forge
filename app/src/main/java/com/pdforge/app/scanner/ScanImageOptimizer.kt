package com.pdforge.app.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Optimizes a camera capture before it is embedded in a scanned PDF.
 *
 * WHY: scanning used to decode the camera JPEG at full sensor resolution (12 MP
 * and up), keep every page's full-resolution ARGB bitmap in memory, and hand
 * those bitmaps straight to the PDF writer. A 6-page scan came out around
 * 114 MB and put hundreds of megabytes of bitmaps in RAM.
 *
 * Pipeline now (per page):
 *   camera JPEG bytes
 *     -> decode downsampled to [DOCUMENT_LONG_EDGE_PX] (document resolution)
 *     -> bake in the EXIF/orientation rotation
 *     -> re-encode as a JPEG at [JPEG_QUALITY]
 *     -> keep only the compressed bytes (the large bitmap is released)
 *   ...later, at PDF build time...
 *     -> embed the JPEG bytes directly (no second rasterization, no re-encode)
 *
 * The images stay readable for documents: ~2000 px on the long edge is roughly
 * 200-250 DPI when placed on an A4 page.
 *
 * The sizing/quality decisions live in pure functions so they can be tested
 * without a camera.
 */
object ScanImageOptimizer {

    /**
     * Long-edge resolution of the stored page image. Large enough to keep small
     * print legible, small enough that a page is a few hundred kilobytes instead
     * of ~19 MB.
     */
    const val DOCUMENT_LONG_EDGE_PX = 2000

    /** JPEG quality for stored scan pages — visually lossless for text. */
    const val JPEG_QUALITY = 82

    /** Long-edge resolution used for the on-screen page thumbnails. */
    const val PREVIEW_LONG_EDGE_PX = 320

    /**
     * Power-of-two subsample factor that brings [srcWidth] x [srcHeight] at or
     * below [targetLongEdge] on the long edge. Decoding with this factor avoids
     * ever allocating the full-resolution bitmap.
     */
    fun computeInSampleSize(srcWidth: Int, srcHeight: Int, targetLongEdge: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetLongEdge <= 0) return 1
        var sample = 1
        while (max(srcWidth, srcHeight) / (sample * 2) >= targetLongEdge) {
            sample *= 2
        }
        return sample
    }

    /** Target size for an image with the given source size, preserving aspect ratio. */
    fun scaledDimensions(srcWidth: Int, srcHeight: Int, maxLongEdge: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0 || maxLongEdge <= 0) return srcWidth to srcHeight
        val longest = max(srcWidth, srcHeight)
        if (longest <= maxLongEdge) return srcWidth to srcHeight
        val scale = maxLongEdge.toDouble() / longest
        return (srcWidth * scale).roundToInt().coerceAtLeast(1) to
            (srcHeight * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Decodes [data] (a camera JPEG) down to at most [targetLongEdge] on the long
     * edge. Never allocates the full-resolution bitmap; returns null when the
     * image cannot be decoded.
     */
    fun decodeDownsampled(data: ByteArray, targetLongEdge: Int = DOCUMENT_LONG_EDGE_PX): Bitmap? {
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        } ?: return null

        val (targetWidth, targetHeight) = scaledDimensions(decoded.width, decoded.height, targetLongEdge)
        if (targetWidth == decoded.width && targetHeight == decoded.height) return decoded
        return try {
            val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            if (scaled != decoded) decoded.recycle()
            scaled
        } catch (e: OutOfMemoryError) {
            decoded
        }
    }

    /** Rotates [bitmap] clockwise; returns the same instance when no rotation applies. */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    /** Re-encodes [bitmap] as JPEG at [quality] and recycles the bitmap. */
    fun compress(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /**
     * Turns raw captured JPEG bytes into an optimized page: downsampled, rotated
     * to [orientationDegrees], and stored as compressed bytes. The large decoded
     * bitmap is released before this returns.
     */
    fun optimizeCapture(data: ByteArray, orientationDegrees: Int = 0): ScanPage? {
        val decoded = decodeDownsampled(data) ?: return null
        val upright = rotate(decoded, orientationDegrees)
        return try {
            val bytes = compress(upright)
            ScanPage(jpegBytes = bytes, width = upright.width, height = upright.height)
        } finally {
            if (!upright.isRecycled) upright.recycle()
        }
    }

    /** Rotates an already-optimized page by [degrees], re-encoding the result. */
    fun rotatePage(page: ScanPage, degrees: Int): ScanPage? {
        val decoded = decodeDownsampled(page.jpegBytes, max(page.width, page.height)) ?: return null
        val rotated = rotate(decoded, degrees)
        return try {
            ScanPage(
                jpegBytes = compress(rotated),
                width = rotated.width,
                height = rotated.height
            )
        } finally {
            if (!rotated.isRecycled) rotated.recycle()
        }
    }
}

/**
 * One scanned page.
 *
 * Holds only the compressed JPEG bytes plus its pixel size — never a
 * full-resolution bitmap — so a 6-page (or 20-page) scan stays small in memory.
 * Bitmaps are decoded on demand, one at a time, for previews and PDF building.
 */
class ScanPage(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
) {
    val sizeBytes: Long get() = jpegBytes.size.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanPage) return false
        return width == other.width && height == other.height && jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int =
        jpegBytes.contentHashCode() * 31 + width * 31 + height
}
