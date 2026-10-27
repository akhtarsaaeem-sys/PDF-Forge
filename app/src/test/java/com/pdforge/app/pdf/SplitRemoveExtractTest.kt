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
import com.pdforge.app.ui.components.contiguousPageGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Split / Remove / Extract behaviour after the switch to visual page selection.
 *
 * Split and remove/extract read their page lists from the shared selection. The
 * grouping ([contiguousPageGroups]), the pages kept ([pagesToKeep]) and the pages
 * extracted ([pagesToExtractInOrder]) are the real production helpers; Split runs
 * the real [splitPdfIntoFiles].
 */
class SplitRemoveExtractTest {

    private fun createLabeledPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { index ->
            document.add(Paragraph("PAGE_${index + 1}"))
            // Only BETWEEN pages — a trailing AreaBreak would add a blank page.
            if (index < pages - 1) document.add(AreaBreak())
        }
        document.close()
        return File(path)
    }

    private fun pageTexts(path: String): List<String> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).map { pageNum ->
                val strategy = SimpleTextExtractionStrategy()
                PdfCanvasProcessor(strategy).processPageContent(doc.getPage(pageNum))
                strategy.resultantText.trim()
            }
        } finally {
            doc.close()
        }
    }

    // ── Split PDF ───────────────────────────────────────────────────────────

    @Test
    fun split_with_a_single_selected_page_creates_a_one_page_pdf() {
        val dir = Files.createTempDirectory("split_single").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 5)
        val outDir = File(dir, "out").apply { mkdirs() }

        val outputs = splitPdfIntoFiles(
            input.absolutePath,
            outDir.absolutePath,
            contiguousPageGroups(setOf(3)).map { it.first to it.last }
        )

        assertEquals(1, outputs.size)
        assertEquals(listOf("PAGE_3"), pageTexts(outputs.first()))
    }

    @Test
    fun split_creates_one_file_per_run_of_consecutive_pages() {
        val dir = Files.createTempDirectory("split_runs").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 8)
        val outDir = File(dir, "out").apply { mkdirs() }

        // Selecting 2, 4, 5, 7 -> two single pages and one two-page run = 3 files.
        val groups = contiguousPageGroups(setOf(2, 4, 5, 7))
        val outputs = splitPdfIntoFiles(input.absolutePath, outDir.absolutePath, groups.map { it.first to it.last })

        assertEquals(3, outputs.size)
        assertEquals(listOf("PAGE_2"), pageTexts(outputs[0]))
        assertEquals(listOf("PAGE_4", "PAGE_5"), pageTexts(outputs[1]))
        assertEquals(listOf("PAGE_7"), pageTexts(outputs[2]))
    }

    @Test
    fun split_skips_reversed_ranges_instead_of_making_empty_files() {
        val dir = Files.createTempDirectory("split_reversed").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 4)
        val outDir = File(dir, "out").apply { mkdirs() }

        val outputs = splitPdfIntoFiles(input.absolutePath, outDir.absolutePath, listOf(3 to 1, 4 to 4))

        assertEquals(1, outputs.size)
        assertEquals(listOf("PAGE_4"), pageTexts(outputs.first()))
    }

    @Test
    fun split_produces_no_files_and_no_zero_page_pdf_when_every_range_is_invalid() {
        val dir = Files.createTempDirectory("split_all_invalid").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 4)
        val outDir = File(dir, "out").apply { mkdirs() }

        val outputs = splitPdfIntoFiles(input.absolutePath, outDir.absolutePath, listOf(3 to 1, 4 to 2))

        // No file at all is correct here — Split must never emit a zero-page PDF,
        // and PdfEngine.splitPdf reports a clean failure for this case.
        assertEquals(emptyList<String>(), outputs)
        assertEquals(0, outDir.listFiles()?.size ?: 0)
    }

    // ── Remove Pages ────────────────────────────────────────────────────────

    @Test
    fun removing_selected_pages_keeps_the_rest_in_order() {
        val dir = Files.createTempDirectory("remove_pages").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 8)
        val output = File(dir, "cleaned.pdf")

        // The screen sends selectedPages.sorted() as pagesToRemove.
        val keep = pagesToKeep(8, listOf(2, 3, 5))
        assertEquals(listOf(1, 4, 6, 7, 8), keep)

        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (p in keep) outputDoc.addPage(pdfDoc.getPage(p).copyTo(outputDoc))
        outputDoc.close(); pdfDoc.close()

        assertEquals(5, pageTexts(output.absolutePath).size)
        assertEquals(
            listOf("PAGE_1", "PAGE_4", "PAGE_6", "PAGE_7", "PAGE_8"),
            pageTexts(output.absolutePath)
        )
    }

    @Test
    fun removing_nothing_is_handled_as_a_no_op_selection() {
        // An empty selection is rejected by the tool before the engine is called;
        // the helper still has to behave sanely.
        assertEquals((1..4).toList(), pagesToKeep(4, emptyList()))
    }

    // ── Extract Pages ───────────────────────────────────────────────────────

    @Test
    fun extracting_selected_pages_outputs_exactly_those_pages() {
        val dir = Files.createTempDirectory("extract_pages").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 8)
        val output = File(dir, "extracted.pdf")

        val selected = pagesToExtractInOrder(listOf(2, 5, 7), 8)
        assertEquals(listOf(2, 5, 7), selected)

        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (p in selected) outputDoc.addPage(pdfDoc.getPage(p).copyTo(outputDoc))
        outputDoc.close(); pdfDoc.close()

        assertEquals(listOf("PAGE_2", "PAGE_5", "PAGE_7"), pageTexts(output.absolutePath))
    }

    @Test
    fun extracting_one_page_outputs_a_single_page_document() {
        val dir = Files.createTempDirectory("extract_one").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 3)
        val output = File(dir, "one.pdf")

        val selected = pagesToExtractInOrder(setOf(2).toList(), 3)
        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (p in selected) outputDoc.addPage(pdfDoc.getPage(p).copyTo(outputDoc))
        outputDoc.close(); pdfDoc.close()

        assertEquals(listOf("PAGE_2"), pageTexts(output.absolutePath))
    }

    @Test
    fun split_output_files_are_all_readable() {
        val dir = Files.createTempDirectory("split_readable").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 6)
        val outDir = File(dir, "out").apply { mkdirs() }

        val outputs = splitPdfIntoFiles(
            input.absolutePath,
            outDir.absolutePath,
            contiguousPageGroups(setOf(1, 2, 5)).map { it.first to it.last }
        )

        assertEquals(2, outputs.size)
        outputs.forEach { path ->
            assertTrue(File(path).length() > 0)
            assertTrue(pageTexts(path).isNotEmpty())
        }
    }
}
