package com.pdforge.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.PdfPage
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.pdf.PdfEngine
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.AppButton
import com.pdforge.app.ui.components.AppButtonPadding
import com.pdforge.app.ui.components.AppOutlinedButton
import com.pdforge.app.ui.components.ButtonLabel
import com.pdforge.app.ui.components.ProcessingIndicator
import com.pdforge.app.ui.components.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Thumbnail render width for the organize grid — small on purpose (memory). */
private const val THUMBNAIL_WIDTH_PX = 220

/** One page in the organized document: original page number + accumulated rotation. */
data class OrganizePage(
    val originalNumber: Int,   // 1-based page in the source PDF
    val rotation: Int = 0      // 0 / 90 / 180 / 270
)

/**
 * Organize PDF — reorder, rotate and delete pages.
 *
 * Pages are shown as a scrollable thumbnail grid. Tap to select, then use the
 * controls to move up/down, rotate 90° (accumulates), or delete. Undo restores
 * the previous page arrangement. Saving goes through the same centralized
 * pipeline as every other tool (engine → temp → validate → publish → Recent
 * Files → viewer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizePdfScreen(
    onBack: () -> Unit,
    onResult: (ProcessingResult) -> Unit,
    onOpenInApp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfEngine = remember { PdfEngine(context) }

    var filePath by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<OrganizePage>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var isProcessing by remember { mutableStateOf(false) }
    var doneResult by remember { mutableStateOf<ProcessingResult?>(null) }

    // Undo: each snapshot is the full page list before a mutation.
    val undoStack = remember { mutableStateListOf<List<OrganizePage>>() }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selected ->
            scope.launch {
                selectDocument(context, pdfEngine, selected, { path, name, count ->
                    filePath = path
                    fileName = name
                    pages = (1..count).map { OrganizePage(originalNumber = it) }
                    selectedIndex = -1
                    undoStack.clear()
                }, { Toast.makeText(context, "Could not read the selected PDF", Toast.LENGTH_SHORT).show() })
            }
        }
    }

    if (isProcessing) {
        ProcessingIndicator(message = "Saving organized PDF…", modifier = modifier)
        return
    }

    doneResult?.let { r ->
        ResultScreen(
            result = r,
            toolName = "Organize PDF",
            onOpenInApp = onOpenInApp,
            onBack = { onResult(r) },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Organize PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (undoStack.isNotEmpty()) {
                        IconButton(onClick = {
                            if (undoStack.isNotEmpty()) {
                                pages = undoStack.removeLast()
                                selectedIndex = if (pages.isEmpty()) -1 else selectedIndex.coerceIn(0, pages.size - 1)
                            }
                        }) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (filePath != null) {
                Surface(shadowElevation = 8.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        // Page controls — operate on the selected page.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppOutlinedButton(
                                text = "Up",
                                onClick = {
                                    if (selectedIndex > 0) {
                                        undoStack.add(pages)
                                        pages = pages.toMutableList().apply {
                                            val item = removeAt(selectedIndex)
                                            add(selectedIndex - 1, item)
                                        }
                                        selectedIndex--
                                    }
                                },
                                enabled = selectedIndex > 0,
                                modifier = Modifier.weight(1f)
                            )
                            AppOutlinedButton(
                                text = "Down",
                                onClick = {
                                    if (selectedIndex in 0 until pages.size - 1) {
                                        undoStack.add(pages)
                                        pages = pages.toMutableList().apply {
                                            val item = removeAt(selectedIndex)
                                            add(selectedIndex + 1, item)
                                        }
                                        selectedIndex++
                                    }
                                },
                                enabled = selectedIndex in 0 until pages.size - 1,
                                modifier = Modifier.weight(1f)
                            )
                            AppOutlinedButton(
                                text = "Rotate",
                                onClick = {
                                    if (selectedIndex in pages.indices) {
                                        undoStack.add(pages)
                                        pages = pages.toMutableList().apply {
                                            set(selectedIndex, get(selectedIndex).let { it.copy(rotation = (it.rotation + 90) % 360) })
                                        }
                                    }
                                },
                                enabled = selectedIndex in pages.indices,
                                modifier = Modifier.weight(1f)
                            )
                            AppOutlinedButton(
                                text = "Delete",
                                onClick = {
                                    if (selectedIndex in pages.indices) {
                                        undoStack.add(pages)
                                        val newPages = pages.toMutableList().apply { removeAt(selectedIndex) }
                                        pages = newPages
                                        selectedIndex = if (newPages.isEmpty()) -1 else selectedIndex.coerceIn(0, newPages.size - 1)
                                    }
                                },
                                enabled = selectedIndex in pages.indices,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (selectedIndex in pages.indices) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Page ${selectedIndex + 1} selected" +
                                    pages[selectedIndex].rotation.let { r -> if (r == 0) "" else " — rotated ${r}°" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (pages.isEmpty()) {
                                    Toast.makeText(context, "No pages left to save", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (isProcessing) return@Button
                                isProcessing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        saveOrganized(context, pdfEngine, filePath ?: "", fileName, pages)
                                    }
                                    isProcessing = false
                                    doneResult = result
                                }
                            },
                            enabled = pages.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = AppButtonPadding
                        ) {
                            ButtonLabel(
                                text = "Save Organized PDF (${pages.size} page${if (pages.size == 1) "" else "s"})",
                                icon = Icons.Default.PictureAsPdf
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (filePath == null) {
            Box(modifier = modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DragIndicator, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select a PDF to organize", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Reorder, rotate and delete pages before saving.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    AppButton(
                        text = "Select PDF",
                        onClick = { fileLauncher.launch(arrayOf("application/pdf")) }
                    )
                }
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(pages, key = { _, page -> page.originalNumber.toString() + "_" + page.rotation }) { index, page ->
                OrganizeThumbnail(
                    page = page,
                    index = index,
                    isSelected = index == selectedIndex,
                    filePath = filePath ?: "",
                    pdfEngine = pdfEngine,
                    onClick = { selectedIndex = index }
                )
            }
        }
    }
}

@Composable
private fun OrganizeThumbnail(
    page: OrganizePage,
    index: Int,
    isSelected: Boolean,
    filePath: String,
    pdfEngine: PdfEngine,
    onClick: () -> Unit
) {
    // Keyed on the rotation so the thumbnail re-renders (rotated) as soon as the
    // user taps Rotate. Without this the preview never showed the rotation, which
    // is why Rotate looked like it did nothing on the device.
    val bitmap by produceState<Bitmap?>(null, filePath, page.originalNumber, page.rotation) {
        value = withContext(Dispatchers.IO) {
            pdfEngine.renderPage(
                filePath,
                page.originalNumber - 1,
                width = THUMBNAIL_WIDTH_PX,
                rotationDegrees = page.rotation
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${page.originalNumber}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            // Page number badge
            Text(
                text = "${index + 1}",
                modifier = Modifier
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            // Rotation badge
            if (page.rotation != 0) {
                Text(
                    text = "${page.rotation}°",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

private suspend fun selectDocument(
    context: android.content.Context,
    pdfEngine: PdfEngine,
    uri: Uri,
    onLoaded: (path: String, name: String, pageCount: Int) -> Unit,
    onError: () -> Unit
) {
    val name = FileManager.getFileName(context, uri)
    val tempFile = FileManager.createTempFile(context, "organize_input_${System.currentTimeMillis()}")
    val copied = withContext(Dispatchers.IO) {
        FileManager.copyUriToFile(context, uri, tempFile)
    }
    if (copied) {
        val count = withContext(Dispatchers.IO) { pdfEngine.getPageCount(tempFile.absolutePath) }
        if (count > 0) {
            onLoaded(tempFile.absolutePath, name, count)
        } else {
            tempFile.delete()
            onError()
        }
    } else {
        onError()
    }
}

/** Runs the engine, validates and publishes through the centralized pipeline. */
private fun saveOrganized(
    context: android.content.Context,
    pdfEngine: PdfEngine,
    inputPath: String,
    inputName: String,
    pages: List<OrganizePage>
): ProcessingResult {
    return try {
        val output = FileManager.createTempFile(context, FileManager.generateOutputName(inputName, "organized"))
        val engineResult = pdfEngine.organizePdf(
            inputPath,
            output.absolutePath,
            pages.map { PdfPage(number = it.originalNumber, rotation = it.rotation) }
        )
        if (!engineResult.success) return engineResult

        val saved = FileManager.finalizeOutput(context, output)
            ?: return ProcessingResult(false, message = "Output validation failed — the organized PDF could not be verified.")
        ProcessingResult(
            success = true,
            outputPath = saved.localPath,
            outputUri = saved.publicUri,
            fileName = saved.name,
            mimeType = saved.mimeType,
            outputSize = saved.size,
            pageCount = pages.size,
            outputPaths = listOf(saved.localPath)
        )
    } catch (e: Exception) {
        ProcessingResult(false, message = "Failed to save organized PDF: ${e.message}")
    }
}