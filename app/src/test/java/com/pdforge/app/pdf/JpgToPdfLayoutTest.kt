package com.pdforge.app.pdf

import com.pdforge.app.scanner.ScanPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Regression tests for the JPG → PDF white-border bug.
 *
 * The old implementation placed every image on a fixed A4 page with `scaleToFit`
 * and centred it, so a landscape photo was shrunk and left large accidental white
 * bands. Each page must now share the image's aspect ratio (the same layout maths
 * the scanner uses), with no margins, no stretch and no crop.
 *
 * These tests run the REAL production function [imagesToPdf].
 */
class JpgToPdfLayoutTest {

    private fun convert(vararg resources: String): Pair<File, List<Pair<Float, Float>>> {
        val dir = TestSupport.tempDir("jpg_layout")
        val paths = resources.mapIndexed { index, name ->
            val file = File(dir, "image_$index.jpg")
            TestSupport.copyResourceToFile(name, file)
            file.absolutePath
        }
        val output = File(dir, "out.pdf")
        imagesToPdf(paths, output.absolutePath)
        return output to TestSupport.pdfPageSizes(output.absolutePath)
    }

    @Test
    fun portrait_image_produces_a_portrait_page() {
        val (_, sizes) = convert("/fixtures/jpg_portrait.jpg")
        assertEquals(1, sizes.size)
        val (w, h) = sizes.first()
        assertTrue("portrait image must give a portrait page (w=$w h=$h)", h > w)
        assertEquals(842f, maxOf(w, h), 0.5f)
    }

    @Test
    fun landscape_image_produces_a_landscape_page() {
        val (_, sizes) = convert("/fixtures/jpg_landscape.jpg")
        val (w, h) = sizes.first()
        assertTrue("landscape image must give a landscape page (w=$w h=$h)", w > h)
        assertEquals(842f, maxOf(w, h), 0.5f)
    }

    @Test
    fun page_aspect_matches_image_aspect_for_several_ratios() {
        val cases = listOf(
            "/fixtures/jpg_portrait.jpg" to (1200.0 / 1600.0),
            "/fixtures/jpg_landscape.jpg" to (1600.0 / 1200.0),
            "/fixtures/jpg_wide.jpg" to (2400.0 / 1000.0),
            "/fixtures/jpg_square.jpg" to 1.0
        )
        for ((resource, expectedRatio) in cases) {
            val (_, sizes) = convert(resource)
            val (w, h) = sizes.first()
            assertEquals(
                "page aspect must match the image aspect for $resource (no border)",
                expectedRatio,
                w.toDouble() / h,
                0.01
            )
        }
    }

    @Test
    fun image_is_not_stretched() {
        // When the page aspect equals the image aspect the fitted image uses
        // scaleX == scaleY, so the image cannot be distorted.
        val (_, sizes) = convert("/fixtures/jpg_landscape.jpg")
        val (pageW, pageH) = sizes.first()
        val scaleX = pageW / 1600f
        val scaleY = pageH / 1200f
        assertTrue("image must not be stretched", abs(scaleX - scaleY) < 0.001f)
    }

    @Test
    fun page_is_not_a_fixed_a4_page_for_a_non_a4_image() {
        val (_, sizes) = convert("/fixtures/jpg_wide.jpg")
        val (w, h) = sizes.first()
        // A4 portrait is 595 x 842. A wide photo must not be forced onto it: its
        // page is landscape with the long edge at A4's length.
        assertTrue("a wide image must give a landscape page (w=$w h=$h)", w > h)
        assertEquals(842f, w, 0.5f)
        assertTrue("height must not equal A4's 842pt", abs(h - 842f) > 1f)
    }

    @Test
    fun multiple_images_produce_one_page_each_in_order() {
        val (output, sizes) = convert(
            "/fixtures/jpg_portrait.jpg",
            "/fixtures/jpg_landscape.jpg",
            "/fixtures/jpg_square.jpg"
        )
        assertEquals(3, sizes.size)
        assertEquals(3, TestSupport.pdfPageCount(output.absolutePath))
        assertTrue("portrait first", sizes[0].second > sizes[0].first)
        assertTrue("landscape second", sizes[1].first > sizes[1].second)
        assertTrue("square third", abs(sizes[2].first - sizes[2].second) < 0.5f)
    }

    @Test
    fun the_shared_scanner_layout_is_the_source_of_truth() {
        // The JPG path and the scanner must agree; a second incompatible layout
        // would reintroduce the bug in one of them.
        val (w, h) = ScanPageLayout.pageSize(1600, 1200)
        val (_, sizes) = convert("/fixtures/jpg_landscape.jpg")
        assertEquals(w, sizes.first().first, 0.01f)
        assertEquals(h, sizes.first().second, 0.01f)
    }
}
