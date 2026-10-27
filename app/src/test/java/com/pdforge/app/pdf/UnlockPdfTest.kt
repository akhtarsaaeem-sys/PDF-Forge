package com.pdforge.app.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.pdforge.app.security.PdfSecurity
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unlock PDF.
 *
 * The encrypted inputs are produced by the project's OWN Protect PDF
 * implementation, and decryption goes through the production unlock path
 * ([PdfEngine.unlockPdf] / [PdfSecurity.unlockDocument], both delegating to
 * [unlockPdfFile]) so these tests exercise what the app actually runs.
 */
class UnlockPdfTest {

    private val engine = PdfEngine(null)

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun textPdf(path: String, pages: Int, text: String = "locked document"): File {
        val file = File(path)
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        val font = PdfFontFactory.createFont()
        repeat(pages) { index ->
            val page = doc.addNewPage()
            val canvas = PdfCanvas(page)
            canvas.beginText()
                .setFontAndSize(font, 12f)
                .moveText(72.0, 700.0)
                .showText("$text page ${index + 1}")
                .endText()
        }
        doc.close()
        return file
    }

    /** Encrypts [input] with the production Protect PDF implementation. */
    private fun protect(input: File, output: File, user: String, owner: String = user): File {
        val result = PdfSecurity().protectWithPassword(input.absolutePath, output.absolutePath, user, owner)
        assertTrue("precondition: Protect PDF must succeed (${result.message})", result.success)
        return output
    }

    private fun pageText(path: String): String {
        val doc = PdfDocument(PdfReader(path))
        return try {
            val builder = StringBuilder()
            for (i in 1..doc.numberOfPages) {
                val strategy = SimpleTextExtractionStrategy()
                PdfCanvasProcessor(strategy).processPageContent(doc.getPage(i))
                builder.append(strategy.resultantText).append('\n')
            }
            builder.toString()
        } finally {
            doc.close()
        }
    }

    private fun opensWithoutPassword(path: String): Boolean = try {
        val doc = PdfDocument(PdfReader(path))
        val pages = doc.numberOfPages
        doc.close()
        pages > 0
    } catch (e: Exception) {
        false
    }

    // ── A. Correct password ──────────────────────────────────────────────────

    @Test
    fun correct_password_produces_a_valid_unencrypted_pdf() {
        val d = dir("unlock_ok_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 3)
        val encrypted = protect(input, File(d, "locked.pdf"), "secret")
        val output = File(d, "unlocked.pdf")

        assertFalse("precondition: the input really is locked", opensWithoutPassword(encrypted.absolutePath))
        val originalText = pageText(input.absolutePath)

        val result = engine.unlockPdf(encrypted.absolutePath, output.absolutePath, "secret")

        assertTrue("unlock must succeed: ${result.message}", result.success)
        assertEquals(3, result.pageCount)
        assertEquals(output.absolutePath, result.outputPath)
        assertTrue("output must exist", output.exists() && output.length() > 0)
        assertTrue("the result must satisfy normal PDF validation", OutputValidator.validatePdf(output))
        assertTrue("the unlocked file must open without a password", opensWithoutPassword(output.absolutePath))
        assertEquals("visible content must be preserved", originalText.trim(), pageText(output.absolutePath).trim())
    }

    @Test
    fun the_owner_password_also_unlocks_the_document() {
        val d = dir("unlock_owner_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 2)
        val encrypted = protect(input, File(d, "locked.pdf"), user = "userpass", owner = "ownerpass")
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(encrypted.absolutePath, output.absolutePath, "ownerpass")
        assertTrue("the owner password must work too: ${result.message}", result.success)
        assertTrue(opensWithoutPassword(output.absolutePath))
    }

    @Test
    fun multi_page_encrypted_pdf_keeps_every_page_in_order() {
        val d = dir("unlock_pages_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 4, "page")
        val encrypted = protect(input, File(d, "locked.pdf"), "secret")
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(encrypted.absolutePath, output.absolutePath, "secret")
        assertTrue(result.success)

        val doc = PdfDocument(PdfReader(output.absolutePath))
        try {
            assertEquals(4, doc.numberOfPages)
        } finally {
            doc.close()
        }
        val text = pageText(output.absolutePath)
        for (page in 1..4) {
            assertTrue("page $page content must survive: $text", text.contains("page $page"))
        }
    }

    @Test
    fun unlock_document_uses_the_same_implementation() {
        val d = dir("unlock_delegate_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 1)
        val encrypted = protect(input, File(d, "locked.pdf"), "secret")
        val output = File(d, "unlocked.pdf")

        val result = PdfSecurity().unlockDocument(encrypted.absolutePath, output.absolutePath, "secret")
        assertTrue(result.success)
        assertTrue(opensWithoutPassword(output.absolutePath))
    }

    @Test
    fun a_permissions_only_pdf_is_still_rewritten_without_encryption() {
        // An encrypted document with an EMPTY user password opens freely but still
        // carries restrictions; unlocking must drop the encryption entirely.
        val d = dir("unlock_perm_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 1)
        val encrypted = protect(input, File(d, "locked.pdf"), user = "", owner = "ownerpass")
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(encrypted.absolutePath, output.absolutePath, "")
        assertTrue("a restrictions-only PDF must still be unlockable: ${result.message}", result.success)

        val reader = PdfReader(output.absolutePath)
        val doc = PdfDocument(reader)
        val stillEncrypted = reader.isEncrypted
        doc.close()
        assertFalse("the rewritten output must not carry /Encrypt", stillEncrypted)
    }

    // ── B. Incorrect password ────────────────────────────────────────────────

    @Test
    fun wrong_password_fails_clearly_and_publishes_nothing() {
        val d = dir("unlock_wrong_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 2)
        val encrypted = protect(input, File(d, "locked.pdf"), "secret")
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(encrypted.absolutePath, output.absolutePath, "nope")

        assertFalse("a wrong password must fail", result.success)
        assertTrue(
            "the message must point at the password: ${result.message}",
            result.message!!.contains("Incorrect password", ignoreCase = true)
        )
        assertFalse("no partial output may be left behind", output.exists())
    }

    @Test
    fun an_empty_password_against_a_password_protected_pdf_fails_clearly() {
        val d = dir("unlock_empty_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 1)
        val encrypted = protect(input, File(d, "locked.pdf"), "secret")
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(encrypted.absolutePath, output.absolutePath, "")

        assertFalse(result.success)
        assertTrue(
            "an empty password must be reported as an incorrect password, not as an unencrypted file: ${result.message}",
            result.message!!.contains("Incorrect password", ignoreCase = true)
        )
        assertFalse(output.exists())
    }

    // ── C. Unsupported / malformed input ─────────────────────────────────────

    @Test
    fun a_damaged_file_is_reported_as_unreadable() {
        val d = dir("unlock_damaged_")
        val junk = File(d, "damaged.pdf")
        junk.writeText("this is definitely not a PDF file")
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(junk.absolutePath, output.absolutePath, "secret")

        assertFalse(result.success)
        assertTrue(
            "the message must explain the file could not be read: ${result.message}",
            result.message!!.contains("could not be read", ignoreCase = true)
        )
        assertFalse(output.exists())
    }

    @Test
    fun a_missing_or_empty_file_fails_without_crashing() {
        val d = dir("unlock_missing_")
        val output = File(d, "unlocked.pdf")

        val missing = engine.unlockPdf(File(d, "does_not_exist.pdf").absolutePath, output.absolutePath, "x")
        assertFalse(missing.success)
        assertTrue(missing.message!!.isNotBlank())

        val empty = File(d, "empty.pdf")
        empty.writeText("")
        val emptyResult = engine.unlockPdf(empty.absolutePath, output.absolutePath, "x")
        assertFalse(emptyResult.success)
        assertTrue(emptyResult.message!!.isNotBlank())
        assertFalse(output.exists())
    }

    // ── D. Unencrypted input ─────────────────────────────────────────────────

    @Test
    fun an_unencrypted_pdf_is_explained_instead_of_silently_copied() {
        val d = dir("unlock_plain_")
        val input = textPdf(File(d, "input.pdf").absolutePath, 1)
        val output = File(d, "unlocked.pdf")

        val result = engine.unlockPdf(input.absolutePath, output.absolutePath, "")

        assertFalse("an unencrypted file must not be reported as unlocked", result.success)
        assertTrue(
            "the message must explain there is nothing to unlock: ${result.message}",
            result.message!!.contains("not password-protected", ignoreCase = true)
        )
        assertFalse("no fake output may be published", output.exists())
    }

    @Test
    fun the_encryption_state_classifier_matches_the_inputs() {
        val d = dir("unlock_state_")
        val plain = textPdf(File(d, "plain.pdf").absolutePath, 1)
        val encrypted = protect(plain, File(d, "locked.pdf"), "secret")
        val permissionsOnly = protect(plain, File(d, "owner.pdf"), user = "", owner = "ownerpass")
        val junk = File(d, "junk.pdf").apply { writeText("nope") }

        assertEquals(PdfEncryptionState.NOT_ENCRYPTED, pdfEncryptionState(plain.absolutePath))
        assertEquals(PdfEncryptionState.ENCRYPTED, pdfEncryptionState(encrypted.absolutePath))
        assertEquals(PdfEncryptionState.ENCRYPTED_OPENABLE, pdfEncryptionState(permissionsOnly.absolutePath))
        assertEquals(PdfEncryptionState.UNREADABLE, pdfEncryptionState(junk.absolutePath))
    }
}
