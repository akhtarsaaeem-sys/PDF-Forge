package com.pdforge.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the PDF-selection crash.
 *
 * The Android picker (`ActivityResultContracts.OpenDocument`) always returns a
 * `content://` reference. `PdfEngine.getPageCount(Uri)` forwarded that reference
 * to `getPageCount(String)`, which then forwarded `content://` references
 * straight back to `getPageCount(Uri)`:
 *
 *     getPageCount(uri) -> getPageCount(uri.toString()) -> getPageCount(uri) -> ...
 *
 * This mutual recursion overflowed the stack the moment any picked PDF was
 * inspected. `StackOverflowError` is an [Error], not an [Exception], so none of
 * the surrounding `catch (e: Exception)` blocks could ever observe it and the
 * app died immediately after selection.
 *
 * The routing decision now lives in the pure [classifyPdfInput] helper used by
 * [PdfEngine]'s dispatch, so a `content://` reference is always resolved in
 * place and is never re-dispatched through the `Uri` overload.
 */
class PdfSelectionInputTest {

    // ── Classification of picker references ──────────────────────────────

    @Test
    fun content_uri_from_picker_is_classified_as_content() {
        // Real-world providers plus awkward characters some of them emit.
        val pickerUris = listOf(
            "content://com.android.providers.downloads.documents/document/1234",
            "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Freport.pdf",
            "content://com.android.providers.media.documents/document/document%3A1000000042",
            "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D1",
            "content://com.samsung.android.providers.media.documents/document/1+2%3"
        )
        for (uri in pickerUris) {
            assertEquals(
                "content:// reference misrouted: $uri",
                PdfInputSource.CONTENT_URI,
                classifyPdfInput(uri)
            )
        }
    }

    @Test
    fun file_uri_is_classified_as_file() {
        val fileUris = listOf(
            "file:///storage/emulated/0/Download/report.pdf",
            "file:///storage/emulated/0/Download/report (final).pdf",
            "file:///storage/emulated/0/Download/100%_report.pdf",
            "file:///storage/emulated/0/Download/report+final.pdf",
            "file:///storage/emulated/0/Download/报告 2026.pdf"
        )
        for (uri in fileUris) {
            assertEquals(
                "file:// reference misrouted: $uri",
                PdfInputSource.FILE_URI,
                classifyPdfInput(uri)
            )
        }
    }

    @Test
    fun raw_working_file_path_is_classified_as_path() {
        // PDF Forge's own working copies (cache/temp, sanitized names).
        val paths = listOf(
            "/data/user/0/com.pdforge.app/cache/temp/input_1712345678901.pdf",
            "/data/user/0/com.pdforge.app/files/output/report_rotated.pdf",
            "/storage/emulated/0/Documents/PDF Forge/report final.pdf",
            "report.pdf"
        )
        for (path in paths) {
            assertEquals(
                "raw path misrouted: $path",
                PdfInputSource.FILE_PATH,
                classifyPdfInput(path)
            )
        }
    }

    @Test
    fun content_uri_is_never_treated_as_a_filesystem_path() {
        // Guards against the "guess the file path from a content URI" class of
        // bugs: a content:// reference must never fall through to FILE_PATH.
        val uri = "content://com.android.providers.downloads.documents/document/42"
        assertTrue(classifyPdfInput(uri) != PdfInputSource.FILE_PATH)
        assertTrue(classifyPdfInput(uri) != PdfInputSource.FILE_URI)
    }

    @Test
    fun classification_is_pure_and_does_not_depend_on_being_called_again() {
        // The old bug was a re-entrant dispatch: classifying once and resolving
        // must be enough. Calling the classifier repeatedly must yield the same
        // answer (no hidden state driving a second hop).
        val uri = "content://media/external/file/99"
        assertEquals(PdfInputSource.CONTENT_URI, classifyPdfInput(uri))
        assertEquals(PdfInputSource.CONTENT_URI, classifyPdfInput(uri))
        assertEquals(PdfInputSource.CONTENT_URI, classifyPdfInput(uri))
    }

    @Test
    fun empty_reference_is_classified_as_path_and_never_crashes() {
        // A cancelled picker can hand back an empty reference downstream; it must
        // classify safely instead of throwing.
        assertEquals(PdfInputSource.FILE_PATH, classifyPdfInput(""))
    }
}
