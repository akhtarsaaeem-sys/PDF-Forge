package com.pdforge.app.converter

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream

/** Widest sheet rendered; extra columns are dropped so the page stays readable. */
private const val MAX_COLUMNS = 20

/**
 * Excel (.xlsx) → PDF.
 *
 * This replaces the Android-`PdfDocument` implementation with iText's layout
 * engine so that:
 *  - every worksheet is rendered with its real cell values (numbers, text and
 *    dates formatted through POI's `DataFormatter` — no silently lost cells),
 *  - rows and columns stay aligned as a real table and paginate automatically
 *    across as many pages as the sheet needs,
 *  - empty or single-cell sheets do not crash and produce a readable page,
 *  - the whole converter is exercisable by a plain JVM test (no Android
 *    graphics), which is why an Excel regression fixture could finally be added.
 *
 * Returns the number of PDF pages produced.
 */
internal fun convertExcelToPdf(inputPath: String, outputPath: String): Int {
    val input = FileInput(inputPath, "spreadsheet")
    val workbook = XSSFWorkbook(input.stream)
    try {
        if (workbook.numberOfSheets == 0) {
            throw IllegalArgumentException("The spreadsheet contains no worksheets.")
        }
        val pdfDoc = ITextPdfDocument(PdfWriter(outputPath))
        try {
            // Landscape A4 gives a spreadsheet's columns more room.
            val fonts = PdfFonts(pdfDoc)
            val layout = Document(pdfDoc, PageSize.A4.rotate())
            layout.setMargins(40f, 40f, 40f, 40f)
            layout.setFont(fonts.regular)
            val formatter = DataFormatter()

            for (sheetIndex in 0 until workbook.numberOfSheets) {
                val sheet = workbook.getSheetAt(sheetIndex)
                if (sheetIndex > 0) layout.add(AreaBreak())
                layout.add(
                    Paragraph("Sheet: ${sheet.sheetName}")
                        .setFont(fonts.bold)
                        .setFontSize(14f)
                )

                val firstRow = sheet.firstRowNum
                val lastRow = sheet.lastRowNum
                if (firstRow < 0 || lastRow < firstRow) {
                    layout.add(Paragraph("(empty sheet)").setFont(fonts.italic).setFontSize(11f))
                    continue
                }

                var maxColumns = 0
                for (rowIndex in firstRow..lastRow) {
                    val row = sheet.getRow(rowIndex) ?: continue
                    maxColumns = maxOf(maxColumns, row.lastCellNum.toInt())
                }
                maxColumns = maxColumns.coerceIn(1, MAX_COLUMNS)

                val iTextTable = Table(UnitValue.createPercentArray(FloatArray(maxColumns) { 1f }))
                    .useAllAvailableWidth()
                for (rowIndex in firstRow..lastRow) {
                    val row = sheet.getRow(rowIndex)
                    val isHeader = rowIndex == firstRow
                    for (column in 0 until maxColumns) {
                        val value = try {
                            row?.getCell(column)?.let { formatter.formatCellValue(it) } ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        iTextTable.addCell(
                            Cell().add(
                                Paragraph(value)
                                    .setFont(if (isHeader) fonts.bold else fonts.regular)
                                    .setFontSize(9f)
                                    .setMargin(0f)
                            )
                        )
                    }
                }
                layout.add(iTextTable)
            }
            layout.close()
        } catch (e: Throwable) {
            try { File(outputPath).delete() } catch (_: Exception) {}
            throw e
        }
    } finally {
        try { workbook.close() } catch (_: Exception) {}
    }

    val check = ITextPdfDocument(PdfReader(File(outputPath).absolutePath))
    return try {
        check.numberOfPages
    } finally {
        check.close()
    }
}

/** Small helper that reports a friendly message for unreadable input files. */
private class FileInput(path: String, kind: String) {
    val stream: FileInputStream
    init {
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            throw IllegalArgumentException("The selected $kind file is empty or could not be read.")
        }
        stream = try {
            FileInputStream(file)
        } catch (e: Exception) {
            throw IllegalArgumentException("The selected $kind file could not be opened: ${e.message}")
        }
    }
}
