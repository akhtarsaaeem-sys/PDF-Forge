package com.pdforge.app.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Verifies the organize semantics used by the Organize PDF screen: reordering,
 * page deletion, and per-page rotation produce the requested page order,
 * page count and rotation metadata in the output PDF.
 *
 * Mirrors PdfEngine.organizePdf (copyTo + addPage + rotation) exactly.
 */
class OrganizePdfTest {

    private fun createPdfWithPageLabels(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val font = PdfFontFactory.createFont()
        repeat(pages) { p ->
            val page = doc.addNewPage()
            val canvas = PdfCanvas(page)
            canvas.beginText()
            canvas.setFontAndSize(font, 20f)
            canvas.moveText(72.0, 720.0)
            canvas.showText("PAGE_${p + 1}")
            canvas.endText()
        }
        doc.close()
        return File(path)
    }

    /** Same loop as PdfEngine.organizePdf: pages = (originalNumber, rotation). */
    private fun organize(inputPath: String, outputPath: String, pages: List<Pair<Int, Int>>): Int {
        val pdfDoc = PdfDocument(PdfReader(inputPath))
        val outputDoc = PdfDocument(PdfWriter(outputPath))
        for ((number, rotation) in pages) {
            if (number in 1..pdfDoc.numberOfPages) {
                val copied = outputDoc.addPage(pdfDoc.getPage(number).copyTo(outputDoc))
                if (rotation != 0) {
                    copied.rotation = (copied.rotation + rotation) % 360
                }
            }
        }
        val count = outputDoc.numberOfPages
        outputDoc.close()
        pdfDoc.close()
        return count
    }

    private fun pageTexts(path: String): List<String> {
        val doc = PdfDocument(PdfReader(path))
        val texts = (1..doc.numberOfPages).map { pageNum ->
            val strategy = com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy()
            val processor = com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor(strategy)
            processor.processPageContent(doc.getPage(pageNum))
            strategy.resultantText.trim()
        }
        doc.close()
        return texts
    }

    @Test
    fun organize_reorders_rotates_and_skips_deleted_pages() {
        val dir = Files.createTempDirectory("organize_test").toFile()
        val input = createPdfWithPageLabels(File(dir, "input.pdf").absolutePath, 4)
        val output = File(dir, "organized.pdf")

        // Screen sends: order [3, 1, 4, 2], page 4 rotated 90°, page 2 not rotated.
        val count = organize(input.absolutePath, output.absolutePath, listOf(3 to 0, 1 to 0, 4 to 90, 2 to 0))

        assertEquals(4, count)
        assertTrue(output.length() > 0)

        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(4, reopened.numberOfPages)
        // Order preserved: original 3, 1, 4, 2
        assertEquals("PAGE_3", pageTexts(output.absolutePath)[0])
        assertEquals("PAGE_1", pageTexts(output.absolutePath)[1])
        assertEquals("PAGE_4", pageTexts(output.absolutePath)[2])
        assertEquals("PAGE_2", pageTexts(output.absolutePath)[3])
        // Rotation metadata on the rotated page only.
        assertEquals(90, reopened.getPage(3).rotation)
        assertEquals(0, reopened.getPage(1).rotation)
        assertEquals(0, reopened.getPage(2).rotation)
        assertEquals(0, reopened.getPage(4).rotation)
        reopened.close()
    }

    @Test
    fun organize_delete_reduces_page_count_and_preserves_order() {
        val dir = Files.createTempDirectory("organize_del").toFile()
        val input = createPdfWithPageLabels(File(dir, "input.pdf").absolutePath, 4)
        val output = File(dir, "organized.pdf")

        // Delete page 3 (and 2 by omission): [1, 4]
        val count = organize(input.absolutePath, output.absolutePath, listOf(1 to 0, 4 to 0))

        assertEquals(2, count)
        val texts = pageTexts(output.absolutePath)
        assertEquals(listOf("PAGE_1", "PAGE_4"), texts)
    }

    @Test
    fun organize_reverses_page_order() {
        val dir = Files.createTempDirectory("organize_rev").toFile()
        val input = createPdfWithPageLabels(File(dir, "input.pdf").absolutePath, 3)
        val output = File(dir, "organized.pdf")

        val count = organize(input.absolutePath, output.absolutePath, listOf(3 to 0, 2 to 0, 1 to 0))

        assertEquals(3, count)
        assertEquals(listOf("PAGE_3", "PAGE_2", "PAGE_1"), pageTexts(output.absolutePath))
    }
}