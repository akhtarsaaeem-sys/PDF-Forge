package com.pdforge.app.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Verifies the core page-manipulation logic used by PdfEngine (rotate/merge/split)
 * produces valid PDFs that can be reopened by a parser with the expected page
 * count and rotation metadata.
 *
 * These tests run on the JVM with pure iText (no Android classes), matching the
 * exact copy + setRotation sequence used by PdfEngine.rotatePages.
 */
class PdfEngineRotationTest {

    private fun createSimplePdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        repeat(pages) { doc.addNewPage() }
        doc.close()
        return File(path)
    }

    @Test
    fun rotate_90_degrees_is_persisted_and_parseable() {
        val dir = Files.createTempDirectory("rotate_test").toFile()
        val input = createSimplePdf(File(dir, "input.pdf").absolutePath, 3)

        // Same sequence as PdfEngine.rotatePages(temp, output, pages, 90)
        // NOTE: copyTo returns the copied page — it must be added to the destination
        // document explicitly via addPage, otherwise the output has zero pages.
        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val output = File(dir, "rotated.pdf")
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (i in 1..pdfDoc.numberOfPages) {
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
        outputDoc.getPage(1).rotation = (outputDoc.getPage(1).rotation + 90) % 360
        val count = outputDoc.numberOfPages
        outputDoc.close()
        pdfDoc.close()

        assertEquals(3, count)
        assertTrue(output.length() > 0)

        // Reopen and verify the rotation metadata is visible to a fresh parser
        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(3, reopened.numberOfPages)
        assertEquals(90, reopened.getPage(1).rotation)
        assertEquals(0, reopened.getPage(2).rotation)
        assertEquals(0, reopened.getPage(3).rotation)
        reopened.close()
    }

    @Test
    fun rotate_180_and_270_are_persisted() {
        val dir = Files.createTempDirectory("rotate_test2").toFile()
        val input = createSimplePdf(File(dir, "input.pdf").absolutePath, 2)

        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        val output = File(dir, "rotated.pdf")
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        for (i in 1..pdfDoc.numberOfPages) {
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
        outputDoc.getPage(1).rotation = (outputDoc.getPage(1).rotation + 180) % 360
        outputDoc.getPage(2).rotation = (outputDoc.getPage(2).rotation + 270) % 360
        outputDoc.close()
        pdfDoc.close()

        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(180, reopened.getPage(1).rotation)
        assertEquals(270, reopened.getPage(2).rotation)
        reopened.close()
    }

    @Test
    fun merge_produces_parseable_multi_page_output() {
        val dir = Files.createTempDirectory("merge_test").toFile()
        val input1 = createSimplePdf(File(dir, "a.pdf").absolutePath, 2)
        val input2 = createSimplePdf(File(dir, "b.pdf").absolutePath, 1)
        val output = File(dir, "merged.pdf")

        // Same sequence as PdfEngine.mergePdfs
        val outputDoc = PdfDocument(PdfWriter(output.absolutePath))
        val merger = com.itextpdf.kernel.utils.PdfMerger(outputDoc)
        for (path in listOf(input1, input2)) {
            val inputDoc = PdfDocument(PdfReader(path.absolutePath))
            merger.merge(inputDoc, 1, inputDoc.numberOfPages)
            inputDoc.close()
        }
        merger.close()
        outputDoc.close()

        assertTrue(output.length() > 0)
        val reopened = PdfDocument(PdfReader(output.absolutePath))
        assertEquals(3, reopened.numberOfPages)
        reopened.close()
    }

    @Test
    fun split_produces_individual_parseable_files() {
        val dir = Files.createTempDirectory("split_test").toFile()
        val input = createSimplePdf(File(dir, "input.pdf").absolutePath, 3)

        val pdfDoc = PdfDocument(PdfReader(input.absolutePath))
        var index = 1
        for (start in 1..3) {
            val outFile = File(dir, "split_${"%02d".format(index)}.pdf")
            val outputDoc = PdfDocument(PdfWriter(outFile))
            for (i in start..start) {
                outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
            }
            outputDoc.close()
            index++
        }
        pdfDoc.close()

        for (i in 1..3) {
            val f = File(dir, "split_${"%02d".format(i)}.pdf")
            assertTrue(f.exists() && f.length() > 0)
            val reopened = PdfDocument(PdfReader(f.absolutePath))
            assertEquals(1, reopened.numberOfPages)
            reopened.close()
        }
    }
}