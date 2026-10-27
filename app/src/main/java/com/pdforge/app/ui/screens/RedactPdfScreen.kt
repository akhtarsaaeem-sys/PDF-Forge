package com.pdforge.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.pdf.PdfEngine
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.AppButton
import com.pdforge.app.ui.components.ProcessingIndicator
import com.pdforge.app.ui.components.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class RedactRegion(
    val pageNumber: Int,   // 0-indexed
    val fx1: Float, val fy1: Float,   // normalized (0..1) within the page area
    val fx2: Float, val fy2: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactPdfScreen(
    inputUri: Uri? = null,
    inputPath: String? = null,
    onBack: () -> Unit,
    onResult: (ProcessingResult) -> Unit,
    onOpenInApp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfEngine = remember { PdfEngine(context) }

    var filePath by remember { mutableStateOf<String?>(inputPath) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var isProcessing by remember { mutableStateOf(false) }
    var doneResult by remember { mutableStateOf<ProcessingResult?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Redaction regions per page (0-indexed page)
    var redactRegions by remember { mutableStateOf(emptyMap<Int, MutableList<RedactRegion>>()) }

    // Current drag state
    var dragStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var dragEnd by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var selectedRegionIndex by remember { mutableIntStateOf(-1) }
    // Displayed size of the page area — drag coordinates are normalized against it
    // so preview and final output use the same coordinate system.
    var displaySize by remember { mutableStateOf(IntSize.Zero) }

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                // Copy + page count run off the main thread for large PDFs.
                val tempFile = FileManager.createTempFile(context, "redact_input_${System.currentTimeMillis()}")
                val copied = withContext(Dispatchers.IO) {
                    FileManager.copyUriToFile(context, it, tempFile)
                }
                if (copied) {
                    filePath = tempFile.absolutePath
                    pageCount = withContext(Dispatchers.IO) { pdfEngine.getPageCount(tempFile.absolutePath) }
                    currentPage = 0
                } else {
                    Toast.makeText(context, "Could not read the selected PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Load page bitmap at the displayed size (region coordinates are normalized,
    // so the mapping to the output is exact regardless of render resolution).
    LaunchedEffect(currentPage, filePath, displaySize) {
        filePath?.let {
            pageBitmap = withContext(Dispatchers.Default) {
                pdfEngine.renderPage(it, currentPage, displaySize.width.coerceAtLeast(200), 1100)
            }
        }
    }

    if (isProcessing) {
        ProcessingIndicator(message = "Applying redaction…", modifier = modifier)
        return
    }

    doneResult?.let { r ->
        ResultScreen(
            result = r,
            toolName = "Redact PDF",
            onOpenInApp = onOpenInApp,
            onBack = { onResult(r) },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Redact PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo last region on current page
                    IconButton(onClick = {
                        val pageRegions = redactRegions[currentPage]?.toMutableList()
                        if (pageRegions != null && pageRegions.isNotEmpty()) {
                            pageRegions.removeLast()
                            redactRegions = redactRegions.toMutableMap().apply {
                                if (pageRegions.isEmpty()) remove(currentPage) else put(currentPage, pageRegions)
                            }
                            selectedRegionIndex = -1
                        }
                    }, enabled = (redactRegions[currentPage]?.size ?: 0) > 0) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo last region")
                    }
                    // Clear all regions on current page
                    IconButton(onClick = {
                        redactRegions = redactRegions.toMutableMap().apply { remove(currentPage) }
                        selectedRegionIndex = -1
                    }, enabled = (redactRegions[currentPage]?.size ?: 0) > 0) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all regions")
                    }
                    // Apply redaction
                    IconButton(onClick = {
                        val totalRegions = redactRegions.values.sumOf { it.size }
                        if (totalRegions == 0) {
                            Toast.makeText(context, "Draw redaction regions first", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        isProcessing = true
                        scope.launch {                                val result = withContext(Dispatchers.IO) {
                                    applyRedaction(context, filePath ?: "", redactRegions, pdfEngine)
                                }
                                isProcessing = false
                                doneResult = result
                        }
                    }, enabled = (redactRegions.values.sumOf { it.size }) > 0) {
                        Icon(Icons.Default.Check, contentDescription = "Apply redactions")
                    }
                }
            )
        }
    ) { padding ->
        if (filePath == null) {
            // File selection
            Box(modifier = modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select a PDF to redact", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    AppButton(
                        text = "Select PDF",
                        onClick = { fileLauncher.launch(arrayOf("application/pdf")) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Marked areas are covered by black boxes. Each page is rebuilt from its rendered image, so the original text underneath is removed and can no longer be selected or searched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
            return@Scaffold
        }

        Column(modifier = modifier.fillMaxSize().padding(padding)) {
            // Page navigation
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (currentPage > 0) { currentPage--; selectedRegionIndex = -1 } }, enabled = currentPage > 0) {
                    Icon(Icons.Default.ChevronLeft, "Previous")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Page ${currentPage + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                    val pageRegions = redactRegions[currentPage]?.size ?: 0
                    if (pageRegions > 0) {
                        Text("$pageRegions region(s) marked", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = { if (currentPage < pageCount - 1) { currentPage++; selectedRegionIndex = -1 } }, enabled = currentPage < pageCount - 1) {
                    Icon(Icons.Default.ChevronRight, "Next")
                }
            }

            // PDF page with redaction overlay
            Box(
                modifier = Modifier
                    .weight(1f).fillMaxWidth().padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .background(ComposeColor.White)
                    .onSizeChanged { displaySize = it }
            ) {
                if (pageBitmap != null) {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = "Page ${currentPage + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentPage) {
                                detectDragGestures(
                                    onDragStart = { offset -> dragStart = offset.x to offset.y },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        dragEnd = change.position.x to change.position.y
                                    },
                                    onDragEnd = {
                                        val start = dragStart
                                        val end = dragEnd
                                        val size = displaySize
                                        if (start != null && end != null && size != IntSize.Zero) {
                                            // Store regions as fractions of the page area so the
                                            // preview and the final PDF use the same coordinates.
                                            val region = RedactRegion(
                                                pageNumber = currentPage,
                                                fx1 = (minOf(start.first, end.first) / size.width).coerceIn(0f, 1f),
                                                fy1 = (minOf(start.second, end.second) / size.height).coerceIn(0f, 1f),
                                                fx2 = (maxOf(start.first, end.first) / size.width).coerceIn(0f, 1f),
                                                fy2 = (maxOf(start.second, end.second) / size.height).coerceIn(0f, 1f)
                                            )
                                            // Only add if the region has meaningful size
                                            if (region.fx2 - region.fx1 > 0.01f && region.fy2 - region.fy1 > 0.01f) {
                                                val pageRegions = redactRegions[currentPage]?.toMutableList() ?: mutableListOf()
                                                pageRegions.add(region)
                                                redactRegions = redactRegions.toMutableMap().apply { put(currentPage, pageRegions) }
                                            }
                                        }
                                        dragStart = null
                                        dragEnd = null
                                    }
                                )
                            },
                        contentScale = ContentScale.Fit
                    )

                    // Draw existing redaction regions + drag preview in the page's
                    // pixel space (density-correct, exactly matching the output).
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        redactRegions[currentPage]?.forEach { region ->
                            val left = region.fx1 * size.width
                            val top = region.fy1 * size.height
                            val w = (region.fx2 - region.fx1) * size.width
                            val h = (region.fy2 - region.fy1) * size.height
                            drawRect(ComposeColor.Black.copy(alpha = 0.7f), topLeft = Offset(left, top), size = Size(w, h))
                            drawRect(ComposeColor.Red, topLeft = Offset(left, top), size = Size(w, h), style = Stroke(width = 2.dp.toPx()))
                        }
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null) {
                            val left = minOf(start.first, end.first)
                            val top = minOf(start.second, end.second)
                            val w = kotlin.math.abs(end.first - start.first)
                            val h = kotlin.math.abs(end.second - start.second)
                            drawRect(ComposeColor.Red.copy(alpha = 0.4f), topLeft = Offset(left, top), size = Size(w, h))
                            drawRect(ComposeColor.Red, topLeft = Offset(left, top), size = Size(w, h), style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            // Instruction card
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "Drag to select areas to redact. The selected content will be permanently removed.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun applyRedaction(
    context: android.content.Context,
    inputPath: String,
    redactRegions: Map<Int, List<RedactRegion>>,
    pdfEngine: PdfEngine
): ProcessingResult {
    return try {
        val fd = ParcelFileDescriptor.open(File(inputPath), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val totalPages = renderer.pageCount

        val output = FileManager.createTempFile(
            context,
            File(inputPath).nameWithoutExtension + "_redacted"
        )
        val outputPath = output.absolutePath
        val pdfDocument = PdfDocument()

        for (pageNum in 0 until totalPages) {
            val page = renderer.openPage(pageNum)
            val scale = 2f
            val bitmap = android.graphics.Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtMost(3000),
                (page.height * scale).toInt().coerceAtMost(4200),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val pageInfo = PdfDocument.PageInfo.Builder(
                (page.width * 72f / 72f).toInt().coerceAtLeast(1),
                (page.height * 72f / 72f).toInt().coerceAtLeast(1),
                pageNum + 1
            ).create()
            val pdfPage = pdfDocument.startPage(pageInfo)
            val canvas = pdfPage.canvas

            // Draw original page
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, canvas.width, canvas.height), null)
            bitmap.recycle()

            // Apply redaction: draw black filled rectangles over redacted areas.
            // Region coordinates are normalized (0..1), so they map 1:1 from the
            // preview to the output canvas regardless of render resolution.
            val regions = redactRegions[pageNum] ?: emptyList()
            for (region in regions) {
                val paint = Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
                canvas.drawRect(
                    region.fx1 * canvas.width, region.fy1 * canvas.height,
                    region.fx2 * canvas.width, region.fy2 * canvas.height,
                    paint
                )
            }

            pdfDocument.finishPage(pdfPage)
        }

        renderer.close(); fd.close()

        FileOutputStream(outputPath).use { pdfDocument.writeTo(it) }
        pdfDocument.close()

        val totalRedacted = redactRegions.values.sumOf { it.size }
        // Validate and publish to user-visible storage
        val saved = FileManager.finalizeOutput(context, output)
            ?: return ProcessingResult(false, message = "Output validation failed — the redacted PDF could not be verified.")
        ProcessingResult(
            success = true,
            outputPath = saved.localPath,
            outputUri = saved.publicUri,
            fileName = saved.name,
            mimeType = saved.mimeType,
            outputSize = saved.size,
            pageCount = totalPages,
            message = "Redaction applied. $totalRedacted region(s) covered across ${redactRegions.size} page(s). Pages were rebuilt from rendered images, so the original text layer is gone — it cannot be selected or searched. (Image-based redaction, not content-stream editing.)"
        )
    } catch (e: Exception) {
        ProcessingResult(false, message = "Failed to apply redaction: ${e.message}")
    }
}
