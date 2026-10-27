package com.pdforge.app.pdf

import com.pdforge.app.storage.OutputValidator
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for the shared output-validation bug (Part J).
 *
 * Root cause: [OutputValidator] compared the little-endian value of a file's
 * first four bytes with big-endian magic constants, so `PK\x03\x04` (0x04034B50)
 * never matched 0x504B0304. EVERY generated DOCX/XLSX/PPTX therefore failed
 * validation with "Output validation failed" — the reported PDF→Word and
 * PDF→Excel failures.
 *
 * Validation must also dispatch on the real output type, not treat everything as
 * a PDF.
 */
class OutputValidatorTypeDispatchTest {

    private val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val pptxMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private fun makeDocx(dir: File, text: String = "Hello"): File {
        val file = File(dir, "doc.docx")
        val doc = XWPFDocument()
        doc.createParagraph().createRun().setText(text)
        FileOutputStream(file).use { doc.write(it) }
        doc.close()
        return file
    }

    private fun makeXlsx(dir: File): File {
        val file = File(dir, "book.xlsx")
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("Data")
        sheet.createRow(0).createCell(0).setCellValue("Header")
        FileOutputStream(file).use { wb.write(it) }
        wb.close()
        return file
    }

    private fun makeZip(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    // ── The old bug: valid OOXML must pass ───────────────────────────────────

    @Test
    fun a_real_docx_passes_docx_validation() {
        val dir = TestSupport.tempDir("dispatch_docx")
        val file = makeDocx(dir)
        assertTrue("a real DOCX must validate", OutputValidator.validateDocx(file))
    }

    @Test
    fun a_real_xlsx_passes_xlsx_validation() {
        val dir = TestSupport.tempDir("dispatch_xlsx")
        val file = makeXlsx(dir)
        assertTrue("a real XLSX must validate", OutputValidator.validateXlsx(file))
    }

    @Test
    fun the_pptx_fixture_passes_pptx_validation() {
        val dir = TestSupport.tempDir("dispatch_pptx")
        val file = File(dir, "deck.pptx")
        TestSupport.copyResourceToFile("/fixtures/sample_3slides.pptx", file)
        assertTrue("a real PPTX must validate", OutputValidator.validatePptx(file))
    }

    @Test
    fun ooxml_magic_bytes_are_compared_in_file_order() {
        // This is the exact regression: PK\x03\x04 must be recognised.
        val dir = TestSupport.tempDir("dispatch_magic")
        val file = makeDocx(dir)
        val header = file.inputStream().use { input ->
            val buffer = ByteArray(4)
            input.read(buffer)
            buffer.map { String.format("%02X", it) }
        }
        assertEquals(listOf("50", "4B", "03", "04"), header)
        assertTrue("OOXML signature must be recognised (old bug rejected it)", OutputValidator.validateDocx(file))
    }

    // ── Dispatch reports the real type ───────────────────────────────────────

    @Test
    fun outcome_reports_the_actual_output_type() {
        val dir = TestSupport.tempDir("dispatch_labels")
        val docx = makeDocx(dir)
        val xlsx = makeXlsx(dir)
        val pdf = File(dir, "in.pdf")
        TestSupport.createTextPdf(pdf.absolutePath, listOf("text"))

        assertEquals("Word document (.docx)", OutputValidator.validateOutcome(docx, docxMime).typeLabel)
        assertEquals("Excel workbook (.xlsx)", OutputValidator.validateOutcome(xlsx, xlsxMime).typeLabel)
        assertEquals("PDF file", OutputValidator.validateOutcome(pdf, "application/pdf").typeLabel)

        val pptx = File(dir, "deck.pptx")
        TestSupport.copyResourceToFile("/fixtures/sample_3slides.pptx", pptx)
        assertEquals("PowerPoint presentation (.pptx)", OutputValidator.validateOutcome(pptx, pptxMime).typeLabel)
    }

    // ── Broken packages are still rejected ───────────────────────────────────

    @Test
    fun a_zip_without_word_document_xml_is_rejected() {
        val dir = TestSupport.tempDir("dispatch_baddocx")
        val file = makeZip(dir, "not_really.docx", mapOf("hello.txt" to "nope"))
        assertFalse(OutputValidator.validateDocx(file))
        val outcome = OutputValidator.validateOutcome(file, docxMime)
        assertFalse(outcome.valid)
        assertTrue(outcome.message.contains("Word document", ignoreCase = true))
    }

    @Test
    fun a_zip_without_workbook_xml_is_rejected() {
        val dir = TestSupport.tempDir("dispatch_badxlsx")
        val file = makeZip(dir, "not_really.xlsx", mapOf("hello.txt" to "nope"))
        assertFalse(OutputValidator.validateXlsx(file))
    }

    @Test
    fun a_pptx_with_no_slides_is_rejected() {
        val dir = TestSupport.tempDir("dispatch_badpptx")
        val file = makeZip(
            dir, "empty.pptx",
            mapOf(
                "[Content_Types].xml" to "<Types/>",
                "ppt/presentation.xml" to "<p:presentation/>"
            )
        )
        assertFalse(OutputValidator.validatePptx(file))
    }

    @Test
    fun a_plain_text_file_named_docx_is_rejected() {
        val dir = TestSupport.tempDir("dispatch_text")
        val file = File(dir, "fake.docx")
        file.writeText("this is definitely not a zip package")
        assertFalse(OutputValidator.validateDocx(file))
    }

    @Test
    fun a_docx_is_not_validated_as_a_pdf() {
        val dir = TestSupport.tempDir("dispatch_cross")
        val file = makeDocx(dir)
        // A DOCX is not a PDF; the PDF validator must reject it.
        assertFalse(OutputValidator.validatePdf(file))
        // And the DOCX validator must accept it.
        assertTrue(OutputValidator.validateDocx(file))
    }
}
