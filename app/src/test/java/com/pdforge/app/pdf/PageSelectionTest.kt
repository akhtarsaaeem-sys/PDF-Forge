package com.pdforge.app.pdf

import com.pdforge.app.ui.components.contiguousPageGroups
import com.pdforge.app.ui.components.pageSelectionToRangeText
import com.pdforge.app.ui.components.parsePageRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the shared page-selection model used by every page-based tool
 * (Split / Remove / Extract / Rotate / Page Numbers):
 *
 *  - individual page selection (single tap),
 *  - multiple page selection,
 *  - the optional range fast path ("2-5", "2,4,7-9"),
 *  - contiguous grouping for Split PDF,
 *  - the pages kept/extracted by Remove and Extract.
 */
class PageSelectionTest {

    // ── Individual selection ────────────────────────────────────────────────

    @Test
    fun selecting_one_page_yields_exactly_that_page() {
        assertEquals(listOf(3), parsePageRange("3", 10))
    }

    @Test
    fun selecting_a_page_by_tap_matches_the_typed_equivalent() {
        // Tapping page 4 in the grid produces the same selection as typing "4".
        val tapped = setOf(4)
        assertEquals(tapped, parsePageRange("4", 10).toSet())
    }

    // ── Multiple selection ──────────────────────────────────────────────────

    @Test
    fun multiple_individual_pages_are_supported() {
        assertEquals(listOf(2, 4, 7), parsePageRange("2, 4, 7", 10))
    }

    @Test
    fun toggling_pages_builds_and_clears_a_selection() {
        var selection = mutableSetOf<Int>()
        // tap
        selection = selection.toMutableSet().apply { add(2) }
        selection = selection.toMutableSet().apply { add(5) }
        selection = selection.toMutableSet().apply { add(7) }
        assertEquals(setOf(2, 5, 7), selection)

        // tap again to deselect
        selection = selection.toMutableSet().apply { remove(5) }
        assertEquals(setOf(2, 7), selection)

        // clear all
        selection = mutableSetOf()
        assertTrue(selection.isEmpty())

        // select all
        selection = (1..10).toMutableSet()
        assertEquals(10, selection.size)
    }

    // ── Range fast path ─────────────────────────────────────────────────────

    @Test
    fun range_expression_supports_single_ranges_and_mixed_groups() {
        assertEquals(listOf(2, 3, 4, 5), parsePageRange("2-5", 10))
        assertEquals(listOf(2, 4, 7, 8, 9), parsePageRange("2,4,7-9", 10))
    }

    @Test
    fun selection_round_trips_through_the_range_text() {
        val selection = setOf(1, 2, 3, 5, 7, 8, 9)
        val text = pageSelectionToRangeText(selection)
        assertEquals("1-3, 5, 7-9", text)
        assertEquals(selection, parsePageRange(text, 10).toSet())
    }

    @Test
    fun empty_selection_renders_as_empty_range_text() {
        assertEquals("", pageSelectionToRangeText(emptySet()))
    }

    @Test
    fun malformed_range_input_is_handled_without_crashing() {
        // Garbage, half-typed ranges and stray separators must all degrade to an
        // empty (or partially valid) selection — never an exception.
        listOf("", "   ", "-", "2-", "-5", "1-2-3", "a-b", ",", ",,", ";", ".", "0-0", "99-1", "١-٣")
            .forEach { input ->
                val parsed = parsePageRange(input, 10)
                assertTrue("parsed pages must stay in range for \"$input\": $parsed", parsed.all { it in 1..10 })
            }
    }

    @Test
    fun malformed_range_input_never_selects_pages_out_of_range() {
        assertEquals(emptyList<Int>(), parsePageRange("-", 10))
        assertEquals(emptyList<Int>(), parsePageRange("a-b", 10))
        assertEquals(emptyList<Int>(), parsePageRange("99-1", 10))
        // Out-of-range bounds are clamped to the document, never rejected silently.
        assertEquals((2..10).toList(), parsePageRange("2-99", 10))
    }

    @Test
    fun selection_order_is_page_order_not_tap_order() {
        // Tapping 7 then 2 produces the same output as tapping 2 then 7.
        val tappedOutOfOrder = setOf(7, 2, 5)
        assertEquals(listOf(2, 5, 7), tappedOutOfOrder.sorted())
    }

    // ── Split grouping ──────────────────────────────────────────────────────

    @Test
    fun contiguous_run_becomes_one_split_group() {
        assertEquals(listOf(2..5), contiguousPageGroups(setOf(2, 3, 4, 5)))
    }

    @Test
    fun separate_pages_become_separate_split_groups() {
        assertEquals(listOf(2..2, 4..4, 7..7), contiguousPageGroups(setOf(2, 4, 7)))
    }

    @Test
    fun mixed_pages_group_into_consecutive_runs() {
        assertEquals(listOf(2..2, 4..5, 7..9), contiguousPageGroups(setOf(2, 4, 5, 7, 8, 9)))
    }

    @Test
    fun no_selection_produces_no_groups() {
        assertEquals(emptyList<IntRange>(), contiguousPageGroups(emptySet()))
    }

    // ── Remove / Extract ────────────────────────────────────────────────────

    @Test
    fun removing_single_pages_keeps_the_rest_in_order() {
        // 8-page document, remove 2, 3 and 5 -> 1, 4, 6, 7, 8
        assertEquals(listOf(1, 4, 6, 7, 8), pagesToKeep(8, listOf(2, 3, 5)))
    }

    @Test
    fun removing_every_page_leaves_nothing_to_keep() {
        assertEquals(emptyList<Int>(), pagesToKeep(3, listOf(1, 2, 3)))
    }

    @Test
    fun removing_nothing_keeps_the_whole_document() {
        assertEquals((1..5).toList(), pagesToKeep(5, emptyList()))
    }

    @Test
    fun extracting_selected_pages_keeps_them_in_selection_order() {
        // Select 2, 5, 7 -> output contains exactly 2, 5, 7 in that order.
        assertEquals(listOf(2, 5, 7), pagesToExtractInOrder(listOf(2, 5, 7), 8))
    }

    @Test
    fun extracting_ignores_out_of_range_and_duplicate_entries() {
        assertEquals(listOf(2, 4), pagesToExtractInOrder(listOf(2, 0, 4, 2, 99), 5))
    }

    @Test
    fun extracting_from_an_empty_document_yields_nothing() {
        assertEquals(emptyList<Int>(), pagesToExtractInOrder(listOf(1, 2), 0))
    }
}
