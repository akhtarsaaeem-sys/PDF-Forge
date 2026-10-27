package com.pdforge.app.pdf

import com.pdforge.app.ui.screens.resolvePageNumberRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Add Page Numbers settings and the per-edge Crop UI.
 *
 * These exercise the pure planning helpers used by the tools, so the UI and the
 * engine cannot drift apart (the old page-number code numbered by absolute page
 * index, ignoring where the selected range started).
 */
class PageNumbersAndCropTest {

    // ── planPageNumbers (Add Page Numbers engine) ────────────────────────────

    @Test
    fun numbering_all_pages_starts_at_the_configured_number() {
        assertEquals(
            listOf(1 to 1, 2 to 2, 3 to 3),
            planPageNumbers(totalPages = 3, startNumber = 1, pagesToNumber = null)
        )
        assertEquals(
            listOf(1 to 5, 2 to 6, 3 to 7),
            planPageNumbers(totalPages = 3, startNumber = 5, pagesToNumber = null)
        )
    }

    @Test
    fun numbering_a_range_starts_at_the_configured_number_on_the_first_selected_page() {
        // Pages 2..4 numbered starting at 1 -> 1, 2, 3 (not 2, 3, 4).
        assertEquals(
            listOf(2 to 1, 3 to 2, 4 to 3),
            planPageNumbers(totalPages = 5, startNumber = 1, pagesToNumber = listOf(2, 3, 4))
        )
    }

    @Test
    fun numbering_ignores_out_of_range_pages() {
        assertEquals(
            listOf(2 to 1, 3 to 2),
            planPageNumbers(totalPages = 3, startNumber = 1, pagesToNumber = listOf(0, 2, 3, 99))
        )
    }

    @Test
    fun numbering_empty_document_produces_no_labels() {
        assertEquals(emptyList<Pair<Int, Int>>(), planPageNumbers(0, 1, null))
    }

    // ── resolvePageNumberRange (ToolScreen -> Add Page Numbers UI) ───────────

    @Test
    fun blank_range_means_all_pages() {
        assertNull(resolvePageNumberRange(null, null, 10))
    }

    @Test
    fun from_and_to_are_clamped_to_the_document() {
        assertEquals((1..10).toList(), resolvePageNumberRange(1, 99, 10))
        assertEquals((1..10).toList(), resolvePageNumberRange(0, 10, 10))
        assertEquals(listOf(3, 4, 5), resolvePageNumberRange(3, 5, 10))
    }

    @Test
    fun open_ended_ranges_are_supported() {
        assertEquals(listOf(4, 5), resolvePageNumberRange(4, null, 5))
        assertEquals(listOf(1, 2), resolvePageNumberRange(null, 2, 5))
    }

    @Test
    fun inverted_range_is_empty_and_never_crashes() {
        assertEquals(emptyList<Int>(), resolvePageNumberRange(5, 2, 10))
    }

    // ── computeCropBox (per-edge percentage crop) ────────────────────────────

    @Test
    fun crop_percentages_map_to_points_on_each_edge() {
        // 600 x 800 page, 5% on every edge -> 30pt left/right, 40pt top/bottom.
        val box = computeCropBox(
            pageWidth = 600f, pageHeight = 800f,
            leftPercent = 5f, rightPercent = 5f, topPercent = 5f, bottomPercent = 5f
        )!!
        assertEquals(30f, box.x, 0.01f)
        assertEquals(40f, box.y, 0.01f)
        assertEquals(540f, box.width, 0.01f)
        assertEquals(720f, box.height, 0.01f)
    }

    @Test
    fun uneven_edges_are_respected() {
        val box = computeCropBox(
            pageWidth = 100f, pageHeight = 100f,
            leftPercent = 10f, rightPercent = 20f, topPercent = 0f, bottomPercent = 30f
        )!!
        assertEquals(10f, box.x, 0.01f)
        assertEquals(70f, box.width, 0.01f)
        assertEquals(30f, box.y, 0.01f)
        assertEquals(70f, box.height, 0.01f)
    }

    @Test
    fun fully_cropped_page_is_rejected() {
        assertNull(
            computeCropBox(600f, 800f, leftPercent = 60f, rightPercent = 60f, topPercent = 0f, bottomPercent = 0f)
        )
    }

    @Test
    fun zero_crop_keeps_the_full_page() {
        val box = computeCropBox(600f, 800f, 0f, 0f, 0f, 0f)!!
        assertEquals(0f, box.x, 0.01f)
        assertEquals(0f, box.y, 0.01f)
        assertEquals(600f, box.width, 0.01f)
        assertEquals(800f, box.height, 0.01f)
    }
}
