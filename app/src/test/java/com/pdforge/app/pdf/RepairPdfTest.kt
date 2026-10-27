package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for PDF Repair, using SYNTHETIC damaged documents generated
 * in the test environment (no downloading of untrusted files is required).
 *
 * They cover:
 *  - a valid PDF is repaired into a valid PDF with the same pages,
 *  - a truncated PDF either yields a valid partial recovery or a clear error
 *    (never a fake success with an unreadable file),
 *  - a structurally damaged PDF with a broken xref is recovered where iText can
 *    rebuild it,
 *  - an invalid non-PDF file produces a clear error, not a fake repair.
 *
 * These run the real [repairPdfFile] production function.
 */
class RepairPdfTest {

    private fun createLabeledPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { i ->
            document.add(Paragraph("REPAIR_PAGE_${i + 1}"))
            if (i < pages - 1) document.add(AreaBreak())
        }
        document.close()
        return File(path)
    }

    private fun pageCount(path: String): Int {
        val doc = PdfDocument(PdfReader(path))
        return try {
            doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    @Test
    fun a_valid_pdf_is_repaired_into_a_valid_pdf_with_the_same_pages() {
        val dir = Files.createTempDirectory("repair_valid").toFile()
        val input = createLabeledPdf(File(dir, "valid.pdf").absolutePath, 3)
        val output = File(dir, "repaired.pdf")

        val result = repairPdfFile(input.absolutePath, output.absolutePath)

        assertTrue("a valid PDF must be repairable", result.success)
        assertTrue(output.exists() && output.length() > 0)
        assertTrue("repaired output must validate", OutputValidator.validatePdf(output))
        assertEquals(3, pageCount(output.absolutePath))
        assertEquals(3, result.pageCount)
    }

    @Test
    fun a_truncated_pdf_never_reports_a_fake_success() {
        val dir = Files.createTempDirectory("repair_truncated").toFile()
        val input = createLabeledPdf(File(dir, "valid.pdf").absolutePath, 4)
        // Keep only the first 55% of the file: the trailer/xref are gone.
        val bytes = input.readBytes()
        val truncated = File(dir, "truncated.pdf")
        truncated.writeBytes(bytes.copyOfRange(0, (bytes.size * 0.55).toInt()))
        val output = File(dir, "repaired.pdf")

        val result = repairPdfFile(truncated.absolutePath, output.absolutePath)

        if (result.success) {
            // A reported success must be a real, openable PDF with at least one page
            // and no more pages than the original.
            assertTrue("successful recovery must produce a valid file", OutputValidator.validatePdf(output))
            val pages = pageCount(output.absolutePath)
            assertTrue("recovered file must contain pages", pages >= 1)
            assertTrue("recovery cannot invent pages", pages <= 4)
        } else {
            assertFalse("a failed repair must not leave a published file",
                output.exists() && output.length() > 0 && OutputValidator.validatePdf(output))
            assertTrue("a failed repair must explain why", !result.message.isNullOrBlank())
        }
    }

    @Test
    fun a_pdf_with_a_broken_xref_is_recovered_when_possible() {
        val dir = Files.createTempDirectory("repair_xref").toFile()
        val input = createLabeledPdf(File(dir, "valid.pdf").absolutePath, 3)
        // Corrupt the xref keyword so a normal reader cannot use the table, but the
        // page objects are still present for the repair reader to rebuild.
        val damaged = File(dir, "damaged.pdf")
        damaged.writeBytes(String(input.readBytes(), Charsets.ISO_8859_1).replace("xref", "xrEf").toByteArray(Charsets.ISO_8859_1))
        val output = File(dir, "repaired.pdf")

        val result = repairPdfFile(damaged.absolutePath, output.absolutePath)

        if (result.success) {
            assertTrue(OutputValidator.validatePdf(output))
            assertTrue(pageCount(output.absolutePath) >= 1)
        } else {
            assertTrue("failure must carry a clear message", !result.message.isNullOrBlank())
        }
    }

    @Test
    fun an_invalid_non_pdf_file_produces_a_clear_error() {
        val dir = Files.createTempDirectory("repair_invalid").toFile()
        val junk = File(dir, "not-a-pdf.pdf")
        junk.writeText("This is definitely not a PDF document, just plain text.")
        val output = File(dir, "repaired.pdf")

        val result = repairPdfFile(junk.absolutePath, output.absolutePath)

        assertFalse("a non-PDF must never be reported as repaired", result.success)
        assertTrue("the error must be understandable", !result.message.isNullOrBlank())
        assertFalse("no output may be published for an invalid input", output.exists() && output.length() > 0)
    }

    @Test
    fun missing_input_produces_a_clear_error() {
        val dir = Files.createTempDirectory("repair_missing").toFile()
        val output = File(dir, "repaired.pdf")

        val result = repairPdfFile(File(dir, "does-not-exist.pdf").absolutePath, output.absolutePath)

        assertFalse(result.success)
        assertTrue(!result.message.isNullOrBlank())
    }
}
