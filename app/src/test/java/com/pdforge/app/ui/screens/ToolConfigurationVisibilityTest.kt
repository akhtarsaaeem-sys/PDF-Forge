package com.pdforge.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the gate that decides whether a tool's configuration
 * controls (including the password fields) are shown.
 *
 * THE BUG THIS LOCKS DOWN: Unlock PDF showed no password field at all, because
 * the controls were gated on `pageCount > 0` and a password-protected PDF reports
 * 0 pages — the renderer used to count pages cannot open an encrypted document
 * (it has no way to accept a password). The user therefore ran Unlock with an
 * empty password and always got "Incorrect password".
 */
class ToolConfigurationVisibilityTest {

    @Test
    fun unlock_pdf_shows_its_controls_even_when_the_page_count_is_unknown() {
        assertTrue(
            "the Unlock PDF password field must be reachable for an encrypted PDF",
            showsToolConfiguration(toolId = "unlock_pdf", hasInputCopy = true, pageCount = 0)
        )
    }

    @Test
    fun protect_pdf_shows_its_controls_even_when_the_page_count_is_unknown() {
        assertTrue(showsToolConfiguration(toolId = "protect_pdf", hasInputCopy = true, pageCount = 0))
    }

    @Test
    fun page_dependent_tools_still_require_a_known_page_count() {
        for (toolId in listOf("crop_pdf", "page_numbers", "rotate_pdf", "remove_pages", "extract_pages", "watermark")) {
            assertFalse(
                "$toolId must wait for a readable input with pages",
                showsToolConfiguration(toolId, hasInputCopy = true, pageCount = 0)
            )
            assertTrue(
                "$toolId must show its controls once the page count is known",
                showsToolConfiguration(toolId, hasInputCopy = true, pageCount = 3)
            )
        }
    }

    @Test
    fun nothing_is_shown_without_a_readable_input_copy() {
        for (toolId in listOf("unlock_pdf", "protect_pdf", "crop_pdf", "page_numbers")) {
            assertFalse(showsToolConfiguration(toolId, hasInputCopy = false, pageCount = 5))
            assertFalse(showsToolConfiguration(toolId, hasInputCopy = false, pageCount = 0))
        }
    }
}
