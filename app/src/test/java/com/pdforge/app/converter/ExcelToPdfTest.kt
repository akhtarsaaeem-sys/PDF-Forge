package com.pdforge.app.converter

import com.pdforge.app.pdf.TestSupport
import com.pdforge.app.storage.OutputValidator
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Regression tests for Excel → PDF.
 *
 * There was no convenient spreadsheet to test manually, so a small deterministic
 * fixture is generated here with the project's own Excel library (POI XSSF): a
 * named worksheet with a header row, text cells and numeric cells, plus an empty
 * sheet to exercise the degenerate path.
 */
class ExcelToPdfTest {

    private fun buildWorkbook(file: File) {
        val workbook = XSSFWorkbook()
        val sales = workbook.createSheet("Sales")
        val header = sales.createRow(0)
        header.createCell(0).setCellValue("Region")
        header.createCell(1).setCellValue("Revenue")
        header.createCell(2).setCellValue("Units")
        val data = listOf(
            Triple("North", 120000.0, 1450.0),
            Triple("South", 98000.0, 1180.0),
            Triple("West", 143500.0, 1720.0),
            Triple("East", 87000.0, 990.0)
        )
        data.forEachIndexed { index, (region, revenue, units) ->
            val row = sales.createRow(index + 1)
            row.createCell(0).setCellValue(region)
            row.createCell(1).setCellValue(revenue)
            row.createCell(2).setCellValue(units)
        }
        workbook.createSheet("Empty")
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()
    }

    @Test
    fun every_sheet_is_rendered_with_its_cell_values() {
        val dir = TestSupport.tempDir("excel_pdf")
        val input = File(dir, "in.xlsx")
        buildWorkbook(input)
        val output = File(dir, "out.pdf")

        convertExcelToPdf(input.absolutePath, output.absolutePath)

        assertTrue("output must be non-empty", output.exists() && output.length() > 0)
        assertTrue("output must be a valid PDF", OutputValidator.validatePdf(output))
        val text = TestSupport.pdfText(output.absolutePath)
        for (expected in listOf("Sheet: Sales", "Region", "Revenue", "Units", "North", "South", "West")) {
            assertTrue("missing '$expected' in the generated PDF", text.contains(expected))
        }
    }

    @Test
    fun an_empty_sheet_does_not_crash_and_is_flagged() {
        val dir = TestSupport.tempDir("excel_pdf_empty")
        val input = File(dir, "in.xlsx")
        buildWorkbook(input)
        val output = File(dir, "out.pdf")
        convertExcelToPdf(input.absolutePath, output.absolutePath)

        val text = TestSupport.pdfText(output.absolutePath)
        assertTrue("the empty sheet must still be represented", text.contains("Sheet: Empty"))
        assertTrue("the empty sheet must be marked as empty", text.contains("empty sheet"))
    }

    @Test
    fun a_workbook_with_no_sheets_fails_clearly() {
        val dir = TestSupport.tempDir("excel_pdf_nosheets")
        val input = File(dir, "nosheets.xlsx")
        val workbook = XSSFWorkbook()
        FileOutputStream(input).use { workbook.write(it) }
        workbook.close()
        val output = File(dir, "out.pdf")

        val error = assertThrows(IllegalArgumentException::class.java) {
            convertExcelToPdf(input.absolutePath, output.absolutePath)
        }
        assertTrue(error.message!!.contains("no worksheets", ignoreCase = true))
    }

    @Test
    fun a_corrupt_input_produces_an_error_not_a_crash_or_empty_file() {
        val dir = TestSupport.tempDir("excel_pdf_corrupt")
        val input = File(dir, "corrupt.xlsx")
        input.writeText("definitely not a spreadsheet")
        val output = File(dir, "out.pdf")

        assertThrows(Exception::class.java) {
            convertExcelToPdf(input.absolutePath, output.absolutePath)
        }
    }

    @Test
    fun a_sheet_with_more_rows_than_fit_still_converts() {
        val dir = TestSupport.tempDir("excel_pdf_long")
        val input = File(dir, "long.xlsx")
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Long")
        sheet.createRow(0).createCell(0).setCellValue("Index")
        for (i in 1..120) {
            sheet.createRow(i).createCell(0).setCellValue("Row$i")
        }
        FileOutputStream(input).use { workbook.write(it) }
        workbook.close()
        val output = File(dir, "out.pdf")

        convertExcelToPdf(input.absolutePath, output.absolutePath)
        assertTrue("a long sheet should span multiple pages", TestSupport.pdfPageCount(output.absolutePath) >= 2)
        assertTrue(TestSupport.pdfText(output.absolutePath).contains("Row120"))
    }
}
