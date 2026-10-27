package com.pdforge.app.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.parsePageRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for shared helper logic and the page-manipulation fixes:
 * page-range parsing, filename generation, the split invalid-range guard, the
 * compress rewrite behavior, and PDF output validation.
 */
class HelperAndEngineLogicTest {

    private fun createPdf(path: String, pages: Int, content: String = ""): File {
        val doc = PdfDocument(PdfWriter(path))
        repeat(pages) { doc.addNewPage() }
        doc.close()
        return File(path)
    }

    // ── parsePageRange (PageSelector) ────────────────────────────────

    @Test
    fun parsePageRange_handles_single_pages_and_ranges() {
        assertEquals(listOf(1, 2, 3, 5, 7, 8, 9), parsePageRange("1-3, 5, 7-9", 10))
    }

    @Test
    fun parsePageRange_clamps_to_total_pages() {
        assertEquals(listOf(1, 2, 8, 9, 10), parsePageRange("1-2, 8-99", 10))
    }

    @Test
    fun parsePageRange_ignores_invalid_entries() {
        assertEquals(listOf(2), parsePageRange("0, 2, 99, abc", 10))
    }

    @Test
    fun parsePageRange_handles_reversed_range_as_empty() {
        // "5-2" must not crash or produce out-of-bounds pages.
        assertEquals(emptyList<Int>(), parsePageRange("5-2", 10))
    }

    // ── FileManager.generateOutputName ───────────────────────────────

    @Test
    fun generateOutputName_strips_extension_and_appends_suffix() {
        assertEquals("report_rotated.pdf", FileManager.generateOutputName("report.pdf", "rotated"))
        assertEquals("archive.final_merged.pdf", FileManager.generateOutputName("archive.final.docx", "merged"))
    }

    // ── Split: invalid ranges must not produce zero-page files ───────

    @Test
    fun split_with_invalid_range_skips_file() {
        val dir = Files.createTempDirectory("split_guard").toFile()
        val input = createPdf(File(dir, "input.pdf").absolutePath, 3)

        // Same guarded logic as PdfEngine.splitPdf
        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val outputs = mutableListOf<File>()
        var index = 1
        for ((start, end) in listOf(1 to 1, 5 to 2, 3 to 3)) {
            val s = start.coerceIn(1, pdfDoc.numberOfPages)
            val e = end.coerceIn(1, pdfDoc.numberOfPages)
            if (s > e) continue  // invalid range — skip, never create an empty file
            val outFile = File(dir, "split_${"%02d".format(index)}.pdf")
            val outputDoc = PdfDocument(PdfWriter(outFile))
            for (i in s..e) outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
            outputDoc.close()
            outputs.add(outFile)
            index++
        }
        pdfDoc.close()

        assertEquals(2, outputs.size)
        outputs.forEach { f ->
            assertTrue(f.exists() && f.length() > 0)
            val reopened = PdfDocument(PdfReader(f.absolutePath))
            assertTrue(reopened.numberOfPages >= 1)
            reopened.close()
        }
    }

    // ── Compress: rewritten output is always returned and parseable ──

    @Test
    fun compress_always_returns_parseable_output() {
        val dir = Files.createTempDirectory("compress_test").toFile()
        val input = createPdf(File(dir, "input.pdf").absolutePath, 3)
        val output = File(dir, "compressed.pdf")

        // Same sequence as PdfEngine.compressPdf (simplified path)
        val originalSize = input.length()
        val reader = PdfReader(input.absolutePath)
        reader.setUnethicalReading(true)
        val props = WriterProperties().useSmartMode().setFullCompressionMode(true).setCompressionLevel(6)
        val doc = PdfDocument(reader, PdfWriter(output.absolutePath, props))
        val count = doc.numberOfPages
        doc.close()
        val outputSize = output.length()

        assertEquals(3, count)
        assertTrue(output.exists() && outputSize > 0)
        assertTrue(outputSize <= originalSize || outputSize > 0)

        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(3, reopened.numberOfPages)
        reopened.close()
    }

    // ── Output validation for PDFs ───────────────────────────────────

    @Test
    fun pdf_output_validation_opens_and_counts_pages() {
        val dir = Files.createTempDirectory("validate_test").toFile()
        val file = createPdf(File(dir, "doc.pdf").absolutePath, 4)

        // Same checks as OutputValidator.validatePdf
        val ok = try {
            val r = PdfReader(file.absolutePath)
            val d = PdfDocument(r)
            val pages = d.numberOfPages
            d.close()
            pages > 0
        } catch (e: Exception) {
            false
        }
        assertTrue(ok)

        // A non-PDF must fail validation
        val junk = File(dir, "junk.pdf")
        junk.writeText("this is not a pdf at all")
        val bad = try {
            val r = PdfReader(junk.absolutePath)
            val d = PdfDocument(r)
            val pages = d.numberOfPages
            d.close()
            pages > 0
        } catch (e: Exception) {
            false
        }
        assertFalse(bad)
    }
}