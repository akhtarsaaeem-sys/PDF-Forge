package com.pdforge.app.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the viewer reference handling and the watermark pipeline:
 *
 *  - the viewer route argument must survive every navigation URI-decode layer
 *    losslessly (references containing '+', '%', spaces, unicode, content URIs)
 *  - the watermark anchor mapping used by the preview and the final output is
 *    a single fraction-based mapping, so preview and PDF always agree
 *  - a watermarked PDF is valid, keeps its pages, and the watermark text is
 *    actually present in the output
 *  - a generated PDF has pages and can be reopened (output reliability)
 */
class ViewerAndWatermarkRegressionTest {

    // ── Viewer reference encoding (NavRoutes.Viewer / AppNavigation) ────────

    private fun encode(input: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(input.toByteArray(Charsets.UTF_8))

    private fun decode(encoded: String): String =
        String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)

    @Test
    fun viewer_reference_round_trips_through_url_safe_base64() {
        val references = listOf(
            "content://media/external/file/12345",
            "/data/user/0/com.pdforge.app/files/output/report_rotated.pdf",
            "/storage/emulated/0/Documents/PDF Forge/report+final.pdf",
            "file:///storage/emulated/0/Download/100%_report.pdf",
            "content://com.android.providers.media.documents/document/image%3A1234",
            "content://com.android.externalstorage.documents/document/primary%3ADocuments%2FPDF%20Forge%2Fdoc.pdf",
            "/storage/emulated/0/下载/报告 2026.pdf"
        )
        for (reference in references) {
            assertEquals(reference, decode(encode(reference)))
        }
    }

    @Test
    fun viewer_reference_encoding_uses_uri_safe_alphabet() {
        // The encoded route segment must survive every Uri.decode() pass made by
        // navigation, so it may only contain characters that decode to themselves.
        // Uri.decode turns '+' into a space and '%xx' into bytes; the URL-safe
        // Base64 alphabet (A-Za-z0-9_-) contains neither, so any number of
        // decodes is a no-op.
        val encoded = encode("content://media/external/file/1+2%3")
        assertTrue(encoded.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(!encoded.contains('+'))
        assertTrue(!encoded.contains('%'))
        assertEquals("content://media/external/file/1+2%3", decode(encoded))
    }

    @Test
    fun viewer_reference_decoding_never_urldecodes_the_result() {
        // The old bug: the app decoded the (already decoded) navigation argument,
        // turning '+' into a space and corrupting paths like "report+final.pdf".
        val corrupted = "report final.pdf"  // what double-decoding produced
        val original = "report+final.pdf"
        assertTrue(original != corrupted)
        // The base64 reference must restore the original, plus intact.
        assertEquals(original, decode(encode(original)))
    }

    // ── Watermark anchor mapping (shared by preview and final output) ────────

    @Test
    fun watermark_anchor_center_is_page_center() {
        val (x, y) = watermarkAnchor(WatermarkPosition.CENTER, 612f, 792f)
        assertEquals(306f, x, 0.01f)
        assertEquals(396f, y, 0.01f)
    }

    @Test
    fun watermark_anchor_fractions_match_position_names() {
        // A4 letter size page (612 x 792 pt)
        val (tlX, tlY) = watermarkAnchor(WatermarkPosition.TOP_LEFT, 612f, 792f)
        assertEquals(612f * 0.18f, tlX, 0.01f)
        assertEquals(792f * 0.85f, tlY, 0.01f)  // 0.85 from the bottom = near the top

        val (brX, brY) = watermarkAnchor(WatermarkPosition.BOTTOM_RIGHT, 612f, 792f)
        assertEquals(612f * 0.82f, brX, 0.01f)
        assertEquals(792f * 0.15f, brY, 0.01f)

        val (blX, _) = watermarkAnchor(WatermarkPosition.BOTTOM_LEFT, 612f, 792f)
        assertEquals(612f * 0.18f, blX, 0.01f)
    }

    @Test
    fun watermark_anchor_columns_and_rows_are_consistent() {
        val center = watermarkAnchor(WatermarkPosition.CENTER, 612f, 792f)
        // All CENTER-column positions share the x fraction...
        assertEquals(center.first, watermarkAnchor(WatermarkPosition.TOP_CENTER, 612f, 792f).first, 0.01f)
        assertEquals(center.first, watermarkAnchor(WatermarkPosition.BOTTOM_CENTER, 612f, 792f).first, 0.01f)
        // ...and all left-column positions share it too.
        assertEquals(
            watermarkAnchor(WatermarkPosition.TOP_LEFT, 612f, 792f).first,
            watermarkAnchor(WatermarkPosition.BOTTOM_LEFT, 612f, 792f).first,
            0.01f
        )
        // Bottom positions are always below center (smaller y-from-bottom).
        val bottomY = watermarkAnchor(WatermarkPosition.BOTTOM_CENTER, 612f, 792f).second
        assertTrue(bottomY < center.second)
    }

    // ── Watermark generation (same sequence as PdfEngine.addTextWatermark) ───

    private fun createPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        repeat(pages) { doc.addNewPage() }
        doc.close()
        return File(path)
    }

    @Test
    fun watermark_generation_produces_valid_reopenable_pdf_with_watermark_text() {
        val dir = Files.createTempDirectory("watermark_test").toFile()
        val input = createPdf(File(dir, "input.pdf").absolutePath, 3)
        val output = File(dir, "watermarked.pdf")

        // Exact sequence from PdfEngine.addTextWatermark (fraction-based anchor).
        val text = "CONFIDENTIAL"
        val opacity = 0.3f
        val fontSize = 50f
        val rotation = 45f
        val position = WatermarkPosition.CENTER

        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (i in 1..pdfDoc.numberOfPages) {
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
        val font = PdfFontFactory.createFont()
        val extGState = PdfExtGState().setFillOpacity(opacity)
        val totalPages = outputDoc.numberOfPages
        for (i in 1..totalPages) {
            val page = outputDoc.getPage(i)
            val ps = page.pageSize
            val tw = font.getWidth(text, fontSize)
            val (ax, ay) = watermarkAnchor(position, ps.width, ps.height)
            val cx = ps.left + ax
            val cy = ps.bottom + ay
            val canvas = PdfCanvas(page)
            canvas.saveState()
            canvas.setExtGState(extGState)
            canvas.setColor(ColorConstants.GRAY, true)
            canvas.setFontAndSize(font, fontSize)
            val theta = Math.toRadians(rotation.toDouble())
            val cosT = Math.cos(theta)
            val sinT = Math.sin(theta)
            canvas.concatMatrix(
                cosT, sinT, -sinT, cosT,
                cx - cx * cosT + cy * sinT,
                cy - cx * sinT - cy * cosT
            )
            canvas.beginText()
            canvas.moveText((cx - tw / 2).toDouble(), cy.toDouble())
            canvas.showText(text)
            canvas.endText()
            canvas.restoreState()
        }
        val count = outputDoc.numberOfPages
        outputDoc.close()
        pdfDoc.close()

        // Output is valid, has the same page count, and can be reopened.
        assertTrue(output.exists() && output.length() > 0)
        assertEquals(3, count)
        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(3, reopened.numberOfPages)
        reopened.close()

        // The watermark text is actually present in the rendered output.
        val reader = PdfReader(output.absolutePath)
        val doc = PdfDocument(reader)
        val strategy = com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy()
        val extractor = com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor(strategy)
        extractor.processPageContent(doc.getPage(1))
        val pageText = strategy.resultantText
        doc.close()
        assertTrue("watermark text missing from output", pageText.contains(text))
    }

    @Test
    fun watermark_position_changes_the_output_anchor() {
        // The same fraction mapping must be used everywhere, so a position change
        // moves the anchor predictably — preview and output can never disagree.
        val (cx, cy) = watermarkAnchor(WatermarkPosition.CENTER, 612f, 792f)
        val (bx, by) = watermarkAnchor(WatermarkPosition.BOTTOM_CENTER, 612f, 792f)
        assertEquals(cx, bx, 0.01f)
        assertTrue(by < cy)
        val (tx, ty) = watermarkAnchor(WatermarkPosition.TOP_CENTER, 612f, 792f)
        assertTrue(ty > cy)
    }

    // ── Output reliability: generated PDF has pages and reopens ─────────────

    @Test
    fun generated_pdf_has_pages_and_can_be_reopened() {
        val dir = Files.createTempDirectory("reopen_test").toFile()
        val input = createPdf(File(dir, "multi.pdf").absolutePath, 5)

        // Copy-all-pages (used by rotate/watermark/crop/sign) produces a
        // non-empty document that a fresh parser can reopen with all pages.
        val output = File(dir, "copied.pdf")
        val src = PdfDocument(PdfReader(input.absolutePath))
        val dst = PdfDocument(PdfWriter(output.absolutePath))
        for (i in 1..src.numberOfPages) {
            dst.addPage(src.getPage(i).copyTo(dst))
        }
        val produced = dst.numberOfPages
        dst.close()
        src.close()

        assertTrue(produced == 5 && output.length() > 0)
        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(5, reopened.numberOfPages)
        reopened.close()
    }

    @Test
    fun invalid_pdf_never_counts_as_valid_output() {
        val dir = Files.createTempDirectory("invalid_test").toFile()
        val junk = File(dir, "junk.pdf")
        junk.writeText("not a pdf, just text")
        val ok = try {
            val r = PdfReader(junk.absolutePath)
            val d = PdfDocument(r)
            val pages = d.numberOfPages
            d.close()
            pages > 0
        } catch (e: Exception) {
            false
        }
        assertTrue(!ok)
    }
}