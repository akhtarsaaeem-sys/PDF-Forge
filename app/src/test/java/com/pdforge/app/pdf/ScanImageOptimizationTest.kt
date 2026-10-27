package com.pdforge.app.pdf

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Image
import com.pdforge.app.scanner.ScanImageOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Scan-to-PDF sizing tests.
 *
 * No camera is needed. The resize/subsample decisions are pure functions, and the
 * file-size claim is measured by building real PDFs through iText — the same
 * embedding path the scanner uses.
 *
 * Fixtures (app/src/test/resources):
 *  - scan_sample_camera.jpg — a raw 4000x3000 (12 MP) camera frame, i.e. what the
 *    capture step used to hand to the PDF writer untouched.
 *  - scan_sample_page.jpg   — the same document optimized to 2000x1500 at JPEG 82,
 *    i.e. what the pipeline now stores and embeds.
 */
class ScanImageOptimizationTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream(name)
        assertNotNull("missing test fixture $name", stream)
        return stream!!.use { it.readBytes() }
    }

    // ── Pure sizing logic ───────────────────────────────────────────────────

    @Test
    fun full_resolution_photo_is_subsampled_to_document_resolution() {
        // A 12 MP camera frame must never be decoded at full size for a 2000 px target.
        assertEquals(2, ScanImageOptimizer.computeInSampleSize(4000, 3000, 2000))
        val (w, h) = ScanImageOptimizer.scaledDimensions(4000 / 2, 3000 / 2, 2000)
        assertEquals(2000, w)
        assertEquals(1500, h)
    }

    @Test
    fun a_50mp_frame_gets_a_larger_subsample_factor() {
        assertEquals(4, ScanImageOptimizer.computeInSampleSize(8160, 6120, 2000))
    }

    @Test
    fun images_smaller_than_the_target_are_left_alone() {
        assertEquals(1, ScanImageOptimizer.computeInSampleSize(800, 600, 2000))
        assertEquals(800 to 600, ScanImageOptimizer.scaledDimensions(800, 600, 2000))
    }

    @Test
    fun scaling_preserves_aspect_ratio_approximately() {
        val (w, h) = ScanImageOptimizer.scaledDimensions(4032, 3024, 2000)
        assertEquals(2000, w)
        assertTrue("aspect ratio must be preserved", abs(w.toDouble() / h - 4032.0 / 3024.0) < 0.01)
    }

    @Test
    fun degenerate_sizes_never_produce_zero_dimensions() {
        assertEquals(1 to 1, ScanImageOptimizer.scaledDimensions(1, 1000, 1))
        assertEquals(1, ScanImageOptimizer.computeInSampleSize(0, 0, 2000))
        assertEquals(0 to 0, ScanImageOptimizer.scaledDimensions(0, 0, 2000))
    }

    @Test
    fun downsampling_removes_three_quarters_of_the_pixels() {
        // 4000x3000 -> 2000x1500 is exactly a 4x pixel reduction, which is what
        // makes a scan page a few hundred KB instead of many MB.
        val sourcePixels = 4000L * 3000L
        val (w, h) = ScanImageOptimizer.scaledDimensions(4000, 3000, ScanImageOptimizer.DOCUMENT_LONG_EDGE_PX)
        assertEquals(sourcePixels / 4, w.toLong() * h.toLong())
    }

    @Test
    fun document_resolution_and_quality_stay_in_a_sane_document_range() {
        assertTrue(
            "document resolution must be document-scale, not camera-scale",
            ScanImageOptimizer.DOCUMENT_LONG_EDGE_PX in 1500..2500
        )
        assertTrue(
            "quality must stay readable while dropping the size",
            ScanImageOptimizer.JPEG_QUALITY in 70..90
        )
        assertTrue(
            "thumbnails must be small",
            ScanImageOptimizer.PREVIEW_LONG_EDGE_PX <= 400
        )
    }

    // ── Real embedding size ─────────────────────────────────────────────────

    private fun buildPdf(path: String, pageJpegs: List<ByteArray>): Long {
        return buildPdfFromImages(path, pageJpegs.map { ImageDataFactory.create(it) })
    }

    private fun buildPdfFromImages(path: String, images: List<com.itextpdf.io.image.ImageData>): Long {
        val pdfDoc = PdfDocument(PdfWriter(path))
        val document = Document(pdfDoc, PageSize.A4)
        val maxWidth = PageSize.A4.width - document.leftMargin - document.rightMargin
        val maxHeight = PageSize.A4.height - document.topMargin - document.bottomMargin
        images.forEachIndexed { index, imageData ->
            val image = Image(imageData)
            image.scaleToFit(maxWidth, maxHeight)
            document.add(image)
            if (index < images.lastIndex) document.add(AreaBreak())
        }
        document.close()
        return File(path).length()
    }

    @Test
    fun bytes_per_page_comes_from_the_optimized_image_not_a_raster() {
        // Per-page footprint of the pipeline. The old pipeline measured ~19 MB per
        // page on the device (114 MB for a 6-page scan) because the full-resolution
        // camera bitmap was embedded with no useful compression.
        val dir = Files.createTempDirectory("scan_per_page").toFile()
        val optimizedPage = resourceBytes("/scan_sample_page.jpg")
        val pdf = File(dir, "per_page.pdf")
        val size = buildPdf(pdf.absolutePath, listOf(optimizedPage))

        println("SCAN SIZE: optimized bytes per page = ${size / 1024} KB (${size} bytes)")
        assertEquals(1, pageCount(pdf.absolutePath))
        assertTrue("optimized page must stay well under 1 MB, was $size bytes", size < 1024L * 1024)
    }

    private fun pageCount(path: String): Int {
        val doc = PdfDocument(PdfReader(path))
        return try {
            doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    @Test
    fun a_six_page_optimized_scan_is_far_smaller_than_full_resolution() {
        val dir = Files.createTempDirectory("scan_size").toFile()
        val cameraFrame = resourceBytes("/scan_sample_camera.jpg")   // 12 MP raw capture
        val optimizedPage = resourceBytes("/scan_sample_page.jpg")   // 2000 px, JPEG 82

        val before = buildPdf(File(dir, "before_6_pages.pdf").absolutePath, List(6) { cameraFrame })
        val after = buildPdf(File(dir, "after_6_pages.pdf").absolutePath, List(6) { optimizedPage })

        println("SCAN SIZE: 6 pages, full resolution = ${before / 1024 / 1024} MB (${before} bytes)")
        println("SCAN SIZE: 6 pages, optimized       = ${after / 1024} KB (${after} bytes)")

        assertEquals(6, pageCount(File(dir, "before_6_pages.pdf").absolutePath))
        assertEquals(6, pageCount(File(dir, "after_6_pages.pdf").absolutePath))
        assertTrue("optimized scan must not be empty", after > 0)
        assertTrue(
            "optimized 6-page scan ($after bytes) must be far smaller than full resolution ($before bytes)",
            after * 3 < before
        )
    }

    @Test
    fun optimized_pages_are_embedded_as_jpeg_without_re_rasterizing() {
        val dir = Files.createTempDirectory("scan_embed").toFile()
        val optimizedPage = resourceBytes("/scan_sample_page.jpg")
        val pdf = File(dir, "embed.pdf")
        val size = buildPdf(pdf.absolutePath, listOf(optimizedPage))

        assertEquals(1, pageCount(pdf.absolutePath))
        // DCTDecode == the JPEG was embedded as-is. If the pipeline rasterized the
        // bitmap instead, the image would be stored losslessly and balloon.
        val raw = pdf.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("page image must be embedded as JPEG (DCTDecode)", raw.contains("/DCTDecode"))
        assertTrue(
            "PDF ($size) must stay close to the embedded JPEG (${optimizedPage.size} bytes)",
            size < optimizedPage.size * 2L
        )
    }

    @Test
    fun jpeg_fixture_dimensions_match_the_optimizer_target() {
        // Guards the fixture against silent replacement: the "optimized" fixture must
        // really be the resolution the optimizer produces for a 12 MP capture.
        val camera = ImageDataFactory.create(resourceBytes("/scan_sample_camera.jpg"))
        val page = ImageDataFactory.create(resourceBytes("/scan_sample_page.jpg"))

        val cameraWidth = camera.width.toInt()
        val cameraHeight = camera.height.toInt()
        assertEquals(4000, cameraWidth)
        assertEquals(3000, cameraHeight)
        val (targetW, targetH) = ScanImageOptimizer.scaledDimensions(
            cameraWidth, cameraHeight, ScanImageOptimizer.DOCUMENT_LONG_EDGE_PX
        )
        assertEquals(targetW, page.width.toInt())
        assertEquals(targetH, page.height.toInt())
    }

    @Test
    fun a_single_optimized_page_stays_document_sized() {
        val dir = Files.createTempDirectory("scan_single").toFile()
        val optimizedPage = resourceBytes("/scan_sample_page.jpg")
        val pdf = File(dir, "one_page.pdf")
        val size = buildPdf(pdf.absolutePath, listOf(optimizedPage))

        assertEquals(1, pageCount(pdf.absolutePath))
        assertTrue("optimized page should be well under 3 MB, was $size", size < 3L * 1024 * 1024)
    }
}
