package com.pdforge.app.storage

import android.graphics.BitmapFactory
import com.itextpdf.kernel.exceptions.BadPasswordException
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.ReaderProperties
import java.io.File
import java.util.zip.ZipFile

/**
 * Validates generated output files before they are reported as successful.
 *
 * Validation is dispatched by the ACTUAL output type (declared by the converter)
 * rather than assuming every generated file is a PDF:
 *
 *  - PDF   -> opens with a PDF parser and reports at least one page
 *  - DOCX  -> is a readable OOXML zip package containing word/document.xml, and
 *             can be reopened with the project's Word library (Apache POI XWPF)
 *  - XLSX  -> is a readable OOXML zip package containing xl/workbook.xml, and
 *             can be reopened with the project's Excel library (Apache POI XSSF)
 *  - PPTX  -> is a readable OOXML zip package containing ppt/presentation.xml and
 *             at least one slide part
 *  - image -> decodes via BitmapFactory
 *  - HTML  -> non-empty text
 *  - legacy .doc/.xls/.ppt -> OLE2 (or OOXML) container magic bytes
 *
 * A previous version compared the little-endian value of the file's first four
 * bytes against big-endian constants, so `PK\x03\x04` was read as 0x04034B50 and
 * compared to 0x504B0304. The comparison was therefore ALWAYS false and every
 * generated DOCX/XLSX/PPTX failed validation with "Output validation failed" —
 * this is the root cause of the PDF→Word and PDF→Excel failures. The magic bytes
 * are now compared byte-by-byte.
 */
object OutputValidator {

    /** Human-readable output-type label used in validation failure messages. */
    private const val PDF_LABEL = "PDF file"
    private const val DOCX_LABEL = "Word document (.docx)"
    private const val XLSX_LABEL = "Excel workbook (.xlsx)"
    private const val PPTX_LABEL = "PowerPoint presentation (.pptx)"
    private const val IMAGE_LABEL = "image"
    private const val HTML_LABEL = "HTML file"
    private const val LEGACY_LABEL = "Office document"
    private const val UNKNOWN_LABEL = "file"

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)           // "PK\x03\x04"
    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    /** Outcome of a validation pass, including a message that names the real type. */
    data class Outcome(val valid: Boolean, val typeLabel: String, val message: String)

    /**
     * Validates [file] according to the type implied by [mimeType].
     * Returns true only when the file exists, is non-empty, and its content can
     * actually be parsed as that type.
     */
    fun validate(file: File, mimeType: String): Boolean = validateOutcome(file, mimeType).valid

    /**
     * Same as [validate] but also returns the resolved type label and a message
     * describing why validation failed, so the UI can name the real output type
     * instead of always saying "PDF".
     */
    fun validateOutcome(file: File, mimeType: String): Outcome {
        val typeLabel = typeLabelFor(mimeType, file)
        if (!file.exists()) {
            return Outcome(false, typeLabel, "The generated $typeLabel was not found.")
        }
        if (file.length() <= 0L) {
            return Outcome(false, typeLabel, "The generated $typeLabel is empty.")
        }
        val valid = try {
            when {
                isPdf(mimeType, file) -> validatePdf(file)
                isDocx(mimeType, file) -> validateDocx(file)
                isXlsx(mimeType, file) -> validateXlsx(file)
                isPptx(mimeType, file) -> validatePptx(file)
                mimeType.startsWith("image/") -> validateImage(file)
                mimeType == "application/msword" || mimeType == "application/vnd.ms-excel" ||
                    mimeType == "application/vnd.ms-powerpoint" ->
                    hasMagic(file, OLE2_MAGIC) || hasMagic(file, ZIP_MAGIC)
                mimeType == "text/html" || mimeType == "text/plain" || mimeType == "text/csv" ->
                    file.readText().trim().isNotEmpty()
                else -> true
            }
        } catch (e: Exception) {
            false
        }
        val message = if (valid) "" else "The generated $typeLabel could not be verified as a valid $typeLabel."
        return Outcome(valid, typeLabel, message)
    }

    // ── Type detection ───────────────────────────────────────────────────────

    private fun isPdf(mimeType: String, file: File): Boolean =
        mimeType == "application/pdf" || file.name.endsWith(".pdf", true)

    private fun isDocx(mimeType: String, file: File): Boolean =
        mimeType.contains("wordprocessingml") || file.name.endsWith(".docx", true)

    private fun isXlsx(mimeType: String, file: File): Boolean =
        mimeType.contains("spreadsheetml") || file.name.endsWith(".xlsx", true)

    private fun isPptx(mimeType: String, file: File): Boolean =
        mimeType.contains("presentationml") || file.name.endsWith(".pptx", true)

    private fun typeLabelFor(mimeType: String, file: File): String = when {
        isPdf(mimeType, file) -> PDF_LABEL
        isDocx(mimeType, file) -> DOCX_LABEL
        isXlsx(mimeType, file) -> XLSX_LABEL
        isPptx(mimeType, file) -> PPTX_LABEL
        mimeType.startsWith("image/") -> IMAGE_LABEL
        mimeType == "text/html" -> HTML_LABEL
        mimeType == "application/msword" || mimeType == "application/vnd.ms-excel" ||
            mimeType == "application/vnd.ms-powerpoint" -> LEGACY_LABEL
        else -> UNKNOWN_LABEL
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    internal fun validatePdf(file: File): Boolean {
        return try {
            // The PdfReader is lazy: nothing is parsed until a PdfDocument is
            // constructed around it. Do NOT touch reader state such as
            // isEncrypted before that — it throws
            // "The PDF document has not been read yet. Document reading occurs in
            // PdfDocument class constructor", which used to make this function
            // reject every valid PDF and fail the whole output pipeline.
            val reader = PdfReader(file.absolutePath)
            val doc = PdfDocument(reader)
            val pageCount = doc.numberOfPages
            doc.close()
            pageCount > 0
        } catch (e: Exception) {
            false
        }
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────

    internal fun validateDocx(file: File): Boolean {
        if (!isZipPackage(file) || !zipContains(file, "word/document.xml")) return false
        // Reopen with the project's Word library where practical — a zip with a
        // document.xml that POI cannot parse is still not a usable DOCX.
        return try {
            org.apache.poi.xwpf.usermodel.XWPFDocument(file.inputStream()).use { it.paragraphs.size >= 0 }
        } catch (e: Exception) {
            false
        }
    }

    // ── XLSX ─────────────────────────────────────────────────────────────────

    internal fun validateXlsx(file: File): Boolean {
        if (!isZipPackage(file) || !zipContains(file, "xl/workbook.xml")) return false
        return try {
            org.apache.poi.xssf.usermodel.XSSFWorkbook(file.inputStream()).use { it.numberOfSheets > 0 }
        } catch (e: Exception) {
            false
        }
    }

    // ── PPTX ─────────────────────────────────────────────────────────────────

    /**
     * Structural OOXML check only (no Apache POI XSLF).
     *
     * The XSLF usermodel pulls in `java.awt.geom`, which does not exist on
     * Android, so opening the presentation with it would crash the validation
     * path on a real device. A PPTX is a zip package whose presentation part
     * lists at least one slide; that is validated directly here.
     */
    internal fun validatePptx(file: File): Boolean {
        if (!isZipPackage(file)) return false
        if (!zipContains(file, "ppt/presentation.xml")) return false
        val slideCount = countEntriesMatching(file, Regex("^ppt/slides/slide\\d+\\.xml$"))
        return slideCount > 0
    }

    // ── Images ───────────────────────────────────────────────────────────────

    private fun validateImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    // ── Encrypted PDF (Protect) ──────────────────────────────────────────────

    /**
     * Explicit validation mode for intentionally encrypted output (Protect PDF).
     *
     * Normal [validatePdf] cannot open an encrypted document because it has no
     * password, so Protect PDF uses this mode instead. It checks, in order:
     *  1. the file exists and is non-empty
     *  2. it begins with the %PDF- signature
     *  3. it really is encrypted (without a password iText cannot read it)
     *  4. it opens with the supplied password and reports a non-zero page count
     *
     * This does NOT weaken normal validation: unencrypted PDFs still go through
     * the full [validatePdf] parse, and an unencrypted file fails this mode.
     */
    internal fun validateEncryptedPdf(file: File, password: String): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (!hasPdfHeader(file)) return false

        // 1. The document must genuinely be encrypted.
        val encrypted = try {
            val reader = PdfReader(file.absolutePath)
            val doc = PdfDocument(reader)
            doc.close()
            false // readable without a password -> not encrypted
        } catch (e: BadPasswordException) {
            true
        } catch (e: Exception) {
            false
        }
        if (!encrypted) return false

        // 2. It must open with the supplied password and expose its pages.
        return try {
            val reader = PdfReader(
                file.absolutePath,
                ReaderProperties().setPassword(password.toByteArray())
            )
            val doc = PdfDocument(reader)
            val pageCount = doc.numberOfPages
            doc.close()
            pageCount > 0
        } catch (e: Exception) {
            false
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** True when [file] begins with the %PDF- signature. */
    private fun hasPdfHeader(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(5)
                val read = input.read(buffer)
                read == 5 && String(buffer, Charsets.ISO_8859_1) == "%PDF-"
            }
        } catch (e: Exception) {
            false
        }
    }

    /** True when [file] is a readable zip package (OOXML container). */
    private fun isZipPackage(file: File): Boolean {
        if (!hasMagic(file, ZIP_MAGIC)) return false
        return try {
            ZipFile(file).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun zipContains(file: File, entryName: String): Boolean {
        return try {
            ZipFile(file).use { it.getEntry(entryName) != null }
        } catch (e: Exception) {
            false
        }
    }

    private fun countEntriesMatching(file: File, pattern: Regex): Int {
        return try {
            ZipFile(file).use { zip ->
                var count = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (pattern.matches(entries.nextElement().name)) count++
                }
                count
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Compares the leading bytes of [file] with [magic] in file order (big-endian
     * byte sequence), which is how every file format documents its signature.
     */
    private fun hasMagic(file: File, magic: ByteArray): Boolean {
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(magic.size)
                val read = input.read(buffer)
                if (read < magic.size) return false
                magic.indices.all { buffer[it] == magic[it] }
            }
        } catch (e: Exception) {
            false
        }
    }
}
