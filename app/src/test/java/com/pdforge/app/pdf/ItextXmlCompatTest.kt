package com.pdforge.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reproduces the PDF/A root cause and verifies the fix.
 *
 * On Android, `DocumentBuilderFactory.setXIncludeAware` throws
 * `UnsupportedOperationException: This parser does not support specification
 * "Unknown" version "0.0"`. iText calls it from
 * `DefaultSafeXmlParserFactory.configureSafeDocumentBuilderFactory` whenever it
 * parses a PDF's XMP metadata, which is why opening (and converting) such a PDF
 * failed. [AndroidCompatXmlParserFactory] overrides that configuration to skip the
 * unsupported setters while keeping the security hardening.
 */
class ItextXmlCompatTest {

    /**
     * Mimics Android's DocumentBuilderFactory, which accepts `setFeature` but
     * throws from `setXIncludeAware` / `setExpandEntityReferences`.
     */
    private class AndroidLikeDocumentBuilderFactory : DocumentBuilderFactory() {
        override fun newDocumentBuilder(): DocumentBuilder =
            DocumentBuilderFactory.newInstance().newDocumentBuilder()

        override fun setXIncludeAware(state: Boolean) {
            throw UnsupportedOperationException(
                "This parser does not support specification \"Unknown\" version \"0.0\""
            )
        }

        override fun setExpandEntityReferences(enable: Boolean) {
            throw UnsupportedOperationException(
                "This parser does not support specification \"Unknown\" version \"0.0\""
            )
        }

        override fun setAttribute(name: String, value: Any?) {}
        override fun getAttribute(name: String): Any? = null
        override fun setFeature(name: String, value: Boolean) {}
        override fun getFeature(name: String): Boolean = false
    }

    @Test
    fun configuring_an_android_like_factory_does_not_throw() {
        val factory = AndroidCompatXmlParserFactory()
        // Must not propagate the UnsupportedOperationException.
        factory.configureSafely(AndroidLikeDocumentBuilderFactory())
    }

    @Test
    fun configuring_a_normal_factory_still_works() {
        val factory = AndroidCompatXmlParserFactory()
        factory.configureSafely(DocumentBuilderFactory.newInstance())
    }

    @Test
    fun the_compat_factory_still_produces_a_working_document_builder() {
        val builder = AndroidCompatXmlParserFactory().createDocumentBuilderInstance(true, true)
        val doc = builder.parse(
            ByteArrayInputStream("<root><child>ok</child></root>".toByteArray())
        )
        assertEquals("ok", doc.documentElement.textContent)
    }

    @Test
    fun installing_the_compat_factory_allows_converting_a_pdf_with_xmp_metadata() {
        // This is the production setup (installed in Application.onCreate).
        AndroidCompatXmlParserFactory.install()

        val dir = TestSupport.tempDir("xml_compat_pdfa")
        val input = File(dir, "in.pdf")
        // Build a PDF that already carries XMP metadata — the input shape that
        // triggered the failure.
        run {
            val writerProps = com.itextpdf.kernel.pdf.WriterProperties().apply {
                addXmpMetadata()
                setFullCompressionMode(true)
            }
            val doc = com.itextpdf.kernel.pdf.PdfDocument(
                com.itextpdf.kernel.pdf.PdfWriter(input.absolutePath, writerProps)
            )
            doc.addNewPage()
            doc.close()
        }
        assertTrue("input must carry XMP metadata", input.readBytes().toString(Charsets.ISO_8859_1).contains("xmpmeta"))

        val output = File(dir, "out.pdf")
        val pages = convertPdfToPdfA(input.absolutePath, output.absolutePath)
        assertEquals(1, pages)
        assertTrue(output.length() > 0)
    }
}
