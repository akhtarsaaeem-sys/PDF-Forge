package com.pdforge.app.pdf

import com.pdforge.app.storage.OutputValidator
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Regression tests for "PDF → Word: Output validation failed".
 *
 * The converter itself was producing a DOCX; the shared validator rejected it
 * (see [OutputValidatorTypeDispatchTest]). These tests run the real conversion
 * [pdfTextToDocx] and verify both that the DOCX is structurally valid AND that the
 * known text and page order survive.
 */
class PdfToDocxTest {

    private fun docxText(file: File): String {
        XWPFDocument(FileInputStream(file)).use { doc ->
            return doc.paragraphs.joinToString("\n") { it.text }
        }
    }

    @Test
    fun known_text_survives_conversion_and_the_docx_is_valid() {
        val dir = TestSupport.tempDir("pdf_docx")
        val input = File(dir, "in.pdf")
        TestSupport.createTextPdf(
            input.absolutePath,
            listOf("UniqueMarkerAlpha 12345", "Second page content here")
        )
        val output = File(dir, "out.docx")

        val pages = pdfTextToDocx(input.absolutePath, output.absolutePath)

        assertEquals(2, pages)
        assertTrue("output must exist and be non-empty", output.exists() && output.length() > 0)
        assertTrue("DOCX must pass real validation", OutputValidator.validateDocx(output))
        val text = docxText(output)
        assertTrue("extracted text missing the first page marker", text.contains("UniqueMarkerAlpha"))
        assertTrue("extracted text missing the second page", text.contains("Second page content"))
    }

    @Test
    fun page_order_is_preserved() {
        val dir = TestSupport.tempDir("pdf_docx_order")
        val input = File(dir, "in.pdf")
        TestSupport.createTextPdf(
            input.absolutePath,
            listOf("AlphaPageMarker", "BetaPageMarker", "GammaPageMarker")
        )
        val output = File(dir, "out.docx")
        pdfTextToDocx(input.absolutePath, output.absolutePath)

        val text = docxText(output)
        val alpha = text.indexOf("AlphaPageMarker")
        val beta = text.indexOf("BetaPageMarker")
        val gamma = text.indexOf("GammaPageMarker")
        assertTrue("all page markers must be present", alpha >= 0 && beta >= 0 && gamma >= 0)
        assertTrue("page order must be preserved", alpha < beta && beta < gamma)
    }

    @Test
    fun the_output_is_a_real_ooxml_package_with_document_xml() {
        val dir = TestSupport.tempDir("pdf_docx_zip")
        val input = File(dir, "in.pdf")
        TestSupport.createTextPdf(input.absolutePath, listOf("hello"))
        val output = File(dir, "out.docx")
        pdfTextToDocx(input.absolutePath, output.absolutePath)

        java.util.zip.ZipFile(output).use { zip ->
            assertTrue(
                "DOCX must contain word/document.xml",
                zip.getEntry("word/document.xml") != null
            )
        }
    }

    @Test
    fun a_page_without_text_does_not_crash_and_is_flagged() {
        val dir = TestSupport.tempDir("pdf_docx_blank")
        val input = File(dir, "blank.pdf")
        // A PDF with pages but no extractable text (like a scan).
        TestSupport.createTextPdf(input.absolutePath, listOf("", ""))
        val output = File(dir, "out.docx")

        val pages = pdfTextToDocx(input.absolutePath, output.absolutePath)
        assertEquals(2, pages)
        assertTrue(OutputValidator.validateDocx(output))
        val text = docxText(output)
        assertTrue("a text-less page must be flagged, not silently dropped", text.contains("no selectable text"))
    }
}
