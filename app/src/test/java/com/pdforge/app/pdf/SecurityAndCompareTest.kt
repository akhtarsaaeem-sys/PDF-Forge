package com.pdforge.app.pdf

import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.ReaderProperties
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import com.pdforge.app.security.PdfSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Code-level verification of the security tools (Protect / Unlock), the
 * Compare tool's diff logic, and graceful failure on truncated/corrupt input.
 */
class SecurityAndCompareTest {

    private fun createPdfWithText(path: String, pages: Int, text: String): File {
        val doc = PdfDocument(PdfWriter(path))
        val font = PdfFontFactory.createFont()
        repeat(pages) { p ->
            val page = doc.addNewPage()
            val canvas = PdfCanvas(page)
            canvas.beginText()
            canvas.setFontAndSize(font, 12f)
            canvas.moveText(72.0, 720.0)
            canvas.showText("$text page ${p + 1}")
            canvas.endText()
        }
        doc.close()
        return File(path)
    }

    // ── Protect: output is actually encrypted ───────────────────────────────

    @Test
    fun protect_produces_an_encrypted_pdf_that_requires_the_password() {
        val dir = Files.createTempDirectory("protect_test").toFile()
        val input = createPdfWithText(File(dir, "input.pdf").absolutePath, 2, "secret doc")
        val protectedFile = File(dir, "protected.pdf")

        // Same sequence as PdfEngine.protectPdf / PdfSecurity.protectWithPassword
        val writerProps = WriterProperties().setStandardEncryption(
            "userpass".toByteArray(), "ownerpass".toByteArray(),
            EncryptionConstants.ALLOW_PRINTING, EncryptionConstants.STANDARD_ENCRYPTION_128
        )
        val pdfDoc = PdfDocument(PdfReader(input.absolutePath), PdfWriter(protectedFile.absolutePath, writerProps))
        val count = pdfDoc.numberOfPages
        pdfDoc.close()

        assertEquals(2, count)
        assertTrue(protectedFile.length() > 0)

        // Opening WITHOUT the password must fail (proves it is really encrypted).
        var openedWithoutPassword = false
        try {
            val r = PdfReader(protectedFile.absolutePath)
            val d = PdfDocument(r)
            openedWithoutPassword = d.numberOfPages > 0
            d.close()
        } catch (e: Exception) {
            openedWithoutPassword = false
        }
        assertFalse("protected PDF must not open without the password", openedWithoutPassword)

        // Opening WITH the password works and preserves pages.
        val reader = PdfReader(protectedFile.absolutePath, ReaderProperties().setPassword("userpass".toByteArray()))
        val doc = PdfDocument(reader)
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }

    // ── Unlock: output opens without a password ─────────────────────────────

    @Test
    fun unlock_removes_the_password_and_output_opens_plain() {
        val dir = Files.createTempDirectory("unlock_test").toFile()
        val input = createPdfWithText(File(dir, "input.pdf").absolutePath, 2, "locked")
        val protectedFile = File(dir, "locked.pdf")

        val writerProps = WriterProperties().setStandardEncryption(
            "secret".toByteArray(), "secret".toByteArray(),
            EncryptionConstants.ALLOW_PRINTING, EncryptionConstants.STANDARD_ENCRYPTION_128
        )
        PdfDocument(PdfReader(input.absolutePath), PdfWriter(protectedFile.absolutePath, writerProps)).close()

        // Same sequence as PdfEngine.unlockPdf / PdfSecurity.unlockDocument
        val reader = PdfReader(protectedFile.absolutePath, ReaderProperties().setPassword("secret".toByteArray()))
        reader.setUnethicalReading(true)
        val unlocked = File(dir, "unlocked.pdf")
        val pdfDoc = PdfDocument(reader, PdfWriter(unlocked.absolutePath))
        val count = pdfDoc.numberOfPages
        pdfDoc.close()

        assertEquals(2, count)
        assertTrue(unlocked.length() > 0)

        // The unlocked output opens WITHOUT any password.
        val reopened = PdfDocument(PdfReader(unlocked.absolutePath))
        assertEquals(2, reopened.numberOfPages)
        reopened.close()
    }

    // ── Compare: diff logic ─────────────────────────────────────────────────

    @Test
    fun compare_identical_pdfs_report_no_differences() {
        val dir = Files.createTempDirectory("compare_id").toFile()
        val a = createPdfWithText(File(dir, "a.pdf").absolutePath, 2, "identical")
        val b = createPdfWithText(File(dir, "b.pdf").absolutePath, 2, "identical")

        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)
        assertTrue("identical docs must report no differences: ${result.differences}", result.areIdentical)
        assertEquals(0, result.pageCountDiff)
        assertEquals(0, result.addedLines)
        assertEquals(0, result.removedLines)
        assertEquals(0, result.changedPages)
    }

    @Test
    fun compare_detects_changed_text_and_word_counts() {
        val dir = Files.createTempDirectory("compare_diff").toFile()
        val a = createPdfWithText(File(dir, "a.pdf").absolutePath, 2, "original")
        val b = createPdfWithText(File(dir, "b.pdf").absolutePath, 2, "modified")

        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)
        assertFalse("changed docs must report differences", result.areIdentical)
        assertTrue(result.changedPages >= 1)
        assertTrue(result.differences.isNotEmpty())
        assertTrue(result.totalWordsA > 0)
        assertTrue(result.totalWordsB > 0)
    }

    @Test
    fun compare_reports_different_page_counts() {
        val dir = Files.createTempDirectory("compare_pages").toFile()
        val a = createPdfWithText(File(dir, "a.pdf").absolutePath, 3, "doc")
        val b = createPdfWithText(File(dir, "b.pdf").absolutePath, 2, "doc")

        val result = PdfSecurity().comparePdfs(a.absolutePath, b.absolutePath)
        assertFalse(result.areIdentical)
        assertEquals(-1, result.pageCountDiff)
        assertTrue(result.differences.any { it.contains("pages") })
    }

    // ── Truncated / corrupt input fails gracefully ──────────────────────────

    @Test
    fun truncated_pdf_fails_validation_gracefully() {
        val dir = Files.createTempDirectory("truncated_test").toFile()
        val file = createPdfWithText(File(dir, "doc.pdf").absolutePath, 2, "content")
        val truncated = File(dir, "truncated.pdf")
        // Keep only the header + partial body — the trailer/xref is gone.
        val bytes = file.readBytes()
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))

        var opens = false
        try {
            val r = PdfReader(truncated.absolutePath)
            val d = PdfDocument(r)
            opens = d.numberOfPages > 0
            d.close()
        } catch (e: Exception) {
            opens = false
        }
        assertFalse("truncated pdf must not validate as a readable document", opens)
    }
}