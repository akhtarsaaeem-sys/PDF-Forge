package com.pdforge.app.converter

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File
import java.io.FileInputStream

/**
 * Word (.docx) → PDF.
 *
 * WHY THIS WAS REWRITTEN:
 * The old converter drew text onto an Android `PdfDocument` with `Canvas`/`Paint`.
 * It iterated `document.paragraphs` and then `document.tables` SEPARATELY, so
 * every table was moved to the end of the document (paragraph ordering was lost);
 * embedded images and explicit page breaks were dropped entirely; a paragraph's
 * mixed formatting collapsed to a single style; table cell text was truncated to
 * 30 characters; and headings/lists/alignment were barely handled. The result
 * "looked poor and some content disappeared".
 *
 * This converter renders with iText's layout engine (already a project
 * dependency) reading the document with Apache POI XWPF (already used on device,
 * no `java.awt` involved for text/tables). It walks `bodyElements` so paragraphs
 * and tables keep their TRUE document order, and preserves:
 *   - every paragraph's text (never silently discarded),
 *   - headings (by style) with sensible sizes,
 *   - bold / italic and per-run font size,
 *   - paragraph alignment and spacing,
 *   - tables (all rows and columns, full cell text),
 *   - embedded images,
 *   - explicit page breaks,
 *   - list paragraphs (rendered as bulleted lines).
 *
 * Documented limits: field codes, footnotes/endnotes, text boxes and complex
 * numbering schemes are not reproduced; their surrounding text is preserved.
 *
 * Returns the number of pages in the generated PDF.
 */
internal fun convertWordToPdf(inputPath: String, outputPath: String): Int {
    val document = XWPFDocument(FileInputStream(inputPath))
    val pdfDoc = ITextPdfDocument(PdfWriter(outputPath))
    try {
        val fonts = PdfFonts(pdfDoc)
        val layout = Document(pdfDoc, PageSize.A4)
        layout.setMargins(54f, 54f, 54f, 54f)
        layout.setFont(fonts.regular)

        for (element in document.bodyElements) {
            when (element) {
                is XWPFParagraph -> addParagraph(layout, element, fonts)
                is XWPFTable -> addTable(layout, element, fonts)
            }
        }
        layout.close()
    } finally {
        try { document.close() } catch (_: Exception) {}
    }

    // Report the real page count by reopening the finished file.
    val check = ITextPdfDocument(PdfReader(File(outputPath).absolutePath))
    return try {
        check.numberOfPages
    } finally {
        check.close()
    }
}

private fun headingSize(normalizedStyleId: String): Float? = when {
    normalizedStyleId.contains("title") -> 26f
    normalizedStyleId.contains("heading1") -> 22f
    normalizedStyleId.contains("heading2") -> 18f
    normalizedStyleId.contains("heading3") -> 15f
    normalizedStyleId.contains("heading") -> 14f
    else -> null
}

private fun addParagraph(layout: Document, paragraph: XWPFParagraph, fonts: PdfFonts) {
    val styleId = (paragraph.style ?: paragraph.styleID ?: "").lowercase().replace(" ", "")
    val headingPt = headingSize(styleId)
    val isHeading = headingPt != null

    val target = Paragraph()
    target.setMultipliedLeading(1.15f)
    target.setMarginTop((paragraph.spacingBefore / 20f).coerceIn(0f, 36f))
    target.setMarginBottom((paragraph.spacingAfter / 20f).coerceIn(0f, 36f).coerceAtLeast(if (paragraph.text.isBlank()) 6f else 2f))
    target.setTextAlignment(paragraph.alignment.toIText())

    val runs = paragraph.runs
    val prefix = if (paragraph.isListItemByNumbering()) "• " else ""
    if (prefix.isNotEmpty()) target.add(Text(prefix).setFont(fonts.regular).setFontSize(11f))

    for (run in runs) {
        val runText = run.text() ?: ""
        if (runText.isNotEmpty()) {
            val runSize = run.fontSizeAsDouble?.toFloat()
            val size = when {
                isHeading && (runSize == null || runSize < headingPt!!) -> headingPt
                runSize != null && runSize > 0f -> runSize
                else -> 11f
            }
            val isBold = run.isBold || isHeading
            target.add(Text(runText).setFont(fonts.forStyle(isBold, run.isItalic)).setFontSize(size))
        }
        for (picture in run.embeddedPictures) {
            val bytes = picture.pictureData?.data ?: continue
            try {
                val image = Image(com.itextpdf.io.image.ImageDataFactory.create(bytes))
                image.setAutoScale(true)
                target.add(image)
            } catch (_: Exception) {
                // Unsupported image format: keep the rest of the run/paragraph.
            }
        }
    }

    // A run may carry only a picture, and a paragraph may be empty. Either way the
    // paragraph element still gets added so vertical spacing and order are kept.
    layout.add(target)

    if (paragraph.hasExplicitPageBreak()) {
        layout.add(AreaBreak())
    }
}

/**
 * Best-effort list detection.
 *
 * POI exposes numbering through the paragraph's `w:numPr`; `getNumIlvl()` is
 * non-null when the paragraph is numbered. The previous converter ignored lists
 * entirely.
 */
private fun XWPFParagraph.isListItemByNumbering(): Boolean {
    return try {
        numIlvl != null
    } catch (_: Exception) {
        false
    }
}

/** True when the paragraph contains an explicit `<w:br w:type="page"/>`. */
private fun XWPFParagraph.hasExplicitPageBreak(): Boolean {
    return try {
        ctp.xmlText().contains("w:type=\"page\"")
    } catch (_: Exception) {
        false
    }
}

private fun ParagraphAlignment?.toIText(): TextAlignment = when (this) {
    ParagraphAlignment.CENTER -> TextAlignment.CENTER
    ParagraphAlignment.RIGHT -> TextAlignment.RIGHT
    ParagraphAlignment.BOTH -> TextAlignment.JUSTIFIED
    ParagraphAlignment.DISTRIBUTE -> TextAlignment.JUSTIFIED
    else -> TextAlignment.LEFT
}

private fun addTable(layout: Document, table: XWPFTable, fonts: PdfFonts) {
    val columns = table.rows.maxOfOrNull { it.tableCells.size } ?: 0
    if (columns == 0) return
    val widths = FloatArray(columns) { 1f }
    val iTextTable = Table(UnitValue.createPercentArray(widths)).useAllAvailableWidth()
    for (row in table.rows) {
        for (column in 0 until columns) {
            val cellText = row.getCell(column)?.text ?: ""
            val cellFont = if (row === table.rows.firstOrNull() && cellText.isNotBlank()) fonts.bold else fonts.regular
            iTextTable.addCell(
                Cell().add(
                    Paragraph(cellText).setFont(cellFont).setFontSize(10f).setMargin(0f)
                )
            )
        }
    }
    // Keep a little breathing room around the table.
    layout.add(Paragraph().setMarginTop(4f).setMarginBottom(2f))
    layout.add(iTextTable)
}
