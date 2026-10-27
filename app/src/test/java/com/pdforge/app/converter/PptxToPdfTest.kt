package com.pdforge.app.converter

import com.pdforge.app.pdf.TestSupport
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for "PowerPoint → PDF: does not work / output is broken".
 *
 * The fixture `sample_3slides.pptx` is a real 3-slide presentation (two text
 * slides, one image slide) generated once with Apache POI and stored under
 * `app/src/test/resources/fixtures`. The tests run the real converter
 * [convertPptxToPdf].
 */
class PptxToPdfTest {

    private fun fixture(name: String, dir: File): File {
        val file = File(dir, name)
        TestSupport.copyResourceToFile("/fixtures/$name", file)
        return file
    }

    @Test
    fun every_slide_becomes_one_valid_pdf_page() {
        val dir = TestSupport.tempDir("pptx_pdf")
        val input = fixture("sample_3slides.pptx", dir)
        val output = File(dir, "out.pdf")

        val slides = convertPptxToPdf(input.absolutePath, output.absolutePath)

        assertEquals("slide count must equal PDF page count", 3, slides)
        assertEquals(3, TestSupport.pdfPageCount(output.absolutePath))
        assertTrue("output must be non-empty", output.length() > 0)
        assertTrue("output must be a valid PDF", OutputValidator.validatePdf(output))
    }

    @Test
    fun visible_text_is_preserved() {
        val dir = TestSupport.tempDir("pptx_pdf_text")
        val input = fixture("sample_3slides.pptx", dir)
        val output = File(dir, "out.pdf")
        convertPptxToPdf(input.absolutePath, output.absolutePath)

        val text = TestSupport.pdfText(output.absolutePath)
        for (expected in listOf(
            "PDF Forge Presentation",
            "First slide body text",
            "Second slide content here"
        )) {
            assertTrue("slide text '$expected' is missing from the PDF", text.contains(expected))
        }
    }

    @Test
    fun slide_order_and_per_page_text_are_preserved() {
        val dir = TestSupport.tempDir("pptx_pdf_order")
        val input = fixture("sample_3slides.pptx", dir)
        val output = File(dir, "out.pdf")
        convertPptxToPdf(input.absolutePath, output.absolutePath)

        val page1 = TestSupport.pdfTextOfPage(output.absolutePath, 1)
        val page2 = TestSupport.pdfTextOfPage(output.absolutePath, 2)
        assertTrue("first slide text on page 1", page1.contains("PDF Forge Presentation"))
        assertTrue("second slide text on page 2", page2.contains("Second slide content here"))
    }

    @Test
    fun the_slide_aspect_ratio_is_reasonable() {
        val dir = TestSupport.tempDir("pptx_pdf_ratio")
        val input = fixture("sample_3slides.pptx", dir)
        val output = File(dir, "out.pdf")
        convertPptxToPdf(input.absolutePath, output.absolutePath)

        val (width, height) = TestSupport.pdfPageSizes(output.absolutePath).first()
        assertTrue("presentations are usually landscape", width > height)
        // The fixture is 960x540 pt (16:9); allow a small tolerance.
        assertEquals(16.0 / 9.0, (width / height).toDouble(), 0.05)
    }

    @Test
    fun a_non_pptx_file_produces_a_clear_error() {
        val dir = TestSupport.tempDir("pptx_pdf_bad")
        val input = File(dir, "not.pptx")
        input.writeText("this is not a pptx package")
        val output = File(dir, "out.pdf")

        assertThrows(Exception::class.java) {
            convertPptxToPdf(input.absolutePath, output.absolutePath)
        }
    }
}
