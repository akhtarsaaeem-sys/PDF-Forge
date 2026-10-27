package com.pdforge.app.converter

import android.content.Context
import com.pdforge.app.data.models.ProcessingResult
import java.io.File

/**
 * Office/HTML → PDF conversion facade.
 *
 * The real work lives in the top-level `convertXxxToPdf` functions (Word, PPTX,
 * Excel, HTML) so the conversions run the same code in the app and in unit tests.
 * Each converter renders with iText and reads the source with Apache POI (or a
 * direct OOXML zip reader for PPTX), which is both faithful and Android-safe —
 * the previous implementations drew onto an Android canvas with POI XSLF, which
 * lost content/ordering and referenced `java.awt.geom` (absent on Android).
 */
class DocumentConverter(private val context: Context) {

    fun wordToPdf(inputPath: String, outputPath: String): ProcessingResult =
        convert(outputPath, "Failed to convert Word document") {
            convertWordToPdf(inputPath, outputPath)
        }

    fun pptToPdf(inputPath: String, outputPath: String): ProcessingResult =
        convert(outputPath, "Failed to convert PowerPoint") {
            convertPptxToPdf(inputPath, outputPath)
        }

    fun excelToPdf(inputPath: String, outputPath: String): ProcessingResult =
        convert(outputPath, "Failed to convert Excel") {
            convertExcelToPdf(inputPath, outputPath)
        }

    fun htmlToPdf(inputPath: String, outputPath: String): ProcessingResult =
        convert(outputPath, "Failed to convert HTML") {
            convertHtmlToPdf(inputPath, outputPath)
        }

    private inline fun convert(outputPath: String, failurePrefix: String, action: () -> Int): ProcessingResult {
        return try {
            val pageCount = action()
            val file = File(outputPath)
            if (!file.exists() || file.length() <= 0L) {
                return ProcessingResult(false, message = "$failurePrefix: the generated PDF is empty.")
            }
            // Declare the real output type so the shared validator dispatches on
            // "application/pdf" rather than inferring it from the file name.
            ProcessingResult(true, outputPath, 0, file.length(), null, pageCount, mimeType = "application/pdf")
        } catch (e: Throwable) {
            // Catch Throwable, not just Exception, so an Error from a document
            // library (e.g. a missing java.awt class on Android) becomes a clear
            // failure instead of crashing the app. Never publish a partial file.
            try { File(outputPath).delete() } catch (_: Exception) {}
            ProcessingResult(false, message = "$failurePrefix: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
