package com.pdforge.app.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Add Page Numbers on rotated pages.
 *
 * The number is placed using the page's DISPLAY space (how the page looks after
 * /Rotate is applied) and converted into raw user space. These tests drive the
 * real production [PdfEngine.addPageNumbers], then read the text back out of the
 * generated PDF through a render listener: the position is converted to display
 * coordinates with an independently written transform and the baseline angle is
 * checked, so a misplacement or an upside-down number fails the test.
 */
class PageNumbersRotationTest {

    private val engine = PdfEngine(null)

    /**
     * Captures each text run's geometry. The values MUST be read during event
     * dispatching: iText deletes the graphics state right after a render event, so
     * touching the baseline later throws.
     */
    private class CapturingListener : IEventListener {
        val texts = mutableListOf<RenderedText>()

        override fun eventOccurred(data: IEventData, type: EventType) {
            if (type != EventType.RENDER_TEXT) return
            val info = data as TextRenderInfo
            val baseline = info.baseline
            val start = baseline.startPoint
            val end = baseline.endPoint
            texts.add(
                RenderedText(
                    text = info.text,
                    startX = start[0],
                    startY = start[1],
                    endX = end[0],
                    endY = end[1],
                    fontSize = info.fontSize
                )
            )
        }

        override fun getSupportedEvents(): MutableSet<EventType> = mutableSetOf(EventType.RENDER_TEXT)
    }

    private data class RenderedText(
        val text: String,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val fontSize: Float
    ) {
        val length: Double
            get() = Math.hypot((endX - startX).toDouble(), (endY - startY).toDouble())

        val angleDegrees: Double
            get() = Math.toDegrees(atan2((endY - startY).toDouble(), (endX - startX).toDouble()))
    }

    private fun textsOfPage(path: String, pageNumber: Int): List<RenderedText> {
        val doc = PdfDocument(PdfReader(path))
        try {
            val listener = CapturingListener()
            PdfCanvasProcessor(listener).processPageContent(doc.getPage(pageNumber))
            return listener.texts
        } finally {
            doc.close()
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun rotatedPdf(dir: File, rotations: List<Int>, size: PageSize = PageSize.A4): File {
        val file = File(dir, "rotated_${rotations.joinToString("_")}.pdf")
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        for (rotation in rotations) {
            val page = doc.addNewPage(size)
            if (rotation != 0) page.rotation = rotation
        }
        doc.close()
        return file
    }

    private fun newDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    // ── Independently written display-space transform (NOT the production helper,
    //    so the assertions are a genuine cross-check) ──────────────────────────

    private fun displayWidth(w: Float, h: Float, rotation: Int) = if (rotation % 180 == 90) h else w
    private fun displayHeight(w: Float, h: Float, rotation: Int) = if (rotation % 180 == 90) w else h

    private fun rawToDisplay(x: Float, y: Float, w: Float, h: Float, rotation: Int): Pair<Float, Float> =
        when (rotation) {
            90 -> y to (w - x)
            180 -> (w - x) to (h - y)
            270 -> (h - y) to x
            else -> x to y
        }

    /** Smallest signed difference between two angles in degrees. */
    private fun angleDelta(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun bottom_center_is_bottom_centered_and_upright_for_every_page_rotation() {
        val width = PageSize.A4.width
        val height = PageSize.A4.height
        val fontSize = 12f
        val margin = 36f
        val textWidth = PdfFontFactory.createFont().getWidth("1", fontSize)

        for (rotation in listOf(0, 90, 180, 270)) {
            val dir = newDir("pn_${rotation}_")
            val input = rotatedPdf(dir, listOf(rotation))
            val output = File(dir, "numbered.pdf")

            val result = engine.addPageNumbers(
                input.absolutePath, output.absolutePath,
                PageNumberPosition.BOTTOM_CENTER, 1, fontSize, margin
            )
            assertTrue("rotation $rotation must succeed: ${result.message}", result.success)

            val rendered = textsOfPage(output.absolutePath, 1)
            assertEquals("rotation $rotation must add exactly one number", 1, rendered.size)
            val number = rendered.single()
            assertEquals("1", number.text.trim())

            val (displayX, displayY) = rawToDisplay(number.startX, number.startY, width, height, rotation)
            val dispW = displayWidth(width, height, rotation)

            assertEquals(
                "rotation $rotation: number must sit $margin pt above the displayed bottom edge",
                margin.toDouble(), displayY.toDouble(), 0.5
            )
            assertEquals(
                "rotation $rotation: number must be horizontally centered",
                dispW.toDouble() / 2.0, displayX.toDouble() + textWidth / 2.0, 0.5
            )
            // The content stream is rotated by +rotation (counter-clockwise) so the
            // viewer's own clockwise rotation cancels out: the DISPLAYED angle is
            // the raw angle minus the page rotation.
            assertEquals(
                "rotation $rotation: number must read upright on the displayed page",
                0.0, angleDelta(number.angleDegrees, rotation.toDouble()), 0.5
            )
        }
    }

    @Test
    fun top_right_is_top_right_on_every_page_rotation() {
        val width = PageSize.A4.width
        val height = PageSize.A4.height
        val fontSize = 12f
        val margin = 36f

        for (rotation in listOf(0, 90, 180, 270)) {
            val dir = newDir("pn_tr_${rotation}_")
            val input = rotatedPdf(dir, listOf(rotation))
            val output = File(dir, "numbered.pdf")

            val result = engine.addPageNumbers(
                input.absolutePath, output.absolutePath,
                PageNumberPosition.TOP_RIGHT, 7, fontSize, margin
            )
            assertTrue("rotation $rotation must succeed: ${result.message}", result.success)

            val number = textsOfPage(output.absolutePath, 1).single()
            assertEquals("7", number.text.trim())

            val (displayX, displayY) = rawToDisplay(number.startX, number.startY, width, height, rotation)
            val dispW = displayWidth(width, height, rotation)
            val dispH = displayHeight(width, height, rotation)
            val textWidth = number.length.toFloat()

            // Right edge at dispW - margin, top edge at dispH - margin.
            assertEquals(
                "rotation $rotation: right edge",
                dispW.toDouble() - margin.toDouble(), displayX.toDouble() + textWidth.toDouble(), 0.5
            )
            assertEquals(
                "rotation $rotation: top edge (baseline sits one font size below the top margin)",
                dispH.toDouble() - margin.toDouble() - fontSize.toDouble(), displayY.toDouble(), 0.5
            )
            assertEquals(
                "rotation $rotation: upright",
                0.0, angleDelta(number.angleDegrees, rotation.toDouble()), 0.5
            )
        }
    }

    @Test
    fun unrotated_pages_keep_the_original_raw_coordinates() {
        // Regression guard for the "do not change unrotated behaviour" requirement:
        // bottom-center must still be (pageWidth - textWidth)/2, margin.
        val dir = newDir("pn_plain_")
        val input = rotatedPdf(dir, listOf(0))
        val output = File(dir, "numbered.pdf")

        val result = engine.addPageNumbers(
            input.absolutePath, output.absolutePath,
            PageNumberPosition.BOTTOM_CENTER, 1, 12f, 36f
        )
        assertTrue(result.success)

        val number = textsOfPage(output.absolutePath, 1).single()
        val textWidth = PdfFontFactory.createFont().getWidth("1", 12f)
        assertEquals((PageSize.A4.width - textWidth) / 2.0, number.startX.toDouble(), 0.01)
        assertEquals(36.0, number.startY.toDouble(), 0.01)
        assertEquals(0.0, number.angleDegrees, 0.01)
    }

    @Test
    fun mixed_rotated_and_unrotated_document_numbers_every_page_in_display_space() {
        val dir = newDir("pn_mixed_")
        val rotations = listOf(0, 90, 180, 270)
        val input = rotatedPdf(dir, rotations)
        val output = File(dir, "numbered.pdf")

        val result = engine.addPageNumbers(
            input.absolutePath, output.absolutePath,
            PageNumberPosition.BOTTOM_CENTER, 1, 12f, 36f
        )
        assertTrue("mixed document must succeed: ${result.message}", result.success)
        assertEquals(4, result.pageCount)

        val doc = PdfDocument(PdfReader(output.absolutePath))
        try {
            assertEquals(4, doc.numberOfPages)
            for ((index, rotation) in rotations.withIndex()) {
                val pageNumber = index + 1
                assertEquals(
                    "page $pageNumber must keep its rotation",
                    rotation, doc.getPage(pageNumber).rotation
                )
                val numbers = textsOfPage(output.absolutePath, pageNumber).filter { it.text.isNotBlank() }
                assertEquals("page $pageNumber must carry exactly its own number", 1, numbers.size)
                assertEquals("${pageNumber}", numbers.single().text.trim())

                val (_, displayY) = rawToDisplay(
                    numbers.single().startX, numbers.single().startY,
                    PageSize.A4.width, PageSize.A4.height, rotation
                )
                assertEquals(
                    "page $pageNumber number must be at the displayed bottom edge",
                    36.0, displayY.toDouble(), 0.5
                )
                assertEquals(
                    "page $pageNumber number must be upright",
                    0.0, angleDelta(numbers.single().angleDegrees, rotation.toDouble()), 0.5
                )
            }
        } finally {
            doc.close()
        }
    }

    @Test
    fun numbering_only_selected_pages_leaves_the_rest_untouched() {
        val dir = newDir("pn_selected_")
        val input = rotatedPdf(dir, listOf(0, 90, 0))
        val output = File(dir, "numbered.pdf")

        val result = engine.addPageNumbers(
            input.absolutePath, output.absolutePath,
            PageNumberPosition.BOTTOM_CENTER, 1, 12f, 36f,
            pagesToNumber = listOf(2)
        )
        assertTrue(result.success)
        assertEquals(3, result.pageCount)

        assertTrue(textsOfPage(output.absolutePath, 1).isEmpty())
        assertEquals("1", textsOfPage(output.absolutePath, 2).single().text.trim())
        assertTrue(textsOfPage(output.absolutePath, 3).isEmpty())
    }

    @Test
    fun rotated_page_dimensions_and_boxes_are_preserved() {
        val dir = newDir("pn_boxes_")
        val input = rotatedPdf(dir, listOf(90))
        val output = File(dir, "numbered.pdf")

        assertTrue(
            engine.addPageNumbers(
                input.absolutePath, output.absolutePath,
                PageNumberPosition.BOTTOM_CENTER, 1, 12f, 36f
            ).success
        )

        val doc = PdfDocument(PdfReader(output.absolutePath))
        try {
            val page = doc.getPage(1)
            assertEquals(90, page.rotation)
            assertEquals(PageSize.A4.width, page.pageSize.width, 0.01f)
            assertEquals(PageSize.A4.height, page.pageSize.height, 0.01f)
        } finally {
            doc.close()
        }
    }
}
