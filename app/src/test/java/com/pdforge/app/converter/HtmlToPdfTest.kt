package com.pdforge.app.converter

import com.pdforge.app.pdf.TestSupport
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for HTML → PDF.
 *
 * The fixture `sample.html` (copied next to `jpg_square.jpg` so its relative
 * `<img>` resolves) exercises headings, paragraphs, bold/italic text, a list, a
 * table, basic CSS and a local image. The converter is intentionally limited; the
 * tests confirm the content it claims to support really survives.
 */
class HtmlToPdfTest {

    private fun stageFixture(dir: File): File {
        TestSupport.copyResourceToFile("/fixtures/sample.html", File(dir, "sample.html"))
        TestSupport.copyResourceToFile("/fixtures/jpg_square.jpg", File(dir, "jpg_square.jpg"))
        return File(dir, "sample.html")
    }

    @Test
    fun text_and_basic_formatting_survive() {
        val dir = TestSupport.tempDir("html_pdf")
        val input = stageFixture(dir)
        val output = File(dir, "out.pdf")

        convertHtmlToPdf(input.absolutePath, output.absolutePath)

        assertTrue("output must be non-empty", output.exists() && output.length() > 0)
        assertTrue("output must be a valid PDF", OutputValidator.validatePdf(output))
        val text = TestSupport.pdfText(output.absolutePath)
        for (expected in listOf(
            "Quarterly Report",
            "bold",
            "italic",
            "Revenue increased by 12 percent",
            "Customer count reached 4200"
        )) {
            assertTrue("missing '$expected' in the PDF", text.contains(expected))
        }
    }

    @Test
    fun the_table_survives_conversion() {
        val dir = TestSupport.tempDir("html_pdf_table")
        val input = stageFixture(dir)
        val output = File(dir, "out.pdf")
        convertHtmlToPdf(input.absolutePath, output.absolutePath)

        val text = TestSupport.pdfText(output.absolutePath)
        for (expected in listOf("Region", "Revenue", "Units", "North", "120000", "South", "West")) {
            assertTrue("table cell '$expected' was dropped", text.contains(expected))
        }
    }

    @Test
    fun script_content_is_never_rendered() {
        val dir = TestSupport.tempDir("html_pdf_script")
        val input = stageFixture(dir)
        val output = File(dir, "out.pdf")
        convertHtmlToPdf(input.absolutePath, output.absolutePath)

        val text = TestSupport.pdfText(output.absolutePath)
        assertFalse("script content must not be rendered", text.contains("this must not appear"))
    }

    @Test
    fun the_local_image_is_embedded() {
        val dir = TestSupport.tempDir("html_pdf_image")
        val input = stageFixture(dir)
        val output = File(dir, "out.pdf")
        convertHtmlToPdf(input.absolutePath, output.absolutePath)

        val raw = output.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("the local image should be embedded", raw.contains("/Image"))
    }

    @Test
    fun a_minimal_html_document_converts() {
        val dir = TestSupport.tempDir("html_pdf_min")
        val input = File(dir, "min.html")
        input.writeText("<html><body><h1>Title</h1><p>Hello world</p></body></html>")
        val output = File(dir, "out.pdf")

        convertHtmlToPdf(input.absolutePath, output.absolutePath)

        val text = TestSupport.pdfText(output.absolutePath)
        assertTrue(text.contains("Title") && text.contains("Hello world"))
        assertTrue(TestSupport.pdfPageCount(output.absolutePath) >= 1)
    }

    @Test
    fun an_empty_html_file_produces_a_clear_error() {
        val dir = TestSupport.tempDir("html_pdf_empty")
        val input = File(dir, "empty.html")
        input.writeBytes(ByteArray(0))
        val output = File(dir, "out.pdf")

        assertThrows(IllegalArgumentException::class.java) {
            convertHtmlToPdf(input.absolutePath, output.absolutePath)
        }
    }
}
