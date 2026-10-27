package com.pdforge.app.pdf

import com.pdforge.app.storage.OutputValidator
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Regression tests for "PDF → Excel: Output validation failed".
 *
 * The validation failure was the shared validator rejecting every XLSX (see
 * [OutputValidatorTypeDispatchTest]). These tests run the real conversion
 * [pdfTableToXlsx] and verify the workbook is valid, the known table values are
 * present in their rows/columns, and that a PDF with no recoverable data fails
 * clearly instead of producing an empty "successful" workbook.
 */
class PdfToXlsxTest {

    private fun allCellValues(file: File): List<String> {
        XSSFWorkbook(FileInputStream(file)).use { wb ->
            val sheet = wb.getSheet("Extracted Data") ?: return emptyList()
            val formatter = DataFormatter()
            val values = mutableListOf<String>()
            for (row in sheet) {
                for (cell in row) values.add(formatter.formatCellValue(cell))
            }
            return values
        }
    }

    private fun rowsWithMultipleCells(file: File): Int {
        XSSFWorkbook(FileInputStream(file)).use { wb ->
            val sheet = wb.getSheet("Extracted Data") ?: return 0
            var count = 0
            for (row in sheet) {
                val cells = row.physicalNumberOfCells
                if (cells > 1) count++
            }
            return count
        }
    }

    @Test
    fun known_table_values_survive_conversion() {
        val dir = TestSupport.tempDir("pdf_xlsx")
        val input = File(dir, "table.pdf")
        TestSupport.createTablePdf(
            input.absolutePath,
            listOf(
                listOf("Region", "Revenue", "Units"),
                listOf("North", "120000", "1450"),
                listOf("South", "98000", "1180"),
                listOf("West", "143500", "1720")
            )
        )
        val output = File(dir, "out.xlsx")

        pdfTableToXlsx(input.absolutePath, output.absolutePath)

        assertTrue("output must exist and be non-empty", output.exists() && output.length() > 0)
        assertTrue("XLSX must pass real validation", OutputValidator.validateXlsx(output))
        val values = allCellValues(output)
        for (expected in listOf("Region", "Revenue", "Units", "North", "120000", "1450", "South", "98000", "West", "143500")) {
            assertTrue("cell value '$expected' missing from the workbook", values.any { it.trim() == expected })
        }
    }

    @Test
    fun rows_keep_multiple_columns() {
        val dir = TestSupport.tempDir("pdf_xlsx_cols")
        val input = File(dir, "table.pdf")
        TestSupport.createTablePdf(
            input.absolutePath,
            listOf(
                listOf("Header1", "Header2", "Header3"),
                listOf("A", "B", "C")
            )
        )
        val output = File(dir, "out.xlsx")
        pdfTableToXlsx(input.absolutePath, output.absolutePath)
        assertTrue("table rows must be spread across columns, not flattened", rowsWithMultipleCells(output) >= 2)
    }

    @Test
    fun a_multi_page_pdf_produces_a_multi_row_workbook() {
        val dir = TestSupport.tempDir("pdf_xlsx_pages")
        val input = File(dir, "long.pdf")
        val rows = mutableListOf(listOf("Header", "Value"))
        for (i in 0 until 120) rows.add(listOf("Row$i", "${i * 2}"))
        TestSupport.createTablePdf(input.absolutePath, rows)
        val output = File(dir, "out.xlsx")

        val pages = pdfTableToXlsx(input.absolutePath, output.absolutePath)
        assertTrue("the generated PDF should span multiple pages", pages >= 2)
        assertTrue(OutputValidator.validateXlsx(output))
        val values = allCellValues(output)
        assertTrue("rows from the last page must survive", values.any { it.trim() == "Row119" })
    }

    @Test
    fun a_pdf_without_tabular_data_fails_instead_of_faking_success() {
        val dir = TestSupport.tempDir("pdf_xlsx_empty")
        val input = File(dir, "blank.pdf")
        TestSupport.createTextPdf(input.absolutePath, listOf("", ""))
        val output = File(dir, "out.xlsx")

        val error = assertThrows(IllegalStateException::class.java) {
            pdfTableToXlsx(input.absolutePath, output.absolutePath)
        }
        assertTrue(
            "the error must explain that no data was recoverable",
            error.message!!.contains("No recoverable", ignoreCase = true)
        )
    }

    @Test
    fun a_valid_pdf_produces_an_xlsx_that_reopens_with_poi() {
        val dir = TestSupport.tempDir("pdf_xlsx_reopen")
        val input = File(dir, "table.pdf")
        TestSupport.createTablePdf(input.absolutePath, listOf(listOf("Only", "Row")))
        val output = File(dir, "out.xlsx")
        pdfTableToXlsx(input.absolutePath, output.absolutePath)

        XSSFWorkbook(FileInputStream(output)).use { wb ->
            assertEquals(1, wb.numberOfSheets)
            assertEquals("Extracted Data", wb.getSheetName(0))
        }
    }
}
