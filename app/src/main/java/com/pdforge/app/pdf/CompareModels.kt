package com.pdforge.app.pdf

/**
 * Pure state model for the Compare PDF screen.
 *
 * The screen renders two documents next to each other and lets the user page
 * through each one independently. Everything that decides WHAT is shown (which
 * page is valid, what the indicator says, whether the comparison can render at
 * all) lives here so it is unit-testable; the screen only renders it.
 */

/** One document loaded into the side-by-side comparison. */
data class CompareDocument(
    val name: String,
    val path: String,
    val pageCount: Int
) {
    val hasPages: Boolean get() = pageCount > 0
}

/** Which half of the comparison a value belongs to. */
enum class CompareSide { A, B }

/**
 * Clamps a 0-based page index into the document. Returns 0 for a document with
 * no readable pages so navigation can never address a missing page.
 */
fun clampComparePage(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

/** "Page 3 / 8", or an explicit empty-state label when nothing is loaded. */
fun comparePageLabel(document: CompareDocument?, page: Int): String {
    if (document == null) return "No PDF selected"
    if (!document.hasPages) return "No readable pages"
    val current = clampComparePage(page, document.pageCount) + 1
    return "Page $current / ${document.pageCount}"
}

/** True when both sides hold a document that can actually be rendered. */
fun comparisonReady(a: CompareDocument?, b: CompareDocument?): Boolean =
    a != null && b != null && a.hasPages && b.hasPages

/**
 * The page to render for a side after page navigation, so "next"/"previous" can
 * never walk past the end of a document with a different page count than its
 * neighbour.
 */
fun compareNextPage(page: Int, pageCount: Int): Int =
    clampComparePage(page + 1, pageCount)

fun comparePreviousPage(page: Int, pageCount: Int): Int =
    clampComparePage(page - 1, pageCount)
