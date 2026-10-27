package com.pdforge.app.ocr

import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfPage
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.PdfCanvasConstants
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy

/**
 * Builds a searchable PDF by adding an INVISIBLE text layer to the ORIGINAL pages.
 *
 * WHY this exists: the previous OCR writer rendered every page to a full-resolution
 * bitmap and embedded that bitmap as the page background, which is why a 1 MB PDF
 * became ~14 MB. It also drew the OCR text with a fully transparent paint, which
 * made it unreliable to select/search.
 *
 * This builder instead:
 *  - copies each source page unchanged (vector/text/images preserved),
 *  - receives OCR word boxes for that page (in the pixel space of a rendered
 *    bitmap of size [OcrPageWords.renderWidth] x [OcrPageWords.renderHeight]),
 *  - draws those words with text rendering mode INVISIBLE (mode 3), so they are
 *    selectable/searchable but change nothing visually.
 *
 * No page bitmap is ever embedded, so the output stays close to the input size.
 * The OCR itself is injected via [ocrPage] so it can come from Tesseract on a
 * device or from a deterministic double in tests.
 */

/** One recognized word with its bounding box in rendered-bitmap pixels (top-left origin). */
internal data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** OCR result for one page plus the dimensions of the bitmap it was measured on. */
internal data class OcrPageWords(
    val words: List<OcrWord>,
    val renderWidth: Int,
    val renderHeight: Int
)

internal data class SearchablePdfOutcome(
    val pageCount: Int,
    /** Pages that received a new invisible text layer from OCR. */
    val pagesWithText: Int,
    /** Pages skipped because they already contained real (searchable) text. */
    val pagesAlreadyTextual: Int,
    val wordsAdded: Int
)

/**
 * Copies [inputPath] to [outputPath] and adds an invisible text layer per page.
 *
 * @param ocrPage 1-based page number -> OCR words, or null to leave the page as-is.
 * @param skipTextPagesWhen true, pages that already contain real text are copied
 *        untouched and [ocrPage] is NOT called for them (avoids needless rasterization).
 */
internal fun buildSearchablePdf(
    inputPath: String,
    outputPath: String,
    ocrPage: (pageNumber: Int) -> OcrPageWords?,
    skipTextPages: Boolean = true,
    minExistingTextChars: Int = 20,
    onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
): SearchablePdfOutcome {
    val reader = PdfReader(inputPath)
    val src = ITextPdfDocument(reader)
    val dst = ITextPdfDocument(PdfWriter(outputPath))
    val font = PdfFontFactory.createFont()
    var pagesWithText = 0
    var pagesAlreadyTextual = 0
    var wordsAdded = 0
    val total = src.numberOfPages
    try {
        for (i in 1..total) {
            onProgress(i, total)
            val srcPage = src.getPage(i)
            val copied = srcPage.copyTo(dst)
            dst.addPage(copied)

            if (skipTextPages && extractPageText(srcPage).trim().length >= minExistingTextChars) {
                pagesAlreadyTextual++
                continue
            }
            val ocr = try {
                ocrPage(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val added = addInvisibleTextLayer(copied, ocr.words, ocr.renderWidth, ocr.renderHeight, font)
            if (added > 0) {
                pagesWithText++
                wordsAdded += added
            }
        }
    } finally {
        dst.close()
        src.close()
    }
    return SearchablePdfOutcome(
        pageCount = total,
        pagesWithText = pagesWithText,
        pagesAlreadyTextual = pagesAlreadyTextual,
        wordsAdded = wordsAdded
    )
}

/**
 * Adds [words] to [page] as invisible text. Coordinates are mapped from the
 * rendered bitmap space (top-left origin) to PDF points (bottom-left origin).
 * Returns the number of words written.
 */
internal fun addInvisibleTextLayer(
    page: PdfPage,
    words: List<OcrWord>,
    renderWidth: Int,
    renderHeight: Int,
    font: PdfFont
): Int {
    if (words.isEmpty() || renderWidth <= 0 || renderHeight <= 0) return 0
    val box = page.pageSize
    val canvas = PdfCanvas(page)
    canvas.saveState()
    canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.INVISIBLE)
    var added = 0
    for (word in words) {
        val rawText = word.text.trim()
        if (rawText.isEmpty()) continue
        val wPx = word.right - word.left
        val hPx = word.bottom - word.top
        if (wPx <= 0 || hPx <= 0) continue

        // The standard font cannot encode every code point OCR may return; skip
        // characters it cannot render so one odd glyph never fails the whole page.
        val safeText = buildString {
            for (ch in rawText) {
                if (font.containsGlyph(ch.code)) append(ch)
            }
        }
        if (safeText.isBlank()) continue

        val x = box.left + word.left.toFloat() / renderWidth * box.width
        val baselineFromTop = word.bottom.toFloat() / renderHeight * box.height
        val y = box.top - baselineFromTop
        val fontSize = (hPx.toFloat() / renderHeight * box.height).coerceIn(1f, 200f)

        canvas.beginText()
        canvas.setFontAndSize(font, fontSize)
        canvas.moveText(x.toDouble(), y.toDouble())
        canvas.showText(safeText)
        canvas.endText()
        added++
    }
    canvas.restoreState()
    return added
}

/** Extracts the real text of [page], or "" when it has none. */
internal fun extractPageText(page: PdfPage): String {
    return try {
        val strategy = SimpleTextExtractionStrategy()
        PdfCanvasProcessor(strategy).processPageContent(page)
        strategy.resultantText
    } catch (_: Exception) {
        ""
    }
}
