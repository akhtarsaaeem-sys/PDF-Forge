package com.pdforge.app.security

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.Canvas
import android.graphics.Paint
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.pdf.displayToRaw
import com.pdforge.app.pdf.normalizeRotation
import com.pdforge.app.pdf.rotationMatrixAbout
import com.pdforge.app.pdf.unlockPdfFile
import java.io.File
import java.io.FileOutputStream

class PdfSecurity(private val context: Context? = null) {

    companion object {
        private const val TAG = "PdfSecurity"
    }

    private fun copyAllPages(pdfDoc: ITextPdfDocument, outputDoc: ITextPdfDocument) {
        for (i in 1..pdfDoc.numberOfPages) {
            // copyTo returns the copied page — it must be explicitly added to the
            // destination's page tree, otherwise the output document has zero pages.
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
    }

    // ── 12.26 SIGN PDF ──────────────────────────────────────────

    /**
     * Adds [signatureText] as a visual overlay.
     *
     * [pages] is a list of 1-based page numbers; an empty list means every page.
     * The old behaviour - always signing the LAST page - is gone: callers must
     * say where the signature goes (see [signatureTargetPages] for the UI choice).
     */
    fun addVisualSignature(
        inputPath: String, outputPath: String, signatureText: String,
        pages: List<Int> = emptyList()
    ): ProcessingResult = addVisualSignatureToPages(inputPath, outputPath, signatureText, pages)

    // ── 12.28 PROTECT PDF ───────────────────────────────────────

    fun protectWithPassword(
        inputPath: String, outputPath: String, userPassword: String, ownerPassword: String,
        allowPrinting: Boolean = true, allowCopying: Boolean = false, allowEditing: Boolean = false
    ): ProcessingResult {
        return try {
            var permissions = EncryptionConstants.ALLOW_PRINTING
            if (allowCopying) permissions = permissions or 16
            if (allowEditing) permissions = permissions or 8

            val writerProps = WriterProperties().setStandardEncryption(
                userPassword.toByteArray(), ownerPassword.toByteArray(),
                permissions, EncryptionConstants.STANDARD_ENCRYPTION_128
            )
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath), PdfWriter(outputPath, writerProps))
            val count = pdfDoc.numberOfPages
            pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            ProcessingResult(false, message = "Failed to protect PDF: ${e.message}")
        }
    }

    // ── 12.27 UNLOCK PDF ────────────────────────────────────────

    /**
     * Removes password protection from [inputPath].
     *
     * Delegates to the shared [unlockPdfFile] so the JVM regression tests run the
     * exact production implementation (password detection, real decryption,
     * distinct error messages, cleanup of partial output).
     */
    fun unlockDocument(inputPath: String, outputPath: String, password: String): ProcessingResult =
        unlockPdfFile(inputPath, outputPath, password)

    // ── 12.29 REDACT PDF ────────────────────────────────────────

    fun redactContent(
        inputPath: String, outputPath: String,
        redactionAreas: List<Rectangle> = emptyList(),
        redactionLabels: List<String> = emptyList()
    ): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)

            if (redactionAreas.isNotEmpty()) {
                val page = outputDoc.getPage(1)
                val canvas = com.itextpdf.kernel.pdf.canvas.PdfCanvas(page)
                for (area in redactionAreas) {
                    canvas.saveState()
                    canvas.setColor(ColorConstants.BLACK, true)
                    canvas.rectangle(area)
                    canvas.fill()
                    canvas.restoreState()
                }
            }

            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            val outputSize = File(outputPath).length()
            ProcessingResult(true, outputPath, 0, outputSize, null, count)
        } catch (e: Exception) {
            ProcessingResult(false, message = "Failed to redact PDF: ${e.message}")
        }
    }

    // ── 12.30 COMPARE PDF ───────────────────────────────────────

    fun comparePdfs(path1: String, path2: String): ComparisonResult {
        return try {
            val doc1 = ITextPdfDocument(PdfReader(path1))
            val doc2 = ITextPdfDocument(PdfReader(path2))
            val pages1 = doc1.numberOfPages
            val pages2 = doc2.numberOfPages
            val differences = mutableListOf<String>()
            var totalWordsA = 0
            var totalWordsB = 0
            var totalAdded = 0
            var totalRemoved = 0
            var changedPages = 0

            if (pages1 != pages2) {
                differences.add("Document 1 has $pages1 pages, Document 2 has $pages2 pages")
            }

            val minPages = minOf(pages1, pages2)

            // Page dimensions (compare numerically — PageSize has no value equality)
            for (i in 1..minPages) {
                val ps1 = doc1.getPage(i).pageSize
                val ps2 = doc2.getPage(i).pageSize
                if (ps1.width != ps2.width || ps1.height != ps2.height) {
                    differences.add("Page $i: different dimensions (${ps1.width}x${ps1.height} vs ${ps2.width}x${ps2.height})")
                }
            }

            // Text content comparison
            for (i in 1..minPages) {
                try {
                    val strategy1 = SimpleTextExtractionStrategy()
                    val processor1 = PdfCanvasProcessor(strategy1)
                    processor1.processPageContent(doc1.getPage(i))
                    val text1 = strategy1.resultantText

                    val strategy2 = SimpleTextExtractionStrategy()
                    val processor2 = PdfCanvasProcessor(strategy2)
                    processor2.processPageContent(doc2.getPage(i))
                    val text2 = strategy2.resultantText

                    val words1 = text1.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    val words2 = text2.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    totalWordsA += words1.size
                    totalWordsB += words2.size

                    if (text1.trim() != text2.trim()) {
                        changedPages++
                        val pageDiffs = mutableListOf<String>()
                        if (words1.size != words2.size) {
                            pageDiffs.add("Word count changed (${words1.size} → ${words2.size})")
                        }
                        val lines1 = text1.trim().split("\n")
                        val lines2 = text2.trim().split("\n")
                        val removed = lines1.filter { it.trim().isNotBlank() } - lines2.filter { it.trim().isNotBlank() }
                        val added = lines2.filter { it.trim().isNotBlank() } - lines1.filter { it.trim().isNotBlank() }
                        totalRemoved += removed.size
                        totalAdded += added.size
                        if (removed.isNotEmpty()) pageDiffs.add("${removed.size} line(s) removed")
                        if (added.isNotEmpty()) pageDiffs.add("${added.size} line(s) added")
                        if (pageDiffs.isEmpty()) pageDiffs.add("Text content differs")

                        differences.add("Page $i: ${pageDiffs.joinToString("; ")}")
                    }
                } catch (_: Exception) {}
            }

            doc1.close(); doc2.close()

            ComparisonResult(
                pageCountDiff = pages2 - pages1,
                differences = differences,
                areIdentical = differences.isEmpty(),
                pagesA = pages1,
                pagesB = pages2,
                totalWordsA = totalWordsA,
                totalWordsB = totalWordsB,
                addedLines = totalAdded,
                removedLines = totalRemoved,
                changedPages = changedPages
            )
        } catch (e: Exception) {
            ComparisonResult(0, listOf("Error comparing files: ${e.message}"), false)
        }
    }
}

/** Which pages a visual signature is applied to (the choice offered by the UI). */
enum class SignaturePageMode { THIS_PAGE, ALL_PAGES }

/** Font size (pt) of a typed visual signature overlay. */
internal const val SIGNATURE_FONT_SIZE = 14f

/** Gap (pt) a visual signature keeps from the page's displayed edges. */
internal const val SIGNATURE_MARGIN = 36f

/**
 * The 0-based page indices a signature is drawn on.
 *
 * THIS_PAGE signs exactly the page the user is looking at (clamped into range),
 * ALL_PAGES signs every page. Both are expressed in 0-based indices because that
 * is what the Android renderer used by the Sign screen works with.
 *
 * Previously the screen always passed its current page and defaulted that page to
 * the LAST page, so a signature silently landed on the last page only and there
 * was no way to choose — this function is the single place that decision is made.
 */
internal fun signatureTargetPages(
    mode: SignaturePageMode,
    currentPageIndex: Int,
    totalPages: Int
): List<Int> {
    if (totalPages <= 0) return emptyList()
    return when (mode) {
        SignaturePageMode.ALL_PAGES -> (0 until totalPages).toList()
        SignaturePageMode.THIS_PAGE -> listOf(currentPageIndex.coerceIn(0, totalPages - 1))
    }
}

/** Converts 0-based target indices into the 1-based page numbers the engine takes. */
internal fun signaturePagesOneBased(zeroBased: List<Int>, totalPages: Int): List<Int> =
    zeroBased.map { it + 1 }.filter { it in 1..totalPages }.distinct().sorted()

/** The 1-based pages to sign: empty input means every page. */
internal fun resolveSignaturePages(pages: List<Int>, totalPages: Int): List<Int> {
    if (totalPages <= 0) return emptyList()
    if (pages.isEmpty()) return (1..totalPages).toList()
    return pages.filter { it in 1..totalPages }.distinct().sorted()
}

/**
 * Draws [signatureText] as a visual overlay on [pages] (1-based; empty = every
 * page) with iText, using the same shared display-space placement for each page
 * so "all pages" produces one consistent position throughout the document.
 *
 * This is a VISUAL overlay, not a cryptographic signature.
 */
internal fun addVisualSignatureToPages(
    inputPath: String,
    outputPath: String,
    signatureText: String,
    pages: List<Int> = emptyList()
): ProcessingResult {
    if (signatureText.isBlank()) {
        return ProcessingResult(false, message = "Enter the signature text first.")
    }
    return try {
        val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
        val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
        val font = com.itextpdf.kernel.font.PdfFontFactory.createFont()
        for (i in 1..pdfDoc.numberOfPages) {
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
        val totalPages = outputDoc.numberOfPages
        val targets = resolveSignaturePages(pages, totalPages)
        if (targets.isEmpty()) {
            outputDoc.close(); pdfDoc.close()
            try { File(outputPath).delete() } catch (_: Exception) {}
            return ProcessingResult(false, message = "The PDF has no pages to sign.")
        }

        for (p in targets) {
            val page = outputDoc.getPage(p)
            val ps = page.pageSize
            val rotation = normalizeRotation(page.rotation)
            // Same corner on every page, measured on the page as it is DISPLAYED,
            // so a rotated page gets the signature where the user sees the corner.
            val (localX, localY) = displayToRaw(
                SIGNATURE_MARGIN, SIGNATURE_MARGIN, ps.width, ps.height, rotation
            )
            val x = ps.left + localX
            val y = ps.bottom + localY
            val canvas = com.itextpdf.kernel.pdf.canvas.PdfCanvas(page)
            canvas.saveState()
            canvas.setFontAndSize(font, SIGNATURE_FONT_SIZE)
            if (rotation != 0) {
                val m = rotationMatrixAbout(x, y, rotation.toFloat())
                canvas.concatMatrix(m.a, m.b, m.c, m.d, m.e, m.f)
            }
            canvas.beginText()
            canvas.moveText(x.toDouble(), y.toDouble())
            canvas.showText(signatureText)
            canvas.endText()
            canvas.restoreState()
        }

        outputDoc.close(); pdfDoc.close()
        val size = File(outputPath).length()
        val where = if (targets.size == totalPages) {
            "every page ($totalPages)"
        } else {
            "page${if (targets.size == 1) "" else "s"} ${targets.joinToString(", ")}"
        }
        ProcessingResult(
            success = true,
            outputPath = outputPath,
            outputSize = size,
            pageCount = totalPages,
            message = "Visual signature applied to $where. " +
                "Note: this is a visual overlay, not a cryptographic digital signature."
        )
    } catch (e: Exception) {
        try { File(outputPath).delete() } catch (_: Exception) {}
        ProcessingResult(false, message = "Failed to sign PDF: ${e.message}")
    }
}

data class ComparisonResult(
    val pageCountDiff: Int,
    val differences: List<String>,
    val areIdentical: Boolean,
    val pagesA: Int = 0,
    val pagesB: Int = 0,
    val totalWordsA: Int = 0,
    val totalWordsB: Int = 0,
    val addedLines: Int = 0,
    val removedLines: Int = 0,
    val changedPages: Int = 0
)
