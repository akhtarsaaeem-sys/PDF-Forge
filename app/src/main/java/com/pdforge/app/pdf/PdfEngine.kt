package com.pdforge.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.exceptions.BadPasswordException
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.data.models.PdfPage
import com.pdforge.app.storage.FileManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The PDF engine.
 *
 * [context] is only needed for content:// references (resolving them through the
 * ContentResolver) and for temp-file creation during rendering. Keeping it
 * nullable — the same contract [com.pdforge.app.security.PdfSecurity] already
 * uses — lets the JVM unit tests drive the real production methods that work
 * purely on files, instead of testing a copy of their logic.
 */
class PdfEngine(private val context: Context?) {

    companion object {
        private const val TAG = "PdfEngine"

        /** Long edge (px) that a PDF page is rasterized to for a slide image. */
        private const val MAX_SLIDE_IMAGE_EDGE = 1600f
    }

    fun getPageCount(uri: Uri): Int = getPageCount(uri.toString())

    /**
     * Opens a read descriptor for either a content:// URI, a file:// URI,
     * or a raw filesystem path. Never converts content URIs into fake paths.
     *
     * Some content providers (cloud/document providers) return non-seekable
     * descriptors that PdfRenderer cannot use. In that case — or when the
     * provider cannot be opened directly — the URI is copied into a temporary
     * cache file (seekable by construction) used solely for rendering. The
     * temporary file never replaces the user's permanent file.
     */
    private fun openPdfDescriptor(input: String): ParcelFileDescriptor? {
        return try {
            when {
                classifyPdfInput(input) == PdfInputSource.CONTENT_URI ->
                    context?.contentResolver?.openFileDescriptor(Uri.parse(input), "r")
                classifyPdfInput(input) == PdfInputSource.FILE_URI ->
                    ParcelFileDescriptor.open(File(Uri.parse(input).path ?: return null), ParcelFileDescriptor.MODE_READ_ONLY)
                else ->
                    ParcelFileDescriptor.open(File(input), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "openPdfDescriptor failed [${e.javaClass.simpleName}]: ${e.message}")
            null
        }
    }

    /**
     * Opens a [PdfRenderer] for the given reference, falling back to a temporary
     * cache copy when the direct descriptor is unavailable or not seekable.
     * The caller must close both the renderer and the descriptor.
     */
    private fun openPdfRenderer(input: String): Pair<PdfRenderer, ParcelFileDescriptor>? {
        val direct = openPdfDescriptor(input)
        if (direct != null) {
            try {
                return PdfRenderer(direct) to direct
            } catch (e: Exception) {
                Log.w(TAG, "PdfRenderer rejected direct descriptor (not seekable?), falling back: ${e.message}")
                try { direct.close() } catch (_: Exception) {}
            }
        }
        if (classifyPdfInput(input) == PdfInputSource.CONTENT_URI && context != null) {
            val cacheFile = FileManager.createTempFile(context, "viewer_${System.currentTimeMillis()}")
            if (FileManager.copyUriToFile(context, Uri.parse(input), cacheFile)) {
                try {
                    val fd = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    return PdfRenderer(fd) to fd
                } catch (e: Exception) {
                    Log.w(TAG, "PdfRenderer failed on cached copy: ${e.message}")
                    try { cacheFile.delete() } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    /** Page count for content:// URIs, file:// URIs, or raw paths. */
    fun getPageCount(input: String): Int {
        // openPdfRenderer resolves content://, file:// and raw paths directly. A content://
        // reference must never be routed back through getPageCount(Uri): that recursed
        // String -> Uri -> String forever and crashed every picked PDF.
        val pair = openPdfRenderer(input) ?: return 0
        val (renderer, fd) = pair
        return try {
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (e: Exception) {
            Log.w(TAG, "getPageCount failed [${e.javaClass.simpleName}]: ${e.message}")
            try { renderer.close() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
            0
        }
    }

    /** Returns a page's dimensions in PDF points, or (0, 0) when unavailable. */
    fun getPageSize(input: String, pageNumber: Int): Pair<Int, Int> {
        val pair = openPdfRenderer(input) ?: return 0 to 0
        val (renderer, fd) = pair
        return try {
            if (pageNumber < 0 || pageNumber >= renderer.pageCount) {
                0 to 0
            } else {
                val page = renderer.openPage(pageNumber)
                val size = page.width to page.height
                page.close()
                size
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPageSize($pageNumber) failed [${e.javaClass.simpleName}]: ${e.message}")
            0 to 0
        } finally {
            try { renderer.close() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
        }
    }

    /**
     * Renders a page from a content:// URI, file:// URI, or raw path.
     *
     * [rotationDegrees] rotates the rendered bitmap (0/90/180/270). Organize PDF
     * passes its pending per-page rotation so the thumbnail actually shows the
     * rotation the user asked for — previously the preview ignored it, so Rotate
     * looked like it did nothing even though the value was stored.
     *
     * The returned bitmap is only as large as [width] implies; callers rendering
     * many thumbnails should request a small width to avoid holding big bitmaps.
     */
    fun renderPage(
        input: String,
        pageNumber: Int,
        width: Int = 800,
        height: Int = 1100,
        rotationDegrees: Int = 0
    ): Bitmap? {
        val pair = openPdfRenderer(input) ?: return null
        val (renderer, fd) = pair
        return try {
            if (pageNumber < 0 || pageNumber >= renderer.pageCount) return null
            val page = renderer.openPage(pageNumber)
            val scale = width.toFloat() / page.width
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); renderer.close(); fd.close()
            rotateBitmap(bitmap, rotationDegrees)
        } catch (e: Exception) {
            Log.w(TAG, "renderPage($pageNumber) failed [${e.javaClass.simpleName}]: ${e.message}")
            try { renderer.close() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
            null
        }
    }

    /** Rotates [bitmap] clockwise by [degrees]; returns the original when no rotation applies. */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        return try {
            val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "rotateBitmap failed: ${e.message}")
            bitmap
        }
    }

    private fun copyAllPages(pdfDoc: ITextPdfDocument, outputDoc: ITextPdfDocument) {
        for (i in 1..pdfDoc.numberOfPages) {
            // copyTo returns the copied page — it must be explicitly added to the
            // destination's page tree, otherwise the output document has zero pages.
            outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
        }
    }

    // ── 12.1 MERGE PDF ──────────────────────────────────────────

    /**
     * Merges every page of every input PDF (in the given order) into [outputPath].
     *
     * Lifecycle contract — read carefully before changing this method:
     *  - Each source document is opened, fully merged, and only then closed. A source
     *    is never reused after `close()` and never shared between iterations.
     *  - `PdfMerger.close()` **also closes the destination [ITextPdfDocument]** (that
     *    is iText's documented behaviour). The destination document must therefore
     *    not be touched afterwards. The previous implementation read
     *    `outputDoc.numberOfPages` after `merger.close()`, which threw
     *    `PdfException: "Document was closed. It is impossible to execute action."`
     *    and surfaced on the device as "Failed to merge PDFs: The document was
     *    closed" — every merge failed, for any number of inputs.
     *  - The page count is verified by re-opening the produced file with a fresh
     *    reader, so the reported count is the real, on-disk page count rather than
     *    an assumption.
     */
    fun mergePdfs(inputPaths: List<String>, outputPath: String): ProcessingResult {
        if (inputPaths.isEmpty()) {
            return ProcessingResult(false, message = "Select at least one PDF to merge.")
        }
        return try {
            // Real work lives in mergePdfFiles so the merge lifecycle is covered by a
            // JVM unit test that runs the actual production code path.
            val count = mergePdfFiles(inputPaths, outputPath)
            val outputFile = File(outputPath)
            if (count <= 0) {
                try { outputFile.delete() } catch (_: Exception) {}
                return ProcessingResult(false, message = "None of the selected PDFs contained any pages.")
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                return ProcessingResult(false, message = "Failed to merge PDFs: the merged file is empty.")
            }
            val size = outputFile.length()
            ProcessingResult(true, outputPath, size, size, null, count, inputPaths.size)
        } catch (e: Exception) {
            Log.w(TAG, "mergePdfs failed [${e.javaClass.simpleName}]: ${e.message}")
            // Never leave a partial merge behind for the output pipeline to publish.
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to merge PDFs: ${e.message}")
        }
    }

    // ── 12.2 SPLIT PDF ──────────────────────────────────────────

    fun splitPdf(inputPath: String, outputDir: String, ranges: List<Pair<Int, Int>>): ProcessingResult {
        return try {
            splitPdfResult(inputPath, outputDir, ranges)
        } catch (e: Exception) {
            Log.w(TAG, "splitPdf failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to split PDF: ${e.message}")
        }
    }

    // ── 12.3 REMOVE PAGES ───────────────────────────────────────

    fun removePages(inputPath: String, outputPath: String, pagesToRemove: List<Int>): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val keep = pagesToKeep(pdfDoc.numberOfPages, pagesToRemove)
            if (pdfDoc.numberOfPages > 0 && keep.isEmpty()) {
                pdfDoc.close()
                return ProcessingResult(
                    success = false,
                    message = "Cannot remove every page — at least one page must remain."
                )
            }
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            for (p in keep) outputDoc.addPage(pdfDoc.getPage(p).copyTo(outputDoc))
            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            ProcessingResult(false, message = "Failed to remove pages: ${e.message}")
        }
    }

    // ── 12.4 EXTRACT PAGES ──────────────────────────────────────

    fun extractPages(inputPath: String, outputPath: String, pagesToExtract: List<Int>): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val selected = pagesToExtractInOrder(pagesToExtract, pdfDoc.numberOfPages)
            if (selected.isEmpty()) {
                pdfDoc.close()
                return ProcessingResult(
                    success = false,
                    message = "Select at least one page to extract."
                )
            }
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            for (p in selected) {
                outputDoc.addPage(pdfDoc.getPage(p).copyTo(outputDoc))
            }
            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            ProcessingResult(false, message = "Failed to extract pages: ${e.message}")
        }
    }

    // ── 12.5 ORGANIZE PDF ───────────────────────────────────────

    fun organizePdf(inputPath: String, outputPath: String, pages: List<PdfPage>): ProcessingResult {
        return try {
            val count = organizePdfFiles(inputPath, outputPath, pages)
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            ProcessingResult(false, message = "Failed to organize PDF: ${e.message}")
        }
    }

    // ── 12.7 COMPRESS PDF ───────────────────────────────────────

    fun compressPdf(inputPath: String, outputPath: String, quality: CompressQuality = CompressQuality.RECOMMENDED): ProcessingResult {
        return try {
            // Real work lives in compressPdfFile so the preset behaviour and image
            // recompression are covered by JVM tests that run the production engine.
            val outcome = compressPdfFile(inputPath, outputPath, quality, AndroidImageRecompressor())
            val msg = buildString {
                if (outcome.keptOriginal) {
                    append("This PDF is already optimized, or the available transformations did not reduce its size. ")
                    append("The original (${FileManager.formatFileSize(outcome.originalSize)}) was kept.")
                } else {
                    val reduction = ((outcome.originalSize - outcome.outputSize).toDouble() / outcome.originalSize * 100)
                    append("Original: ${FileManager.formatFileSize(outcome.originalSize)}, ")
                    append("Compressed: ${FileManager.formatFileSize(outcome.outputSize)}, ")
                    append("Reduced: ${"%.1f".format(reduction)}%")
                    if (outcome.imagesRecompressed > 0) {
                        append(" (${outcome.imagesRecompressed} image(s) recompressed)")
                    }
                }
            }
            ProcessingResult(true, outputPath, outcome.originalSize, outcome.outputSize, msg, outcome.pageCount)
        } catch (e: Exception) {
            Log.w(TAG, "compressPdf failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to compress PDF: ${e.message}")
        }
    }

    // ── 12.8 REPAIR PDF ─────────────────────────────────────────

    fun repairPdf(inputPath: String, outputPath: String): ProcessingResult {
        // Real work lives in repairPdfFile so the recovery behaviour is covered by
        // a JVM unit test that runs the production code path.
        return repairPdfFile(inputPath, outputPath)
    }

    // ── 12.10 JPG TO PDF ────────────────────────────────────────

    /**
     * JPG/PNG → PDF. Each page is built with the SAME aspect ratio as its image
     * (the shared [ScanPageLayout] maths), so no accidental white border appears
     * and the image is neither stretched nor cropped. See [imagesToPdf].
     */
    fun createPdfFromImages(imagePaths: List<String>, outputPath: String): ProcessingResult {
        return try {
            val count = imagesToPdf(imagePaths, outputPath)
            if (count <= 0) {
                try { File(outputPath).delete() } catch (_: Exception) {}
                return ProcessingResult(false, message = "No images could be converted.")
            }
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count, 1)
        } catch (e: Throwable) {
            // Never leave a partial output behind for the pipeline to publish.
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to create PDF from images: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 12.15 PDF TO JPG ────────────────────────────────────────

    fun pdfToImages(inputPath: String, outputDir: String, pages: List<Int>? = null, quality: Int = 90): ProcessingResult {
        return try {
            val fd = ParcelFileDescriptor.open(File(inputPath), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val totalPages = renderer.pageCount
            val targetPages = pages?.filter { it in 1..totalPages }?.map { it - 1 } ?: (0 until totalPages).toList()
            var totalSize = 0L
            for (pn in targetPages) {
                val page = renderer.openPage(pn)
                val bitmap = Bitmap.createBitmap((page.width * 2f).toInt(), (page.height * 2f).toInt(), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val outFile = File(outputDir, "page_${pn + 1}.jpg")
                FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                totalSize += outFile.length()
                bitmap.recycle(); page.close()
            }
            renderer.close(); fd.close()
            ProcessingResult(true, outputDir, 0, totalSize, null, targetPages.size)
        } catch (e: Exception) {
            ProcessingResult(false, message = "Failed to convert PDF to images: ${e.message}")
        }
    }

    // ── 12.16 PDF TO WORD ───────────────────────────────────────

    /**
     * PDF → Word. Real work lives in [pdfTextToDocx] so the extraction is covered
     * by a JVM test. Returns a valid, non-empty DOCX; the failure that used to be
     * reported ("Output validation failed") was the shared validator rejecting
     * every OOXML file, not this conversion.
     */
    fun pdfToWord(inputPath: String, outputPath: String): ProcessingResult {
        return try {
            val totalPages = pdfTextToDocx(inputPath, outputPath)
            if (!File(outputPath).exists() || File(outputPath).length() <= 0L) {
                return ProcessingResult(false, message = "Failed to convert PDF to Word: the output document is empty.")
            }
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, totalPages,
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        } catch (e: Throwable) {
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to convert PDF to Word: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 12.17 PDF TO POWERPOINT ──────────────────────────────────

    /**
     * PDF → PowerPoint.
     *
     * RELIABILITY: one PDF page becomes one PowerPoint slide whose image is the
     * rendered page, at the page's own aspect ratio (the accepted, stable
     * baseline). The PPTX is written by [writeSlideImagesToPptx] with plain
     * `java.util.zip` — Apache POI's XSLF usermodel is NOT used because it
     * references `java.awt.geom`, which does not exist on Android and crashed the
     * app with `NoClassDefFoundError` (an `Error`, so `catch (Exception)` could
     * not stop it).
     *
     * The whole conversion is wrapped so that ANY failure — including an
     * `OutOfMemoryError` on a very large PDF — returns a controlled error, leaves
     * no partial file behind, and keeps the UI alive.
     */
    fun pdfToPpt(inputPath: String, outputPath: String): ProcessingResult {
        return pptxConversionResult(outputPath) {
            val input = File(inputPath)
            if (!input.exists() || input.length() <= 0L) {
                throw IllegalArgumentException("The selected PDF is empty or could not be read.")
            }
            val fd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            try {
                val totalPages = renderer.pageCount
                if (totalPages <= 0) {
                    throw IllegalStateException("The PDF has no pages to convert.")
                }
                val pages = ArrayList<RenderedPage>(totalPages)
                for (index in 0 until totalPages) {
                    val page = renderer.openPage(index)
                    try {
                        pages.add(
                            RenderedPage(
                                imageBytes = renderPageToJpeg(page),
                                widthPt = page.width.toFloat(),
                                heightPt = page.height.toFloat()
                            )
                        )
                    } finally {
                        try { page.close() } catch (_: Exception) {}
                    }
                }
                buildPptxFromRenderedPages(pages, outputPath)
            } finally {
                try { renderer.close() } catch (_: Exception) {}
                try { fd.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Renders one PDF page to a bounded JPEG for embedding in a slide.
     *
     * The raster size is capped (long edge ≤ [MAX_SLIDE_IMAGE_EDGE]) so a large or
     * high-resolution page cannot allocate an enormous bitmap and run the device
     * out of memory; the bitmap is recycled immediately after compressing.
     */
    private fun renderPageToJpeg(page: PdfRenderer.Page): ByteArray {
        val longestEdge = maxOf(page.width, page.height).coerceAtLeast(1).toFloat()
        val scale = (MAX_SLIDE_IMAGE_EDGE / longestEdge).coerceIn(0.5f, 3f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            return baos.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    // ── 12.18 PDF TO EXCEL ──────────────────────────────────────

    /**
     * PDF → Excel. Real work lives in [pdfTableToXlsx], which reconstructs rows
     * and columns from the text positions and FAILS clearly (instead of faking
     * success) when the PDF contains no recoverable tabular data.
     */
    fun pdfToExcel(inputPath: String, outputPath: String): ProcessingResult {
        return try {
            val totalPages = pdfTableToXlsx(inputPath, outputPath)
            if (!File(outputPath).exists() || File(outputPath).length() <= 0L) {
                return ProcessingResult(false, message = "Failed to convert PDF to Excel: the output workbook is empty.")
            }
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, totalPages,
                mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        } catch (e: Throwable) {
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to convert PDF to Excel: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 12.19 PDF TO PDF/A ──────────────────────────────────────

    /**
     * PDF → PDF/A-compatible output. See [convertPdfToPdfA] for the details and the
     * honest conformance caveat. The device failure was an iText/Android XML parser
     * incompatibility, fixed in [AndroidCompatXmlParserFactory].
     */
    fun pdfToPdfA(inputPath: String, outputPath: String): ProcessingResult {
        return try {
            val originalSize = File(inputPath).length()
            val count = convertPdfToPdfA(inputPath, outputPath)
            val outputSize = File(outputPath).length()
            val msg = "Created PDF/A-compatible output: ${FileManager.formatFileSize(outputSize)}. " +
                "This is not a certified ISO PDF/A-2b conversion."
            ProcessingResult(true, outputPath, originalSize, outputSize, msg, count)
        } catch (e: Throwable) {
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to convert to PDF/A: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 12.22 ROTATE PDF ────────────────────────────────────────

    fun rotatePages(inputPath: String, outputPath: String, pagesToRotate: List<Int>, rotation: Int): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)
            for (p in pagesToRotate) {
                if (p in 1..outputDoc.numberOfPages) {
                    outputDoc.getPage(p).rotation = (outputDoc.getPage(p).rotation + rotation) % 360
                }
            }
            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            Log.w(TAG, "rotatePages failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to rotate PDF: ${e.message}")
        }
    }

    // ── 12.23 ADD PAGE NUMBERS ──────────────────────────────────

    fun addPageNumbers(
        inputPath: String, outputPath: String,
        position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
        startNumber: Int = 1, fontSize: Float = 12f,
        margin: Float = 36f, pagesToNumber: List<Int>? = null
    ): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)

            val font = PdfFontFactory.createFont()
            val totalPages = outputDoc.numberOfPages

            // Sequential numbering: the first selected page shows startNumber, the
            // next shows startNumber + 1, and so on (see planPageNumbers).
            for ((i, label) in planPageNumbers(totalPages, startNumber, pagesToNumber)) {
                val page = outputDoc.getPage(i)
                val ps = page.pageSize
                val text = "$label"
                val tw = font.getWidth(text, fontSize)

                // The requested position is resolved in the page's DISPLAY space
                // (how the page looks once its /Rotate is applied) and only then
                // converted to raw user space. Positioning directly in raw space
                // put "bottom center" on a rotated edge for /Rotate 90/180/270
                // pages, which is why numbers looked misplaced on rotated pages.
                val rotation = normalizeRotation(page.rotation)
                val (displayWidth, displayHeight) = displayPageSize(ps.width, ps.height, rotation)

                val displayX = when (position) {
                    PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.TOP_LEFT -> margin
                    PageNumberPosition.BOTTOM_RIGHT, PageNumberPosition.TOP_RIGHT -> displayWidth - margin - tw
                    else -> (displayWidth - tw) / 2f
                }
                val displayY = when (position) {
                    PageNumberPosition.TOP_LEFT, PageNumberPosition.TOP_CENTER, PageNumberPosition.TOP_RIGHT ->
                        displayHeight - margin - fontSize
                    PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.BOTTOM_RIGHT ->
                        margin
                }
                val (localX, localY) = displayToRaw(displayX, displayY, ps.width, ps.height, rotation)
                val x = ps.left + localX
                val y = ps.bottom + localY

                val canvas = PdfCanvas(page)
                canvas.saveState()
                canvas.setFontAndSize(font, fontSize)
                if (rotation != 0) {
                    // Counter-rotate the number so it reads upright once the viewer
                    // applies the page's own (clockwise) rotation.
                    val m = rotationMatrixAbout(x, y, rotation.toFloat())
                    canvas.concatMatrix(m.a, m.b, m.c, m.d, m.e, m.f)
                }
                canvas.beginText()
                canvas.moveText(x.toDouble(), y.toDouble())
                canvas.showText(text)
                canvas.endText()
                canvas.restoreState()
            }

            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            Log.w(TAG, "addPageNumbers failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to add page numbers: ${e.message}")
        }
    }

    // ── 12.24 ADD WATERMARK ─────────────────────────────────────

    fun addTextWatermark(
        inputPath: String, outputPath: String, text: String,
        opacity: Float = 0.3f, fontSize: Float = 50f, rotation: Float = 45f,
        position: WatermarkPosition = WatermarkPosition.CENTER
    ): ProcessingResult {
        if (text.isBlank()) {
            return ProcessingResult(false, message = "Watermark text cannot be empty.")
        }
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)

            val font = PdfFontFactory.createFont()
            val extGState = PdfExtGState().setFillOpacity(opacity.coerceIn(0f, 1f))
            val totalPages = outputDoc.numberOfPages

            for (i in 1..totalPages) {
                val page = outputDoc.getPage(i)
                val ps = page.pageSize

                // Everything is resolved in the page's DISPLAY space (the way the
                // page looks after /Rotate is applied) and then converted to raw
                // user space, so the preview and the saved PDF agree on rotated
                // pages as well as on unrotated ones.
                val pageRotation = normalizeRotation(page.rotation)
                val (displayWidth, displayHeight) = displayPageSize(ps.width, ps.height, pageRotation)

                // Long text is scaled down until it fits the page instead of
                // running off the edge; the usable width accounts for the slant,
                // because a rotated run needs more text width for the same
                // horizontal footprint.
                val usableWidth = watermarkUsableWidth(displayWidth, displayHeight, rotation)
                val fittedFontSize = fitWatermarkFontSize(
                    requestedSize = fontSize,
                    textWidthAtRequestedSize = font.getWidth(text, fontSize),
                    usableWidth = usableWidth
                )
                val tw = font.getWidth(text, fittedFontSize)

                val (ax, ay) = watermarkAnchor(position, displayWidth, displayHeight)
                val (localX, localY) = displayToRaw(ax, ay, ps.width, ps.height, pageRotation)
                val cx = ps.left + localX
                val cy = ps.bottom + localY

                val canvas = PdfCanvas(page)
                canvas.saveState()
                canvas.setExtGState(extGState)
                canvas.setColor(ColorConstants.GRAY, true)
                canvas.setFontAndSize(font, fittedFontSize)
                // Rotation matrix about (cx, cy); the page's own rotation is added
                // so the slant the user picked is the slant that is displayed.
                val m = rotationMatrixAbout(cx, cy, watermarkDrawAngle(rotation, pageRotation))
                canvas.concatMatrix(m.a, m.b, m.c, m.d, m.e, m.f)
                canvas.beginText()
                canvas.moveText((cx - tw / 2).toDouble(), cy.toDouble())
                canvas.showText(text)
                canvas.endText()
                canvas.restoreState()
            }

            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            Log.w(TAG, "addTextWatermark failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to add watermark: ${e.message}")
        }
    }

    // ── 12.25 CROP PDF ──────────────────────────────────────────

    fun cropPdf(inputPath: String, outputPath: String, left: Float, top: Float, right: Float, bottom: Float): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)
            for (i in 1..outputDoc.numberOfPages) {
                val page = outputDoc.getPage(i)
                val ps = page.pageSize
                val newWidth = ps.width - left - right
                val newHeight = ps.height - top - bottom
                if (newWidth > 0 && newHeight > 0) {
                    val cropBox = Rectangle(ps.left + left, ps.bottom + bottom, newWidth, newHeight)
                    page.mediaBox = cropBox
                    page.cropBox = cropBox
                }
            }
            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            Log.w(TAG, "cropPdf failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to crop PDF: ${e.message}")
        }
    }

    /**
     * Crops each page by per-edge percentages of that page's own size, so the
     * same percentages behave consistently across pages of different sizes.
     *
     * [pages] limits the crop to those 1-based pages; null/empty keeps the
     * original behaviour of cropping every page.
     */
    fun cropPdfByPercent(
        inputPath: String, outputPath: String,
        topPercent: Float, bottomPercent: Float, leftPercent: Float, rightPercent: Float,
        pages: List<Int>? = null
    ): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)
            for (i in resolveCropPages(pages, outputDoc.numberOfPages)) {
                val page = outputDoc.getPage(i)
                val ps = page.pageSize
                val box = computeCropBox(
                    ps.width, ps.height,
                    leftPercent, rightPercent, topPercent, bottomPercent
                ) ?: continue
                val cropBox = Rectangle(ps.left + box.x, ps.bottom + box.y, box.width, box.height)
                page.mediaBox = cropBox
                page.cropBox = cropBox
            }
            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            Log.w(TAG, "cropPdfByPercent failed [${e.javaClass.simpleName}]: ${e.message}")
            ProcessingResult(false, message = "Failed to crop PDF: ${e.message}")
        }
    }

    /**
     * Crops [pages] (1-based; empty/null = every page) to a region described by
     * per-edge FRACTIONS of each page's DISPLAYED size (0..1: how much is trimmed
     * from the left / top / right / bottom as the page is shown).
     *
     * Fractions — never raw pixels — is what makes the same manual crop work on
     * documents whose pages have different sizes or rotations: the fraction is
     * converted into each target page's own PDF coordinate system by
     * [computeCropBoxNormalized], which also accounts for the page's /Rotate so a
     * rotated page is cropped on the side the user actually pointed at.
     */
    fun cropPdfNormalized(
        inputPath: String, outputPath: String,
        leftFraction: Float, topFraction: Float, rightFraction: Float, bottomFraction: Float,
        pages: List<Int>? = null
    ): ProcessingResult {
        return try {
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
            val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
            copyAllPages(pdfDoc, outputDoc)

            val targets = resolveCropPages(pages, outputDoc.numberOfPages)
            var cropped = 0
            for (i in targets) {
                val page = outputDoc.getPage(i)
                val ps = page.pageSize
                val box = computeCropBoxNormalized(
                    pageWidth = ps.width,
                    pageHeight = ps.height,
                    rotation = normalizeRotation(page.rotation),
                    leftFraction = leftFraction,
                    topFraction = topFraction,
                    rightFraction = rightFraction,
                    bottomFraction = bottomFraction
                ) ?: continue
                val cropBox = Rectangle(ps.left + box.x, ps.bottom + box.y, box.width, box.height)
                page.mediaBox = cropBox
                page.cropBox = cropBox
                cropped++
            }

            if (cropped == 0) {
                outputDoc.close(); pdfDoc.close()
                try { File(outputPath).delete() } catch (_: Exception) {}
                return ProcessingResult(
                    success = false,
                    message = "The crop area is empty. Leave less than 100% of the page cropped away."
                )
            }

            val count = outputDoc.numberOfPages
            outputDoc.close(); pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            Log.w(TAG, "cropPdfNormalized failed [${e.javaClass.simpleName}]: ${e.message}")
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "Failed to crop PDF: ${e.message}")
        }
    }

    // ── 12.26 SIGN PDF ──────────────────────────────────────────

    /**
     * Adds [signatureText] as a visual overlay on [pages] (1-based; empty = every
     * page). Delegates to the shared, JVM-testable [com.pdforge.app.security.addVisualSignatureToPages]
     * so the code exercised by the regression tests is the code the app runs.
     */
    fun signPdf(
        inputPath: String, outputPath: String, signatureText: String,
        pages: List<Int> = emptyList()
    ): ProcessingResult = com.pdforge.app.security.addVisualSignatureToPages(
        inputPath, outputPath, signatureText, pages
    )

    // ── 12.27 UNLOCK PDF ────────────────────────────────────────

    /**
     * Removes password protection from an encrypted PDF.
     *
     * Delegates to the shared, JVM-testable [unlockPdfFile] so the production
     * path and the tests run the same implementation.
     */
    fun unlockPdf(inputPath: String, outputPath: String, password: String): ProcessingResult =
        unlockPdfFile(inputPath, outputPath, password)

    // ── 12.28 PROTECT PDF ───────────────────────────────────────

    fun protectPdf(
        inputPath: String, outputPath: String, userPassword: String,
        ownerPassword: String = userPassword,
        allowPrinting: Boolean = true, allowCopying: Boolean = false,
        allowEditing: Boolean = false
    ): ProcessingResult {
        return try {
            var permissions = EncryptionConstants.ALLOW_PRINTING
            if (allowCopying) permissions = permissions or 16
            if (allowEditing) permissions = permissions or 8

            val writerProps = WriterProperties().setStandardEncryption(
                userPassword.toByteArray(), ownerPassword.toByteArray(),
                permissions, EncryptionConstants.STANDARD_ENCRYPTION_128
            )
            val pdfDoc = ITextPdfDocument(PdfReader(inputPath), PdfWriter(outputPath, writerProps))
            val count = pdfDoc.numberOfPages
            pdfDoc.close()
            ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
        } catch (e: Exception) {
            // Fallback without permission flags
            try {
                val writerProps = WriterProperties().setStandardEncryption(
                    userPassword.toByteArray(), ownerPassword.toByteArray(),
                    EncryptionConstants.ALLOW_PRINTING, EncryptionConstants.STANDARD_ENCRYPTION_128
                )
                val pdfDoc = ITextPdfDocument(PdfReader(inputPath), PdfWriter(outputPath, writerProps))
                val count = pdfDoc.numberOfPages
                pdfDoc.close()
                ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
            } catch (e2: Exception) {
                ProcessingResult(false, message = "Failed to protect PDF: ${e2.message}")
            }
        }
    }

    // ── HELPER: get page text ───────────────────────────────────

    fun getPageText(inputPath: String, pageNumber: Int): String {
        return try {
            val reader = PdfReader(inputPath)
            val pdfDoc = ITextPdfDocument(reader)
            val page = pdfDoc.getPage(pageNumber)
            val strategy = com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy()
            val extractor = com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor(strategy)
            extractor.processPageContent(page)
            val text = strategy.resultantText
            pdfDoc.close()
            text
        } catch (e: Exception) { "" }
    }

    fun getAllPagesText(inputPath: String): Map<Int, String> {
        return try {
            val reader = PdfReader(inputPath)
            val pdfDoc = ITextPdfDocument(reader)
            val result = mutableMapOf<Int, String>()
            for (i in 1..pdfDoc.numberOfPages) {
                val strategy = com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy()
                val extractor = com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor(strategy)
                extractor.processPageContent(pdfDoc.getPage(i))
                result[i] = strategy.resultantText
            }
            pdfDoc.close()
            result
        } catch (e: Exception) { emptyMap() }
    }
}

/** Normalises a page rotation to one of 0/90/180/270 degrees. */
internal fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

/**
 * Copies [pages] (in order, with their rotations) from [inputPath] into
 * [outputPath] and returns the resulting page count.
 *
 * A page's requested rotation is accumulated onto whatever rotation the copied
 * page already carried and normalised, so "rotate twice" really saves 180°, and
 * a saved file always reopens with the rotation the user asked for.
 *
 * Extracted from [PdfEngine.organizePdf] so the rotation persistence is covered
 * by a unit test that runs the production code rather than a copy of it.
 */
internal fun organizePdfFiles(inputPath: String, outputPath: String, pages: List<PdfPage>): Int {
    val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
    val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
    try {
        for (p in pages) {
            if (p.number in 1..pdfDoc.numberOfPages) {
                val copied = outputDoc.addPage(pdfDoc.getPage(p.number).copyTo(outputDoc))
                if (p.rotation != 0) {
                    copied.rotation = normalizeRotation(copied.rotation + p.rotation)
                }
            }
        }
        return outputDoc.numberOfPages
    } finally {
        outputDoc.close()
        pdfDoc.close()
    }
}

/**
 * Writes one PDF per [ranges] entry into [outputDir] and returns the created file
 * paths in order. A range whose start is after its end is skipped rather than
 * producing a zero-page file (used by Split PDF).
 */
internal fun splitPdfIntoFiles(
    inputPath: String,
    outputDir: String,
    ranges: List<Pair<Int, Int>>
): List<String> {
    val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
    val outputs = mutableListOf<String>()
    try {
        var index = 1
        for ((start, end) in ranges) {
            val s = start.coerceIn(1, pdfDoc.numberOfPages)
            val e = end.coerceIn(1, pdfDoc.numberOfPages)
            if (s > e) continue
            val outFile = File(outputDir, "split_${String.format("%02d", index)}.pdf")
            val outputDoc = ITextPdfDocument(PdfWriter(outFile))
            try {
                for (i in s..e) {
                    outputDoc.addPage(pdfDoc.getPage(i).copyTo(outputDoc))
                }
            } finally {
                outputDoc.close()
            }
            outputs.add(outFile.absolutePath)
            index++
        }
    } finally {
        pdfDoc.close()
    }
    return outputs
}

/**
 * Builds the Split PDF result.
 *
 * IMPORTANT: the result points at the OUTPUT DIRECTORY, not at the first file.
 * The shared finalizer publishes every file in a directory result; the old code
 * set `outputPath` to the first split file, so the finalizer took the single-file
 * branch and published only that one file — the user always saw a single PDF, no
 * matter how many parts were created. Pointing at the directory makes every part
 * appear in the result UI, get a unique name, and be published correctly.
 */
internal fun splitPdfResult(
    inputPath: String,
    outputDir: String,
    ranges: List<Pair<Int, Int>>
): ProcessingResult {
    val outputs = splitPdfIntoFiles(inputPath, outputDir, ranges)
    if (outputs.isEmpty()) {
        return ProcessingResult(
            success = false,
            message = "Nothing to split — no valid page range was selected."
        )
    }
    return ProcessingResult(
        success = true,
        outputPath = outputDir,
        outputSize = outputs.sumOf { File(it).length() },
        fileCount = outputs.size,
        outputPaths = outputs
    )
}

/**
 * The 1-based pages that remain after removing [pagesToRemove] from a document
 * with [totalPages] pages, in ascending order (used by Remove Pages).
 */
internal fun pagesToKeep(totalPages: Int, pagesToRemove: Collection<Int>): List<Int> {
    if (totalPages <= 0) return emptyList()
    val removed = pagesToRemove.toSet()
    return (1..totalPages).filter { it !in removed }
}

/**
 * The 1-based pages to extract (used by Extract Pages): out-of-range entries are
 * dropped, duplicates are collapsed, and the caller's order is preserved so the
 * output can follow the user's selection order.
 */
internal fun pagesToExtractInOrder(pagesToExtract: Collection<Int>, totalPages: Int): List<Int> {
    if (totalPages <= 0) return emptyList()
    val seen = mutableSetOf<Int>()
    val result = mutableListOf<Int>()
    for (page in pagesToExtract) {
        if (page in 1..totalPages && seen.add(page)) result.add(page)
    }
    return result
}

/**
 * Merges every page of every PDF in [inputPaths] (in order) into [outputPath] and
 * returns the verified page count of the produced file (0 when no input had any
 * page).
 *
 * LIFECYCLE RULES — do not change without re-reading the regression test:
 *  - Each source document is opened, fully merged, and then closed. A source is
 *    never reused after close and never shared across iterations.
 *  - `PdfMerger.close()` ALSO closes the destination [ITextPdfDocument] (iText's
 *    documented behaviour). Nothing may touch `outputDoc` after that call. The
 *    previous implementation read `outputDoc.numberOfPages` afterwards, which
 *    threw `PdfException: "Document was closed. It is impossible to execute
 *    action."` — every merge failed on device with "The document was closed".
 *  - The returned count comes from re-opening the finished file with a fresh
 *    reader, so it is the real on-disk page count, never an assumption.
 */
internal fun mergePdfFiles(inputPaths: List<String>, outputPath: String): Int {
    if (inputPaths.isEmpty()) return 0

    val outputDoc = ITextPdfDocument(PdfWriter(outputPath))
    val merger = PdfMerger(outputDoc)
    var mergedPages = 0
    for (path in inputPaths) {
        val inputDoc = ITextPdfDocument(PdfReader(path))
        try {
            val pages = inputDoc.numberOfPages
            if (pages > 0) {
                merger.merge(inputDoc, 1, pages)
                mergedPages += pages
            }
        } finally {
            inputDoc.close()
        }
    }

    // Closes the destination document as well — outputDoc must not be used below.
    merger.close()

    if (mergedPages == 0) return 0

    // Verify the result the same way the output pipeline will: by re-opening it.
    val checkDoc = ITextPdfDocument(PdfReader(outputPath))
    return try {
        checkDoc.numberOfPages
    } finally {
        checkDoc.close()
    }
}

/** Crop rectangle in PDF points (x/y in iText user space, y measured from the bottom). */
internal data class CropBox(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Computes the crop box for a page from per-edge crop percentages (0..100).
 *
 * Percentages are taken from the page's own width (left/right) and height
 * (top/bottom), so a 5% crop removes 5% of each page regardless of page size.
 * Returns null when the result would have no area (opposite edges total >= 100%).
 */
internal fun computeCropBox(
    pageWidth: Float,
    pageHeight: Float,
    leftPercent: Float,
    rightPercent: Float,
    topPercent: Float,
    bottomPercent: Float
): CropBox? {
    val left = pageWidth * (leftPercent.coerceIn(0f, 100f) / 100f)
    val right = pageWidth * (rightPercent.coerceIn(0f, 100f) / 100f)
    val top = pageHeight * (topPercent.coerceIn(0f, 100f) / 100f)
    val bottom = pageHeight * (bottomPercent.coerceIn(0f, 100f) / 100f)
    val width = pageWidth - left - right
    val height = pageHeight - top - bottom
    if (width <= 0f || height <= 0f) return null
    return CropBox(x = left, y = bottom, width = width, height = height)
}

/**
 * The displayed width/height of a page, i.e. the size the page has once its
 * /Rotate value is applied. A 90° or 270° rotation swaps width and height.
 */
internal fun displayPageSize(rawWidth: Float, rawHeight: Float, rotation: Int): Pair<Float, Float> =
    if (normalizeRotation(rotation) % 180 == 90) rawHeight to rawWidth else rawWidth to rawHeight

/**
 * Converts a point from the page's DISPLAY space (x measured from the left edge
 * and y from the bottom edge, as the page is shown after /Rotate is applied)
 * into the page's raw PDF user-space coordinates.
 *
 * A PDF viewer rotates a page clockwise by its /Rotate value, so a raw point
 * (x, y) appears at the position this function's inverse produces:
 *   /Rotate 0   -> (x, y)
 *   /Rotate 90  -> (y, rawWidth - x)
 *   /Rotate 180 -> (rawWidth - x, rawHeight - y)
 *   /Rotate 270 -> (rawHeight - y, x)
 * All four cases keep the two coordinate systems aligned to the same physical
 * edges, which is what makes "bottom center" mean the same thing on screen as
 * it does in raw space.
 */
internal fun displayToRaw(
    displayX: Float,
    displayY: Float,
    rawWidth: Float,
    rawHeight: Float,
    rotation: Int
): Pair<Float, Float> = when (normalizeRotation(rotation)) {
    90 -> (rawWidth - displayY) to displayX
    180 -> (rawWidth - displayX) to (rawHeight - displayY)
    270 -> displayY to (rawHeight - displayX)
    else -> displayX to displayY
}

/** A 2-D affine transform in the [a b 0; c d 0; e f 1] form used by PDF content. */
internal data class AffineMatrix(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double
)

/**
 * Builds the transform that rotates the coordinate system counter-clockwise by
 * [degrees] around ([cx], [cy]) — the PDF convention (y grows upwards), which
 * is the OPPOSITE direction from an Android canvas rotation.
 */
internal fun rotationMatrixAbout(cx: Float, cy: Float, degrees: Float): AffineMatrix {
    val theta = Math.toRadians(degrees.toDouble())
    val cosT = Math.cos(theta)
    val sinT = Math.sin(theta)
    return AffineMatrix(
        a = cosT,
        b = sinT,
        c = -sinT,
        d = cosT,
        e = cx - cx * cosT + cy * sinT,
        f = cy - cx * sinT - cy * cosT
    )
}

/**
 * Splits larger-than-usable watermark text until it fits the page.
 *
 * [textWidthAtRequestedSize] is the width of the whole text at
 * [requestedSize] (font sizes and text widths scale linearly), so the fitted
 * size is a plain ratio. Text is never truncated: a very long watermark simply
 * becomes smaller. Returns [requestedSize] unchanged when it already fits.
 */
internal fun fitWatermarkFontSize(
    requestedSize: Float,
    textWidthAtRequestedSize: Float,
    usableWidth: Float
): Float {
    if (requestedSize <= 0f) return requestedSize
    if (textWidthAtRequestedSize <= 0f || usableWidth <= 0f) return requestedSize
    if (textWidthAtRequestedSize <= usableWidth) return requestedSize
    return (requestedSize * usableWidth / textWidthAtRequestedSize).coerceAtLeast(1f)
}

/** Fraction of a page a watermark is allowed to occupy (per axis). */
internal const val WATERMARK_MAX_PAGE_FRACTION = 0.9f

/**
 * The widest text run that still keeps a watermark inside the page.
 *
 * A slanted or vertical watermark needs MORE text width for the same on-screen
 * footprint, so the page width is divided by the text run's projection onto the
 * horizontal axis (|cos θ|) and likewise the height by its projection onto the
 * vertical axis (|sin θ|). A projection of (almost) zero imposes no constraint
 * on that axis, which is why it is clamped instead of dividing by zero.
 */
internal fun watermarkUsableWidth(pageWidth: Float, pageHeight: Float, rotationDegrees: Float): Float {
    val normalized = ((rotationDegrees % 360f) + 360f) % 360f
    val theta = Math.toRadians(normalized.toDouble())
    val cosT = kotlin.math.abs(Math.cos(theta).toFloat()).coerceAtLeast(WATERMARK_PROJECTION_FLOOR)
    val sinT = kotlin.math.abs(Math.sin(theta).toFloat()).coerceAtLeast(WATERMARK_PROJECTION_FLOOR)
    val byWidth = pageWidth * WATERMARK_MAX_PAGE_FRACTION / cosT
    val byHeight = pageHeight * WATERMARK_MAX_PAGE_FRACTION / sinT
    return minOf(byWidth, byHeight)
}

/**
 * Smallest axis projection used by [watermarkUsableWidth]. A run at 0° has no
 * vertical projection, so that axis must not constrain the text width at all.
 */
internal const val WATERMARK_PROJECTION_FLOOR = 0.05f

/**
 * The angle the watermark text run is drawn at inside the page content stream.
 *
 * The viewer rotates the page clockwise by its own /Rotate value, so the page
 * rotation is ADDED to the requested slant. The result is the angle that is
 * actually displayed, which is what the preview shows too.
 */
internal fun watermarkDrawAngle(watermarkRotation: Float, pageRotation: Int): Float =
    watermarkRotation + normalizeRotation(pageRotation).toFloat()

/**
 * The angle the on-screen preview must rotate the watermark by.
 *
 * An Android canvas (y grows downwards) rotates CLOCKWISE for a positive angle,
 * while PDF space (y grows upwards) rotates COUNTER-CLOCKWISE. Without the sign
 * flip the preview showed the slant mirrored relative to the saved PDF. The two
 * angles are therefore exact negatives of each other, which is what
 * [watermarkDrawAngle] and this function guarantee together.
 */
internal fun watermarkPreviewAngleDegrees(watermarkRotation: Float): Float = -watermarkRotation

/**
 * Keeps a crop rectangle inside the page it is applied to.
 *
 * The crop rectangle is described by four fractions of the page's DISPLAYED
 * size (left / top / right / bottom), exactly like the interactive crop UI, and
 * is converted into the page's raw user space here. Because the page rotation is
 * applied in [displayToRaw], the same crop always removes the edge the user
 * pointed at — for portrait, landscape and rotated pages alike.
 *
 * Returns null when the rectangle would have no area (the fractions would
 * remove the whole page).
 */
internal fun computeCropBoxNormalized(
    pageWidth: Float,
    pageHeight: Float,
    rotation: Int,
    leftFraction: Float,
    topFraction: Float,
    rightFraction: Float,
    bottomFraction: Float
): CropBox? {
    val left = leftFraction.coerceIn(0f, 1f)
    val right = rightFraction.coerceIn(0f, 1f)
    val top = topFraction.coerceIn(0f, 1f)
    val bottom = bottomFraction.coerceIn(0f, 1f)
    if (left + right >= 1f || top + bottom >= 1f) return null

    val (displayWidth, displayHeight) = displayPageSize(pageWidth, pageHeight, rotation)
    val first = displayToRaw(left * displayWidth, bottom * displayHeight, pageWidth, pageHeight, rotation)
    val second = displayToRaw(
        (1f - right) * displayWidth,
        (1f - top) * displayHeight,
        pageWidth,
        pageHeight,
        rotation
    )
    val x = minOf(first.first, second.first)
    val y = minOf(first.second, second.second)
    val width = kotlin.math.abs(second.first - first.first)
    val height = kotlin.math.abs(second.second - first.second)
    if (width <= 0f || height <= 0f) return null
    return CropBox(x, y, width, height)
}

/**
 * The 1-based pages a crop applies to: null/empty means every page, otherwise
 * the requested pages clamped to the document, de-duplicated and in order.
 */
internal fun resolveCropPages(pages: List<Int>?, totalPages: Int): List<Int> {
    if (totalPages <= 0) return emptyList()
    if (pages.isNullOrEmpty()) return (1..totalPages).toList()
    return pages.filter { it in 1..totalPages }.distinct().sorted()
}

/** How an input PDF behaves when it is opened without a password. */
internal enum class PdfEncryptionState {
    /** Could not be parsed at all (damaged, or not a PDF). */
    UNREADABLE,

    /** Opens with no password and carries no /Encrypt dictionary. */
    NOT_ENCRYPTED,

    /**
     * IS encrypted but opens with no password (an empty user password, i.e. only
     * owner restrictions are set). The document still needs to be rewritten to
     * drop the encryption.
     */
    ENCRYPTED_OPENABLE,

    /** Refuses to open without a password: a real open password is required. */
    ENCRYPTED
}

/**
 * Classifies [path] by trying to open it with no password.
 *
 * A PDF reader is lazy: nothing is parsed until a [ITextPdfDocument] is built, so
 * `PdfReader.isEncrypted` may only be read afterwards (before that it throws
 * "The PDF document has not been read yet").
 */
internal fun pdfEncryptionState(path: String): PdfEncryptionState = try {
    val reader = PdfReader(path)
    val doc = ITextPdfDocument(reader)
    val encrypted = reader.isEncrypted
    doc.close()
    if (encrypted) PdfEncryptionState.ENCRYPTED_OPENABLE else PdfEncryptionState.NOT_ENCRYPTED
} catch (e: BadPasswordException) {
    PdfEncryptionState.ENCRYPTED
} catch (e: Exception) {
    PdfEncryptionState.UNREADABLE
}

/**
 * Decrypts an encrypted PDF into a brand-new, unencrypted document.
 *
 * Every page is copied explicitly instead of relying on the reader/writer
 * "stamping" constructor, so the output is guaranteed to be a fresh, plain file
 * with no inherited /Encrypt dictionary. The password is verified by iText — a
 * wrong password throws [BadPasswordException] and no output is published.
 *
 * Failure paths always delete a partially written output file so a corrupt
 * file can never reach the result screen.
 */
internal fun unlockPdfFile(inputPath: String, outputPath: String, password: String): ProcessingResult {
    val input = File(inputPath)
    if (!input.exists() || input.length() <= 0L) {
        return ProcessingResult(false, message = "The selected PDF could not be read. Please select it again.")
    }

    when (pdfEncryptionState(inputPath)) {
        PdfEncryptionState.NOT_ENCRYPTED -> return ProcessingResult(
            success = false,
            message = "This PDF is not password-protected, so there is nothing to unlock."
        )
        PdfEncryptionState.UNREADABLE -> return ProcessingResult(
            success = false,
            message = "This file could not be read as a PDF. It may be damaged or use an unsupported format."
        )
        // Both encrypted states are handled by the decryption rewrite below: one
        // needs the password, the other (empty user password) only the restrictions.
        PdfEncryptionState.ENCRYPTED, PdfEncryptionState.ENCRYPTED_OPENABLE -> Unit
    }

    var source: ITextPdfDocument? = null
    try {
        val reader = PdfReader(inputPath, ReaderProperties().setPassword(password.toByteArray()))
        // Permits rewriting a document that was opened with the user password only
        // (owner permissions withheld). It does NOT skip password verification.
        reader.setUnethicalReading(true)
        source = ITextPdfDocument(reader)
        val count = source.numberOfPages
        val out = ITextPdfDocument(PdfWriter(outputPath))
        try {
            for (i in 1..count) {
                out.addPage(source.getPage(i).copyTo(out))
            }
        } finally {
            out.close()
        }
        return ProcessingResult(true, outputPath, 0, File(outputPath).length(), null, count)
    } catch (e: BadPasswordException) {
        try { File(outputPath).delete() } catch (_: Exception) {}
        return ProcessingResult(
            success = false,
            message = "Incorrect password. Please check the password and try again."
        )
    } catch (e: Exception) {
        try { File(outputPath).delete() } catch (_: Exception) {}
        return ProcessingResult(
            success = false,
            message = "Could not unlock this PDF: ${e.message ?: e.javaClass.simpleName}"
        )
    } finally {
        try { source?.close() } catch (_: Exception) {}
    }
}

/** The three reference forms a PDF tool can receive from the Android picker. */
internal enum class PdfInputSource { CONTENT_URI, FILE_URI, FILE_PATH }

/**
 * Classifies a PDF reference without touching the filesystem or ContentResolver.
 *
 * This is the single routing point for content:// URIs, file:// URIs and raw
 * filesystem paths. Keeping it in one pure function is what stops a content://
 * reference from being bounced between the String and Uri overloads of
 * [PdfEngine.getPageCount] (the recursion that crashed PDF selection).
 */
internal fun classifyPdfInput(input: String): PdfInputSource = when {
    input.startsWith("content://") -> PdfInputSource.CONTENT_URI
    input.startsWith("file://") -> PdfInputSource.FILE_URI
    else -> PdfInputSource.FILE_PATH
}

enum class CompressQuality { HIGH_QUALITY, RECOMMENDED, SMALLER_FILE }
/**
 * Plans which pages receive a number and what that number is.
 *
 * Numbering is sequential over the pages that are actually numbered: the first
 * selected page shows [startNumber], the next shows `startNumber + 1`, and so on.
 * Pages outside [pagesToNumber] are left untouched. When [pagesToNumber] is null
 * every page is numbered.
 *
 * Returns (1-based page index to printed label) pairs in page order.
 */
internal fun planPageNumbers(
    totalPages: Int,
    startNumber: Int,
    pagesToNumber: List<Int>?
): List<Pair<Int, Int>> {
    if (totalPages <= 0) return emptyList()
    val selected = pagesToNumber?.filter { it in 1..totalPages }?.toSortedSet()
        ?: (1..totalPages).toSortedSet()
    val plan = mutableListOf<Pair<Int, Int>>()
    var printed = 0
    for (i in 1..totalPages) {
        if (i !in selected) continue
        plan.add(i to (startNumber + printed))
        printed++
    }
    return plan
}

enum class PageNumberPosition(val label: String) {
    TOP_LEFT("Top Left"),
    TOP_CENTER("Top Center"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right")
}
enum class WatermarkPosition(val label: String) {
    TOP_LEFT("Top Left"),
    TOP_CENTER("Top Center"),
    TOP_RIGHT("Top Right"),
    CENTER("Center"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right")
}

/**
 * Maps a [WatermarkPosition] to an anchor point expressed as a fraction of the
 * page width/height (0..1). The x fraction is measured from the left edge; the
 * y fraction is measured from the BOTTOM edge (iText page coordinate space).
 *
 * This single source of truth is used by both the watermark preview (bitmap
 * coordinates, y from top) and the final PDF rendering (y from bottom), so a
 * watermark can never appear in one place in the preview and another in the
 * output. The returned anchor is the CENTER of the watermark text.
 */
fun watermarkAnchor(
    position: WatermarkPosition,
    pageWidth: Float,
    pageHeight: Float
): Pair<Float, Float> {
    val xFraction = when (position) {
        WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_LEFT -> 0.18f
        WatermarkPosition.TOP_CENTER, WatermarkPosition.CENTER, WatermarkPosition.BOTTOM_CENTER -> 0.5f
        else -> 0.82f  // TOP_RIGHT, BOTTOM_RIGHT
    }
    val yFractionFromBottom = when (position) {
        WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_CENTER, WatermarkPosition.TOP_RIGHT -> 0.85f
        WatermarkPosition.CENTER -> 0.5f
        else -> 0.15f  // BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }
    return pageWidth * xFraction to pageHeight * yFractionFromBottom
}
