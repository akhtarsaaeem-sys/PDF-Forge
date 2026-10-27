package com.pdforge.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.pdforge.app.data.models.ProcessingResult
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class OcrEngine(private val context: Context) {

    private var tessBaseAPI: TessBaseAPI? = null
    private var isInitialized = false

    fun initialize(language: String = "eng"): Boolean {
        return try {
            val dataPath = File(context.filesDir, "tessdata")
            if (!dataPath.exists()) {
                dataPath.mkdirs()
            }

            val trainedDataFile = File(dataPath, "$language.traineddata")
            if (!trainedDataFile.exists()) {
                try {
                    context.assets.open("tessdata/$language.traineddata").use { input ->
                        FileOutputStream(trainedDataFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    return false
                }
            }

            tessBaseAPI = TessBaseAPI()
            tessBaseAPI?.init(context.filesDir.absolutePath, language)
            isInitialized = true
            true
        } catch (e: Exception) {
            false
        }
    }

    fun recognizeText(bitmap: Bitmap): String? {
        if (!isInitialized || tessBaseAPI == null) return null
        return try {
            tessBaseAPI?.setImage(bitmap)
            val text = tessBaseAPI?.utF8Text
            tessBaseAPI?.clear()
            text
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Makes [inputPdfPath] searchable.
     *
     * The original pages are preserved as-is (vector text stays vector text) and an
     * invisible, selectable text layer is added from OCR word boxes. No full-page
     * bitmap is embedded, so the output stays close to the input size instead of
     * growing by tens of megabytes.
     *
     * Pages that already contain real text are skipped and copied unchanged, so an
     * already-searchable document is not needlessly rasterized or OCR'd.
     */
    fun createSearchablePdf(
        inputPdfPath: String,
        outputPath: String,
        language: String = "eng",
        onProgress: ((String) -> Unit)? = null
    ): ProcessingResult {
        if (!isInitialized && !initialize(language)) {
            return ProcessingResult(
                success = false,
                message = "OCR initialization failed. Make sure the language data is bundled in assets/tessdata/."
            )
        }

        val inputFile = File(inputPdfPath)
        if (!inputFile.exists() || inputFile.length() <= 0L) {
            return ProcessingResult(false, message = "The selected PDF could not be read.")
        }

        return try {
            val fd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            try {
                val outcome = buildSearchablePdf(
                    inputPath = inputPdfPath,
                    outputPath = outputPath,
                    ocrPage = { pageNumber -> recognizePageWords(renderer, pageNumber - 1, onProgress) },
                    skipTextPages = true,
                    onProgress = { current, total ->
                        onProgress?.invoke("Recognizing text on page $current of $total")
                    }
                )
                val outputSize = File(outputPath).length()
                // Success only when something useful happened: either we added text, or
                // the document was already searchable. Finding no text at all is a real
                // failure, not a silent "success".
                if (outcome.pagesWithText == 0 && outcome.pagesAlreadyTextual == 0) {
                    try { File(outputPath).delete() } catch (_: Exception) {}
                    ProcessingResult(
                        success = false,
                        message = "OCR did not find any searchable text in this PDF. The pages may be blank or the language data may not match."
                    )
                } else if (outcome.pagesWithText == 0) {
                    ProcessingResult(
                        success = true,
                        outputPath = outputPath,
                        originalSize = inputFile.length(),
                        outputSize = outputSize,
                        pageCount = outcome.pageCount,
                        message = "This PDF already contains searchable text. It was copied without re-OCRing."
                    )
                } else {
                    ProcessingResult(
                        success = true,
                        outputPath = outputPath,
                        originalSize = inputFile.length(),
                        outputSize = outputSize,
                        pageCount = outcome.pageCount,
                        message = "OCR completed. ${outcome.pagesWithText} of ${outcome.pageCount} page(s) now have searchable text."
                    )
                }
            } finally {
                try { renderer.close() } catch (_: Exception) {}
                try { fd.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            // Clean up the partial output on any failure.
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "OCR processing failed: ${e.message}")
        }
    }

    /**
     * Renders page [index] to a temporary bitmap, runs Tesseract, and returns the
     * recognized word boxes. The bitmap is released before this returns.
     */
    private fun recognizePageWords(
        renderer: PdfRenderer,
        index: Int,
        onProgress: ((String) -> Unit)?
    ): OcrPageWords? {
        val api = tessBaseAPI ?: return null
        if (index < 0 || index >= renderer.pageCount) return null
        val page = renderer.openPage(index)
        val bitmap = try {
            // ~2000 px on the long edge is plenty for OCR and keeps the temporary
            // bitmap small; it is never embedded in the output.
            val longEdge = max(page.width, page.height)
            val scale = if (longEdge <= 0) 1f else (OCR_TARGET_LONG_EDGE_PX.toFloat() / longEdge).coerceIn(0.5f, 4f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (e: Exception) {
            page.close()
            return null
        }
        page.close()

        return try {
            val words = recognizeWordBoxes(api, bitmap)
            OcrPageWords(words = words, renderWidth = bitmap.width, renderHeight = bitmap.height)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Runs Tesseract on [bitmap] and reads the word-level boxes from the result iterator. */
    private fun recognizeWordBoxes(api: TessBaseAPI, bitmap: Bitmap): List<OcrWord> {
        return try {
            api.setImage(bitmap)
            // getUTF8Text() forces recognition to run; the result iterator is only
            // valid once recognition has completed.
            api.utF8Text
            val iterator = api.resultIterator ?: return emptyList()
            val words = mutableListOf<OcrWord>()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD) ?: ""
                    val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (text.isNotBlank() && rect != null && rect.width() > 0 && rect.height() > 0) {
                        words.add(OcrWord(text, rect.left, rect.top, rect.right, rect.bottom))
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            } finally {
                iterator.delete()
            }
            words.also { api.clear() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extract text from all pages of a PDF using OCR.
     */
    fun processPdfPages(pdfPath: String, outputDir: String, pages: List<Int>? = null): Map<Int, String> {
        val results = mutableMapOf<Int, String>()
        if (!isInitialized) return results

        try {
            val fd = ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val totalPages = renderer.pageCount
            val targetPages = pages?.filter { it in 1..totalPages }?.map { it - 1 }
                ?: (0 until totalPages).toList()

            for (pageNum in targetPages) {
                val page = renderer.openPage(pageNum)
                val scale = 3.0f
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtMost(4000),
                    (page.height * scale).toInt().coerceAtMost(5600),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val text = recognizeText(bitmap)
                if (text != null) {
                    results[pageNum + 1] = text
                }

                bitmap.recycle()
                page.close()
            }

            renderer.close()
            fd.close()
        } catch (e: Exception) {
            // Return whatever we've collected so far
        }

        return results
    }

    fun isLanguageDataAvailable(language: String): Boolean {
        val dataPath = File(context.filesDir, "tessdata")
        val trainedDataFile = File(dataPath, "$language.traineddata")
        if (trainedDataFile.exists()) return true

        // Check assets
        return try {
            context.assets.open("tessdata/$language.traineddata").use { true }
        } catch (e: Exception) {
            false
        }
    }

    fun getAvailableLanguages(): List<Pair<String, String>> {
        return SUPPORTED_LANGUAGES.filter { (code, _) -> isLanguageDataAvailable(code) }
    }

    fun destroy() {
        tessBaseAPI?.recycle()
        tessBaseAPI = null
        isInitialized = false
    }

    companion object {
        /** Long-edge render resolution used only to feed OCR (never embedded). */
        private const val OCR_TARGET_LONG_EDGE_PX = 2000

        val SUPPORTED_LANGUAGES = listOf(
            "eng" to "English"
        )
    }
}
