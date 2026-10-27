package com.pdforge.app.pdf

import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.pdforge.app.scanner.ScanPageLayout
import java.io.File

/**
 * Writes one PDF page per image into [outputPath] and returns the page count.
 *
 * WHY (JPG → PDF white-border bug):
 * The old implementation placed every image on a fixed A4 page and called
 * `scaleToFit`, then centred it. A landscape photo therefore shrank to the A4
 * content width and left large accidental white bands above and below — the same
 * class of bug the scanner had. Here each PDF page is built with the SAME aspect
 * ratio as its image (see [ScanPageLayout.pageSize], the scanner's shared layout
 * maths) and the image is fitted into that page edge-to-edge:
 *
 *  - the page long edge stays at a standard document length (A4's 842 pt),
 *  - portrait images produce portrait pages, landscape images landscape pages,
 *  - the image is never stretched (page aspect == image aspect),
 *  - the image is never cropped (it is fitted, not clipped),
 *  - there are no margins, so no unintended white border remains.
 *
 * Extracted from [PdfEngine.createPdfFromImages] so the layout is covered by a
 * JVM test that runs the real production code.
 */
internal fun imagesToPdf(imagePaths: List<String>, outputPath: String): Int {
    require(imagePaths.isNotEmpty()) { "No images to convert." }
    val pdfDoc = ITextPdfDocument(PdfWriter(outputPath))
    try {
        for (path in imagePaths) {
            val imageData = ImageDataFactory.create(path)
            val (pageWidth, pageHeight) = ScanPageLayout.pageSize(
                imageData.width.toInt(),
                imageData.height.toInt()
            )
            val page = pdfDoc.addNewPage(PageSize(pageWidth, pageHeight))
            val canvas = PdfCanvas(page)
            // Fits the image into the whole page, preserving the aspect ratio.
            // Because the page aspect matches the image aspect, this fills the
            // page exactly and leaves no border.
            canvas.addImageFittedIntoRectangle(
                imageData,
                Rectangle(0f, 0f, pageWidth, pageHeight),
                false
            )
            canvas.release()
        }
        return pdfDoc.numberOfPages
    } finally {
        pdfDoc.close()
    }
}

/**
 * True when a generated image-to-PDF output holds exactly one page per source
 * image. Used to keep the result reporting honest.
 */
internal fun imagePdfPageCount(outputPath: String): Int {
    val doc = ITextPdfDocument(com.itextpdf.kernel.pdf.PdfReader(File(outputPath).absolutePath))
    return try {
        doc.numberOfPages
    } finally {
        doc.close()
    }
}
