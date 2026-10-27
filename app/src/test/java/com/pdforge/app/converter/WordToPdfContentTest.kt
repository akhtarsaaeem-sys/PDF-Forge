package com.pdforge.app.converter

import com.pdforge.app.pdf.TestSupport
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Regression tests for "Word → PDF: poor appearance, some content disappears".
 *
 * The old converter treated paragraphs and tables as two separate passes (moving
 * every table to the end), dropped images and page breaks, truncated cell text and
 * collapsed formatting. These tests convert a fixture that contains every element
 * the prompt lists, using the real production converter, and assert that the
 * important content survives in the right order.
 */
class WordToPdfContentTest {

    private fun buildDocx(file: File) {
        val doc = XWPFDocument()

        // 1. Heading
        doc.createParagraph().apply {
            style = "Heading1"
            createRun().setText("Annual Summary")
        }
        // 2. Normal paragraph
        doc.createParagraph().createRun().setText("Plain paragraph text alpha")
        // 3. Formatted text
        DocFixtures.addFormattedParagraph(doc)
        // 4. Table
        val table = doc.createTable(2, 2)
        table.getRow(0).getCell(0).text = "HeaderA"
        table.getRow(0).getCell(1).text = "HeaderB"
        table.getRow(1).getCell(0).text = "CellA1"
        table.getRow(1).getCell(1).text = "CellB1"
        // 5. Image
        val imageRun = doc.createParagraph().createRun()
        val imageBytes = TestSupport.resourceBytes("/fixtures/jpg_square.jpg")
        imageRun.addPicture(
            ByteArrayInputStream(imageBytes),
            Document.PICTURE_TYPE_JPEG,
            "picture.jpg",
            Units.toEMU(120.0),
            Units.toEMU(120.0)
        )
        // 6. Explicit page break
        doc.createParagraph().createRun().addBreak(BreakType.PAGE)
        doc.createParagraph().createRun().setText("AfterThePageBreak")

        FileOutputStream(file).use { doc.write(it) }
        doc.close()
    }

    @Test
    fun all_element_types_survive_conversion() {
        val dir = TestSupport.tempDir("word_pdf")
        val input = File(dir, "in.docx")
        buildDocx(input)
        val output = File(dir, "out.pdf")

        convertWordToPdf(input.absolutePath, output.absolutePath)

        assertTrue("output must exist and be non-empty", output.exists() && output.length() > 0)
        assertTrue(
            "output must be a valid PDF",
            com.pdforge.app.storage.OutputValidator.validatePdf(output)
        )
        val text = TestSupport.pdfText(output.absolutePath)
        for (expected in listOf(
            "Annual Summary",
            "Plain paragraph text alpha",
            "bold", "normal", "italic",
            "HeaderA", "HeaderB", "CellA1", "CellB1",
            "AfterThePageBreak"
        )) {
            assertTrue("content '$expected' disappeared during conversion", text.contains(expected))
        }
    }

    @Test
    fun document_order_is_preserved() {
        val dir = TestSupport.tempDir("word_pdf_order")
        val input = File(dir, "in.docx")
        buildDocx(input)
        val output = File(dir, "out.pdf")
        convertWordToPdf(input.absolutePath, output.absolutePath)

        val text = TestSupport.pdfText(output.absolutePath)
        assertTrue("heading before the table", text.indexOf("Annual Summary") < text.indexOf("HeaderA"))
        assertTrue("table before the page break", text.indexOf("CellB1") < text.indexOf("AfterThePageBreak"))
    }

    @Test
    fun a_table_is_rendered_as_a_real_table_not_dropped() {
        val dir = TestSupport.tempDir("word_pdf_table")
        val input = File(dir, "in.docx")
        buildDocx(input)
        val output = File(dir, "out.pdf")
        convertWordToPdf(input.absolutePath, output.absolutePath)
        val text = TestSupport.pdfText(output.absolutePath)
        assertTrue(text.contains("HeaderA") && text.contains("HeaderB"))
        assertTrue(text.contains("CellA1") && text.contains("CellB1"))
    }

    @Test
    fun an_embedded_image_is_placed_in_the_pdf() {
        val dir = TestSupport.tempDir("word_pdf_image")
        val input = File(dir, "in.docx")
        buildDocx(input)
        val output = File(dir, "out.pdf")
        convertWordToPdf(input.absolutePath, output.absolutePath)

        val raw = output.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("the embedded image should be present in the PDF", raw.contains("/Image"))
    }

    @Test
    fun an_explicit_page_break_starts_a_new_page() {
        val dir = TestSupport.tempDir("word_pdf_break")
        val input = File(dir, "in.docx")
        buildDocx(input)
        val output = File(dir, "out.pdf")
        convertWordToPdf(input.absolutePath, output.absolutePath)

        assertTrue(
            "the document should span at least two pages",
            TestSupport.pdfPageCount(output.absolutePath) >= 2
        )
    }
}

/** Shared fixture helpers for the Word tests. */
internal object DocFixtures {
    fun addFormattedParagraph(doc: XWPFDocument) {
        val paragraph = doc.createParagraph()
        paragraph.createRun().apply { setText("bold"); isBold = true }
        paragraph.createRun().apply { setText("normal") }
        paragraph.createRun().apply { setText("italic"); isItalic = true }
    }
}
