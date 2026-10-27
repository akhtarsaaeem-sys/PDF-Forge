package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.pdforge.app.ui.components.parseSplitRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression for the Split PDF bug where the user selected pages but received a
 * single PDF.
 *
 * Root cause: [splitPdfResult] used to set its `outputPath` to the FIRST split
 * file, so the shared finalizer took the single-file branch and published only
 * that one file. The result must point at the output DIRECTORY so every part is
 * finalized, named uniquely, and shown.
 */
class SplitResultRoutingTest {

    private fun createLabeledPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { i ->
            document.add(Paragraph("PAGE_${i + 1}"))
            if (i < pages - 1) document.add(AreaBreak())
        }
        document.close()
        return File(path)
    }

    private fun pageTexts(path: String): List<String> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).map { i ->
                val strategy = SimpleTextExtractionStrategy()
                PdfCanvasProcessor(strategy).processPageContent(doc.getPage(i))
                strategy.resultantText.trim()
            }
        } finally {
            doc.close()
        }
    }

    @Test
    fun splitting_by_ranges_returns_every_part_and_points_at_the_directory() {
        val dir = Files.createTempDirectory("split_result_ranges").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 8)
        val outDir = File(dir, "out").apply { mkdirs() }

        val result = splitPdfResult(
            input.absolutePath,
            outDir.absolutePath,
            listOf(1 to 3, 4 to 6, 7 to 8)
        )

        assertTrue(result.success)
        assertEquals("result must reference the output directory", outDir.absolutePath, result.outputPath)
        assertEquals(3, result.fileCount)
        assertEquals(3, result.outputPaths.size)

        // All files exist, are uniquely named, and reopen with the right pages.
        assertEquals(3, result.outputPaths.toSet().size)
        result.outputPaths.forEach { assertTrue(File(it).exists() && File(it).length() > 0) }

        val byIndex = result.outputPaths.sorted()
        assertEquals(listOf("PAGE_1", "PAGE_2", "PAGE_3"), pageTexts(byIndex[0]))
        assertEquals(listOf("PAGE_4", "PAGE_5", "PAGE_6"), pageTexts(byIndex[1]))
        assertEquals(listOf("PAGE_7", "PAGE_8"), pageTexts(byIndex[2]))
    }

    @Test
    fun splitting_every_page_produces_one_file_per_page() {
        val dir = Files.createTempDirectory("split_result_every").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 5)
        val outDir = File(dir, "out").apply { mkdirs() }

        val result = splitPdfResult(
            input.absolutePath,
            outDir.absolutePath,
            (1..5).map { it to it }
        )

        assertTrue(result.success)
        assertEquals(5, result.fileCount)
        assertEquals(5, result.outputPaths.size)
        result.outputPaths.sorted().forEachIndexed { index, path ->
            assertEquals(listOf("PAGE_${index + 1}"), pageTexts(path))
        }
    }

    @Test
    fun an_empty_or_invalid_range_set_fails_clearly() {
        val dir = Files.createTempDirectory("split_result_empty").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 4)
        val outDir = File(dir, "out").apply { mkdirs() }

        val result = splitPdfResult(input.absolutePath, outDir.absolutePath, listOf(3 to 1))

        assertFalse(result.success)
        assertTrue(!result.message.isNullOrBlank())
        assertEquals(0, outDir.listFiles()?.size ?: 0)
    }

    // ── Range parsing used by the Split UI ──────────────────────────────────

    @Test
    fun range_expression_becomes_one_part_per_range() {
        assertEquals(listOf(1..3, 4..6, 7..8), parseSplitRanges("1-3, 4-6, 7-8", 8))
    }

    @Test
    fun single_pages_and_clamping_are_supported() {
        assertEquals(listOf(2..2, 5..8), parseSplitRanges("2, 5-99", 8))
    }

    @Test
    fun malformed_and_reversed_ranges_are_ignored() {
        assertEquals(emptyList<IntRange>(), parseSplitRanges("5-2, abc, 0", 8))
        assertEquals(listOf(1..1), parseSplitRanges("1-1, 5-2", 8))
    }
}
