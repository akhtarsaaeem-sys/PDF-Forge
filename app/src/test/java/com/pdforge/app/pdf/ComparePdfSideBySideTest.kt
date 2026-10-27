package com.pdforge.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compare PDF — the state model behind the side-by-side screen.
 *
 * These cover input selection (documents with different page counts), page
 * navigation, invalid/unreadable inputs and the render readiness decision. The
 * visual usefulness of the split layout itself still needs a physical device.
 */
class ComparePdfSideBySideTest {

    private fun doc(name: String, pages: Int) = CompareDocument(name, "/tmp/$name", pages)

    @Test
    fun two_readable_documents_are_ready_to_compare() {
        assertTrue(comparisonReady(doc("a.pdf", 3), doc("b.pdf", 3)))
        assertTrue(comparisonReady(doc("a.pdf", 1), doc("b.pdf", 12)))
    }

    @Test
    fun a_missing_or_unreadable_side_is_not_ready() {
        assertFalse("no documents", comparisonReady(null, null))
        assertFalse("only one document", comparisonReady(doc("a.pdf", 2), null))
        assertFalse("only one document", comparisonReady(null, doc("b.pdf", 2)))
        assertFalse("unreadable document", comparisonReady(doc("a.pdf", 0), doc("b.pdf", 2)))
        assertFalse("unreadable document", comparisonReady(doc("a.pdf", 2), doc("b.pdf", 0)))
    }

    @Test
    fun an_unreadable_document_reports_no_pages_instead_of_crashing() {
        val broken = doc("broken.pdf", 0)
        assertFalse(broken.hasPages)
        assertEquals("No readable pages", comparePageLabel(broken, 0))
        assertEquals(0, clampComparePage(5, broken.pageCount))
        assertEquals(0, clampComparePage(-5, broken.pageCount))
    }

    @Test
    fun page_indicators_reflect_each_document_independently() {
        val a = doc("a.pdf", 3)
        val b = doc("b.pdf", 1)

        assertEquals("Page 1 / 3", comparePageLabel(a, 0))
        assertEquals("Page 3 / 3", comparePageLabel(a, 2))
        assertEquals("Page 1 / 1", comparePageLabel(b, 0))
        assertEquals("No PDF selected", comparePageLabel(null, 0))
    }

    @Test
    fun documents_with_different_page_counts_navigate_independently() {
        val a = doc("a.pdf", 5)
        val b = doc("b.pdf", 1)

        // Walking B forward must stop on its only page, never on a page A has.
        var pageB = 0
        repeat(4) { pageB = compareNextPage(pageB, b.pageCount) }
        assertEquals(0, pageB)

        // Walking A forward must respect its own length.
        var pageA = 0
        repeat(9) { pageA = compareNextPage(pageA, a.pageCount) }
        assertEquals(4, pageA)
        assertEquals(3, comparePreviousPage(pageA, a.pageCount))
        assertEquals(0, comparePreviousPage(0, a.pageCount))
    }

    @Test
    fun navigation_never_produces_an_index_outside_the_document() {
        for (pageCount in 0..4) {
            for (page in -3..8) {
                val clamped = clampComparePage(page, pageCount)
                if (pageCount == 0) {
                    assertEquals(0, clamped)
                } else {
                    assertTrue(
                        "page $page of $pageCount must clamp into range, was $clamped",
                        clamped in 0 until pageCount
                    )
                }
                val next = compareNextPage(page, pageCount)
                val previous = comparePreviousPage(page, pageCount)
                if (pageCount > 0) {
                    assertTrue(next in 0 until pageCount)
                    assertTrue(previous in 0 until pageCount)
                }
            }
        }
    }

    @Test
    fun a_document_remembers_its_own_name_and_path() {
        val document = CompareDocument(name = "Contract v2.pdf", path = "/tmp/cache/compare_A.pdf", pageCount = 7)
        assertEquals("Contract v2.pdf", document.name)
        assertEquals("/tmp/cache/compare_A.pdf", document.path)
        assertEquals(7, document.pageCount)
        assertTrue(document.hasPages)
    }
}
