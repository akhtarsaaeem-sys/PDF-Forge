package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import com.pdforge.app.data.models.PdfPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Organize PDF rotation persistence.
 *
 * The on-device symptom was "Rotate does nothing". Two things were wrong:
 *  1. the thumbnail preview ignored the pending rotation (fixed in the screen),
 *  2. the saved file had to be verified end to end.
 *
 * These tests run the real production function [organizePdfFiles] — not a copy of
 * it — and reopen the output file to prove the rotation really persisted.
 */
class OrganizeRotationPersistenceTest {

    private fun createLabeledPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { index ->
            document.add(Paragraph("PAGE_${index + 1}"))
            // Only BETWEEN pages — a trailing AreaBreak would add a blank page.
            if (index < pages - 1) document.add(AreaBreak())
        }
        document.close()
        return File(path)
    }

    private fun rotations(path: String): List<Int> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).map { doc.getPage(it).rotation }
        } finally {
            doc.close()
        }
    }

    private fun pageTexts(path: String): List<String> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).map { pageNum ->
                val strategy = SimpleTextExtractionStrategy()
                PdfCanvasProcessor(strategy).processPageContent(doc.getPage(pageNum))
                strategy.resultantText.trim()
            }
        } finally {
            doc.close()
        }
    }

    @Test
    fun rotating_one_page_90_degrees_persists_in_the_saved_file() {
        val dir = Files.createTempDirectory("organize_rot90").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 3)
        val output = File(dir, "out.pdf")

        val count = organizePdfFiles(
            input.absolutePath, output.absolutePath,
            listOf(PdfPage(1), PdfPage(2), PdfPage(3, rotation = 90))
        )

        assertEquals(3, count)
        val saved = rotations(output.absolutePath)
        assertEquals(listOf(0, 0, 90), saved)
    }

    @Test
    fun rotating_the_same_page_twice_saves_180_degrees() {
        val dir = Files.createTempDirectory("organize_rot_twice").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 2)
        val output = File(dir, "out.pdf")

        // The screen accumulates 90 + 90 = 180 before saving.
        val accumulated = (0 + 90) % 360
        val twice = (accumulated + 90) % 360
        assertEquals(180, twice)

        organizePdfFiles(input.absolutePath, output.absolutePath, listOf(PdfPage(1, rotation = twice), PdfPage(2)))

        assertEquals(listOf(180, 0), rotations(output.absolutePath))
    }

    @Test
    fun fourth_rotation_wraps_back_to_zero() {
        val dir = Files.createTempDirectory("organize_rot_wrap").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 1)
        val output = File(dir, "out.pdf")

        var rotation = 0
        repeat(4) { rotation = (rotation + 90) % 360 }
        assertEquals(0, rotation)

        organizePdfFiles(input.absolutePath, output.absolutePath, listOf(PdfPage(1, rotation = rotation)))

        assertEquals(listOf(0), rotations(output.absolutePath))
    }

    @Test
    fun rotating_multiple_pages_persists_each_page_independently() {
        val dir = Files.createTempDirectory("organize_rot_multi").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 4)
        val output = File(dir, "out.pdf")

        organizePdfFiles(
            input.absolutePath, output.absolutePath,
            listOf(
                PdfPage(1, rotation = 90),
                PdfPage(2),
                PdfPage(3, rotation = 270),
                PdfPage(4, rotation = 180)
            )
        )

        assertEquals(listOf(90, 0, 270, 180), rotations(output.absolutePath))
    }

    @Test
    fun rotation_survives_reordering() {
        val dir = Files.createTempDirectory("organize_rot_reorder").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 3)
        val output = File(dir, "out.pdf")

        // Order 3, 1, 2 with page 3 (now first) rotated.
        organizePdfFiles(
            input.absolutePath, output.absolutePath,
            listOf(PdfPage(3, rotation = 90), PdfPage(1), PdfPage(2))
        )

        assertEquals(listOf("PAGE_3", "PAGE_1", "PAGE_2"), pageTexts(output.absolutePath))
        assertEquals(listOf(90, 0, 0), rotations(output.absolutePath))
    }

    @Test
    fun rotation_survives_deleting_other_pages() {
        val dir = Files.createTempDirectory("organize_rot_delete").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 4)
        val output = File(dir, "out.pdf")

        // Delete page 2: keep 1, 3, 4 and rotate the page that was 3.
        organizePdfFiles(
            input.absolutePath, output.absolutePath,
            listOf(PdfPage(1), PdfPage(3, rotation = 90), PdfPage(4))
        )

        assertEquals(listOf("PAGE_1", "PAGE_3", "PAGE_4"), pageTexts(output.absolutePath))
        assertEquals(listOf(0, 90, 0), rotations(output.absolutePath))
    }

    @Test
    fun rotation_accumulates_onto_a_source_page_that_is_already_rotated() {
        val dir = Files.createTempDirectory("organize_rot_accumulate").toFile()
        val input = File(dir, "in.pdf")
        // Source page already carries /Rotate 90.
        val doc = PdfDocument(PdfWriter(input.absolutePath))
        doc.addNewPage().rotation = 90
        doc.close()
        val output = File(dir, "out.pdf")

        organizePdfFiles(input.absolutePath, output.absolutePath, listOf(PdfPage(1, rotation = 90)))

        assertEquals(listOf(180), rotations(output.absolutePath))
    }

    @Test
    fun saved_rotation_survives_a_reopen_and_is_a_valid_multiple_of_90() {
        val dir = Files.createTempDirectory("organize_rot_reopen").toFile()
        val input = createLabeledPdf(File(dir, "in.pdf").absolutePath, 2)
        val output = File(dir, "out.pdf")

        organizePdfFiles(
            input.absolutePath, output.absolutePath,
            listOf(PdfPage(1, rotation = 270), PdfPage(2, rotation = 90))
        )

        assertTrue(output.length() > 0)
        // Reopened independently: the only values a PDF may carry are 0/90/180/270.
        rotations(output.absolutePath).forEach { r ->
            assertTrue("invalid persisted rotation: $r", r % 90 == 0)
        }
        assertEquals(listOf(270, 90), rotations(output.absolutePath))
    }

    @Test
    fun normalize_rotation_handles_negative_and_oversized_values() {
        assertEquals(0, normalizeRotation(0))
        assertEquals(90, normalizeRotation(90))
        assertEquals(270, normalizeRotation(-90))
        assertEquals(90, normalizeRotation(450))
        assertEquals(180, normalizeRotation(-180))
    }
}
