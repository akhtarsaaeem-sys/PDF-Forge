package com.pdforge.app.pdf

import com.pdforge.app.scanner.ScanPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for the Scan to PDF white-border bug.
 *
 * The scanner placed every photo on a fixed A4 portrait page with default margins,
 * so a landscape photo was shrunk and vertically centred, leaving large white
 * bands. The page must now share the image's aspect ratio so the photo fills it.
 */
class ScanPageLayoutTest {

    private fun ratio(w: Int, h: Int) = w.toDouble() / h

    @Test
    fun a_landscape_photo_gets_a_landscape_page_with_no_border() {
        val (w, h) = ScanPageLayout.pageSize(2000, 1500)
        // Long edge stays at A4's length; page aspect equals image aspect.
        assertEquals(842f, maxOf(w, h), 0.01f)
        assertTrue("page must be landscape", w > h)
        assertEquals(
            "page aspect ratio must match the image so no white border remains",
            ratio(2000, 1500),
            w.toDouble() / h,
            0.001
        )
    }

    @Test
    fun a_portrait_photo_gets_a_portrait_page() {
        val (w, h) = ScanPageLayout.pageSize(1500, 2000)
        assertEquals(842f, maxOf(w, h), 0.01f)
        assertTrue("page must be portrait", h > w)
        assertEquals(ratio(1500, 2000), w.toDouble() / h, 0.001)
    }

    @Test
    fun the_image_is_not_stretched() {
        // If the page aspect matches the image aspect, fitting the image fills the
        // page exactly (scaleX == scaleY) — no distortion.
        val imageW = 4032
        val imageH = 3024
        val (pageW, pageH) = ScanPageLayout.pageSize(imageW, imageH)
        val scaleX = pageW / imageW
        val scaleY = pageH / imageH
        assertTrue("image must not be stretched", abs(scaleX - scaleY) < 0.0001f)
    }

    @Test
    fun degenerate_image_sizes_never_produce_a_zero_area_page() {
        val (w, h) = ScanPageLayout.pageSize(0, 0)
        assertTrue(w > 0f && h > 0f)
        assertEquals(ScanPageLayout.DEFAULT_LONG_EDGE_PT, w, 0.01f)
        assertEquals(ScanPageLayout.DEFAULT_LONG_EDGE_PT, h, 0.01f)
    }

    @Test
    fun a_square_photo_gets_a_square_page() {
        val (w, h) = ScanPageLayout.pageSize(1000, 1000)
        assertEquals(w, h, 0.001f)
        assertEquals(842f, w, 0.01f)
    }
}
