package com.pdforge.app.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the output pipeline bug where every tool reported
 * "Output validation failed — the generated file could not be verified."
 *
 * Root cause: [OutputValidator.validatePdf] called `reader.isEncrypted`
 * *before* constructing a PdfDocument. iText 8's PdfReader is lazy and throws
 *
 *     PdfException: The PDF document has not been read yet.
 *     Document reading occurs in PdfDocument class constructor
 *
 * which was swallowed by the surrounding catch and made validation reject every
 * valid generated PDF. These tests exercise the REAL validator (not a copy of
 * its logic, which is why the old tests did not catch the bug).
 */
class OutputPipelineRegressionTest {

    private fun createPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        repeat(pages) { doc.addNewPage() }
        doc.close()
        return File(path)
    }

    /** Exact sequence used by PdfEngine.addTextWatermark. */
    private fun writeWatermarked(input: File, output: File, text: String): File {
        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (i in 1..pdfDoc.numberOfPages) {
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
        val font = PdfFontFactory.createFont()
        val extGState = PdfExtGState().setFillOpacity(0.3f)
        for (i in 1..outputDoc.numberOfPages) {
            val page = outputDoc.getPage(i)
            val ps = page.pageSize
            val (ax, ay) = watermarkAnchor(WatermarkPosition.CENTER, ps.width, ps.height)
            val canvas = PdfCanvas(page)
            canvas.saveState()
            canvas.setExtGState(extGState)
            canvas.setColor(ColorConstants.GRAY, true)
            canvas.setFontAndSize(font, 50f)
            canvas.beginText()
            canvas.moveText(ax.toDouble(), ay.toDouble())
            canvas.showText(text)
            canvas.endText()
            canvas.restoreState()
        }
        // Writer must be fully closed before the file is handed to validation.
        outputDoc.close()
        pdfDoc.close()
        return output
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    // ── Real validator accepts freshly generated output ──────────────────────

    @Test
    fun freshly_generated_pdf_passes_real_output_validation() {
        val dir = tempDir("validate_fresh")
        val file = createPdf(File(dir, "generated.pdf").absolutePath, 3)

        assertTrue("freshly generated PDF must be valid", file.exists())
        assertTrue("generated PDF must be non-empty", file.length() > 0L)
        assertTrue(
            "real OutputValidator rejected a freshly generated PDF",
            OutputValidator.validatePdf(file)
        )
    }

    @Test
    fun watermarked_pdf_passes_real_output_validation() {
        val dir = tempDir("validate_watermark")
        val input = createPdf(File(dir, "input.pdf").absolutePath, 3)
        val output = writeWatermarked(input, File(dir, "watermarked.pdf"), "TEST")

        assertTrue("watermarked output must be non-empty", output.length() > 0L)
        assertTrue(
            "real OutputValidator rejected a valid watermarked PDF",
            OutputValidator.validatePdf(output)
        )
    }

    // ── Generated output is a real, complete PDF ─────────────────────────────

    @Test
    fun generated_pdf_starts_with_pdf_signature() {
        val dir = tempDir("signature")
        val file = createPdf(File(dir, "doc.pdf").absolutePath, 1)

        val header = file.inputStream().use { input ->
            val buffer = ByteArray(5)
            val read = input.read(buffer)
            String(buffer, 0, read, Charsets.ISO_8859_1)
        }
        assertEquals("%PDF-", header)
    }

    @Test
    fun generated_pdf_can_be_reopened_with_its_page_count() {
        val dir = tempDir("reopen")
        val file = createPdf(File(dir, "doc.pdf").absolutePath, 4)

        val doc = PdfDocument(PdfReader(file.absolutePath))
        val pages = doc.numberOfPages
        doc.close()

        assertEquals(4, pages)
    }

    @Test
    fun validation_runs_on_the_fully_closed_output() {
        val dir = tempDir("closed")
        val input = createPdf(File(dir, "input.pdf").absolutePath, 2)
        val output = writeWatermarked(input, File(dir, "closed_output.pdf"), "AFTER-CLOSE")

        // Everything is flushed only once the writer has been closed; validation
        // must see the finished file, not a partially written one.
        val header = output.inputStream().use { inputStream ->
            val buffer = ByteArray(5)
            inputStream.read(buffer)
            String(buffer, Charsets.ISO_8859_1)
        }
        assertEquals("%PDF-", header)
        assertTrue(OutputValidator.validatePdf(output))
    }

    // ── Validation still rejects genuinely bad output ────────────────────────

    @Test
    fun truncated_or_non_pdf_output_fails_real_validation() {
        val dir = tempDir("junk")
        val junk = File(dir, "junk.pdf")
        junk.writeText("this is not a PDF at all")

        assertFalse(OutputValidator.validatePdf(junk))
    }

    @Test
    fun missing_output_file_fails_real_validation() {
        val dir = tempDir("missing")
        assertFalse(OutputValidator.validatePdf(File(dir, "does-not-exist.pdf")))
    }

    @Test
    fun empty_output_file_fails_real_validation() {
        val dir = tempDir("empty")
        val empty = File(dir, "empty.pdf")
        empty.writeBytes(ByteArray(0))

        assertFalse(OutputValidator.validatePdf(empty))
    }
}
