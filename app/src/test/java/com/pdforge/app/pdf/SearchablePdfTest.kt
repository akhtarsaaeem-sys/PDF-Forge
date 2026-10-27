package com.pdforge.app.pdf

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.pdforge.app.ocr.OcrPageWords
import com.pdforge.app.ocr.OcrWord
import com.pdforge.app.ocr.buildSearchablePdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for OCR "searchable PDF" generation.
 *
 * The old pipeline embedded a full-resolution bitmap of every page (a 1 MB PDF
 * became ~14 MB) and drew the OCR text with a transparent paint, so the text was
 * unreliable to select. These tests run the REAL [buildSearchablePdf] text-layer
 * builder with injected OCR word boxes, so the searchable text is verified with a
 * real PDF parser — no Tesseract binary is required in the JVM test environment.
 *
 * The one thing that genuinely needs a device is running the Tesseract engine
 * itself (see the final report's limitations).
 */
class SearchablePdfTest {

    private fun resourceBytes(name: String): ByteArray =
        javaClass.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun createTextPdf(path: String, pages: Int, phrase: String): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { i ->
            document.add(Paragraph("$phrase PAGE ${i + 1}"))
            if (i < pages - 1) document.add(AreaBreak())
        }
        document.close()
        return File(path)
    }

    /** An image-only page with no text (like a scan). */
    private fun createImageOnlyPdf(path: String, jpegBytes: ByteArray): File {
        val doc = PdfDocument(PdfWriter(path))
        val page = doc.addNewPage(PageSize.A4)
        PdfCanvas(page).addImageFittedIntoRectangle(
            ImageDataFactory.create(jpegBytes),
            Rectangle(0f, 0f, PageSize.A4.width, PageSize.A4.height),
            false
        )
        doc.close()
        return File(path)
    }

    private fun pageTexts(path: String): List<String> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).map { i ->
                val strategy = SimpleTextExtractionStrategy()
                PdfCanvasProcessor(strategy).processPageContent(doc.getPage(i))
                strategy.resultantText
            }
        } finally {
            doc.close()
        }
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
    fun ocr_adds_searchable_text_to_an_image_only_page() {
        val dir = Files.createTempDirectory("ocr_image").toFile()
        val input = createImageOnlyPdf(File(dir, "scan.pdf").absolutePath, resourceBytes("/scan_sample_page.jpg"))
        val output = File(dir, "searchable.pdf")

        val words = listOf(
            OcrWord("HELLO", 100, 100, 220, 130),
            OcrWord("WORLD", 240, 100, 380, 130)
        )
        val outcome = buildSearchablePdf(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            ocrPage = { OcrPageWords(words, 1000, 1400) }
        )

        assertEquals(1, outcome.pageCount)
        assertEquals(1, outcome.pagesWithText)
        assertTrue("output must exist and be non-empty", output.length() > 0)
        assertEquals(1, pageCount(output.absolutePath))

        val text = pageTexts(output.absolutePath).first()
        assertTrue("expected phrase must be searchable, got: $text", text.contains("HELLO"))
        assertTrue("expected phrase must be searchable, got: $text", text.contains("WORLD"))
    }

    @Test
    fun output_page_count_matches_the_input() {
        val dir = Files.createTempDirectory("ocr_pages").toFile()
        val input = createImageOnlyPdf(File(dir, "scan.pdf").absolutePath, resourceBytes("/scan_sample_page.jpg"))
        val output = File(dir, "searchable.pdf")

        val outcome = buildSearchablePdf(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            ocrPage = { OcrPageWords(listOf(OcrWord("X", 10, 10, 40, 40)), 1000, 1400) }
        )

        assertEquals(1, outcome.pageCount)
        assertEquals(1, pageCount(output.absolutePath))
    }

    @Test
    fun pages_that_already_have_text_are_not_ocrd() {
        val dir = Files.createTempDirectory("ocr_skip").toFile()
        val input = createTextPdf(
            File(dir, "text.pdf").absolutePath,
            2,
            "THIS PAGE ALREADY CONTAINS SEARCHABLE REAL_TEXT"
        )
        val output = File(dir, "searchable.pdf")

        var ocrCalls = 0
        val outcome = buildSearchablePdf(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            ocrPage = { ocrCalls++; OcrPageWords(listOf(OcrWord("IGNORED", 10, 10, 40, 40)), 100, 100) },
            skipTextPages = true
        )

        assertEquals("an already-searchable page must not be re-OCR'd", 0, ocrCalls)
        assertEquals(2, outcome.pagesAlreadyTextual)
        assertEquals(0, outcome.pagesWithText)
        // The original text is preserved.
        assertTrue(pageTexts(output.absolutePath).all { it.contains("REAL_TEXT") })
    }

    @Test
    fun adding_a_text_layer_does_not_balloon_a_text_pdf() {
        val dir = Files.createTempDirectory("ocr_size").toFile()
        val input = createTextPdf(File(dir, "text.pdf").absolutePath, 3, "ORIGINAL")
        val inputSize = input.length()
        val output = File(dir, "searchable.pdf")

        // Force the text layer so this measures the layer cost, not the skip path.
        val outcome = buildSearchablePdf(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            ocrPage = { OcrPageWords(listOf(OcrWord("OCRWORD", 100, 100, 260, 130)), 1000, 1400) },
            skipTextPages = false
        )

        assertEquals(3, outcome.pagesWithText)
        val growth = output.length() - inputSize
        assertTrue(
            "adding a text layer must not balloon the file (grew by $growth bytes, input $inputSize)",
            output.length() < inputSize + 64 * 1024
        )
        // The added text is searchable on every page.
        assertTrue(pageTexts(output.absolutePath).all { it.contains("OCRWORD") })
    }

    @Test
    fun a_page_with_no_ocr_result_is_copied_untouched() {
        val dir = Files.createTempDirectory("ocr_none").toFile()
        val input = createImageOnlyPdf(File(dir, "scan.pdf").absolutePath, resourceBytes("/scan_sample_page.jpg"))
        val output = File(dir, "copied.pdf")

        val outcome = buildSearchablePdf(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            ocrPage = { null }
        )

        assertEquals(1, outcome.pageCount)
        assertEquals(0, outcome.pagesWithText)
        assertEquals(1, pageCount(output.absolutePath))
        assertTrue(output.length() > 0)
    }
}
