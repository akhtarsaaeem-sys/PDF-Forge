package com.pdforge.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.pdf.CompareDocument
import com.pdforge.app.pdf.CompareSide
import com.pdforge.app.pdf.PdfEngine
import com.pdforge.app.pdf.clampComparePage
import com.pdforge.app.pdf.compareNextPage
import com.pdforge.app.pdf.comparePageLabel
import com.pdforge.app.pdf.comparePreviousPage
import com.pdforge.app.pdf.comparisonReady
import com.pdforge.app.security.PdfSecurity
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.AppButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compare PDF — a real visual comparison.
 *
 * Two documents are shown next to each other and pages are rendered on demand
 * (one page per side, never the whole document), each side with its own page
 * navigation so documents with different page counts can be inspected
 * comfortably. The text-diff summary is kept as an optional secondary action.
 *
 * All decision logic (valid page indices, indicators, readiness) lives in
 * [com.pdforge.app.pdf.CompareDocument] helpers so it is unit-tested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparePdfScreen(
    onBack: () -> Unit,
    onResult: (ProcessingResult) -> Unit = {},
    onOpenInApp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfEngine = remember { PdfEngine(context) }

    var docA by remember { mutableStateOf<CompareDocument?>(null) }
    var docB by remember { mutableStateOf<CompareDocument?>(null) }
    var errorA by remember { mutableStateOf<String?>(null) }
    var errorB by remember { mutableStateOf<String?>(null) }
    var pageA by remember { mutableIntStateOf(0) }
    var pageB by remember { mutableIntStateOf(0) }
    var splitLayout by remember { mutableStateOf(true) }
    var summary by remember { mutableStateOf<String?>(null) }
    var summaryLoading by remember { mutableStateOf(false) }

    /** Copies the picked PDF and reports its page count; a bad file sets an error. */
    fun load(uri: Uri, side: CompareSide) {
        scope.launch {
            val name = FileManager.getFileName(context, uri)
            val tempFile = FileManager.createTempFile(context, "compare_${side.name}_${System.currentTimeMillis()}")
            val copied = withContext(Dispatchers.IO) { FileManager.copyUriToFile(context, uri, tempFile) }
            if (!copied) {
                if (side == CompareSide.A) errorA = "\"$name\" could not be read." else errorB = "\"$name\" could not be read."
                return@launch
            }
            val count = withContext(Dispatchers.IO) { pdfEngine.getPageCount(tempFile.absolutePath) }
            if (count <= 0) {
                val message = "\"$name\" is not a readable PDF (no pages found)."
                if (side == CompareSide.A) errorA = message else errorB = message
                return@launch
            }
            val document = CompareDocument(name = name, path = tempFile.absolutePath, pageCount = count)
            if (side == CompareSide.A) {
                docA = document
                pageA = 0
                errorA = null
            } else {
                docB = document
                pageB = 0
                errorB = null
            }
        }
    }

    val launcherA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it, CompareSide.A) }
    }
    val launcherB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it, CompareSide.B) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { splitLayout = !splitLayout }) {
                        Icon(
                            if (splitLayout) Icons.Default.ViewAgenda else Icons.Default.ViewColumn,
                            contentDescription = if (splitLayout) "Stack the documents" else "Show side by side"
                        )
                    }
                    if (comparisonReady(docA, docB)) {
                        IconButton(
                            enabled = !summaryLoading,
                            onClick = {
                                summaryLoading = true
                                scope.launch {
                                    val first = docA
                                    val second = docB
                                    val text = withContext(Dispatchers.IO) {
                                        try {
                                            val comparison = PdfSecurity(context).comparePdfs(first!!.path, second!!.path)
                                            formatComparisonSummary(comparison, first.name, second.name)
                                        } catch (e: Exception) {
                                            "Could not compare the documents: ${e.message}"
                                        }
                                    }
                                    summary = text
                                    summaryLoading = false
                                }
                            }
                        ) {
                            if (summaryLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.TextSnippet, contentDescription = "Text summary")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Both documents are rendered live. Use the arrows under each page to move through it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (splitLayout) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ComparePane(
                            label = "Document A",
                            document = docA,
                            error = errorA,
                            page = pageA,
                            onPageChange = { pageA = it },
                            onPick = { launcherA.launch(arrayOf("application/pdf")) },
                            pdfEngine = pdfEngine,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        ComparePane(
                            label = "Document B",
                            document = docB,
                            error = errorB,
                            page = pageB,
                            onPageChange = { pageB = it },
                            onPick = { launcherB.launch(arrayOf("application/pdf")) },
                            pdfEngine = pdfEngine,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ComparePane(
                            label = "Document A",
                            document = docA,
                            error = errorA,
                            page = pageA,
                            onPageChange = { pageA = it },
                            onPick = { launcherA.launch(arrayOf("application/pdf")) },
                            pdfEngine = pdfEngine,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                        ComparePane(
                            label = "Document B",
                            document = docB,
                            error = errorB,
                            page = pageB,
                            onPageChange = { pageB = it },
                            onPick = { launcherB.launch(arrayOf("application/pdf")) },
                            pdfEngine = pdfEngine,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    summary?.let { text ->
        AlertDialog(
            onDismissRequest = { summary = null },
            title = { Text("Text summary") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { summary = null }) { Text("Close") }
            }
        )
    }
}

/**
 * One side of the comparison: the document header, the rendered current page and
 * its own page navigation.
 *
 * Only the current page is ever rendered and retained, at (at most) the width the
 * pane can actually display, so large documents do not hold multiple page images.
 */
@Composable
private fun ComparePane(
    label: String,
    document: CompareDocument?,
    error: String?,
    page: Int,
    onPageChange: (Int) -> Unit,
    onPick: () -> Unit,
    pdfEngine: PdfEngine,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (document != null || error != null) {
                    IconButton(onClick = onPick) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Replace this PDF",
                            modifier = Modifier.size(20.dp))
                    }
                }
            }

            if (document != null) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = comparePageLabel(document, page),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            when {
                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppButton(text = "Select PDF", onClick = onPick, minWidth = 0.dp)
                }
                document == null -> {
                    Text(
                        text = "Select a PDF to compare",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppButton(text = "Select PDF", onClick = onPick, minWidth = 0.dp)
                }
                !document.hasPages -> {
                    Text(
                        text = "This PDF has no readable pages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    RenderedPage(
                        document = document,
                        page = page,
                        pdfEngine = pdfEngine,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }

            if (document != null && document.pageCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onPageChange(comparePreviousPage(page, document.pageCount)) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page")
                    }
                    Text("${clampComparePage(page, document.pageCount) + 1} / ${document.pageCount}",
                        style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { onPageChange(compareNextPage(page, document.pageCount)) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next page")
                    }
                }
            }
        }
    }
}

/** Renders (and re-renders) just the current page at the pane's display width. */
@Composable
private fun RenderedPage(
    document: CompareDocument,
    page: Int,
    pdfEngine: PdfEngine,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        // Render close to the size actually displayed: enough detail to inspect,
        // never a needlessly huge bitmap.
        val targetWidth = with(density) { maxWidth.toPx() }.toInt().coerceIn(240, 1600)
        var bitmap by remember(document.path) { mutableStateOf<Bitmap?>(null) }
        var loading by remember(document.path) { mutableStateOf(true) }

        LaunchedEffect(document.path, page, targetWidth) {
            loading = true
            // The previous page's bitmap is dropped here; only the current page is
            // ever retained (never the whole document).
            bitmap = withContext(Dispatchers.IO) {
                pdfEngine.renderPage(document.path, clampComparePage(page, document.pageCount), targetWidth)
            }
            loading = false
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = "${document.name} page ${clampComparePage(page, document.pageCount) + 1}",
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit
                )
            } else if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = "This page could not be rendered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
