package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Crop PDF — manual (visual) cropping.
 *
 * Covers the pure crop-rectangle model used by the drag gestures, the
 * normalized → raw coordinate conversion (including rotated pages and pages with
 * different sizes), per-page/all-page application, invalid rectangles and output
 * validity.
 *
 * What these tests do NOT cover: the actual touch gestures on a device. They
 * verify the maths and anchoring a gesture feeds into; the finger-on-glass feel
 * still needs a physical-device check.
 */
class CropManualTest {

    private val engine = PdfEngine(null)

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** Builds a PDF with one page per entry: (size, rotation). */
    private fun pdf(dir: File, pages: List<Pair<PageSize, Int>>): File {
        val file = File(dir, "input.pdf")
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        for ((size, rotation) in pages) {
            val page = doc.addNewPage(size)
            if (rotation != 0) page.rotation = rotation
        }
        doc.close()
        return file
    }

    private data class RawBox(val x: Float, val y: Float, val width: Float, val height: Float)

    private fun cropBoxOf(path: String, pageNumber: Int): RawBox {
        val doc = PdfDocument(PdfReader(path))
        try {
            val box = doc.getPage(pageNumber).cropBox
            return RawBox(box.x, box.y, box.width, box.height)
        } finally {
            doc.close()
        }
    }

    /**
     * Independently derived expected raw box for a symmetric fraction crop of the
     * page's DISPLAYED area, written from the documented display mapping rather
     * than by calling the production helper.
     */
    private fun expectedRawBox(pageWidth: Float, pageHeight: Float, rotation: Int, fraction: Float): RawBox {
        val dispW = if (rotation % 180 == 90) pageHeight else pageWidth
        val dispH = if (rotation % 180 == 90) pageWidth else pageHeight
        val x0 = fraction * dispW
        val x1 = (1f - fraction) * dispW
        val y0 = fraction * dispH
        val y1 = (1f - fraction) * dispH

        fun toRaw(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
            90 -> (pageWidth - y) to x
            180 -> (pageWidth - x) to (pageHeight - y)
            270 -> y to (pageHeight - x)
            else -> x to y
        }

        val (ax, ay) = toRaw(x0, y0)
        val (bx, by) = toRaw(x1, y1)
        return RawBox(
            x = minOf(ax, bx),
            y = minOf(ay, by),
            width = abs(bx - ax),
            height = abs(by - ay)
        )
    }

    private fun assertBoxEquals(expected: RawBox, actual: RawBox, tolerance: Float, message: String) {
        assertEquals("$message x", expected.x, actual.x, tolerance)
        assertEquals("$message y", expected.y, actual.y, tolerance)
        assertEquals("$message width", expected.width, actual.width, tolerance)
        assertEquals("$message height", expected.height, actual.height, tolerance)
    }

    // ── Crop rectangle model (drag behaviour) ────────────────────────────────

    @Test
    fun default_crop_is_valid_and_keeps_most_of_the_page() {
        val crop = CropFractions()
        assertTrue(crop.isValid)
        assertEquals(0.9f, crop.keptWidth, 0.001f)
        assertEquals(0.9f, crop.keptHeight, 0.001f)
    }

    @Test
    fun moving_the_rectangle_keeps_its_size_and_stays_on_the_page() {
        val crop = CropFractions(left = 0.2f, top = 0.2f, right = 0.3f, bottom = 0.3f)
        val moved = crop.moved(0.1f, 0.15f)
        assertEquals(0.5f, moved.keptWidth, 0.001f)
        assertEquals(0.5f, moved.keptHeight, 0.001f)
        assertEquals(0.3f, moved.left, 0.001f)
        assertEquals(0.35f, moved.top, 0.001f)

        // Dragging far past the edge clamps instead of leaving the page.
        val clamped = moved.moved(5f, 5f)
        assertEquals(1f - clamped.keptWidth, clamped.left, 0.001f)
        assertEquals(1f - clamped.keptHeight, clamped.top, 0.001f)
        assertTrue(clamped.left >= 0f && clamped.right >= 0f)
        assertTrue(clamped.top >= 0f && clamped.bottom >= 0f)
    }

    @Test
    fun dragging_a_corner_resizes_that_corner_only() {
        val crop = CropFractions(0.2f, 0.2f, 0.2f, 0.2f)
        val resized = crop.adjusted(CropHandle.TOP_LEFT, dx = 0.1f, dy = 0.05f)
        assertEquals(0.3f, resized.left, 0.001f)
        assertEquals(0.25f, resized.top, 0.001f)
        assertEquals(0.2f, resized.right, 0.001f)
        assertEquals(0.2f, resized.bottom, 0.001f)

        // Dragging the bottom-right handle OUTWARD grows the kept area (less trim).
        val grown = crop.adjusted(CropHandle.BOTTOM_RIGHT, dx = 0.1f, dy = 0.05f)
        assertEquals(0.1f, grown.right, 0.001f)
        assertEquals(0.15f, grown.bottom, 0.001f)
        assertEquals(0.2f, grown.left, 0.001f)

        // Dragging it INWARD trims more from that corner.
        val shrunk = crop.adjusted(CropHandle.BOTTOM_RIGHT, dx = -0.1f, dy = -0.05f)
        assertEquals(0.3f, shrunk.right, 0.001f)
        assertEquals(0.25f, shrunk.bottom, 0.001f)
        assertEquals(0.2f, shrunk.left, 0.001f)
    }

    @Test
    fun a_drag_can_never_collapse_the_crop_rectangle() {
        // Dragging the top-left corner far past the bottom-right must leave a
        // usable region (never a zero-sized page).
        val crop = CropFractions(0f, 0f, 0f, 0f)
        val collapsed = crop.adjusted(CropHandle.TOP_LEFT, dx = 5f, dy = 5f)
        assertTrue(collapsed.isValid)
        assertTrue(collapsed.keptWidth >= CropFractions.MIN_KEPT_FRACTION - 0.001f)
        assertTrue(collapsed.keptHeight >= CropFractions.MIN_KEPT_FRACTION - 0.001f)
    }

    @Test
    fun a_fully_trimmed_page_has_no_crop_box() {
        assertNull(computeCropBoxNormalized(600f, 800f, 0, 0.6f, 0f, 0.6f, 0f))
        assertNull(computeCropBoxNormalized(600f, 800f, 0, 0f, 0.5f, 0f, 0.5f))
    }

    // ── Normalized → raw coordinate conversion ───────────────────────────────

    @Test
    fun crop_fractions_convert_to_the_correct_raw_box_for_every_rotation() {
        val width = PageSize.A4.width
        val height = PageSize.A4.height
        for (rotation in listOf(0, 90, 180, 270)) {
            val box = computeCropBoxNormalized(width, height, rotation, 0.1f, 0.1f, 0.1f, 0.1f)
            assertNotNull("rotation $rotation must produce a box", box)
            val expected = expectedRawBox(width, height, rotation, 0.1f)
            assertBoxEquals(expected, RawBox(box!!.x, box.y, box.width, box.height), 0.5f, "rotation $rotation")
        }
    }

    @Test
    fun one_crop_adapts_to_pages_of_different_sizes() {
        val d = dir("crop_sizes_")
        val input = pdf(
            d,
            listOf(PageSize.A4 to 0, PageSize.A5 to 0)
        )
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, 0.25f, 0.25f, 0.25f, 0.25f
        )
        assertTrue(result.success)
        assertEquals(2, result.pageCount)

        assertBoxEquals(
            expectedRawBox(PageSize.A4.width, PageSize.A4.height, 0, 0.25f),
            cropBoxOf(output.absolutePath, 1), 0.5f, "A4 page"
        )
        assertBoxEquals(
            expectedRawBox(PageSize.A5.width, PageSize.A5.height, 0, 0.25f),
            cropBoxOf(output.absolutePath, 2), 0.5f, "A5 page"
        )
    }

    // ── Engine: page targeting ───────────────────────────────────────────────

    @Test
    fun cropping_all_pages_leaves_every_page_cropped() {
        val d = dir("crop_all_")
        val input = pdf(d, listOf(PageSize.A4 to 0, PageSize.A4 to 0, PageSize.A4 to 0))
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, 0.1f, 0.1f, 0.1f, 0.1f
        )
        assertTrue(result.success)
        assertEquals(3, result.pageCount)

        val expected = expectedRawBox(PageSize.A4.width, PageSize.A4.height, 0, 0.1f)
        for (page in 1..3) {
            assertBoxEquals(expected, cropBoxOf(output.absolutePath, page), 0.5f, "page $page")
        }
    }

    @Test
    fun cropping_only_selected_pages_leaves_the_others_untouched() {
        val d = dir("crop_selected_")
        val input = pdf(d, listOf(PageSize.A4 to 0, PageSize.A4 to 0, PageSize.A4 to 0))
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, 0.2f, 0.2f, 0.2f, 0.2f,
            pages = listOf(2)
        )
        assertTrue(result.success)
        assertEquals(3, result.pageCount)

        // Untouched pages keep the full page box.
        assertBoxEquals(
            RawBox(0f, 0f, PageSize.A4.width, PageSize.A4.height),
            cropBoxOf(output.absolutePath, 1), 0.5f, "page 1 must be untouched"
        )
        assertBoxEquals(
            RawBox(0f, 0f, PageSize.A4.width, PageSize.A4.height),
            cropBoxOf(output.absolutePath, 3), 0.5f, "page 3 must be untouched"
        )
        assertBoxEquals(
            expectedRawBox(PageSize.A4.width, PageSize.A4.height, 0, 0.2f),
            cropBoxOf(output.absolutePath, 2), 0.5f, "page 2 must be cropped"
        )
    }

    @Test
    fun rotated_pages_are_cropped_on_the_side_the_user_selected() {
        val d = dir("crop_rotated_")
        val width = PageSize.A4.width
        val height = PageSize.A4.height
        for (rotation in listOf(90, 180, 270)) {
            val input = pdf(d, listOf(PageSize.A4 to rotation))
            val output = File(d, "cropped_$rotation.pdf")

            val result = engine.cropPdfNormalized(
                input.absolutePath, output.absolutePath, 0.1f, 0.1f, 0.1f, 0.1f
            )
            assertTrue("rotation $rotation must succeed: ${result.message}", result.success)

            assertBoxEquals(
                expectedRawBox(width, height, rotation, 0.1f),
                cropBoxOf(output.absolutePath, 1), 0.5f, "rotation $rotation"
            )

            // The page's rotation is preserved so the viewer still shows what the
            // user saw while cropping.
            val doc = PdfDocument(PdfReader(output.absolutePath))
            try {
                assertEquals(rotation, doc.getPage(1).rotation)
            } finally {
                doc.close()
            }
        }
    }

    @Test
    fun mixed_rotated_and_unrotated_pages_all_get_their_own_correct_crop() {
        val d = dir("crop_mixed_")
        val rotations = listOf(0, 90, 180, 270)
        val input = pdf(d, rotations.map { PageSize.A4 to it })
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, 0.15f, 0.15f, 0.15f, 0.15f
        )
        assertTrue(result.success)
        assertEquals(4, result.pageCount)

        rotations.forEachIndexed { index, rotation ->
            assertBoxEquals(
                expectedRawBox(PageSize.A4.width, PageSize.A4.height, rotation, 0.15f),
                cropBoxOf(output.absolutePath, index + 1), 0.5f, "page ${index + 1} rotation $rotation"
            )
        }
    }

    @Test
    fun cropped_output_is_valid_and_page_count_and_order_are_preserved() {
        val d = dir("crop_valid_")
        val input = pdf(d, listOf(PageSize.A4 to 0, PageSize.A5 to 0, PageSize.A4 to 90))
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, 0.05f, 0.05f, 0.05f, 0.05f
        )
        assertTrue(result.success)
        assertEquals(3, result.pageCount)
        assertTrue(OutputValidator.validatePdf(output))

        val doc = PdfDocument(PdfReader(output.absolutePath))
        try {
            assertEquals(3, doc.numberOfPages)
            // Order preserved: A4, A5, A4(rotated). The page box is now the cropped
            // region (5% off each edge), so the widths shrink accordingly.
            assertEquals(PageSize.A4.width * 0.9f, doc.getPage(1).pageSize.width, 0.5f)
            assertEquals(PageSize.A5.width * 0.9f, doc.getPage(2).pageSize.width, 0.5f)
            assertEquals(PageSize.A4.width * 0.9f, doc.getPage(3).pageSize.width, 0.5f)
            assertEquals(90, doc.getPage(3).rotation)
        } finally {
            doc.close()
        }
    }

    @Test
    fun an_empty_crop_rectangle_is_rejected_without_leaving_a_file_behind() {
        val d = dir("crop_invalid_")
        val input = pdf(d, listOf(PageSize.A4 to 0))
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, 0.6f, 0f, 0.6f, 0f
        )
        assertFalse("a zero-sized crop must fail", result.success)
        assertTrue(result.message!!.isNotBlank())
        assertFalse("no partial output may be published", output.exists())
    }

    @Test
    fun a_crop_larger_than_the_page_keeps_the_full_page_instead_of_failing() {
        // Out-of-range fractions are clamped, and a purely negative "crop" leaves
        // the page as it was rather than creating an oversized box.
        val d = dir("crop_clamped_")
        val input = pdf(d, listOf(PageSize.A4 to 0))
        val output = File(d, "cropped.pdf")

        val result = engine.cropPdfNormalized(
            input.absolutePath, output.absolutePath, -0.5f, -0.5f, -0.5f, -0.5f
        )
        assertTrue(result.success)
        assertBoxEquals(
            RawBox(0f, 0f, PageSize.A4.width, PageSize.A4.height),
            cropBoxOf(output.absolutePath, 1), 0.5f, "clamped crop"
        )
    }
}
