package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.pdforge.app.security.SignaturePageMode
import com.pdforge.app.security.addVisualSignatureToPages
import com.pdforge.app.security.resolveSignaturePages
import com.pdforge.app.security.signaturePagesOneBased
import com.pdforge.app.security.signatureTargetPages
import com.pdforge.app.storage.OutputValidator
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign PDF — where the signature is applied.
 *
 * The production iText signature writer is tested directly: the output is
 * reopened and each page is checked, so "only the last page" (the reported
 * behaviour) cannot silently come back. The Compose screen uses the same
 * [signatureTargetPages] decision, so its page choice follows these rules too.
 */
class SignPdfPagesTest {

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun blankPdf(dir: File, pages: Int, rotation: Int = 0): File {
        val file = File(dir, "input_${pages}_${rotation}.pdf")
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        repeat(pages) {
            val page = doc.addNewPage(PageSize.A4)
            if (rotation != 0) page.rotation = rotation
        }
        doc.close()
        return file
    }

    /** Page numbers (1-based) whose text contains [needle]. */
    private fun pagesContaining(path: String, needle: String): List<Int> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).filter { page ->
                val strategy = com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy()
                com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor(strategy)
                    .processPageContent(doc.getPage(page))
                strategy.resultantText.contains(needle)
            }
        } finally {
            doc.close()
        }
    }

    // ── Page-target decision (shared by the UI) ──────────────────────────────

    @Test
    fun this_page_signs_exactly_the_page_on_screen() {
        assertEquals(listOf(0), signatureTargetPages(SignaturePageMode.THIS_PAGE, 0, 5))
        assertEquals(listOf(2), signatureTargetPages(SignaturePageMode.THIS_PAGE, 2, 5))
        // A last page is just a page — explicitly choosing it must work.
        assertEquals(listOf(4), signatureTargetPages(SignaturePageMode.THIS_PAGE, 4, 5))
    }

    @Test
    fun all_pages_signs_every_page() {
        assertEquals((0 until 5).toList(), signatureTargetPages(SignaturePageMode.ALL_PAGES, 2, 5))
        assertEquals(listOf(0), signatureTargetPages(SignaturePageMode.ALL_PAGES, 0, 1))
    }

    @Test
    fun an_out_of_range_or_empty_document_never_addresses_a_missing_page() {
        assertEquals(listOf(4), signatureTargetPages(SignaturePageMode.THIS_PAGE, 99, 5))
        assertEquals(listOf(0), signatureTargetPages(SignaturePageMode.THIS_PAGE, -3, 5))
        assertTrue(signatureTargetPages(SignaturePageMode.THIS_PAGE, 0, 0).isEmpty())
        assertTrue(signatureTargetPages(SignaturePageMode.ALL_PAGES, 0, 0).isEmpty())
    }

    @Test
    fun one_based_conversion_and_resolution_are_consistent() {
        assertEquals(listOf(1, 5), signaturePagesOneBased(listOf(0, 4), 5))
        assertEquals(listOf(1), signaturePagesOneBased(listOf(0, 0, 0), 5))
        assertEquals(listOf(1, 2, 3), resolveSignaturePages(emptyList(), 3))
        assertEquals(listOf(2), resolveSignaturePages(listOf(2, 2, 9), 3))
    }

    // ── Production signature output ──────────────────────────────────────────

    @Test
    fun signing_one_page_places_the_signature_only_there() {
        val d = dir("sign_one_")
        val input = blankPdf(d, 4)
        val output = File(d, "signed.pdf")

        val result = addVisualSignatureToPages(input.absolutePath, output.absolutePath, "A. Signer", listOf(1))

        assertTrue("signing must succeed: ${result.message}", result.success)
        assertEquals(4, result.pageCount)
        assertEquals(listOf(1), pagesContaining(output.absolutePath, "A. Signer"))
        assertTrue(OutputValidator.validatePdf(output))
        assertTrue("the message must name the signed page", result.message!!.contains("page 1"))
    }

    @Test
    fun signing_the_last_page_explicitly_works_and_adds_nothing_elsewhere() {
        val d = dir("sign_last_")
        val input = blankPdf(d, 3)
        val output = File(d, "signed.pdf")

        val result = addVisualSignatureToPages(input.absolutePath, output.absolutePath, "J. Doe", listOf(3))

        assertTrue(result.success)
        assertEquals(listOf(3), pagesContaining(output.absolutePath, "J. Doe"))
    }

    @Test
    fun signing_all_pages_places_the_signature_on_every_page() {
        val d = dir("sign_all_")
        val input = blankPdf(d, 3)
        val output = File(d, "signed.pdf")

        val result = addVisualSignatureToPages(input.absolutePath, output.absolutePath, "Every Page", emptyList())

        assertTrue(result.success)
        assertEquals(listOf(1, 2, 3), pagesContaining(output.absolutePath, "Every Page"))
        assertTrue(result.message!!.contains("every page"))
    }

    @Test
    fun signing_a_rotated_page_keeps_the_page_and_places_the_signature_upright() {
        val d = dir("sign_rotated_")
        val input = blankPdf(d, 2, rotation = 90)
        val output = File(d, "signed.pdf")

        val result = addVisualSignatureToPages(input.absolutePath, output.absolutePath, "Rotated", listOf(2))

        assertTrue(result.success)
        assertEquals(listOf(2), pagesContaining(output.absolutePath, "Rotated"))

        val doc = PdfDocument(PdfReader(output.absolutePath))
        try {
            assertEquals("page rotation must be preserved", 90, doc.getPage(2).rotation)
        } finally {
            doc.close()
        }
    }

    @Test
    fun signing_accepts_the_page_list_the_ui_derives() {
        val d = dir("sign_ui_")
        val input = blankPdf(d, 5)
        val output = File(d, "signed.pdf")

        // Exactly what the screen computes for "This page" while viewing page 3.
        val targets = signaturePagesOneBased(
            signatureTargetPages(SignaturePageMode.THIS_PAGE, 2, 5), 5
        )
        val result = addVisualSignatureToPages(input.absolutePath, output.absolutePath, "From UI", targets)

        assertTrue(result.success)
        assertEquals(listOf(3), pagesContaining(output.absolutePath, "From UI"))
    }

    @Test
    fun a_blank_signature_text_is_rejected() {
        val d = dir("sign_blank_")
        val input = blankPdf(d, 2)
        val result = addVisualSignatureToPages(input.absolutePath, File(d, "signed.pdf").absolutePath, "  ", listOf(1))
        assertFalse(result.success)
        assertTrue(result.message!!.isNotBlank())
    }
}
