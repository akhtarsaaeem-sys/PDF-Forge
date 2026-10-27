package com.pdforge.app.pdf

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the Compress PDF bug: all three presets performed the
 * same rewrite and never touched embedded image streams, so image-heavy files
 * barely shrank and "High Quality" appeared to do nothing.
 *
 * These run the REAL [compressPdfFile] engine with a deterministic fake
 * [ImageRecompressor], so the preset wiring, image recompression and the
 * "never report false savings" behaviour are all exercised on the production
 * code path (only the Android bitmap codec is replaced).
 */
class PdfCompressionTest {

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream(name)
        assertNotNull("missing test fixture $name", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun createImagePdf(path: String, jpegBytes: ByteArray): File {
        val pdfDoc = PdfDocument(PdfWriter(path))
        val page = pdfDoc.addNewPage(PageSize.A4)
        PdfCanvas(page).addImageFittedIntoRectangle(
            ImageDataFactory.create(jpegBytes),
            Rectangle(0f, 0f, PageSize.A4.width, PageSize.A4.height),
            false
        )
        pdfDoc.close()
        return File(path)
    }

    private fun createTextPdf(path: String, pages: Int): File {
        val doc = PdfDocument(PdfWriter(path))
        val document = Document(doc, PageSize.A4)
        repeat(pages) { i ->
            document.add(Paragraph("TEXT_ONLY_PAGE_${i + 1}"))
            if (i < pages - 1) document.add(com.itextpdf.layout.element.AreaBreak())
        }
        document.close()
        return File(path)
    }

    private fun pageCount(path: String): Int {
        val doc = PdfDocument(PdfReader(path))
        return try {
            doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    /** Records how the engine called it and returns a fixed replacement image. */
    private class FakeRecompressor(
        private val replacement: ByteArray,
        private val width: Int,
        private val height: Int
    ) : ImageRecompressor {
        var calls = 0
        var lastQuality = -1
        var lastMaxLongEdge = -1

        override fun recompress(jpegBytes: ByteArray, quality: Int, maxLongEdgePx: Int): RecompressedImage? {
            calls++
            lastQuality = quality
            lastMaxLongEdge = maxLongEdgePx
            return RecompressedImage(replacement, width, height)
        }
    }

    // ── The presets must be meaningfully different ──────────────────────────

    @Test
    fun the_three_presets_perform_different_operations() {
        val high = compressionPresetFor(CompressQuality.HIGH_QUALITY)
        val recommended = compressionPresetFor(CompressQuality.RECOMMENDED)
        val smallest = compressionPresetFor(CompressQuality.SMALLER_FILE)

        // High Quality preserves images; the other two must actually recompress.
        assertEquals(null, high.image)
        assertNotNull(recommended.image)
        assertNotNull(smallest.image)

        // Smallest must be more aggressive than Recommended, not identical.
        assertTrue(
            "Smallest must use lower JPEG quality than Recommended",
            smallest.image!!.jpegQuality < recommended.image!!.jpegQuality
        )
        assertTrue(
            "Smallest must downsample more than Recommended",
            smallest.image!!.maxLongEdgePx < recommended.image!!.maxLongEdgePx
        )
        assertTrue("Smallest should flatten forms", smallest.flattenForms)
        assertFalse(recommended.flattenForms)
    }

    @Test
    fun high_quality_does_not_recompress_images() {
        val dir = Files.createTempDirectory("compress_high").toFile()
        val camera = resourceBytes("/scan_sample_camera.jpg")
        val input = createImagePdf(File(dir, "in.pdf").absolutePath, camera)
        val output = File(dir, "out.pdf")

        val fake = FakeRecompressor(resourceBytes("/scan_sample_page.jpg"), 2000, 1500)
        val outcome = compressPdfFile(input.absolutePath, output.absolutePath, CompressQuality.HIGH_QUALITY, fake)

        assertEquals("High Quality must not recompress images", 0, fake.calls)
        assertEquals(0, outcome.imagesRecompressed)
        assertEquals(1, outcome.pageCount)
        // The file must always be valid and no larger than the original.
        assertTrue(outcome.outputSize in 1..input.length())
        assertEquals(1, pageCount(output.absolutePath))
    }

    @Test
    fun recommended_recompresses_the_embedded_image_and_reduces_size() {
        val dir = Files.createTempDirectory("compress_recommended").toFile()
        val camera = resourceBytes("/scan_sample_camera.jpg")
        val page = resourceBytes("/scan_sample_page.jpg")
        val input = createImagePdf(File(dir, "in.pdf").absolutePath, camera)
        val output = File(dir, "out.pdf")

        val fake = FakeRecompressor(page, 2000, 1500)
        val outcome = compressPdfFile(input.absolutePath, output.absolutePath, CompressQuality.RECOMMENDED, fake)

        assertTrue("image recompression must be invoked", fake.calls >= 1)
        assertTrue("engine must count the recompressed image", outcome.imagesRecompressed >= 1)
        assertFalse("a real reduction must not be reported as kept-original", outcome.keptOriginal)
        assertTrue("output must be smaller than the input", outcome.outputSize < outcome.originalSize)

        // Selected preset must reach the encoder.
        val expected = compressionPresetFor(CompressQuality.RECOMMENDED).image!!
        assertEquals(expected.jpegQuality, fake.lastQuality)
        assertEquals(expected.maxLongEdgePx, fake.lastMaxLongEdge)

        // Output stays valid and keeps its page count.
        assertEquals(1, outcome.pageCount)
        assertEquals(1, pageCount(output.absolutePath))
    }

    @Test
    fun smallest_reaches_the_engine_with_stronger_settings() {
        val dir = Files.createTempDirectory("compress_smallest").toFile()
        val input = createImagePdf(File(dir, "in.pdf").absolutePath, resourceBytes("/scan_sample_camera.jpg"))
        val output = File(dir, "out.pdf")

        val fake = FakeRecompressor(resourceBytes("/scan_sample_page.jpg"), 2000, 1500)
        compressPdfFile(input.absolutePath, output.absolutePath, CompressQuality.SMALLER_FILE, fake)

        val expected = compressionPresetFor(CompressQuality.SMALLER_FILE).image!!
        assertEquals(expected.jpegQuality, fake.lastQuality)
        assertEquals(expected.maxLongEdgePx, fake.lastMaxLongEdge)
        assertTrue("Smallest quality must be lower than Recommended",
            fake.lastQuality < compressionPresetFor(CompressQuality.RECOMMENDED).image!!.jpegQuality)
    }

    @Test
    fun a_replacement_that_is_not_smaller_is_not_used() {
        val dir = Files.createTempDirectory("compress_guard").toFile()
        val small = resourceBytes("/scan_sample_page.jpg")
        // Input already tiny; the "recompressed" bytes are the big camera frame.
        val input = createImagePdf(File(dir, "in.pdf").absolutePath, small)
        val output = File(dir, "out.pdf")

        val fake = FakeRecompressor(resourceBytes("/scan_sample_camera.jpg"), 4000, 3000)
        val outcome = compressPdfFile(input.absolutePath, output.absolutePath, CompressQuality.RECOMMENDED, fake)

        assertEquals("a larger image must never replace the original", 0, outcome.imagesRecompressed)
        assertEquals(1, pageCount(output.absolutePath))
    }

    // ── Never report false savings / never publish a larger file ────────────

    @Test
    fun the_published_file_is_never_larger_than_the_input() {
        val dir = Files.createTempDirectory("compress_no_larger").toFile()
        val input = createTextPdf(File(dir, "text.pdf").absolutePath, 3)
        val output = File(dir, "out.pdf")

        val outcome = compressPdfFile(
            input.absolutePath, output.absolutePath, CompressQuality.RECOMMENDED,
            FakeRecompressor(ByteArray(0), 1, 1) // no images in a text-only PDF anyway
        )

        assertTrue("output must never exceed the original", outcome.outputSize <= outcome.originalSize)
        assertTrue("output size must be reported accurately", outcome.outputSize == output.length())
        if (outcome.keptOriginal) {
            assertEquals("a kept-original result must report the original size", outcome.originalSize, outcome.outputSize)
        }
        assertEquals(3, outcome.pageCount)
        assertEquals(3, pageCount(output.absolutePath))
    }

    @Test
    fun compressed_output_is_valid_and_preserves_page_count_with_images() {
        val dir = Files.createTempDirectory("compress_valid").toFile()
        val input = createImagePdf(File(dir, "in.pdf").absolutePath, resourceBytes("/scan_sample_camera.jpg"))
        val output = File(dir, "out.pdf")

        val outcome = compressPdfFile(
            input.absolutePath, output.absolutePath, CompressQuality.SMALLER_FILE,
            FakeRecompressor(resourceBytes("/scan_sample_page.jpg"), 2000, 1500)
        )

        assertTrue(File(outputPathSafe(output)).length() > 0)
        val doc = PdfDocument(PdfReader(output.absolutePath))
        try {
            assertEquals(1, doc.numberOfPages)
        } finally {
            doc.close()
        }
        assertEquals(1, outcome.pageCount)
    }

    private fun outputPathSafe(file: File): String = file.absolutePath
}
