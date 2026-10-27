package com.pdforge.app.pdf

import com.pdforge.app.data.models.ProcessingResult
import java.io.File

/** One rasterized PDF page ready to become a PowerPoint slide. */
internal data class RenderedPage(
    val imageBytes: ByteArray,
    val widthPt: Float,
    val heightPt: Float
)

/**
 * Assembles the rendered PDF pages into a PPTX using [writeSlideImagesToPptx].
 * Slide dimensions follow the first page, so each slide keeps the page's aspect
 * ratio.
 */
internal fun buildPptxFromRenderedPages(pages: List<RenderedPage>, outputPath: String): Int {
    require(pages.isNotEmpty()) { "The PDF has no pages to convert." }
    val first = pages.first()
    val widthPt = if (first.widthPt > 0f) first.widthPt else 720f
    val heightPt = if (first.heightPt > 0f) first.heightPt else 540f
    return writeSlideImagesToPptx(
        slideImages = pages.map { it.imageBytes },
        slideWidthPt = widthPt,
        slideHeightPt = heightPt,
        imageExtension = "jpg",
        outputPath = outputPath
    )
}

/**
 * The crash boundary for PDF → PowerPoint.
 *
 * Any failure — including an `Error` such as an `OutOfMemoryError` while
 * rasterizing a huge PDF — is turned into a controlled failure [ProcessingResult].
 * No partially written file is left behind. This is extracted so the behaviour can
 * be tested: a raw `catch (Exception)` would not catch an `Error` and the app
 * would crash.
 */
internal inline fun pptxConversionResult(outputPath: String, action: () -> Int): ProcessingResult {
    return try {
        val count = action()
        val file = File(outputPath)
        if (count <= 0 || !file.exists() || file.length() <= 0L) {
            try { file.delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to convert PDF to PowerPoint: no slides were produced.")
        } else {
            // Declare the real output type so the shared validator dispatches on
            // the presentation MIME type rather than inferring it from the name.
            ProcessingResult(
                success = true,
                outputPath = outputPath,
                outputSize = file.length(),
                pageCount = count,
                mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
        }
    } catch (e: Throwable) {
        try { File(outputPath).delete() } catch (_: Exception) {}
        ProcessingResult(
            success = false,
            message = "Failed to convert PDF to PowerPoint: ${e.message ?: e.javaClass.simpleName}"
        )
    }
}
