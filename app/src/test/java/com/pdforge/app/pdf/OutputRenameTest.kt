package com.pdforge.app.pdf

import com.pdforge.app.storage.FileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filename rules for the centralized output rename.
 *
 * These cover the pure helpers used by the shared rename dialog and by
 * [FileManager.renameOutputs]: the .pdf extension is always preserved, illegal
 * characters can never reach the filesystem, reserved device names are refused,
 * and a default name is always producible.
 */
class OutputRenameTest {

    // ── Extension handling ──────────────────────────────────────────────────

    @Test
    fun pdf_extension_is_appended_when_missing() {
        assertEquals("My Document.pdf", FileManager.buildOutputName("My Document"))
    }

    @Test
    fun pdf_extension_is_not_duplicated() {
        assertEquals("My Document.pdf", FileManager.buildOutputName("My Document.pdf"))
        assertEquals("Report.pdf", FileManager.buildOutputName("Report.PDF"))
    }

    @Test
    fun a_different_extension_is_replaced_not_appended() {
        assertEquals("Report.pdf", FileManager.buildOutputName("Report.docx"))
    }

    @Test
    fun internal_dots_are_preserved_and_not_collapsed() {
        // Only ONE trailing extension may be stripped: "My.Doc.v2" must not become "My.pdf".
        assertEquals("My.Doc.pdf", FileManager.buildOutputName("My.Doc.v2"))
        assertEquals("My.Doc.v2.pdf", FileManager.buildOutputName("My.Doc.v2.pdf"))
        assertEquals("2026.09.report.pdf", FileManager.buildOutputName("2026.09.report"))
    }

    @Test
    fun a_name_without_an_extension_falls_back_to_pdf() {
        // finalizeOutput uses getExtension() as the fallback when a display name
        // carries no extension, so a generated output always ends in .pdf.
        assertEquals(".pdf", FileManager.getExtension("scan_20260928_153012"))
        assertEquals(".pdf", FileManager.getExtension("no-extension-here"))
        assertEquals(".docx", FileManager.getExtension("Report.docx"))
        assertTrue(FileManager.buildOutputName("scan_20260928_153012").endsWith(".pdf"))
    }

    @Test
    fun extension_is_preserved_for_non_pdf_outputs() {
        assertEquals("Scanned Set.docx", FileManager.buildOutputName("Scanned Set", ".docx"))
    }

    // ── Illegal characters ──────────────────────────────────────────────────

    @Test
    fun illegal_filename_characters_are_rejected_by_validation() {
        listOf("a/b.pdf", "a\\b.pdf", "a:b.pdf", "a*b.pdf", "a?b.pdf", "a\"b.pdf", "a<b.pdf", "a>b.pdf", "a|b.pdf")
            .forEach { assertFalse("$it must be rejected", FileManager.isValidFileName(it)) }
    }

    @Test
    fun illegal_characters_are_replaced_when_building_a_name() {
        val built = FileManager.buildOutputName("Q1/Q2\\report:final")
        assertFalse(built.contains('/'))
        assertFalse(built.contains('\\'))
        assertFalse(built.contains(':'))
        assertTrue(built.endsWith(".pdf"))
    }

    @Test
    fun blank_and_dot_only_names_are_rejected() {
        assertFalse(FileManager.isValidFileName(""))
        assertFalse(FileManager.isValidFileName("   "))
        assertFalse(FileManager.isValidFileName("..."))
        assertFalse(FileManager.isValidFileName(".pdf"))
    }

    @Test
    fun reserved_device_names_are_rejected() {
        assertFalse(FileManager.isValidFileName("CON.pdf"))
        assertFalse(FileManager.isValidFileName("com1.pdf"))
        assertFalse(FileManager.isValidFileName("LPT9"))
    }

    @Test
    fun ordinary_names_are_valid() {
        assertTrue(FileManager.isValidFileName("My Document.pdf"))
        assertTrue(FileManager.isValidFileName("scan_2026-09-28.pdf"))
        assertTrue(FileManager.isValidFileName("Report (final).pdf"))
    }

    @Test
    fun blank_input_falls_back_to_a_sensible_default() {
        assertEquals("document.pdf", FileManager.buildOutputName("", ".pdf"))
        assertEquals("document.pdf", FileManager.buildOutputName("   /  ", ".pdf"))
    }

    @Test
    fun leading_dots_cannot_create_a_hidden_file() {
        val built = FileManager.buildOutputName("...hidden")
        assertFalse("must not start with a dot", built.startsWith("."))
        assertTrue(built.endsWith(".pdf"))
    }

    @Test
    fun overlong_names_are_capped_but_still_have_an_extension() {
        val built = FileManager.buildOutputName("x".repeat(500))
        assertTrue(built.endsWith(".pdf"))
        assertTrue("name must be capped", built.length <= 110)
    }

    // ── Generated defaults ──────────────────────────────────────────────────

    @Test
    fun timestamped_default_name_is_a_valid_file_name() {
        val name = "${FileManager.timestampedName("scan")}.pdf"
        assertTrue(FileManager.isValidFileName(name))
        assertTrue(name.startsWith("scan_"))
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun sanitize_keeps_readable_spaces() {
        assertEquals("My Document", FileManager.sanitizeName("My  Document"))
    }
}
