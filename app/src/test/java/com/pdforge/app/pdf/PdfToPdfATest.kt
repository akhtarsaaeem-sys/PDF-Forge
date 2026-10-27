package com.pdforge.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for
 * "Failed to convert to PDF/A: This parser does not support specification
 *  \"Unknown\" version \"0.0\"".
 *
 * Root cause: iText parses a PDF's XMP metadata with an XML parser built by
 * `DefaultSafeXmlParserFactory`, which calls
 * `DocumentBuilderFactory.setXIncludeAware(false)` — unsupported by Android's
 * default factory, so it threw. [AndroidCompatXmlParserFactory] fixes the whole
 * iText pipeline (see [ItextXmlCompatTest] for the failure-mode test); these
 * tests verify the conversion itself.
 */
class PdfToPdfATest {

    @Test
    fun a_simple_pdf_is_converted_and_page_count_is_preserved() {
        val dir = TestSupport.tempDir("pdfa_simple")
        val input = File(dir, "in.pdf")
        TestSupport.createTextPdf(input.absolutePath, listOf("Archival content"))
        val output = File(dir, "out.pdf")

        val pages = convertPdfToPdfA(input.absolutePath, output.absolutePath)

        assertEquals(1, pages)
        assertTrue(output.exists() && output.length() > 0)
        assertEquals(1, TestSupport.pdfPageCount(output.absolutePath))
        assertTrue("output must be a readable PDF", com.pdforge.app.storage.OutputValidator.validatePdf(output))
    }

    @Test
    fun a_multi_page_pdf_keeps_every_page() {
        val dir = TestSupport.tempDir("pdfa_multi")
        val input = File(dir, "in.pdf")
        TestSupport.createTextPdf(input.absolutePath, listOf("Page one", "Page two", "Page three", "Page four"))
        val output = File(dir, "out.pdf")

        val pages = convertPdfToPdfA(input.absolutePath, output.absolutePath)

        assertEquals(4, pages)
        assertEquals(4, TestSupport.pdfPageCount(output.absolutePath))
    }

    @Test
    fun an_already_generated_pdf_forge_pdf_can_be_converted_again() {
        val dir = TestSupport.tempDir("pdfa_regen")
        val source = File(dir, "source.pdf")
        TestSupport.createTextPdf(source.absolutePath, listOf("Original"))
        val first = File(dir, "first.pdf")
        convertPdfToPdfA(source.absolutePath, first.absolutePath)

        // The first output carries XMP metadata, which is exactly the input shape
        // that used to break the XML parser.
        val second = File(dir, "second.pdf")
        val pages = convertPdfToPdfA(first.absolutePath, second.absolutePath)

        assertEquals(1, pages)
        assertTrue(com.pdforge.app.storage.OutputValidator.validatePdf(second))
    }

    @Test
    fun the_output_is_distinguishable_by_its_xmp_metadata() {
        val dir = TestSupport.tempDir("pdfa_meta")
        val input = File(dir, "in.pdf")
        TestSupport.createTextPdf(input.absolutePath, listOf("Hello"))
        val output = File(dir, "out.pdf")
        convertPdfToPdfA(input.absolutePath, output.absolutePath)

        val raw = output.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("output should carry an XMP metadata stream", raw.contains("/Metadata"))
        assertTrue("output should contain an XMP packet", raw.contains("xmpmeta") || raw.contains("rdf:RDF"))
    }

    @Test
    fun a_non_pdf_input_produces_a_clear_error() {
        val dir = TestSupport.tempDir("pdfa_bad")
        val input = File(dir, "not.pdf")
        input.writeText("this is not a PDF")
        val output = File(dir, "out.pdf")

        val error = assertThrows(IllegalArgumentException::class.java) {
            convertPdfToPdfA(input.absolutePath, output.absolutePath)
        }
        assertTrue(error.message!!.contains("not a valid PDF", ignoreCase = true))
    }

    @Test
    fun an_empty_input_produces_a_clear_error() {
        val dir = TestSupport.tempDir("pdfa_empty")
        val input = File(dir, "empty.pdf")
        input.writeBytes(ByteArray(0))
        val output = File(dir, "out.pdf")

        val error = assertThrows(IllegalArgumentException::class.java) {
            convertPdfToPdfA(input.absolutePath, output.absolutePath)
        }
        assertTrue(error.message!!.isNotBlank())
    }
}
