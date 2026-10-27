package com.pdforge.app.pdf

import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.ui.components.resolveExternalReference
import com.pdforge.app.ui.components.resolveInAppReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the shared result-screen reference selection (Merge PDF
 * "Open" intermittently doing nothing).
 *
 * Open must use the stable internal copy; Open With must prefer the published
 * content:// URI. Because these helpers are resolved from the result that the
 * result screen keeps up to date (including after a rename), they never point at
 * a stale reference.
 */
class OutputReferenceTest {

    @Test
    fun in_app_open_prefers_the_internal_copy() {
        val result = ProcessingResult(
            success = true,
            outputPath = "/data/user/0/com.pdforge.app/files/output/report.pdf",
            outputUri = "content://media/external/file/123"
        )
        assertEquals("/data/user/0/com.pdforge.app/files/output/report.pdf", resolveInAppReference(result))
    }

    @Test
    fun in_app_open_falls_back_to_the_published_uri() {
        val result = ProcessingResult(success = true, outputUri = "content://media/external/file/123")
        assertEquals("content://media/external/file/123", resolveInAppReference(result))
    }

    @Test
    fun external_open_prefers_the_published_uri() {
        val result = ProcessingResult(
            success = true,
            outputPath = "/data/user/0/com.pdforge.app/files/output/report.pdf",
            outputUri = "content://media/external/file/123"
        )
        assertEquals("content://media/external/file/123", resolveExternalReference(result))
    }

    @Test
    fun external_open_falls_back_to_the_internal_path() {
        val result = ProcessingResult(
            success = true,
            outputPath = "/data/user/0/com.pdforge.app/files/output/report.pdf",
            outputUri = null
        )
        assertEquals("/data/user/0/com.pdforge.app/files/output/report.pdf", resolveExternalReference(result))
    }

    @Test
    fun no_output_produces_no_reference() {
        assertNull(resolveInAppReference(ProcessingResult(success = true)))
        assertNull(resolveExternalReference(ProcessingResult(success = true)))
    }

    @Test
    fun a_renamed_result_points_at_the_new_file() {
        // Simulates what the result screen does after a successful rename: it
        // rebuilds the result from the rename output, so both references follow it.
        val renamed = ProcessingResult(
            success = true,
            outputPath = "/data/user/0/com.pdforge.app/files/output/final_report.pdf",
            outputUri = "content://media/external/file/123",
            outputPaths = listOf("/data/user/0/com.pdforge.app/files/output/final_report.pdf")
        )
        assertEquals("/data/user/0/com.pdforge.app/files/output/final_report.pdf", resolveInAppReference(renamed))
        assertEquals("content://media/external/file/123", resolveExternalReference(renamed))
    }
}
