package com.pdforge.app.pdf

import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.pdforge.app.data.models.ProcessingResult
import java.io.File

/**
 * Repairs a malformed/truncated PDF by re-writing it one page at a time.
 *
 * iText is opened with "unethical reading" so it can reconstruct a damaged xref
 * and recover as much as possible. Each page is copied through a fresh reader so
 * a single unreadable page does not abort the whole recovery.
 *
 * Deliberately free of Android logging so the real code path can run in a JVM
 * unit test (the previous behavior is unchanged; this is the same algorithm that
 * used to live inline in PdfEngine.repairPdf).
 */
internal fun repairPdfFile(inputPath: String, outputPath: String): ProcessingResult {
    val originalSize = File(inputPath).length()

    // Step 1: Try to open and count pages.
    val totalPages: Int
    try {
        val reader = PdfReader(inputPath)
        reader.setUnethicalReading(true)
        val doc = ITextPdfDocument(reader)
        totalPages = doc.numberOfPages
        doc.close()
        reader.close()
    } catch (e: Exception) {
        return ProcessingResult(
            false,
            message = "Cannot read this PDF file. The document may be too damaged or not a valid PDF."
        )
    }

    // Step 2: Progressive recovery — try page by page.
    var recoveredCount = 0
    val recoveredPages = mutableListOf<Int>()

    return try {
        val outputDoc = ITextPdfDocument(PdfWriter(outputPath))

        for (i in 1..totalPages) {
            try {
                val pageReader = PdfReader(inputPath)
                pageReader.setUnethicalReading(true)
                val pageDoc = ITextPdfDocument(pageReader)
                if (i <= pageDoc.numberOfPages) {
                    outputDoc.addPage(pageDoc.getPage(i).copyTo(outputDoc))
                    recoveredPages.add(i)
                    recoveredCount++
                }
                pageDoc.close()
                pageReader.close()
            } catch (e: Exception) {
                // Skip the unrecoverable page, keep the rest.
            }
        }

        val count = outputDoc.numberOfPages
        outputDoc.close()

        if (count == 0) {
            File(outputPath).delete()
            ProcessingResult(
                false,
                message = "Couldn't repair this PDF. The document appears to be too damaged to recover any pages."
            )
        } else {
            val outputSize = File(outputPath).length()
            val msg = if (recoveredCount == totalPages) {
                "Repair complete. Recovered all $totalPages pages."
            } else {
                "Repair complete. Recovered $recoveredCount of $totalPages pages."
            }
            ProcessingResult(true, outputPath, originalSize, outputSize, msg, count)
        }
    } catch (e: Exception) {
        val outFile = File(outputPath)
        if (outFile.exists() && outFile.length() > 0) {
            val outputSize = outFile.length()
            val msg = "Partial recovery. Recovered $recoveredCount of $totalPages pages."
            ProcessingResult(true, outputPath, originalSize, outputSize, msg, recoveredCount)
        } else {
            ProcessingResult(false, message = "Repair failed: ${e.message}")
        }
    }
}
