package com.pdforge.app.ui.screens

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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.pdf.PdfEngine
import com.pdforge.app.security.SignaturePageMode
import com.pdforge.app.security.signatureTargetPages
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.AppButton
import com.pdforge.app.ui.components.AppOutlinedButton
import com.pdforge.app.ui.components.ProcessingIndicator
import com.pdforge.app.ui.components.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class SignatureMode { NONE, DRAW, TYPE, IMAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignPdfScreen(
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
    // Where the signature is applied. This is an explicit user choice now: the
    // old screen always used its current page, which defaulted to the LAST page,
    // so every signature landed there with no way to pick another page.
    var signaturePageMode by remember { mutableStateOf(SignaturePageMode.THIS_PAGE) }
    var isProcessing by remember { mutableStateOf(false) }
    var doneResult by remember { mutableStateOf<ProcessingResult?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var signatureMode by remember { mutableStateOf(SignatureMode.NONE) }
    var signatureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var signatureText by remember { mutableStateOf("") }
    // Signature position/size are stored as fractions (0..1) of the page so the
    // preview and the final PDF use exactly the same coordinate system.
    var signatureXFrac by remember { mutableFloatStateOf(0.30f) }
    var signatureYFrac by remember { mutableFloatStateOf(0.82f) }
    var signatureWidthFrac by remember { mutableFloatStateOf(0.25f) }
    var pageBoxSize by remember { mutableStateOf(IntSize.Zero) }

    // Drawing state
    var currentStrokePoints by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    var savedStrokes by remember { mutableStateOf(listOf<List<Pair<Float, Float>>>()) }
    var strokeColor by remember { mutableIntStateOf(Color.BLACK) }

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                // Copy + page count run off the main thread for large PDFs.
                val tempFile = FileManager.createTempFile(context, "sign_input_${System.currentTimeMillis()}")
                val copied = withContext(Dispatchers.IO) {
                    FileManager.copyUriToFile(context, it, tempFile)
                }
                if (copied) {
                    filePath = tempFile.absolutePath
                    pageCount = withContext(Dispatchers.IO) { pdfEngine.getPageCount(tempFile.absolutePath) }
                    // Start on the first page so "this page" is predictable;
                    // the page target is always shown explicitly in the UI.
                    currentPage = 0
                } else {
                    Toast.makeText(context, "Could not read the selected PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Image import launcher
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    signatureBitmap = bitmap
                    signatureMode = SignatureMode.IMAGE
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Load page bitmap
    LaunchedEffect(currentPage, filePath) {
        filePath?.let {
            pageBitmap = withContext(Dispatchers.Default) {
                pdfEngine.renderPage(it, currentPage, 800, 1100)
            }
        }
    }

    if (isProcessing) {
        ProcessingIndicator(message = "Signing PDF…", modifier = modifier)
        return
    }

    doneResult?.let { r ->
        ResultScreen(
            result = r,
            toolName = "Sign PDF",
            onOpenInApp = onOpenInApp,
            onBack = { onResult(r) },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (signatureMode != SignatureMode.NONE) {
                        IconButton(onClick = {
                            // Clear signature
                            signatureBitmap = null
                            signatureText = ""
                            savedStrokes = emptyList()
                            signatureMode = SignatureMode.NONE
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear signature")
                        }
                    }
                    if (signatureMode != SignatureMode.NONE && (signatureBitmap != null || signatureText.isNotBlank() || savedStrokes.isNotEmpty())) {
                        IconButton(onClick = {
                            isProcessing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    applySignature(
                                        context, filePath ?: "",
                                        signatureBitmap, signatureText, savedStrokes,
                                        strokeColor,
                                        signatureTargetPages(signaturePageMode, currentPage, pageCount).toSet(),
                                        signatureXFrac, signatureYFrac, signatureWidthFrac,
                                        pdfEngine
                                    )
                                }
                                isProcessing = false
                                doneResult = result
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Apply signature")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (filePath == null) {
            Box(modifier = modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Select a PDF to sign", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    AppButton(
                        text = "Select PDF",
                        onClick = { fileLauncher.launch(arrayOf("application/pdf")) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Your signature is placed as a visual overlay (an annotation), not a cryptographic digital signature.",
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
                IconButton(onClick = { if (currentPage > 0) currentPage-- }, enabled = currentPage > 0) {
                    Icon(Icons.Default.ChevronLeft, "Previous")
                }
                Text("Page ${currentPage + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { if (currentPage < pageCount - 1) currentPage++ }, enabled = currentPage < pageCount - 1) {
                    Icon(Icons.Default.ChevronRight, "Next")
                }
            }

            // Where the signature goes: the page on screen, or every page.
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Apply signature to", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = signaturePageMode == SignaturePageMode.THIS_PAGE,
                            onClick = { signaturePageMode = SignaturePageMode.THIS_PAGE },
                            label = { Text("This page (${currentPage + 1})", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = signaturePageMode == SignaturePageMode.ALL_PAGES,
                            onClick = { signaturePageMode = SignaturePageMode.ALL_PAGES },
                            label = { Text("All pages", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // PDF page with signature overlay
            Box(
                modifier = Modifier
                    .weight(1f).fillMaxWidth().padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .background(ComposeColor.White)
                    .onSizeChanged { pageBoxSize = it }
            ) {
                if (pageBitmap != null) {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = "Page ${currentPage + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // Draw signature strokes if in DRAW mode. Stroke points are
                    // stored as fractions of the page so preview and output match.
                    if (signatureMode == SignatureMode.DRAW) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val size = pageBoxSize
                                            if (size != IntSize.Zero) {
                                                currentStrokePoints = listOf(
                                                    (offset.x / size.width).coerceIn(0f, 1f) to (offset.y / size.height).coerceIn(0f, 1f)
                                                )
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val size = pageBoxSize
                                            if (size != IntSize.Zero) {
                                                currentStrokePoints = currentStrokePoints + (
                                                    (change.position.x / size.width).coerceIn(0f, 1f) to (change.position.y / size.height).coerceIn(0f, 1f)
                                                )
                                            }
                                        },
                                        onDragEnd = {
                                            if (currentStrokePoints.size > 1) {
                                                savedStrokes = savedStrokes + listOf(currentStrokePoints)
                                            }
                                            currentStrokePoints = emptyList()
                                        }
                                    )
                                }
                        ) {
                            val strokeWidth = 3.dp.toPx()
                            savedStrokes.forEach { stroke ->
                                if (stroke.size >= 2) {
                                    val p = ComposePath().apply {
                                        moveTo(stroke[0].first * size.width, stroke[0].second * size.height)
                                        for (i in 1 until stroke.size) {
                                            lineTo(stroke[i].first * size.width, stroke[i].second * size.height)
                                        }
                                    }
                                    drawPath(p, ComposeColor(strokeColor), style = Stroke(width = strokeWidth))
                                }
                            }
                            if (currentStrokePoints.size >= 2) {
                                val p = ComposePath().apply {
                                    moveTo(currentStrokePoints[0].first * size.width, currentStrokePoints[0].second * size.height)
                                    for (i in 1 until currentStrokePoints.size) {
                                        lineTo(currentStrokePoints[i].first * size.width, currentStrokePoints[i].second * size.height)
                                    }
                                }
                                drawPath(p, ComposeColor(strokeColor), style = Stroke(width = strokeWidth))
                            }
                        }
                    }

                    // Show typed signature preview (drag to position)
                    if (signatureMode == SignatureMode.TYPE && signatureText.isNotBlank()) {
                        val dragToMove = Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val size = pageBoxSize
                                if (size != IntSize.Zero) {
                                    signatureXFrac = (signatureXFrac + dragAmount.x / size.width).coerceIn(0f, 0.9f)
                                    signatureYFrac = (signatureYFrac + dragAmount.y / size.height).coerceIn(0f, 0.95f)
                                }
                            }
                        }
                        Text(
                            text = signatureText,
                            modifier = Modifier
                                .offset { IntOffset((signatureXFrac * pageBoxSize.width).toInt(), (signatureYFrac * pageBoxSize.height).toInt()) }
                                .then(dragToMove)
                                .background(ComposeColor.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                .padding(8.dp)
                                .border(1.dp, ComposeColor.Blue, RoundedCornerShape(4.dp)),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Cursive,
                            color = ComposeColor(Color.BLUE)
                        )
                    }

                    // Show imported image signature preview (drag to position)
                    if (signatureMode == SignatureMode.IMAGE && signatureBitmap != null) {
                        val density = LocalDensity.current
                        val dragToMove = Modifier.pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val size = pageBoxSize
                                if (size != IntSize.Zero) {
                                    signatureXFrac = (signatureXFrac + dragAmount.x / size.width).coerceIn(0f, 0.9f)
                                    signatureYFrac = (signatureYFrac + dragAmount.y / size.height).coerceIn(0f, 0.95f)
                                }
                            }
                        }
                        val sigWidthPx = signatureWidthFrac * pageBoxSize.width
                        val sigHeightPx = sigWidthPx * signatureBitmap!!.height / signatureBitmap!!.width.toFloat()
                        Image(
                            bitmap = signatureBitmap!!.asImageBitmap(),
                            contentDescription = "Signature",
                            modifier = Modifier
                                .offset { IntOffset((signatureXFrac * pageBoxSize.width).toInt(), (signatureYFrac * pageBoxSize.height).toInt()) }
                                .then(dragToMove)
                                .size(
                                    width = with(density) { sigWidthPx.toDp() },
                                    height = with(density) { sigHeightPx.toDp() }
                                )
                                .border(1.dp, ComposeColor.Blue, RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            // Signature mode selection
            if (signatureMode == SignatureMode.NONE) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Choose signature type:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Shared action buttons: compact padding so the icon + label
                            // never get squeezed onto a second line on a narrow phone.
                            AppOutlinedButton(
                                text = "Draw",
                                icon = Icons.Default.Draw,
                                onClick = { signatureMode = SignatureMode.DRAW },
                                modifier = Modifier.weight(1f),
                                minWidth = 0.dp
                            )
                            AppOutlinedButton(
                                text = "Type",
                                icon = Icons.Default.TextFields,
                                onClick = { signatureMode = SignatureMode.TYPE },
                                modifier = Modifier.weight(1f),
                                minWidth = 0.dp
                            )
                            AppOutlinedButton(
                                text = "Image",
                                icon = Icons.Default.Image,
                                onClick = { imageLauncher.launch(arrayOf("image/jpeg", "image/png")) },
                                modifier = Modifier.weight(1f),
                                minWidth = 0.dp
                            )
                        }
                    }
                }
            }

            // Draw mode controls
            if (signatureMode == SignatureMode.DRAW) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Draw your signature above", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Black" to Color.BLACK, "Blue" to Color.BLUE, "Dark Gray" to Color.DKGRAY).forEach { (name, color) ->
                                FilterChip(
                                    selected = strokeColor == color,
                                    onClick = { strokeColor = color },
                                    label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            // Type mode controls
            if (signatureMode == SignatureMode.TYPE) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = signatureText,
                            onValueChange = { signatureText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Type your name") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Drag the previewed signature on the page to position it. It is applied to the pages chosen above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            // Apply button
            if (signatureMode != SignatureMode.NONE && (signatureBitmap != null || signatureText.isNotBlank() || savedStrokes.isNotEmpty())) {
                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                applySignature(
                                    context, filePath ?: "",
                                    signatureBitmap, signatureText, savedStrokes,
                                    strokeColor,
                                    signatureTargetPages(signaturePageMode, currentPage, pageCount).toSet(),
                                    signatureXFrac, signatureYFrac, signatureWidthFrac,
                                    pdfEngine
                                )
                            }
                            isProcessing = false
                            doneResult = result
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (signaturePageMode == SignaturePageMode.ALL_PAGES) {
                            "Apply to All ${pageCount} Pages"
                        } else {
                            "Apply to Page ${currentPage + 1}"
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun applySignature(
    context: android.content.Context,
    inputPath: String,
    signatureBitmap: Bitmap?,
    signatureText: String,
    savedStrokes: List<List<Pair<Float, Float>>>,
    strokeColor: Int,
    targetPages: Set<Int>,
    posXFrac: Float, posYFrac: Float, widthFrac: Float,
    pdfEngine: PdfEngine
): ProcessingResult {
    return try {
        val fd = ParcelFileDescriptor.open(File(inputPath), ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val totalPages = renderer.pageCount

        val output = FileManager.createTempFile(
            context,
            File(inputPath).nameWithoutExtension + "_signed"
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

            // Apply signature on the target page. Position/size/strokes are
            // normalized (0..1), so they map 1:1 from the preview to the output.
            if (pageNum in targetPages) {
                val sigX = posXFrac * canvas.width
                val sigY = posYFrac * canvas.height

                if (signatureBitmap != null && !signatureBitmap.isRecycled) {
                    // Draw image signature
                    val sigWidth = widthFrac * canvas.width
                    val sigHeight = sigWidth * signatureBitmap.height / signatureBitmap.width.toFloat()
                    val dest = android.graphics.RectF(sigX, sigY, sigX + sigWidth, sigY + sigHeight)
                    canvas.drawBitmap(signatureBitmap, null, dest, null)
                } else if (signatureText.isNotBlank()) {
                    // Draw typed signature
                    val sigTextSize = 0.045f * canvas.width
                    val paint = Paint().apply {
                        color = Color.BLUE
                        textSize = sigTextSize
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
                    }
                    canvas.drawText(signatureText, sigX, sigY + sigTextSize, paint)
                } else if (savedStrokes.isNotEmpty()) {
                    // Draw hand-drawn signature
                    val paint = Paint().apply {
                        color = strokeColor
                        strokeWidth = 0.005f * canvas.width
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                    for (stroke in savedStrokes) {
                        if (stroke.size >= 2) {
                            val path = Path()
                            path.moveTo(stroke[0].first * canvas.width, stroke[0].second * canvas.height)
                            for (i in 1 until stroke.size) {
                                path.lineTo(stroke[i].first * canvas.width, stroke[i].second * canvas.height)
                            }
                            canvas.drawPath(path, paint)
                        }
                    }
                }
            }

            pdfDocument.finishPage(pdfPage)
        }

        renderer.close(); fd.close()
        FileOutputStream(outputPath).use { pdfDocument.writeTo(it) }
        pdfDocument.close()

        // Recycle signature bitmap
        signatureBitmap?.let { if (!it.isRecycled) it.recycle() }

        // Validate and publish to user-visible storage
        val saved = FileManager.finalizeOutput(context, output)
            ?: return ProcessingResult(false, message = "Output validation failed — the signed PDF could not be verified.")
        val where = if (targetPages.size == totalPages) {
            "every page ($totalPages)"
        } else {
            "page${if (targetPages.size == 1) "" else "s"} ${targetPages.sorted().joinToString(", ") { (it + 1).toString() }}"
        }
        ProcessingResult(
            success = true,
            outputPath = saved.localPath,
            outputUri = saved.publicUri,
            fileName = saved.name,
            mimeType = saved.mimeType,
            outputSize = saved.size,
            pageCount = totalPages,
            message = "Visual signature applied to $where. Note: This is a visual overlay, not a cryptographic digital signature."
        )
    } catch (e: Exception) {
        ProcessingResult(false, message = "Failed to sign PDF: ${e.message}")
    }
}
