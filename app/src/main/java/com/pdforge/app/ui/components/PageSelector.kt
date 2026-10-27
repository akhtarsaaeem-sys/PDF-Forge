package com.pdforge.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pdforge.app.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared visual page selector used by every page-based tool
 * (Split / Remove Pages / Extract Pages / Add Page Numbers / Rotate).
 *
 * The primary interaction is tapping page thumbnails; typing a range is an
 * optional fast path that produces exactly the same selection.
 *
 * Memory: thumbnails are rendered at [THUMBNAIL_WIDTH_PX] width on a background
 * dispatcher and only for pages currently on screen (LazyVerticalGrid disposes
 * the rest), so a 500-page document never loads 500 bitmaps.
 */
@Composable
fun PageSelectionPanel(
    filePath: String,
    pageCount: Int,
    selectedPages: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier,
    gridHeight: Dp = 380.dp,
    selectionLabel: (Int) -> String = { count ->
        if (count == 1) "1 page selected" else "$count pages selected"
    },
    emptySelectionHint: String? = null
) {
    val context = LocalContext.current
    val pdfEngine = remember(context) { PdfEngine(context) }
    var rangeText by remember { mutableStateOf(pageSelectionToRangeText(selectedPages)) }

    // Keep the range field in step with taps, without clobbering what the user is
    // currently typing (selection only changes on tap or Apply).
    LaunchedEffect(selectedPages) {
        rangeText = pageSelectionToRangeText(selectedPages)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = selectionLabel(selectedPages.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onSelectionChange((1..pageCount).toSet()) },
                enabled = pageCount > 0 && selectedPages.size < pageCount,
                modifier = Modifier.weight(1f)
            ) {
                ButtonLabel(icon = Icons.Default.DoneAll, text = "Select All")
            }
            OutlinedButton(
                onClick = { onSelectionChange(emptySet()) },
                enabled = selectedPages.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                ButtonLabel(icon = Icons.Default.LayersClear, text = "Clear All")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Optional fast path: the same selection, typed as ranges.
        OutlinedTextField(
            value = rangeText,
            onValueChange = { rangeText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Range (optional)") },
            placeholder = { Text("e.g. 2-5 or 2,4,7-9") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onSelectionChange(parsePageRange(rangeText, pageCount).toSet()) }
            ),
            trailingIcon = {
                AppTextButton(
                    text = "Apply",
                    onClick = { onSelectionChange(parsePageRange(rangeText, pageCount).toSet()) }
                )
            },
            supportingText = {
                Text(
                    if (rangeText.isBlank()) {
                        "Tap pages below, or type a range and press Apply. Total: $pageCount pages"
                    } else {
                        "Tap Apply to select the pages in this range. Total: $pageCount pages"
                    }
                )
            }
        )

        if (emptySelectionHint != null && selectedPages.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = emptySelectionHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // A bounded height keeps this grid usable inside a scrolling parent
        // (LazyVerticalGrid must never be measured with infinite height) and lets
        // the same panel be dropped into any screen layout.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items((1..pageCount).toList()) { pageNumber ->
                val isSelected = pageNumber in selectedPages
                val bitmap by produceState<Bitmap?>(null, filePath, pageNumber) {
                    value = withContext(Dispatchers.IO) {
                        pdfEngine.renderPage(filePath, pageNumber - 1, THUMBNAIL_WIDTH_PX)
                    }
                }
                PageThumbnail(
                    pageNumber = pageNumber,
                    bitmap = bitmap,
                    isSelected = isSelected,
                    onClick = {
                        val next = selectedPages.toMutableSet()
                        if (!next.add(pageNumber)) next.remove(pageNumber)
                        onSelectionChange(next)
                    }
                )
            }
        }
    }
}

/** One tappable page thumbnail with an obvious selected state. */
@Composable
fun PageThumbnail(
    pageNumber: Int,
    bitmap: Bitmap?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp)
                    )
                } else Modifier
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Page $pageNumber",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            Text(
                text = "$pageNumber",
                modifier = Modifier
                    .padding(4.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/** Thumbnail render width in pixels — small on purpose (see [PageSelectionPanel]). */
private const val THUMBNAIL_WIDTH_PX = 220

// ── Pure selection helpers (shared by every page-based tool) ──────────────

/**
 * Parses a page range expression into a sorted, de-duplicated list of pages.
 *
 * Supports single pages and ranges, comma separated:
 *   "1-3, 5, 7-9" -> [1, 2, 3, 5, 7, 8, 9]
 *
 * Out-of-range and malformed parts are ignored, and a reversed range ("5-2")
 * contributes nothing — it leans on the caller to validate the expression.
 */
fun parsePageRange(range: String, totalPages: Int): List<Int> {
    val pages = mutableSetOf<Int>()
    val parts = range.split(",").map { it.trim() }

    for (part in parts) {
        if (part.contains("-")) {
            val rangeParts = part.split("-").map { it.trim().toIntOrNull() }
            if (rangeParts.size == 2 && rangeParts[0] != null && rangeParts[1] != null) {
                val start = rangeParts[0]!!.coerceIn(1, totalPages)
                val end = rangeParts[1]!!.coerceIn(1, totalPages)
                for (i in start..end) {
                    pages.add(i)
                }
            }
        } else {
            val page = part.toIntOrNull()
            if (page != null && page in 1..totalPages) {
                pages.add(page)
            }
        }
    }

    return pages.sorted()
}

/**
 * Renders a selection back into a compact range expression, so the optional
 * range field always shows what the thumbnails show:
 *   {1,2,3,5,7,8,9} -> "1-3, 5, 7-9"
 */
fun pageSelectionToRangeText(selected: Set<Int>): String {
    if (selected.isEmpty()) return ""
    val sorted = selected.toSortedSet().toList()
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var previous = start
    for (page in sorted.drop(1)) {
        if (page == previous + 1) {
            previous = page
            continue
        }
        parts.add(if (start == previous) "$start" else "$start-$previous")
        start = page
        previous = page
    }
    parts.add(if (start == previous) "$start" else "$start-$previous")
    return parts.joinToString(", ")
}

/**
 * Groups a page selection into contiguous runs, preserving page order.
 * Used by Split PDF, where each run becomes one output file:
 *   {2,4,5,7} -> [2..2, 4..5, 7..7]
 */
fun contiguousPageGroups(pages: Collection<Int>): List<IntRange> {
    val sorted = pages.filter { it > 0 }.toSortedSet().toList()
    if (sorted.isEmpty()) return emptyList()
    val groups = mutableListOf<IntRange>()
    var start = sorted.first()
    var previous = start
    for (page in sorted.drop(1)) {
        if (page == previous + 1) {
            previous = page
            continue
        }
        groups.add(start..previous)
        start = page
        previous = page
    }
    groups.add(start..previous)
    return groups
}

/**
 * Parses an explicit split expression into one [IntRange] per output PDF.
 *
 * "1-3, 4-6, 7-8" -> [1..3, 4..6, 7..8]
 *
 * This is what makes Split PDF different from Extract Pages: the user describes
 * where the document is DIVIDED, and each entry becomes a separate file. A single
 * number is a one-page part. Malformed entries are ignored, bounds are clamped to
 * [totalPages], and reversed ranges ("5-2") contribute nothing.
 */
fun parseSplitRanges(expression: String, totalPages: Int): List<IntRange> {
    if (totalPages <= 0) return emptyList()
    val result = mutableListOf<IntRange>()
    for (part in expression.split(",")) {
        val piece = part.trim()
        if (piece.isEmpty()) continue
        if (piece.contains("-")) {
            val bounds = piece.split("-").map { it.trim() }
            if (bounds.size != 2) continue
            val start = bounds[0].toIntOrNull() ?: continue
            val end = bounds[1].toIntOrNull() ?: continue
            if (start > totalPages || end < 1 || start > end) continue
            val s = start.coerceIn(1, totalPages)
            val e = end.coerceIn(1, totalPages)
            if (s > e) continue
            result.add(s..e)
        } else {
            val page = piece.toIntOrNull() ?: continue
            if (page !in 1..totalPages) continue
            result.add(page..page)
        }
    }
    return result
}
