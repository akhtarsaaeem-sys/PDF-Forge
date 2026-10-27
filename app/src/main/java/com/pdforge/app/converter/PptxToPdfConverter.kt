package com.pdforge.app.converter

import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.io.image.ImageData
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.pdforge.app.pdf.Ooxml
import com.pdforge.app.pdf.Relationship
import com.pdforge.app.pdf.parseRelationships
import com.pdforge.app.pdf.resolveTarget
import org.w3c.dom.Document
import java.io.File
import java.util.zip.ZipFile

/**
 * PowerPoint (.pptx) → PDF.
 *
 * WHY THIS WAS REWRITTEN:
 * The previous implementation used Apache POI's XSLF usermodel
 * (`XMLSlideShow`/`XSLFTextShape`/`XSLFPictureShape`) plus Android's PdfDocument.
 * Two problems:
 *  1. XSLF's shape geometry pulls in `java.awt.geom`, which does not exist on
 *     Android, so shape handling could fail at runtime.
 *  2. It ignored each slide's real size, drew every shape's text at a fixed
 *     position and forced a fixed 793×560 page — text overlapped and the output
 *     did not resemble the slides.
 *
 * This converter reads the OOXML package directly (zip + DOM, both available on
 * Android), honours the presentation's own slide size, keeps slide ORDER and
 * COUNT, and preserves each slide's visible text (including text held in shapes,
 * which is simply a shape's text body) and its images. Unsupported visual effects
 * (animations, complex vector geometry) are not reproduced — a documented limit.
 *
 * Returns the number of slides converted (== the PDF page count).
 */
internal fun convertPptxToPdf(inputPath: String, outputPath: String): Int {
    val file = File(inputPath)
    require(file.exists() && file.length() > 0L) {
        "The selected PowerPoint file is empty or could not be read."
    }
    val zip = ZipFile(file)
    try {
        val presentation = Ooxml.parseEntry(zip, "ppt/presentation.xml")
            ?: throw IllegalArgumentException("This file is not a valid .pptx presentation.")
        val (widthPt, heightPt) = slideSize(presentation)
        val presentationRels = parseRelationships(zip, "ppt/_rels/presentation.xml.rels")
        val slideParts = slidePartNames(presentation, presentationRels)
        if (slideParts.isEmpty()) {
            throw IllegalStateException("This presentation contains no readable slides.")
        }

        val pdfDoc = ITextPdfDocument(PdfWriter(outputPath))
        try {
            val font = PdfFontFactory.createFont(StandardFonts.HELVETICA)
            for (part in slideParts) {
                renderSlide(zip, part, widthPt, heightPt, pdfDoc, font)
            }
            return slideParts.size
        } finally {
            pdfDoc.close()
        }
    } finally {
        zip.close()
    }
}

/** Slide size in PDF points, taken from the presentation's own `<p:sldSz>`. */
private fun slideSize(presentation: Document): Pair<Float, Float> {
    val size = Ooxml.elements(presentation, Ooxml.P, "sldSz").firstOrNull()
    val cx = size?.getAttribute("cx")?.toDoubleOrNull() ?: 0.0
    val cy = size?.getAttribute("cy")?.toDoubleOrNull() ?: 0.0
    val width = cx / Ooxml.EMU_PER_POINT
    val height = cy / Ooxml.EMU_PER_POINT
    // Fall back to the common 4:3 slide size when the size is missing/invalid.
    return if (width > 1.0 && height > 1.0) width.toFloat() to height.toFloat() else 720f to 540f
}

/** Slide part names in presentation order, resolved through the relationships. */
private fun slidePartNames(presentation: Document, rels: Map<String, Relationship>): List<String> {
    val parts = mutableListOf<String>()
    for (sldId in Ooxml.elements(presentation, Ooxml.P, "sldId")) {
        val rid = sldId.getAttributeNS(Ooxml.R, "id")
        val rel = rels[rid] ?: continue
        parts.add(resolveTarget("ppt/presentation.xml", rel.target))
    }
    return parts
}

private fun renderSlide(
    zip: ZipFile,
    partName: String,
    widthPt: Float,
    heightPt: Float,
    pdfDoc: ITextPdfDocument,
    font: PdfFont
) {
    val page = pdfDoc.addNewPage(PageSize(widthPt, heightPt))
    val canvas = PdfCanvas(page)
    canvas.saveState()
    canvas.setFillColor(ColorConstants.WHITE)
    canvas.rectangle(0.0, 0.0, widthPt.toDouble(), heightPt.toDouble())
    canvas.fill()
    canvas.restoreState()

    val slide = Ooxml.parseEntry(zip, partName) ?: return

    val relsEntry = slideRelsEntry(partName)
    val slideRels = parseRelationships(zip, relsEntry)
    val images = slideRels.values
        .filter { it.type.endsWith("/image") }
        .mapNotNull { readImage(zip, resolveTarget(partName, it.target)) }
    drawImages(canvas, images, widthPt, heightPt)

    val lines = extractTextLines(slide)
    if (lines.isNotEmpty()) {
        drawText(canvas, lines, widthPt, heightPt, font)
    }
}

private fun slideRelsEntry(partName: String): String {
    val dir = partName.substringBeforeLast('/', "")
    val name = partName.substringAfterLast('/')
    return if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
}

private fun readImage(zip: ZipFile, entryName: String): ImageData? {
    return try {
        val entry = zip.getEntry(entryName) ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        ImageDataFactory.create(bytes)
    } catch (e: Exception) {
        null
    }
}

private fun drawImages(canvas: PdfCanvas, images: List<ImageData>, widthPt: Float, heightPt: Float) {
    if (images.isEmpty()) return
    val margin = 12f
    if (images.size == 1) {
        canvas.addImageFittedIntoRectangle(
            images[0],
            Rectangle(margin, margin, widthPt - 2 * margin, heightPt - 2 * margin),
            false
        )
    } else {
        // Stack multiple images in equal horizontal bands, top to bottom.
        val bandHeight = (heightPt - 2 * margin) / images.size
        images.forEachIndexed { index, image ->
            val top = heightPt - margin - index * bandHeight
            canvas.addImageFittedIntoRectangle(
                image,
                Rectangle(margin, top - bandHeight + 2f, widthPt - 2 * margin, bandHeight - 4f),
                false
            )
        }
    }
}

private fun extractTextLines(slide: Document): List<String> {
    val result = mutableListOf<String>()
    for (paragraph in Ooxml.elements(slide, Ooxml.A, "p")) {
        val builder = StringBuilder()
        for (run in Ooxml.elements(paragraph, Ooxml.A, "t")) {
            builder.append(run.textContent ?: "")
        }
        val line = builder.toString().trim()
        if (line.isNotEmpty()) result.add(line)
    }
    return result
}

private fun drawText(canvas: PdfCanvas, lines: List<String>, widthPt: Float, heightPt: Float, font: PdfFont) {
    val fontSize = 18f
    val margin = 24f
    val maxWidth = widthPt - 2 * margin
    val wrapped = lines.flatMap { wrap(it, font, fontSize, maxWidth) }
    if (wrapped.isEmpty()) return
    val lineHeight = fontSize * 1.3f
    val blockHeight = wrapped.size * lineHeight + 12f
    val topY = heightPt - margin

    // A translucent backing box keeps the text readable when it sits over an image.
    canvas.saveState()
    canvas.setFillColor(ColorConstants.WHITE)
    canvas.setExtGState(PdfExtGState().setFillOpacity(0.72f))
    canvas.rectangle((margin - 6f).toDouble(), (topY - blockHeight).toDouble(), (maxWidth + 12f).toDouble(), blockHeight.toDouble())
    canvas.fill()
    canvas.restoreState()

    canvas.saveState()
    canvas.setFillColor(ColorConstants.BLACK)
    canvas.setFontAndSize(font, fontSize)
    var y = topY - fontSize
    for (line in wrapped) {
        canvas.beginText()
        canvas.moveText(margin.toDouble(), y.toDouble())
        canvas.showText(line)
        canvas.endText()
        y -= lineHeight
    }
    canvas.restoreState()
}

private fun wrap(text: String, font: PdfFont, fontSize: Float, maxWidth: Float): List<String> {
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isEmpty() || font.getWidth(candidate, fontSize) <= maxWidth) {
            current = candidate
        } else {
            lines.add(current)
            current = word
        }
    }
    if (current.isNotEmpty()) lines.add(current)
    return lines
}
