package com.pdforge.app.pdf

import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfNumber
import com.itextpdf.kernel.pdf.PdfObject
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import java.io.File
import java.util.IdentityHashMap

/**
 * PDF compression engine.
 *
 * WHY this exists: the previous implementation only rewrote the file with a
 * different flate compression level. It never touched the embedded image
 * streams, so an image-heavy PDF (which is the common case for scans) came out
 * byte-for-byte the same size and all three presets behaved identically —
 * "High Quality" did nothing and the other two saved ~0.1 MB.
 *
 * The engine now:
 *  1. Rewrites the document with full stream compression (structure/streams).
 *  2. Recompresses supported embedded JPEG images at the preset's quality and
 *     resolution (see [CompressionPreset]).
 *  3. Only replaces an image when the replacement is genuinely smaller.
 *  4. Keeps the ORIGINAL file when the rewrite did not make it smaller, instead
 *     of publishing a larger file and claiming a reduction that did not happen.
 *
 * Image recompression is delegated to an [ImageRecompressor] so the Android
 * bitmap codec and a deterministic test double can both drive the real engine.
 */

/** JPEG quality and maximum long-edge resolution for embedded images. */
internal data class ImageCompressionSettings(
    val jpegQuality: Int,
    val maxLongEdgePx: Int
)

/**
 * The concrete operations a compression preset performs.
 *
 * [image] == null means embedded images are left untouched (High Quality);
 * otherwise the engine recompresses supported images at that quality/size.
 */
internal data class CompressionPreset(
    val compressionLevel: Int,
    val fullCompression: Boolean,
    val image: ImageCompressionSettings?,
    val flattenForms: Boolean
)

/**
 * Maps the user's choice to the operations the engine actually performs.
 * The three presets must be meaningfully different — this is the single source
 * of truth for that difference.
 */
internal fun compressionPresetFor(quality: CompressQuality): CompressionPreset = when (quality) {
    // Preserve visual quality: optimize structure/streams only.
    CompressQuality.HIGH_QUALITY ->
        CompressionPreset(compressionLevel = 9, fullCompression = true, image = null, flattenForms = false)
    // Balanced: recompress images at a good quality and a generous resolution.
    CompressQuality.RECOMMENDED ->
        CompressionPreset(
            compressionLevel = 6,
            fullCompression = true,
            image = ImageCompressionSettings(jpegQuality = 75, maxLongEdgePx = 2200),
            flattenForms = false
        )
    // Smallest: stronger quality loss and downsampling, plus form flattening.
    CompressQuality.SMALLER_FILE ->
        CompressionPreset(
            compressionLevel = 9,
            fullCompression = true,
            image = ImageCompressionSettings(jpegQuality = 45, maxLongEdgePx = 1200),
            flattenForms = true
        )
}

/** A recompressed image: encoded (JPEG) bytes plus the new pixel dimensions. */
internal data class RecompressedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
)

/** Re-encodes one supported JPEG image. Returns null when it cannot be used. */
internal fun interface ImageRecompressor {
    fun recompress(jpegBytes: ByteArray, quality: Int, maxLongEdgePx: Int): RecompressedImage?
}

/** What the engine actually did to one file. */
internal data class CompressionOutcome(
    val pageCount: Int,
    val originalSize: Long,
    val outputSize: Long,
    val imagesRecompressed: Int,
    /** True when the rewritten file was not smaller, so the original was kept. */
    val keptOriginal: Boolean
)

/**
 * Compresses [inputPath] into [outputPath].
 *
 * Throws only on a hard failure (unreadable input / unpublishable output); a case
 * where the rewrite is not smaller is a normal, successful outcome with
 * [CompressionOutcome.keptOriginal] set and the original bytes copied to
 * [outputPath].
 */
internal fun compressPdfFile(
    inputPath: String,
    outputPath: String,
    quality: CompressQuality,
    recompressor: ImageRecompressor?
): CompressionOutcome {
    val preset = compressionPresetFor(quality)
    val originalSize = File(inputPath).length()

    val reader = PdfReader(inputPath)
    reader.setUnethicalReading(true)
    val writerProps = WriterProperties().apply {
        if (preset.fullCompression) setFullCompressionMode(true)
        setCompressionLevel(preset.compressionLevel)
    }

    var pageCount = 0
    var recompressedImages = 0
    val pdfDoc = ITextPdfDocument(reader, PdfWriter(outputPath, writerProps))
    try {
        if (preset.flattenForms) {
            try {
                val acroForm = com.itextpdf.forms.PdfAcroForm.getAcroForm(pdfDoc, false)
                if (acroForm != null && acroForm.allFormFields.isNotEmpty()) {
                    acroForm.flattenFields()
                }
            } catch (_: Exception) {
                // Form flattening is best-effort: an unusual form must not fail compression.
            }
        }

        val settings = preset.image
        if (settings != null && recompressor != null) {
            // Identity set: two page resource entries can point at the same stream,
            // which must be recompressed once (and counted once).
            val visited = java.util.Collections.newSetFromMap(IdentityHashMap<PdfStream, Boolean>())
            for (i in 1..pdfDoc.numberOfPages) {
                recompressedImages += recompressResources(
                    pdfDoc.getPage(i).resources.getPdfObject(),
                    settings,
                    recompressor,
                    visited,
                    0
                )
            }
        }
        pageCount = pdfDoc.numberOfPages
    } finally {
        pdfDoc.close()
    }

    var outputSize = File(outputPath).length()
    var keptOriginal = false
    if (outputSize >= originalSize) {
        // The rewrite did not help (already-optimized or vector/text-heavy file).
        // Keep the smaller original rather than publishing a larger file.
        try {
            File(outputPath).delete()
            File(inputPath).copyTo(File(outputPath), overwrite = true)
        } catch (_: Exception) {
            // If the copy fails, leave whatever the writer produced; the result
            // screen reports the real sizes either way.
        }
        outputSize = File(outputPath).length()
        keptOriginal = true
    }

    return CompressionOutcome(
        pageCount = pageCount,
        originalSize = originalSize,
        outputSize = outputSize,
        imagesRecompressed = recompressedImages,
        keptOriginal = keptOriginal
    )
}

/**
 * Recursively walks a resource dictionary and recompresses supported image
 * XObjects. Form XObjects are followed because images can be nested inside them
 * (this is common in scanned/imported pages).
 */
private fun recompressResources(
    resources: PdfDictionary,
    settings: ImageCompressionSettings,
    recompressor: ImageRecompressor,
    visited: MutableSet<PdfStream>,
    depth: Int
): Int {
    if (depth > 8) return 0
    val xobjects = resources.getAsDictionary(PdfName.XObject) ?: return 0
    var count = 0
    for (name in xobjects.keySet().toList()) {
        val stream = xobjects.get(name) as? PdfStream ?: continue
        when (stream.getAsName(PdfName.Subtype)) {
            PdfName.Image -> {
                if (visited.add(stream) && recompressImageStream(stream, settings, recompressor)) {
                    count++
                }
            }
            PdfName.Form -> {
                val formResources = stream.getAsDictionary(PdfName.Resources) ?: continue
                count += recompressResources(formResources, settings, recompressor, visited, depth + 1)
            }
        }
    }
    return count
}

/**
 * Replaces a JPEG image stream with a smaller recompressed version.
 *
 * Only plain RGB JPEG images with no soft mask / mask / image mask are touched;
 * anything else is left exactly as it was so the document cannot be corrupted.
 */
private fun recompressImageStream(
    stream: PdfStream,
    settings: ImageCompressionSettings,
    recompressor: ImageRecompressor
): Boolean {
    if (stream.getAsName(PdfName.Filter) != PdfName.DCTDecode) return false
    if (stream.get(PdfName.SMask, false) != null || stream.get(PdfName.Mask, false) != null) return false
    if ((stream.get(PdfName.ImageMask) as? com.itextpdf.kernel.pdf.PdfBoolean)?.value == true) return false

    if (stream.getAsName(PdfName.ColorSpace) != PdfName.DeviceRGB) return false
    val width = stream.getAsNumber(PdfName.Width)?.intValue() ?: return false
    val height = stream.getAsNumber(PdfName.Height)?.intValue() ?: return false
    if (width <= 0 || height <= 0) return false

    val original = try {
        stream.getBytes()
    } catch (_: Exception) {
        return false
    }
    if (original.isEmpty()) return false

    val recompressed = try {
        recompressor.recompress(original, settings.jpegQuality, settings.maxLongEdgePx)
    } catch (_: Exception) {
        null
    } ?: return false
    if (recompressed.bytes.isEmpty() || recompressed.width <= 0 || recompressed.height <= 0) return false
    // Never make an image larger than it already is.
    if (recompressed.bytes.size >= original.size) return false

    stream.setData(recompressed.bytes)
    stream.put(PdfName.Filter, PdfName.DCTDecode)
    stream.remove(PdfName.DecodeParms)
    stream.put(PdfName.Width, PdfNumber(recompressed.width))
    stream.put(PdfName.Height, PdfNumber(recompressed.height))
    stream.put(PdfName.ColorSpace, PdfName.DeviceRGB)
    stream.put(PdfName.BitsPerComponent, PdfNumber(8))
    stream.setModified()
    return true
}
