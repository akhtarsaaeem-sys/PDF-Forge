package com.pdforge.app.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.pdforge.app.security.PdfSecurity
import com.pdforge.app.ui.screens.formatComparisonSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for Compare PDF: the engine's page/word/line stats and the
 * structured summary shown to the user (A/B document block, added/removed/
 * changed counts, then the differences list).
 */
class ComparePdfTest {

    private fun textPdf(path: String, pages: List<String>): File {
        val doc = PdfDocument(PdfWriter(path))
        val font = PdfFontFactory.createFont()
        for (text in pages) {
            val page = doc.addNewPage()
            val canvas = PdfCanvas(page)
            canvas.beginText()
                .setFontAndSize(font, 12f)
                .moveText(50.0, 700.0)
                .showText(text)
                .endText()
        }
        doc.close()
        return File(path)
    }

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun identical_documents_compare_as_identical() {
        val d = dir("cmp_same")
        val a = textPdf(File(d, "a.pdf").absolutePath, listOf("Hello world"))
        val b = textPdf(File(d, "b.pdf").absolutePath, listOf("Hello world"))

        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)
        assertTrue(result.areIdentical)
        assertEquals(1, result.pagesA)
        assertEquals(1, result.pagesB)
        assertEquals(0, result.changedPages)
    }

    @Test
    fun differing_text_reports_changed_pages() {
        val d = dir("cmp_diff")
        val a = textPdf(File(d, "a.pdf").absolutePath, listOf("Alpha beta"))
        val b = textPdf(File(d, "b.pdf").absolutePath, listOf("Gamma delta"))

        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)
        assertFalse(result.areIdentical)
        assertTrue(result.changedPages >= 1)
        assertTrue(result.differences.isNotEmpty())
    }

    @Test
    fun page_count_difference_is_reported() {
        val d = dir("cmp_pages")
        val a = textPdf(File(d, "a.pdf").absolutePath, listOf("One", "Two"))
        val b = textPdf(File(d, "b.pdf").absolutePath, listOf("One", "Two", "Three"))

        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)
        assertEquals(2, result.pagesA)
        assertEquals(3, result.pagesB)
        assertEquals(1, result.pageCountDiff)
        assertFalse(result.areIdentical)
    }

    @Test
    fun summary_contains_a_b_blocks_and_counts() {
        val d = dir("cmp_summary")
        val a = textPdf(File(d, "a.pdf").absolutePath, listOf("Alpha"))
        val b = textPdf(File(d, "b.pdf").absolutePath, listOf("Beta"))
        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)

        val summary = formatComparisonSummary(result, "a.pdf", "b.pdf")
        assertTrue(summary.contains("Document A: a.pdf"))
        assertTrue(summary.contains("Document B: b.pdf"))
        assertTrue(summary.contains("Pages changed:"))
        assertTrue(summary.contains("Lines added:"))
        assertTrue(summary.contains("Lines removed:"))
        assertTrue(summary.contains("Differences"))
    }

    @Test
    fun identical_summary_says_so() {
        val d = dir("cmp_summary_same")
        val a = textPdf(File(d, "a.pdf").absolutePath, listOf("Same text"))
        val b = textPdf(File(d, "b.pdf").absolutePath, listOf("Same text"))
        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)

        assertTrue(formatComparisonSummary(result, "a.pdf", "b.pdf").contains("identical"))
    }
}
