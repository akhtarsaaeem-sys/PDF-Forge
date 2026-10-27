package com.pdforge.app.converter

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument

/**
 * The standard (built-in) Helvetica family used by the Office/HTML converters.
 *
 * IMPORTANT: iText font objects must be bound to the document that uses them. A
 * font created once and reused across two different `PdfDocument`s makes the
 * second document fail with
 *   "Pdf indirect object belongs to other PDF document"
 * when it is written. This class therefore creates the fonts with the target
 * document (`createFont(name, encoding, pdfDocument)`), so every conversion gets
 * its own correctly-bound fonts. The fonts ship with every PDF reader, so no
 * external font files are needed (keeping the app fully offline).
 */
internal class PdfFonts(pdfDocument: PdfDocument) {
    val regular: PdfFont = PdfFontFactory.createFont(StandardFonts.HELVETICA, PdfEncodings.WINANSI, pdfDocument)
    val bold: PdfFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD, PdfEncodings.WINANSI, pdfDocument)
    val italic: PdfFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE, PdfEncodings.WINANSI, pdfDocument)
    val boldItalic: PdfFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLDOBLIQUE, PdfEncodings.WINANSI, pdfDocument)

    fun forStyle(isBold: Boolean, isItalic: Boolean): PdfFont = when {
        isBold && isItalic -> boldItalic
        isBold -> bold
        isItalic -> italic
        else -> regular
    }
}
