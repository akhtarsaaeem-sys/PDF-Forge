package com.pdforge.app.pdf

import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.pdforge.app.security.PdfSecurity
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the encrypted-output validation mode used by Protect PDF.
 *
 * Encrypted output must be validated for real (it is encrypted, it opens with the
 * supplied password, and it has pages) — not by catching BadPasswordException and
 * returning true. Normal validation must stay strict.
 */
class ProtectPdfValidationTest {

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun plainPdf(path: String, pages: Int = 3): File {
        val doc = PdfDocument(PdfWriter(path))
        repeat(pages) { doc.addNewPage() }
        doc.close()
        return File(path)
    }

    /** Same encryption sequence as PdfEngine.protectPdf. */
    private fun encryptedPdf(path: String, userPassword: String, ownerPassword: String = userPassword): File {
        val input = plainPdf(File(File(path).parentFile, "src_${File(path).name}").absolutePath)
        val writerProps = WriterProperties().setStandardEncryption(
            userPassword.toByteArray(), ownerPassword.toByteArray(),
            EncryptionConstants.ALLOW_PRINTING, EncryptionConstants.STANDARD_ENCRYPTION_128
        )
        val doc = PdfDocument(PdfReader(input.absolutePath), PdfWriter(path, writerProps))
        doc.close()
        return File(path)
    }

    @Test
    fun encrypted_output_is_valid_with_the_correct_password() {
        val file = encryptedPdf(File(dir("protect_ok"), "protected.pdf").absolutePath, "secret")
        assertTrue("generated protected PDF must be structurally present", file.length() > 0L)
        assertTrue(
            "encrypted output must validate with its password",
            OutputValidator.validateEncryptedPdf(file, "secret")
        )
    }

    @Test
    fun encrypted_output_is_rejected_with_the_wrong_password() {
        val file = encryptedPdf(File(dir("protect_bad"), "protected.pdf").absolutePath, "secret")
        assertFalse(OutputValidator.validateEncryptedPdf(file, "wrong"))
    }

    @Test
    fun unencrypted_pdf_is_not_valid_as_encrypted_output() {
        val file = plainPdf(File(dir("protect_plain"), "plain.pdf").absolutePath)
        // Protect PDF always encrypts; a plain file must not pass the encrypted mode.
        assertFalse(OutputValidator.validateEncryptedPdf(file, "secret"))
    }

    @Test
    fun non_pdf_is_rejected_by_the_encrypted_mode() {
        val file = File(dir("protect_junk"), "junk.pdf")
        file.writeText("not a pdf")
        assertFalse(OutputValidator.validateEncryptedPdf(file, "secret"))
    }

    @Test
    fun missing_file_is_rejected_by_the_encrypted_mode() {
        assertFalse(OutputValidator.validateEncryptedPdf(File(dir("protect_missing"), "x.pdf"), "secret"))
    }

    @Test
    fun real_protect_engine_output_validates_with_its_password() {
        val d = dir("protect_e2e")
        val input = plainPdf(File(d, "input.pdf").absolutePath)
        val out = File(d, "protected.pdf")

        val result = PdfSecurity().protectWithPassword(
            input.absolutePath, out.absolutePath, "secret", "secret"
        )

        assertTrue("protect must succeed", result.success)
        assertTrue("protected output must validate with the password", OutputValidator.validateEncryptedPdf(out, "secret"))
        assertFalse("protected output must not pass the normal (password-less) parse", OutputValidator.validatePdf(out))
    }

    @Test
    fun real_unlock_engine_decrypts_and_output_passes_normal_validation() {
        val d = dir("unlock_e2e")
        val input = plainPdf(File(d, "input.pdf").absolutePath)
        val encrypted = File(d, "encrypted.pdf")
        PdfSecurity().protectWithPassword(input.absolutePath, encrypted.absolutePath, "secret", "secret")

        val unlocked = File(d, "unlocked.pdf")
        val result = PdfSecurity().unlockDocument(encrypted.absolutePath, unlocked.absolutePath, "secret")

        assertTrue("unlock with the correct password must succeed", result.success)
        assertTrue("unlocked output must pass normal validation", OutputValidator.validatePdf(unlocked))
    }

    @Test
    fun real_unlock_with_wrong_password_fails_gracefully() {
        val d = dir("unlock_wrong")
        val input = plainPdf(File(d, "input.pdf").absolutePath)
        val encrypted = File(d, "encrypted.pdf")
        PdfSecurity().protectWithPassword(input.absolutePath, encrypted.absolutePath, "secret", "secret")

        val result = PdfSecurity().unlockDocument(
            encrypted.absolutePath, File(d, "out.pdf").absolutePath, "wrong"
        )

        assertFalse("unlock with a wrong password must fail", result.success)
        assertTrue("failure must carry a user-visible message", !result.message.isNullOrBlank())
    }

    @Test
    fun normal_validation_stays_strict_for_encrypted_files() {
        // The normal mode must keep failing for encrypted files (it has no password),
        // which is exactly why the explicit encrypted mode exists.
        val file = encryptedPdf(File(dir("protect_strict"), "protected.pdf").absolutePath, "secret")
        assertFalse(OutputValidator.validatePdf(file))
    }
}
