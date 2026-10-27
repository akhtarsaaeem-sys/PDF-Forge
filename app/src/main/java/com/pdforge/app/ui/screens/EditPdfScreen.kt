package com.pdforge.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.edit.EDIT_COLOR_PALETTE
import com.pdforge.app.edit.EDIT_STROKE_WIDTHS
import com.pdforge.app.edit.EDIT_TEXT_SIZES
import com.pdforge.app.edit.DrawingStroke
import com.pdforge.app.edit.EditStyle
import com.pdforge.app.edit.EditTool
import com.pdforge.app.edit.ShapeOverlay
import com.pdforge.app.edit.ShapeType
import com.pdforge.app.edit.TextOverlay
import com.pdforge.app.edit.buildEditPagePlan
import com.pdforge.app.edit.newShape
import com.pdforge.app.edit.newStroke
import com.pdforge.app.edit.newText
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
import java.util.UUID

// EditTool / ShapeType / TextOverlay / DrawingStroke / ShapeOverlay / EditStyle
// and the draw plan live in the pure model in `com.pdforge.app.edit`, so the
// properties the user picks (colour, thickness, size, fill) can be verified in a
// JVM unit test. ImageOverlay stays here because it wraps a Bitmap.
data class ImageOverlay(
    val id: String = UUID.randomUUID().toString(),
    val bitmap: Bitmap,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/** Unified element wrapper for selection/move/delete */
sealed class EditElement(val id: String, val pageIndex: Int) {
    class Text(val overlay: TextOverlay, page: Int) : EditElement(overlay.id, page)
    class Stroke(val stroke: DrawingStroke, page: Int) : EditElement(stroke.id, page)
    class Image(val overlay: ImageOverlay, page: Int) : EditElement(overlay.id, page)
    class Shape(val shape: ShapeOverlay, page: Int) : EditElement(shape.id, page)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPdfScreen(
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

    // Edit state per page
    var activeTool by remember { mutableStateOf(EditTool.NONE) }
    var textOverlays by remember { mutableStateOf(emptyMap<Int, List<TextOverlay>>()) }
    var drawingStrokes by remember { mutableStateOf(emptyMap<Int, List<DrawingStroke>>()) }
    var imageOverlays by remember { mutableStateOf(emptyMap<Int, List<ImageOverlay>>()) }
    var shapeOverlays by remember { mutableStateOf(emptyMap<Int, List<ShapeOverlay>>()) }
    var currentDrawingPoints by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }

    // Selection state
    var selectedId by remember { mutableStateOf<String?>(null) }

    // Shape drawing state
    var shapeStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var shapePreview by remember { mutableStateOf<ShapeOverlay?>(null) }
    var selectedShapeType by remember { mutableStateOf(ShapeType.RECTANGLE) }

    // Pen / shape / text properties selected in the toolbar. Every new overlay is
    // built from this, and the values are what the renderer writes into the PDF.
    var editStyle by remember { mutableStateOf(EditStyle()) }

    // Text input dialog
    var showTextInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var tapPosition by remember { mutableStateOf(0f to 0f) }

    // Undo/Redo stacks: each entry is a snapshot of all overlays
    data class EditSnapshot(
        val texts: Map<Int, List<TextOverlay>>,
        val strokes: Map<Int, List<DrawingStroke>>,
        val images: Map<Int, List<ImageOverlay>>,
        val shapes: Map<Int, List<ShapeOverlay>>
    )

    val undoStack = remember { mutableStateListOf<EditSnapshot>() }
    val redoStack = remember { mutableStateListOf<EditSnapshot>() }

    fun currentSnapshot() = EditSnapshot(textOverlays, drawingStrokes, imageOverlays, shapeOverlays)

    fun pushUndo() {
        undoStack.add(currentSnapshot())
        redoStack.clear()
    }

    fun restoreSnapshot(snapshot: EditSnapshot) {
        // Recycle old image bitmaps before restoring
        imageOverlays.values.flatten().forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        textOverlays = snapshot.texts
        drawingStrokes = snapshot.strokes
        imageOverlays = snapshot.images
        shapeOverlays = snapshot.shapes
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val snapshot = undoStack.removeLast()
            redoStack.add(currentSnapshot())
            restoreSnapshot(snapshot)
            selectedId = null
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val snapshot = redoStack.removeLast()
            undoStack.add(currentSnapshot())
            restoreSnapshot(snapshot)
            selectedId = null
        }
    }

    // File picker for the PDF being edited (used when opened without a file)
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                // Copy + page count run off the main thread for large PDFs.
                val tempFile = FileManager.createTempFile(context, "edit_input_${System.currentTimeMillis()}")
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

    // File picker for images
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    pushUndo()
                    val pageImages = imageOverlays[currentPage] ?: emptyList()
                    imageOverlays = imageOverlays + (currentPage to (pageImages + ImageOverlay(
                        bitmap = bitmap,
                        x = 0.10f,
                        y = 0.10f,
                        width = 0.30f,
                        height = 0.30f * bitmap.height / bitmap.width.toFloat()
                    )))
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Load the PDF from an explicit uri/path argument
    LaunchedEffect(inputUri, inputPath) {
        if (inputUri != null && filePath == null) {
            val tempFile = FileManager.createTempFile(context, "edit_input_${System.currentTimeMillis()}")
            val copied = withContext(Dispatchers.IO) {
                FileManager.copyUriToFile(context, inputUri, tempFile)
            }
            if (copied) {
                filePath = tempFile.absolutePath
                pageCount = withContext(Dispatchers.IO) { pdfEngine.getPageCount(tempFile.absolutePath) }
            }
        } else if (filePath != null && pageCount == 0) {
            pageCount = withContext(Dispatchers.IO) { pdfEngine.getPageCount(filePath!!) }
        }
    }

    // Deselect when changing pages
    LaunchedEffect(currentPage) { selectedId = null }

    if (isProcessing) {
        ProcessingIndicator(message = "Saving PDF…", modifier = modifier)
        return
    }

    doneResult?.let { r ->
        ResultScreen(
            result = r,
            toolName = "Edit PDF",
            onOpenInApp = onOpenInApp,
            onBack = { onResult(r) },
            modifier = modifier
        )
        return
    }

    if (showTextInput) {
        AlertDialog(
            onDismissRequest = { showTextInput = false },
            title = { Text("Add Text") },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Enter text") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (inputText.isNotBlank()) {
                        pushUndo()
                        val pageOverlays = textOverlays[currentPage] ?: emptyList()
                        // The chosen text colour and size come from the toolbar.
                        textOverlays = textOverlays + (currentPage to (pageOverlays +
                            newText(inputText, tapPosition.first, tapPosition.second, editStyle)))
                        inputText = ""
                    }
                    showTextInput = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showTextInput = false; inputText = "" }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo
                    IconButton(onClick = ::undo, enabled = undoStack.isNotEmpty()) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    // Redo
                    IconButton(onClick = ::redo, enabled = redoStack.isNotEmpty()) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    // Delete selected
                    if (selectedId != null) {
                        IconButton(onClick = {
                            pushUndo()
                            val id = selectedId ?: return@IconButton
                            val pageTexts = textOverlays[currentPage]?.toMutableList()
                            if (pageTexts != null) {
                                val idx = pageTexts.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    pageTexts.removeAt(idx)
                                    textOverlays = textOverlays + (currentPage to pageTexts)
                                }
                            }
                            val pageStrokes = drawingStrokes[currentPage]?.toMutableList()
                            if (pageStrokes != null) {
                                val idx = pageStrokes.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    pageStrokes.removeAt(idx)
                                    drawingStrokes = drawingStrokes + (currentPage to pageStrokes)
                                }
                            }
                            val pageImages = imageOverlays[currentPage]?.toMutableList()
                            if (pageImages != null) {
                                val idx = pageImages.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    pageImages[idx].bitmap.recycle()
                                    pageImages.removeAt(idx)
                                    imageOverlays = imageOverlays + (currentPage to pageImages)
                                }
                            }
                            val pageShapes = shapeOverlays[currentPage]?.toMutableList()
                            if (pageShapes != null) {
                                val idx = pageShapes.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    pageShapes.removeAt(idx)
                                    shapeOverlays = shapeOverlays + (currentPage to pageShapes)
                                }
                            }
                            selectedId = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    // Save
                    IconButton(
                        onClick = {
                            isProcessing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    saveEditedPdf(context, filePath ?: "", textOverlays, drawingStrokes, imageOverlays, shapeOverlays, pdfEngine)
                                }
                                isProcessing = false
                                doneResult = result
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                // Property bar for the active tool. It is ONE horizontally
                // scrollable row, so colour / thickness / size controls never wrap
                // onto a second line, never grow an oversized toolbar, and never
                // cover the page preview.
                if (activeTool != EditTool.NONE) {
                    EditStyleBar(
                        activeTool = activeTool,
                        style = editStyle,
                        selectedShapeType = selectedShapeType,
                        onShapeTypeChange = { selectedShapeType = it },
                        onStyleChange = { editStyle = it }
                    )
                }
                // Tool buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    EditToolButton(
                        icon = Icons.Default.TextFields, label = "Text",
                        isActive = activeTool == EditTool.TEXT,
                        onClick = {
                            activeTool = if (activeTool == EditTool.TEXT) EditTool.NONE else EditTool.TEXT
                            selectedId = null
                        }
                    )
                    EditToolButton(
                        icon = Icons.Default.Brush, label = "Draw",
                        isActive = activeTool == EditTool.DRAW,
                        onClick = {
                            activeTool = if (activeTool == EditTool.DRAW) EditTool.NONE else EditTool.DRAW
                            selectedId = null
                        }
                    )
                    EditToolButton(
                        icon = Icons.Default.Image, label = "Image",
                        isActive = false,
                        onClick = {
                            imageLauncher.launch(arrayOf("image/jpeg", "image/png"))
                            activeTool = EditTool.NONE
                        }
                    )
                    EditToolButton(
                        icon = Icons.Default.Hexagon, label = "Shape",
                        isActive = activeTool == EditTool.SHAPE,
                        onClick = {
                            activeTool = if (activeTool == EditTool.SHAPE) EditTool.NONE else EditTool.SHAPE
                            selectedId = null
                        }
                    )
                }
            }
        }
    ) { padding ->
        if (filePath == null || pageCount == 0) {
            Box(modifier = modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select a PDF to edit", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    AppButton(
                        text = "Select PDF",
                        onClick = { fileLauncher.launch(arrayOf("application/pdf")) }
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
                IconButton(onClick = { if (currentPage > 0) currentPage-- }, enabled = currentPage > 0) {
                    Icon(Icons.Default.ChevronLeft, "Previous")
                }
                Text("Page ${currentPage + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { if (currentPage < pageCount - 1) currentPage++ }, enabled = currentPage < pageCount - 1) {
                    Icon(Icons.Default.ChevronRight, "Next")
                }
            }

            // PDF page canvas — the page is fitted into the available area and all
            // overlay coordinates are stored as fractions (0..1) of the page, so
            // the on-screen preview and the saved PDF share one coordinate system.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f).fillMaxWidth().padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .background(ComposeColor.White)
            ) {
                val density = LocalDensity.current
                var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(currentPage, filePath) {
                    pageBitmap = withContext(Dispatchers.Default) {
                        pdfEngine.renderPage(filePath!!, currentPage, 800, 1100)
                    }
                }
                val bitmap = pageBitmap

                val containerW = with(density) { maxWidth.toPx() }
                val containerH = with(density) { maxHeight.toPx() }
                val pageAspect = if (bitmap != null) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
                val fitW = if (containerW > 0f && containerH > 0f) minOf(containerW, containerH * pageAspect) else 0f
                val fitH = if (fitW > 0f) fitW / pageAspect else 0f
                val fitOffsetX = (containerW - fitW) / 2f
                val fitOffsetY = (containerH - fitH) / 2f

                if (bitmap != null && fitW > 0f) {
                    // Fitted page surface — all gestures and overlays live in its
                    // pixel space, which equals the bitmap's own coordinate space.
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(fitOffsetX.roundToInt(), fitOffsetY.roundToInt()) }
                            .size(with(density) { fitW.toDp() }, with(density) { fitH.toDp() })
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${currentPage + 1}",
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(activeTool, currentPage) {
                                    when (activeTool) {
                                        EditTool.NONE -> {
                                            detectTapGestures { offset ->
                                                // Try to find an element under the tap
                                                val tapped = hitTestElement(
                                                    offset.x / fitW, offset.y / fitH, currentPage,
                                                    textOverlays, imageOverlays, shapeOverlays
                                                )
                                                selectedId = tapped
                                            }
                                        }
                                        EditTool.TEXT -> {
                                            detectTapGestures { offset ->
                                                tapPosition = (offset.x / fitW) to (offset.y / fitH)
                                                showTextInput = true
                                            }
                                        }
                                        EditTool.DRAW -> {
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    currentDrawingPoints = listOf((offset.x / fitW) to (offset.y / fitH))
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    currentDrawingPoints = currentDrawingPoints + ((change.position.x / fitW) to (change.position.y / fitH))
                                                },
                                                onDragEnd = {
                                                    if (currentDrawingPoints.size > 1) {
                                                        pushUndo()
                                                        val strokes = drawingStrokes[currentPage] ?: emptyList()
                                                        // Selected pen colour + thickness are baked in here.
                                                        drawingStrokes = drawingStrokes + (
                                                            currentPage to (strokes + newStroke(currentDrawingPoints, editStyle))
                                                        )
                                                    }
                                                    currentDrawingPoints = emptyList()
                                                }
                                            )
                                        }
                                        EditTool.SHAPE -> {
                                            detectDragGestures(
                                                onDragStart = { offset -> shapeStart = (offset.x / fitW) to (offset.y / fitH) },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val start = shapeStart ?: return@detectDragGestures
                                                    // Preview uses the live style so colour, width and
                                                    // fill are visible before the shape is committed.
                                                    shapePreview = newShape(
                                                        type = selectedShapeType,
                                                        x1 = start.first, y1 = start.second,
                                                        x2 = change.position.x / fitW, y2 = change.position.y / fitH,
                                                        style = editStyle
                                                    )
                                                },
                                                onDragEnd = {
                                                    val preview = shapePreview
                                                    if (preview != null) {
                                                        pushUndo()
                                                        val shapes = shapeOverlays[currentPage] ?: emptyList()
                                                        shapeOverlays = shapeOverlays + (currentPage to (shapes + preview))
                                                    }
                                                    shapeStart = null
                                                    shapePreview = null
                                                }
                                            )
                                        }
                                        else -> {}
                                    }
                                },
                            contentScale = ContentScale.Fit  // fitted box has the page's aspect ratio, so Fit fills it exactly
                        )

                    // Overlay canvas (fractions × canvas size)
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        // Strokes
                        drawingStrokes[currentPage]?.forEach { stroke ->
                            if (stroke.points.size >= 2) {
                                val p = ComposePath().apply {
                                    moveTo(stroke.points[0].first * size.width, stroke.points[0].second * size.height)
                                    for (i in 1 until stroke.points.size) lineTo(stroke.points[i].first * size.width, stroke.points[i].second * size.height)
                                }
                                drawPath(p, ComposeColor(stroke.color), style = Stroke(width = stroke.strokeWidth * size.width))
                            }
                        }
                        // Current (in-progress) drawing — shows the selected colour
                        // and thickness while the finger is still down.
                        if (currentDrawingPoints.size >= 2) {
                            val p = ComposePath().apply {
                                moveTo(currentDrawingPoints[0].first * size.width, currentDrawingPoints[0].second * size.height)
                                for (i in 1 until currentDrawingPoints.size) lineTo(currentDrawingPoints[i].first * size.width, currentDrawingPoints[i].second * size.height)
                            }
                            drawPath(
                                p,
                                ComposeColor(editStyle.penColor),
                                style = Stroke(width = editStyle.penWidth * size.width)
                            )
                        }
                        // Shapes
                        (shapeOverlays[currentPage] ?: emptyList()).forEach { shape ->
                            drawShape(shape)
                        }
                        // Shape preview
                        shapePreview?.let { drawShape(it) }
                        // Selection highlight
                        selectedId?.let { selId ->
                            drawSelectionHighlight(selId, currentPage, textOverlays, imageOverlays, shapeOverlays)
                        }
                    }

                    // Text overlays with move support (fraction coordinates)
                    textOverlays[currentPage]?.forEach { overlay ->
                        val isSelected = overlay.id == selectedId
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((overlay.x * fitW).roundToInt(), (overlay.y * fitH).roundToInt()) }
                                .then(
                                    if (isSelected) Modifier.border(2.dp, ComposeColor(0xFF2196F3), RoundedCornerShape(4.dp))
                                    else Modifier
                                )
                                .pointerInput(overlay.id, activeTool) {
                                    if (activeTool == EditTool.NONE) {
                                        detectDragGestures(
                                            onDragStart = { _ ->
                                                selectedId = overlay.id
                                                pushUndo()
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val idx = textOverlays[currentPage]?.indexOfFirst { it.id == overlay.id } ?: -1
                                                if (idx >= 0) {
                                                    val old = textOverlays[currentPage]!![idx]
                                                    textOverlays = textOverlays + (currentPage to (textOverlays[currentPage]!!.toMutableList().apply {
                                                        set(idx, old.copy(
                                                            x = (old.x + dragAmount.x / fitW).coerceIn(0f, 1f),
                                                            y = (old.y + dragAmount.y / fitH).coerceIn(0f, 1f)
                                                        ))
                                                    }))
                                                }
                                            }
                                        )
                                    }
                                }
                                .clickable {
                                    if (activeTool == EditTool.NONE) selectedId = overlay.id
                                }
                        ) {
                            Text(
                                text = overlay.text,
                                color = ComposeColor(overlay.color),
                                fontSize = with(density) { (overlay.fontSize * fitW).sp }
                            )
                        }
                    }

                    // Image overlays with move + resize (fraction coordinates)
                    imageOverlays[currentPage]?.forEach { overlay ->
                        val isSelected = overlay.id == selectedId
                        val imgW = overlay.width * fitW
                        val imgH = overlay.height * fitH
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((overlay.x * fitW).roundToInt(), (overlay.y * fitH).roundToInt()) }
                                .size(with(density) { imgW.toDp() }, with(density) { imgH.toDp() })
                                .then(
                                    if (isSelected) Modifier.border(2.dp, ComposeColor(0xFF2196F3), RoundedCornerShape(4.dp))
                                    else Modifier
                                )
                                .pointerInput(overlay.id, activeTool) {
                                    if (activeTool == EditTool.NONE) {
                                        detectDragGestures(
                                            onDragStart = { _ ->
                                                selectedId = overlay.id
                                                pushUndo()
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val idx = imageOverlays[currentPage]?.indexOfFirst { it.id == overlay.id } ?: -1
                                                if (idx >= 0) {
                                                    val old = imageOverlays[currentPage]!![idx]
                                                    imageOverlays = imageOverlays + (currentPage to (imageOverlays[currentPage]!!.toMutableList().apply {
                                                        set(idx, old.copy(
                                                            x = (old.x + dragAmount.x / fitW).coerceIn(0f, 1f),
                                                            y = (old.y + dragAmount.y / fitH).coerceIn(0f, 1f)
                                                        ))
                                                    }))
                                                }
                                            }
                                        )
                                    }
                                }
                                .clickable {
                                    if (activeTool == EditTool.NONE) selectedId = overlay.id
                                }
                        ) {
                            Image(
                                bitmap = overlay.bitmap.asImageBitmap(),
                                contentDescription = "Overlay image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            // Resize handle (bottom-right corner)
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(24.dp)
                                        .background(ComposeColor(0xFF2196F3), CircleShape)
                                        .pointerInput(overlay.id) {
                                            detectDragGestures(
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val idx = imageOverlays[currentPage]?.indexOfFirst { it.id == overlay.id } ?: -1
                                                    if (idx >= 0) {
                                                        val old = imageOverlays[currentPage]!![idx]
                                                        val newW = (old.width + dragAmount.x * 2 / fitW).coerceAtLeast(0.05f)
                                                        val newH = (old.height + dragAmount.y * 2 / fitH).coerceAtLeast(0.05f)
                                                        imageOverlays = imageOverlays + (currentPage to (imageOverlays[currentPage]!!.toMutableList().apply {
                                                            set(idx, old.copy(width = newW, height = newH))
                                                        }))
                                                    }
                                                },
                                                onDragStart = { pushUndo() }
                                            )
                                        }
                                )
                            }
                        }
                    }
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            // Active tool / selection indicator
            when {
                selectedId != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Text(
                            "Element selected — drag to move, tap ✕ to delete",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                activeTool != EditTool.NONE -> {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(
                            text = when (activeTool) {
                                EditTool.TEXT -> "Tap on the page to add text"
                                EditTool.DRAW -> "Draw on the page with your finger"
                                EditTool.IMAGE -> "Image added to page"
                                EditTool.SHAPE -> "Drag to draw a ${selectedShapeType.name.lowercase()}"
                                EditTool.NONE -> ""
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

// ── Hit testing (fraction coordinates) ───────────────────────

private fun hitTestElement(
    x: Float, y: Float, page: Int,
    texts: Map<Int, List<TextOverlay>>,
    images: Map<Int, List<ImageOverlay>>,
    shapes: Map<Int, List<ShapeOverlay>>
): String? {
    val pad = 0.02f
    // Check images first (usually larger)
    images[page]?.forEach { img ->
        if (x in img.x..(img.x + img.width) && y in img.y..(img.y + img.height)) return img.id
    }
    // Check shapes
    shapes[page]?.forEach { s ->
        val minX = minOf(s.x1, s.x2) - pad
        val maxX = maxOf(s.x1, s.x2) + pad
        val minY = minOf(s.y1, s.y2) - pad
        val maxY = maxOf(s.y1, s.y2) + pad
        if (x in minX..maxX && y in minY..maxY) return s.id
    }
    // Check text (approximate bounds)
    texts[page]?.forEach { t ->
        val approxW = t.text.length * (t.fontSize * 0.6f)
        if (x in (t.x - pad)..(t.x + approxW) && y in (t.y - t.fontSize)..(t.y + pad)) return t.id
    }
    return null
}

// ── Shape drawing on Canvas ─────────────────────────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShape(shape: ShapeOverlay) {
    val color = ComposeColor(shape.color)
    // A filled rectangle/ellipse uses the same colour as its outline; a line can
    // only ever be stroked.
    val paintStyle = if (shape.fill && shape.type != ShapeType.LINE) {
        Fill
    } else {
        Stroke(width = shape.strokeWidth * size.width)
    }
    // Convert fraction coordinates to this canvas's pixel space.
    val x1 = shape.x1 * size.width
    val y1 = shape.y1 * size.height
    val x2 = shape.x2 * size.width
    val y2 = shape.y2 * size.height
    when (shape.type) {
        ShapeType.RECTANGLE -> {
            drawRect(color, topLeft = Offset(minOf(x1, x2), minOf(y1, y2)),
                size = Size(kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1)),
                style = paintStyle)
        }
        ShapeType.CIRCLE -> {
            val cx = (x1 + x2) / 2f
            val cy = (y1 + y2) / 2f
            val rx = kotlin.math.abs(x2 - x1) / 2f
            val ry = kotlin.math.abs(y2 - y1) / 2f
            drawOval(color, topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2), style = paintStyle)
        }
        ShapeType.LINE -> {
            drawLine(color, start = Offset(x1, y1),
                end = Offset(x2, y2), strokeWidth = shape.strokeWidth * size.width)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionHighlight(
    selectedId: String, page: Int,
    texts: Map<Int, List<TextOverlay>>,
    images: Map<Int, List<ImageOverlay>>,
    shapes: Map<Int, List<ShapeOverlay>>
) {
    val selColor = ComposeColor(0xFF2196F3)
    val borderPx = 2.dp.toPx()

    texts[page]?.firstOrNull { it.id == selectedId }?.let { t ->
        val fontSizePx = t.fontSize * size.width
        val approxW = t.text.length * (fontSizePx * 0.6f)
        drawRect(selColor, topLeft = Offset(t.x * size.width - borderPx, t.y * size.height - fontSizePx),
            size = Size(approxW + borderPx * 2, fontSizePx + borderPx * 2),
            style = Stroke(width = borderPx))
    }
    images[page]?.firstOrNull { it.id == selectedId }?.let { img ->
        val w = img.width * size.width
        val h = img.height * size.height
        drawRect(selColor, topLeft = Offset(img.x * size.width - borderPx, img.y * size.height - borderPx),
            size = Size(w + borderPx * 2, h + borderPx * 2),
            style = Stroke(width = borderPx))
    }
    shapes[page]?.firstOrNull { it.id == selectedId }?.let { s ->
        val pad = 0.01f * size.width
        val minX = minOf(s.x1, s.x2) * size.width - pad
        val maxX = maxOf(s.x1, s.x2) * size.width + pad
        val minY = minOf(s.y1, s.y2) * size.height - pad
        val maxY = maxOf(s.y1, s.y2) * size.height + pad
        drawRect(selColor, topLeft = Offset(minX, minY),
            size = Size(maxX - minX, maxY - minY),
            style = Stroke(width = borderPx))
    }
}

// ── Tool button ─────────────────────────────────────────────

@Composable
fun EditToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(32.dp).then(
                if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(6.dp)
                else Modifier.padding(6.dp)
            )
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Tool property bar (colour / thickness / text size) ─────────

/**
 * The property controls for the active tool.
 *
 * One horizontally scrollable row on purpose: the palette and the presets never
 * wrap, the toolbar stays one line tall on a normal phone, and the page preview
 * is never covered by a wall of controls.
 */
@Composable
private fun EditStyleBar(
    activeTool: EditTool,
    style: EditStyle,
    selectedShapeType: ShapeType,
    onShapeTypeChange: (ShapeType) -> Unit,
    onStyleChange: (EditStyle) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (activeTool) {
            EditTool.SHAPE -> {
                listOf(
                    ShapeType.RECTANGLE to "Rect",
                    ShapeType.CIRCLE to "Circle",
                    ShapeType.LINE to "Line"
                ).forEach { (type, label) ->
                    PresetChip(label, selectedShapeType == type) { onShapeTypeChange(type) }
                }
                ColorSwatchRow(style.shapeColor) { onStyleChange(style.copy(shapeColor = it)) }
                EDIT_STROKE_WIDTHS.forEach { preset ->
                    PresetChip(preset.label, style.shapeWidth == preset.fraction) {
                        onStyleChange(style.copy(shapeWidth = preset.fraction))
                    }
                }
                PresetChip("Fill", style.shapeFill) { onStyleChange(style.copy(shapeFill = !style.shapeFill)) }
            }
            EditTool.DRAW -> {
                ColorSwatchRow(style.penColor) { onStyleChange(style.copy(penColor = it)) }
                EDIT_STROKE_WIDTHS.forEach { preset ->
                    PresetChip(preset.label, style.penWidth == preset.fraction) {
                        onStyleChange(style.copy(penWidth = preset.fraction))
                    }
                }
            }
            EditTool.TEXT -> {
                EDIT_TEXT_SIZES.forEach { preset ->
                    PresetChip(preset.label, style.textSize == preset.fraction) {
                        onStyleChange(style.copy(textSize = preset.fraction))
                    }
                }
                ColorSwatchRow(style.textColor) { onStyleChange(style.copy(textColor = it)) }
            }
            else -> Unit
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, softWrap = false) },
        modifier = Modifier.heightIn(min = 34.dp)
    )
}

/** Touch-friendly palette row; a thick ring marks the active colour. */
@Composable
private fun ColorSwatchRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        EDIT_COLOR_PALETTE.forEach { option ->
            val isSelected = option.argb == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(option.argb) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 28.dp else 24.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(option.argb))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

// ── Save to PDF ──────────────────────────────────────────────

private fun saveEditedPdf(
    context: Context, inputPath: String,
    textOverlays: Map<Int, List<TextOverlay>>,
    drawingStrokes: Map<Int, List<DrawingStroke>>,
    imageOverlays: Map<Int, List<ImageOverlay>>,
    shapeOverlays: Map<Int, List<ShapeOverlay>>,
    pdfEngine: PdfEngine
): ProcessingResult {
    return try {
        val fd = ParcelFileDescriptor.open(File(inputPath), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val totalPages = renderer.pageCount
        val output = FileManager.createTempFile(context, File(inputPath).nameWithoutExtension + "_edited")
        val outputPath = output.absolutePath
        val pdfDocument = PdfDocument()

        for (pageNum in 0 until totalPages) {
            val page = renderer.openPage(pageNum)
            val scale = 2f
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtMost(3000),
                (page.height * scale).toInt().coerceAtMost(4200),
                Bitmap.Config.ARGB_8888
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

            // Original page
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, canvas.width, canvas.height), null)
            bitmap.recycle()

            // Overlay coordinates are normalized (0..1) fractions of the page, so
            // they map 1:1 from the preview to the output canvas at any resolution.
            // The plan is built by the pure model, so the colour / thickness / text
            // size / fill painted here is exactly the property the user selected.
            val plan = buildEditPagePlan(
                strokes = drawingStrokes[pageNum] ?: emptyList(),
                texts = textOverlays[pageNum] ?: emptyList(),
                shapes = shapeOverlays[pageNum] ?: emptyList()
            )

            // Strokes
            plan.strokes.forEach { stroke ->
                val paint = Paint().apply {
                    color = stroke.colorArgb
                    strokeWidth = stroke.widthFraction * canvas.width
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                val path = Path()
                path.moveTo(stroke.points[0].first * canvas.width, stroke.points[0].second * canvas.height)
                for (i in 1 until stroke.points.size) {
                    path.lineTo(stroke.points[i].first * canvas.width, stroke.points[i].second * canvas.height)
                }
                canvas.drawPath(path, paint)
            }

            // Text
            plan.texts.forEach { overlay ->
                val overlayTextSize = overlay.sizeFraction * canvas.width
                val paint = Paint().apply {
                    color = overlay.colorArgb; textSize = overlayTextSize; isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT
                }
                canvas.drawText(overlay.text, overlay.x * canvas.width, overlay.y * canvas.height + overlayTextSize, paint)
            }

            // Images
            imageOverlays[pageNum]?.forEach { overlay ->
                val dest = android.graphics.RectF(
                    overlay.x * canvas.width, overlay.y * canvas.height,
                    (overlay.x + overlay.width) * canvas.width, (overlay.y + overlay.height) * canvas.height
                )
                canvas.drawBitmap(overlay.bitmap, null, dest, null)
            }

            // Shapes — the plan carries the selected colour, width and fill mode.
            plan.shapes.forEach { command ->
                val shape = command.shape
                val paint = Paint().apply {
                    color = shape.color
                    strokeWidth = shape.strokeWidth * canvas.width
                    style = if (shape.fill && shape.type != ShapeType.LINE) {
                        Paint.Style.FILL
                    } else {
                        Paint.Style.STROKE
                    }
                    isAntiAlias = true
                }
                val x1 = shape.x1 * canvas.width
                val y1 = shape.y1 * canvas.height
                val x2 = shape.x2 * canvas.width
                val y2 = shape.y2 * canvas.height
                when (shape.type) {
                    ShapeType.RECTANGLE -> {
                        canvas.drawRect(
                            minOf(x1, x2), minOf(y1, y2),
                            maxOf(x1, x2), maxOf(y1, y2), paint
                        )
                    }
                    ShapeType.CIRCLE -> {
                        val cx = (x1 + x2) / 2f
                        val cy = (y1 + y2) / 2f
                        val rx = kotlin.math.abs(x2 - x1) / 2f
                        val ry = kotlin.math.abs(y2 - y1) / 2f
                        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
                    }
                    ShapeType.LINE -> {
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
            }

            pdfDocument.finishPage(pdfPage)
        }

        renderer.close(); fd.close()
        FileOutputStream(outputPath).use { pdfDocument.writeTo(it) }
        pdfDocument.close()

        imageOverlays.values.flatten().forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }

        // Validate and publish to user-visible storage
        val saved = FileManager.finalizeOutput(context, output)
            ?: return ProcessingResult(false, message = "Output validation failed — the edited PDF could not be verified.")
        ProcessingResult(
            true,
            outputPath = saved.localPath,
            outputUri = saved.publicUri,
            fileName = saved.name,
            mimeType = saved.mimeType,
            outputSize = saved.size,
            pageCount = totalPages
        )
    } catch (e: Exception) {
        ProcessingResult(false, message = "Failed to save edited PDF: ${e.message}")
    }
}
