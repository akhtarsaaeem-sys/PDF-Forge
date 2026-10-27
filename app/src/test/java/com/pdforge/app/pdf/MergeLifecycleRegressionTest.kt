package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression coverage for the Merge PDF lifecycle bug.
 *
 * On the device every merge failed with "The document was closed". The cause was
 * NOT the source documents: `PdfMerger.close()` also closes the destination
 * PdfDocument, and the old implementation then read `outputDoc.numberOfPages`
 * (and called `outputDoc.close()`) on that already-closed document, which throws
 * `PdfException: "Document was closed. It is impossible to execute action."`.
 *
 * These tests run the real production function [mergePdfFiles] so the lifecycle
 * contract is guarded by code, not by a copy of it.
 */
class MergeLifecycleRegressionTest {

    /** Creates a PDF whose pages carry identifiable text: "DOC_A_PAGE_1" … */
    private fun createLabeledPdf(path: String, label: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { index ->
            document.add(Paragraph("${label}_PAGE_${index + 1}"))
            // Only BETWEEN pages — a trailing AreaBreak would add an extra blank page.
            if (index < pages - 1) document.add(com.itextpdf.layout.element.AreaBreak())
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

    @Test
    fun merge_two_pdfs_keeps_every_page_in_order() {
        val dir = Files.createTempDirectory("merge_lifecycle_2").toFile()
        val a = createLabeledPdf(File(dir, "a.pdf").absolutePath, "DOC_A", 3)
        val b = createLabeledPdf(File(dir, "b.pdf").absolutePath, "DOC_B", 2)
        val out = File(dir, "merged.pdf")

        val count = mergePdfFiles(listOf(a.absolutePath, b.absolutePath), out.absolutePath)

        assertEquals(5, count)
        assertTrue(out.length() > 0)
        // Output opens, page count matches, and each source contributed all pages in order.
        val texts = pageTexts(out.absolutePath)
        assertEquals(
            listOf("DOC_A_PAGE_1", "DOC_A_PAGE_2", "DOC_A_PAGE_3", "DOC_B_PAGE_1", "DOC_B_PAGE_2"),
            texts
        )
    }

    @Test
    fun merge_three_pdfs_keeps_every_page_in_order() {
        val dir = Files.createTempDirectory("merge_lifecycle_3").toFile()
        val paths = listOf(
            createLabeledPdf(File(dir, "a.pdf").absolutePath, "A", 1),
            createLabeledPdf(File(dir, "b.pdf").absolutePath, "B", 2),
            createLabeledPdf(File(dir, "c.pdf").absolutePath, "C", 3)
        ).map { it.absolutePath }
        val out = File(dir, "merged.pdf")

        val count = mergePdfFiles(paths, out.absolutePath)

        assertEquals(6, count)
        val texts = pageTexts(out.absolutePath)
        assertEquals(
            listOf("A_PAGE_1", "B_PAGE_1", "B_PAGE_2", "C_PAGE_1", "C_PAGE_2", "C_PAGE_3"),
            texts
        )
    }

    @Test
    fun merge_many_pdfs_contributes_every_page() {
        val dir = Files.createTempDirectory("merge_lifecycle_many").toFile()
        val paths = (1..6).map { i ->
            createLabeledPdf(File(dir, "src_$i.pdf").absolutePath, "SRC$i", i).absolutePath
        }
        val out = File(dir, "merged.pdf")

        val count = mergePdfFiles(paths, out.absolutePath)

        // 1+2+3+4+5+6 pages, each source present exactly once.
        assertEquals(21, count)
        val texts = pageTexts(out.absolutePath)
        assertEquals(21, texts.size)
        (1..6).forEach { i ->
            assertEquals(
                "source $i must contribute all of its pages",
                i.toLong(),
                texts.count { it.startsWith("SRC${i}_PAGE_") }.toLong()
            )
        }
    }

    @Test
    fun merge_of_a_single_page_source_keeps_that_page() {
        val dir = Files.createTempDirectory("merge_lifecycle_one").toFile()
        val a = createLabeledPdf(File(dir, "a.pdf").absolutePath, "A", 1)
        val out = File(dir, "merged.pdf")

        val count = mergePdfFiles(listOf(a.absolutePath), out.absolutePath)

        assertEquals(1, count)
        assertEquals(listOf("A_PAGE_1"), pageTexts(out.absolutePath))
    }

    @Test
    fun merge_with_no_inputs_reports_zero_pages() {
        val dir = Files.createTempDirectory("merge_no_inputs").toFile()
        val out = File(dir, "merged.pdf")

        assertEquals(0, mergePdfFiles(emptyList(), out.absolutePath))
    }

    /**
     * The original defect, pinned: touching the destination document after
     * `PdfMerger.close()` throws. If a future change reintroduces a post-close read
     * or close, this test documents exactly why it must not.
     */
    @Test
    fun reading_the_destination_after_merger_close_throws_the_original_error() {
        val dir = Files.createTempDirectory("merge_lifecycle_bug").toFile()
        val a = createLabeledPdf(File(dir, "a.pdf").absolutePath, "A", 1)
        val b = createLabeledPdf(File(dir, "b.pdf").absolutePath, "B", 1)
        val out = File(dir, "merged.pdf")

        val outputDoc = PdfDocument(PdfWriter(out.absolutePath))
        val merger = PdfMerger(outputDoc)
        for (path in listOf(a.absolutePath, b.absolutePath)) {
            val inputDoc = PdfDocument(PdfReader(path))
            merger.merge(inputDoc, 1, inputDoc.numberOfPages)
            inputDoc.close()
        }
        // PdfMerger.close() closes the destination document as well.
        merger.close()

        val thrown = try {
            outputDoc.numberOfPages
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("reading the destination after merger.close() must throw", thrown)
        assertTrue(
            "expected the 'document was closed' failure, got: ${thrown!!.javaClass.name}: ${thrown.message}",
            (thrown.message ?: "").contains("closed", ignoreCase = true)
        )
    }

    @Test
    fun merge_produces_a_file_that_a_fresh_parser_can_open() {
        val dir = Files.createTempDirectory("merge_lifecycle_reopen").toFile()
        val a = createLabeledPdf(File(dir, "a.pdf").absolutePath, "A", 2)
        val b = createLabeledPdf(File(dir, "b.pdf").absolutePath, "B", 2)
        val out = File(dir, "merged.pdf")

        mergePdfFiles(listOf(a.absolutePath, b.absolutePath), out.absolutePath)

        try {
            val reopened = PdfDocument(PdfReader(out.absolutePath))
            try {
                assertEquals(4, reopened.numberOfPages)
            } finally {
                reopened.close()
            }
        } catch (e: Exception) {
            fail("merged output must open: ${e.message}")
        }
    }
}
