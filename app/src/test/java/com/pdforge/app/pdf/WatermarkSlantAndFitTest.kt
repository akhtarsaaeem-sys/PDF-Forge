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
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.atan2

/**
 * Add Watermark: slant direction and long-text handling.
 *
 * The slant is checked against the produced PDF (the text run's own baseline),
 * never against an internal variable, so the direction the user sees cannot drift
 * from the direction that is saved. The preview is tied to the same convention by
 * [watermarkPreviewAngleDegrees].
 */
class WatermarkSlantAndFitTest {

    private val engine = PdfEngine(null)

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
                    startX = start[0], startY = start[1],
                    endX = end[0], endY = end[1],
                    fontSize = info.fontSize
                )
            )
        }

        override fun getSupportedEvents(): MutableSet<EventType> = mutableSetOf(EventType.RENDER_TEXT)
    }

    private data class RenderedText(
        val text: String,
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val fontSize: Float
    ) {
        val width: Double
            get() = Math.hypot((endX - startX).toDouble(), (endY - startY).toDouble())

        val angleDegrees: Double
            get() = Math.toDegrees(atan2((endY - startY).toDouble(), (endX - startX).toDouble()))

        val centerX: Double get() = (startX + endX) / 2.0
        val centerY: Double get() = (startY + endY) / 2.0
    }

    private fun watermarkRuns(path: String, pageNumber: Int): List<RenderedText> {
        val doc = PdfDocument(PdfReader(path))
        try {
            val listener = CapturingListener()
            PdfCanvasProcessor(listener).processPageContent(doc.getPage(pageNumber))
            return listener.texts.filter { it.text.isNotBlank() }
        } finally {
            doc.close()
        }
    }

    private fun rawToDisplay(x: Double, y: Double, w: Float, h: Float, rotation: Int): Pair<Double, Double> =
        when (rotation) {
            90 -> y to (w - x)
            180 -> (w - x) to (h - y)
            270 -> (h - y) to x
            else -> x to y
        }

    private fun newPdf(dir: File, pages: Int = 1, rotation: Int = 0, size: PageSize = PageSize.A4): File {
        val file = File(dir, "input.pdf")
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        repeat(pages) {
            val page = doc.addNewPage(size)
            if (rotation != 0) page.rotation = rotation
        }
        doc.close()
        return file
    }

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    // ── Angle convention: preview and output must agree ──────────────────────

    @Test
    fun preview_and_output_rotate_in_the_same_visual_direction() {
        // The exporter draws the run at `watermarkDrawAngle` (PDF: CCW positive).
        // The preview rotates by `watermarkPreviewAngleDegrees` on an Android canvas
        // (CW positive). A positive slant must rise to the right in BOTH, so the two
        // angles are exact negatives.
        for (slant in listOf(-90f, -45f, 0f, 30f, 45f, 90f)) {
            assertEquals(
                "slant $slant must be mirrored between PDF and canvas space",
                watermarkDrawAngle(slant, 0), -watermarkPreviewAngleDegrees(slant), 0.001f
            )
        }
    }

    @Test
    fun page_rotation_is_added_to_the_watermark_angle() {
        assertEquals(45f, watermarkDrawAngle(45f, 0), 0.001f)
        assertEquals(135f, watermarkDrawAngle(45f, 90), 0.001f)
        assertEquals(225f, watermarkDrawAngle(45f, 180), 0.001f)
        assertEquals(315f, watermarkDrawAngle(45f, 270), 0.001f)
        assertEquals(45f, watermarkDrawAngle(45f, 360), 0.001f)
    }

    // ── Fitting long text ────────────────────────────────────────────────────

    @Test
    fun usable_width_accounts_for_the_slant_and_the_page_shape() {
        // Unslanted: the text run may use 90% of the page width.
        assertEquals(595f * 0.9f, watermarkUsableWidth(595f, 842f, 0f), 0.5f)
        // Vertical: the run's length is limited by the page HEIGHT instead.
        assertEquals(842f * 0.9f, watermarkUsableWidth(595f, 842f, 90f), 0.5f)
        // 45 degrees: a diagonal run needs 1/cos45 more text width for the same
        // horizontal footprint.
        assertEquals(595f * 0.9f / 0.7071f, watermarkUsableWidth(595f, 842f, 45f), 1f)
    }

    @Test
    fun text_that_already_fits_keeps_its_requested_size() {
        assertEquals(50f, fitWatermarkFontSize(50f, textWidthAtRequestedSize = 100f, usableWidth = 200f), 0.001f)
    }

    @Test
    fun oversized_text_is_scaled_down_proportionally_and_never_truncated() {
        assertEquals(25f, fitWatermarkFontSize(50f, textWidthAtRequestedSize = 400f, usableWidth = 200f), 0.001f)
        // Degenerate inputs must not produce a zero or negative size.
        assertTrue(fitWatermarkFontSize(50f, 1f, 0f) > 0f)
    }

    // ── Production watermark output ──────────────────────────────────────────

    @Test
    fun saved_watermark_slants_in_the_selected_direction() {
        for (slant in listOf(0f, 45f, -45f)) {
            val d = dir("wm_slant_")
            val input = newPdf(d)
            val output = File(d, "watermarked.pdf")

            val result = engine.addTextWatermark(
                input.absolutePath, output.absolutePath, "CONFIDENTIAL",
                opacity = 0.3f, fontSize = 40f, rotation = slant,
                position = WatermarkPosition.CENTER
            )
            assertTrue("slant $slant must succeed: ${result.message}", result.success)

            val run = watermarkRuns(output.absolutePath, 1).single()
            val expected = ((slant % 360f) + 360f) % 360f
            val actual = ((run.angleDegrees.toFloat() % 360f) + 360f) % 360f
            val delta = minOf(
                Math.abs(actual - expected).toDouble(),
                360.0 - Math.abs(actual - expected).toDouble()
            )
            assertEquals("slant $slant must be applied in PDF space", 0.0, delta, 0.5)
        }
    }

    @Test
    fun long_watermark_text_is_reduced_to_fit_instead_of_running_off_the_page() {
        val d = dir("wm_long_")
        val input = newPdf(d)
        val output = File(d, "watermarked.pdf")
        val longText = "CONFIDENTIAL - INTERNAL USE ONLY - DO NOT DISTRIBUTE - DRAFT COPY 2026"
        val requestedSize = 50f

        val result = engine.addTextWatermark(
            input.absolutePath, output.absolutePath, longText,
            opacity = 0.3f, fontSize = requestedSize, rotation = 45f,
            position = WatermarkPosition.CENTER
        )
        assertTrue(result.success)

        val run = watermarkRuns(output.absolutePath, 1).single()
        val usable = watermarkUsableWidth(PageSize.A4.width, PageSize.A4.height, 45f)
        assertTrue(
            "watermark run (${run.width} pt) must fit the usable width ($usable pt)",
            run.width <= usable + 0.5
        )
        assertTrue("the font must actually have been reduced", run.fontSize < requestedSize)

        // Nothing was truncated: the whole text is still in the output.
        val doc = PdfDocument(PdfReader(output.absolutePath))
        val text = try {
            val strategy = SimpleTextExtractionStrategy()
            PdfCanvasProcessor(strategy).processPageContent(doc.getPage(1))
            strategy.resultantText
        } finally {
            doc.close()
        }
        assertTrue("watermark text must not be truncated", text.contains(longText))
    }

    @Test
    fun short_watermark_text_keeps_the_requested_font_size() {
        val d = dir("wm_short_")
        val input = newPdf(d)
        val output = File(d, "watermarked.pdf")

        val result = engine.addTextWatermark(
            input.absolutePath, output.absolutePath, "DRAFT",
            opacity = 0.3f, fontSize = 40f, rotation = 45f,
            position = WatermarkPosition.CENTER
        )
        assertTrue(result.success)
        val run = watermarkRuns(output.absolutePath, 1).single()
        assertEquals(40f, run.fontSize, 0.1f)
    }

    @Test
    fun preview_anchor_and_saved_anchor_are_the_same_point() {
        for (rotation in listOf(0, 90, 270)) {
            val d = dir("wm_anchor_$rotation")
            val input = newPdf(d, rotation = rotation)
            val output = File(d, "watermarked.pdf")

            assertTrue(
                engine.addTextWatermark(
                    input.absolutePath, output.absolutePath, "DRAFT",
                    opacity = 0.3f, fontSize = 30f, rotation = 0f,
                    position = WatermarkPosition.TOP_LEFT
                ).success
            )

            val run = watermarkRuns(output.absolutePath, 1).single()
            val (displayX, displayY) = rawToDisplay(
                run.centerX, run.centerY, PageSize.A4.width, PageSize.A4.height, rotation
            )
            val dispW = if (rotation % 180 == 90) PageSize.A4.height else PageSize.A4.width
            val dispH = if (rotation % 180 == 90) PageSize.A4.width else PageSize.A4.height
            val (expectedX, expectedY) = watermarkAnchor(WatermarkPosition.TOP_LEFT, dispW, dispH)

            assertEquals("rotation $rotation anchor x", expectedX.toDouble(), displayX, 1.0)
            assertEquals("rotation $rotation anchor y", expectedY.toDouble(), displayY, 1.0)

            // The watermark reads at the requested slant on the displayed page.
            val displayAngle = ((run.angleDegrees - rotation) % 360.0 + 360.0) % 360.0
            assertTrue("rotation $rotation must stay upright", displayAngle < 0.5 || displayAngle > 359.5)
        }
    }

    @Test
    fun every_page_gets_the_watermark() {
        val d = dir("wm_pages_")
        val input = newPdf(d, pages = 3)
        val output = File(d, "watermarked.pdf")

        val result = engine.addTextWatermark(
            input.absolutePath, output.absolutePath, "DRAFT",
            opacity = 0.3f, fontSize = 30f, rotation = 45f,
            position = WatermarkPosition.CENTER
        )
        assertTrue(result.success)
        assertEquals(3, result.pageCount)
        for (page in 1..3) {
            assertEquals("page $page must be watermarked", 1, watermarkRuns(output.absolutePath, page).size)
        }
    }

    @Test
    fun blank_watermark_text_is_rejected() {
        val d = dir("wm_blank_")
        val input = newPdf(d)
        val result = engine.addTextWatermark(
            input.absolutePath, File(d, "out.pdf").absolutePath, "   ",
            opacity = 0.3f, fontSize = 30f, rotation = 45f
        )
        assertFalse(result.success)
        assertTrue(result.message!!.isNotBlank())
    }

    @Test
    fun watermark_output_is_a_valid_reopenable_pdf() {
        val d = dir("wm_valid_")
        val input = newPdf(d, pages = 2)
        val output = File(d, "watermarked.pdf")

        val result = engine.addTextWatermark(
            input.absolutePath, output.absolutePath, "DRAFT",
            opacity = 0.3f, fontSize = 30f, rotation = 45f,
            position = WatermarkPosition.BOTTOM_RIGHT
        )
        assertTrue(result.success)
        assertTrue(output.exists() && output.length() > 0)
        val reopened = PdfDocument(PdfReader(output.absolutePath))
        try {
            assertEquals(2, reopened.numberOfPages)
        } finally {
            reopened.close()
        }
        // Sanity: the same font the engine uses can measure the text at all.
        assertTrue(PdfFontFactory.createFont().getWidth("DRAFT", 30f) > 0f)
    }
}
