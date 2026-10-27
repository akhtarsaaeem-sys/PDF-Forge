package com.pdforge.app.pdf

import com.pdforge.app.storage.OutputValidator
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Regression tests for "PDF → PowerPoint crashes the app".
 *
 * The old implementation used Apache POI XSLF (`XMLSlideShow`/`XSLFPictureShape`),
 * whose geometry references `java.awt.geom` — absent on Android. Depending on the
 * device/PDF this surfaced as an `Error`, which `catch (Exception)` could not
 * catch, so the app crashed (and large pages could also OOM).
 *
 * These tests exercise the real writer and the real crash boundary:
 *  - [writeSlideImagesToPptx] produces a valid PPTX that POI can reopen,
 *  - [buildPptxFromRenderedPages] keeps one slide per page,
 *  - [pptxConversionResult] converts even an `OutOfMemoryError` into a controlled
 *    failure result (no crash, no partial file).
 */
class PdfToPptxWriterTest {

    private fun jpeg(resource: String): ByteArray = TestSupport.resourceBytes(resource)

    private fun slideSizeFromPackage(file: File): Pair<Long, Long> {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("ppt/presentation.xml") ?: error("no presentation part")
            val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            val match = Regex("""<p:sldSz cx="(\d+)" cy="(\d+)"""")
                .find(xml) ?: error("no sldSz")
            return match.groupValues[1].toLong() to match.groupValues[2].toLong()
        }
    }

    @Test
    fun writer_produces_a_valid_pptx_with_one_slide_per_image() {
        val dir = TestSupport.tempDir("pdf_pptx_writer")
        val output = File(dir, "out.pptx")
        val images = listOf(
            jpeg("/fixtures/jpg_landscape.jpg"),
            jpeg("/fixtures/jpg_portrait.jpg"),
            jpeg("/fixtures/jpg_square.jpg")
        )

        val slides = writeSlideImagesToPptx(images, 842f, 595f, "jpg", output.absolutePath)

        assertEquals(3, slides)
        assertTrue("output must exist and be non-empty", output.exists() && output.length() > 0)
        assertTrue("PPTX must pass structural validation", OutputValidator.validatePptx(output))

        XMLSlideShow(FileInputStream(output)).use { ppt ->
            assertEquals("slide count must equal page count", 3, ppt.slides.size)
        }
    }

    @Test
    fun the_slide_size_follows_the_requested_page_size() {
        val dir = TestSupport.tempDir("pdf_pptx_size")
        val output = File(dir, "out.pptx")
        writeSlideImagesToPptx(listOf(jpeg("/fixtures/jpg_portrait.jpg")), 595f, 842f, "jpg", output.absolutePath)

        val (cx, cy) = slideSizeFromPackage(output)
        // 595pt * 12700 EMU/pt
        assertEquals(595L * 12700L, cx)
        assertEquals(842L * 12700L, cy)
        assertTrue("portrait slide must be taller than wide", cy > cx)
    }

    @Test
    fun the_package_contains_the_expected_ooxml_parts() {
        val dir = TestSupport.tempDir("pdf_pptx_parts")
        val output = File(dir, "out.pptx")
        writeSlideImagesToPptx(listOf(jpeg("/fixtures/jpg_landscape.jpg")), 842f, 595f, "jpg", output.absolutePath)

        ZipFile(output).use { zip ->
            for (part in listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "ppt/presentation.xml",
                "ppt/_rels/presentation.xml.rels",
                "ppt/slideMasters/slideMaster1.xml",
                "ppt/slideLayouts/slideLayout1.xml",
                "ppt/theme/theme1.xml",
                "ppt/slides/slide1.xml",
                "ppt/slides/_rels/slide1.xml.rels",
                "ppt/media/image1.jpg"
            )) {
                assertTrue("missing part $part", zip.getEntry(part) != null)
            }
        }
    }

    @Test
    fun build_from_rendered_pages_keeps_page_order_and_aspect() {
        val dir = TestSupport.tempDir("pdf_pptx_build")
        val output = File(dir, "out.pptx")
        val pages = listOf(
            RenderedPage(jpeg("/fixtures/jpg_landscape.jpg"), 842f, 595f),
            RenderedPage(jpeg("/fixtures/jpg_portrait.jpg"), 842f, 595f)
        )
        val count = buildPptxFromRenderedPages(pages, output.absolutePath)
        assertEquals(2, count)
        assertTrue(OutputValidator.validatePptx(output))
        XMLSlideShow(FileInputStream(output)).use { ppt -> assertEquals(2, ppt.slides.size) }
        val (cx, cy) = slideSizeFromPackage(output)
        assertTrue("landscape page must give a landscape slide", cx > cy)
    }

    @Test
    fun an_out_of_memory_error_is_a_controlled_failure_not_a_crash() {
        val dir = TestSupport.tempDir("pdf_pptx_oem")
        val output = File(dir, "out.pptx")
        val result = pptxConversionResult(output.absolutePath) {
            // Simulates the device OOM the old code could hit while rasterizing.
            throw OutOfMemoryError("simulated bitmap allocation failure")
        }
        assertFalse("must not report success", result.success)
        assertTrue("failure must be explained", result.message!!.contains("Failed to convert PDF to PowerPoint"))
        assertFalse("no partial output may be published", File(output.absolutePath).exists())
    }

    @Test
    fun a_missing_linkage_error_is_a_controlled_failure() {
        val dir = TestSupport.tempDir("pdf_pptx_linkage")
        val output = File(dir, "out.pptx")
        val result = pptxConversionResult(output.absolutePath) {
            // Simulates a missing java.awt class on Android.
            throw NoClassDefFoundError("java/awt/geom/Rectangle2D")
        }
        assertFalse(result.success)
        assertFalse(File(output.absolutePath).exists())
    }

    @Test
    fun a_successful_conversion_reports_the_slide_count() {
        val dir = TestSupport.tempDir("pdf_pptx_ok")
        val output = File(dir, "out.pptx")
        val result = pptxConversionResult(output.absolutePath) {
            writeSlideImagesToPptx(
                listOf(jpeg("/fixtures/jpg_square.jpg")),
                595f, 842f, "jpg", output.absolutePath
            )
        }
        assertTrue(result.success)
        assertEquals(1, result.pageCount)
        assertEquals(
            "the converter must declare its real output type",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            result.mimeType
        )
    }
}
