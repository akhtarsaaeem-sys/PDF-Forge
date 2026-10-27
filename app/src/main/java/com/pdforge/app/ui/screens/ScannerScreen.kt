package com.pdforge.app.ui.screens

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Image as ITextImage
import com.itextpdf.layout.properties.HorizontalAlignment
import com.pdforge.app.data.models.ProcessingResult
import androidx.compose.runtime.DisposableEffect
import com.pdforge.app.scanner.ScanImageOptimizer
import com.pdforge.app.scanner.ScanPage
import com.pdforge.app.scanner.ScanPageLayout
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
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onResult: (ProcessingResult) -> Unit,
    onOpenInApp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember { mutableStateOf(false) }

    // Scanned pages are stored as compressed JPEG bytes (see ScanImageOptimizer), so
    // a multi-page scan never holds more than one full document-resolution bitmap.
    var capturedPages by remember { mutableStateOf<List<ScanPage>>(emptyList()) }
    var isCapturing by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var reviewIndex by remember { mutableIntStateOf(-1) }
    var showPageManagement by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("Generating PDF…") }
    var doneResult by remember { mutableStateOf<ProcessingResult?>(null) }

    val imageCapture = remember { ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    }

    // Single shared executor for camera capture callbacks (avoids a thread leak per photo).
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { captureExecutor.shutdown() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission is required to scan documents", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (isProcessing) {
        ProcessingIndicator(message = processingMessage, modifier = modifier)
        return
    }

    doneResult?.let { r ->
        ResultScreen(
            result = r,
            toolName = "Scan to PDF",
            onOpenInApp = onOpenInApp,
            onBack = { onResult(r) },
            modifier = modifier
        )
        return
    }

    if (showReview && reviewIndex >= 0 && reviewIndex < capturedPages.size) {
        ReviewCapturedImage(
            page = capturedPages[reviewIndex],
            onConfirm = { page ->
                capturedPages = capturedPages.toMutableList().apply { set(reviewIndex, page) }
                showReview = false
                reviewIndex = -1
            },
            onRetake = {
                capturedPages = capturedPages.toMutableList().apply { removeAt(reviewIndex) }
                showReview = false
                reviewIndex = -1
            },
            onRotate = { page ->
                scope.launch {
                    val rotated = withContext(Dispatchers.IO) {
                        ScanImageOptimizer.rotatePage(page, 90)
                    }
                    if (rotated != null) {
                        capturedPages = capturedPages.toMutableList().apply { set(reviewIndex, rotated) }
                    }
                }
            },
            onBack = { showReview = false; reviewIndex = -1 }
        )
        return
    }

    if (capturedPages.isNotEmpty() && !showReview && showPageManagement) {
        ScannerPageManagement(
            pages = capturedPages,
            onRemovePage = { index ->
                capturedPages = capturedPages.toMutableList().apply { removeAt(index) }
            },
            onReorder = { from, to ->
                capturedPages = capturedPages.toMutableList().apply {
                    val item = removeAt(from)
                    add(to, item)
                }
            },
            onCreatePdf = {
                // Guard against a double tap re-entering before recomposition swaps
                // the screen for the processing indicator.
                if (isProcessing || capturedPages.isEmpty()) return@ScannerPageManagement
                isProcessing = true
                processingMessage = "Generating PDF…"
                scope.launch {
                    try {
                        // Timestamped default name the user can change from the result
                        // screen (the shared rename action) before or after saving.
                        val defaultName = "${FileManager.timestampedName("scan")}.pdf"
                        val output = FileManager.createTempFile(
                            context,
                            defaultName.substringBeforeLast('.')
                        )
                        // PDF generation is heavy — never run it on the main thread.
                        val result = withContext(Dispatchers.IO) {
                            generatePdfFromScanPages(context, capturedPages, output.absolutePath, defaultName)
                        }
                        isProcessing = false
                        doneResult = result
                    } catch (e: Exception) {
                        isProcessing = false
                        doneResult = ProcessingResult(false, message = "Failed to generate PDF: ${e.message}")
                    }
                }
            },
            onBack = {
                capturedPages = emptyList()
                showPageManagement = false
            }
        )
        return
    }

    // Camera screen
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan to PDF") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture
                                    )
                                } catch (e: Exception) {
                                    Log.e("Scanner", "Camera bind failed", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Page count indicator
                if (capturedPages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopEnd)
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${capturedPages.size} page(s)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Bottom controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { flashEnabled = !flashEnabled }) {
                        Icon(
                            if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle flash",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (!isCapturing) {
                                isCapturing = true
                                captureImage(context, imageCapture, captureExecutor) { page ->
                                    if (page != null) {
                                        // Each capture goes through review (rotate/keep/retake)
                                        // before being added to the document pages.
                                        val newList = capturedPages + page
                                        capturedPages = newList
                                        reviewIndex = newList.size - 1
                                        showReview = true
                                    }
                                    isCapturing = false
                                }
                            }
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Capture",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    if (capturedPages.isNotEmpty()) {
                        IconButton(onClick = { showPageManagement = true }) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Done — manage pages",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(72.dp))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera permission is required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Please grant camera access to scan documents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    AppButton(
                        text = "Grant Permission",
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewCapturedImage(
    page: ScanPage,
    onConfirm: (ScanPage) -> Unit,
    onRetake: () -> Unit,
    onRotate: (ScanPage) -> Unit,
    onBack: () -> Unit
) {
    // Decoded on demand from the stored bytes and keyed on the page, so rotating
    // shows the rotated result and the bitmap is dropped when the screen leaves.
    val bitmap by produceState<Bitmap?>(null, page) {
        value = withContext(Dispatchers.IO) {
            ScanImageOptimizer.decodeDownsampled(page.jpegBytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val preview = bitmap
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Captured page",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppOutlinedButton(
                    text = "Retake",
                    icon = Icons.Default.Refresh,
                    onClick = onRetake,
                    modifier = Modifier.weight(1f)
                )
                AppOutlinedButton(
                    text = "Rotate",
                    icon = Icons.Default.RotateRight,
                    onClick = { onRotate(page) },
                    enabled = bitmap != null,
                    modifier = Modifier.weight(1f)
                )
                AppButton(
                    text = "Keep",
                    icon = Icons.Default.Check,
                    onClick = { onConfirm(page) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerPageManagement(
    pages: List<ScanPage>,
    onRemovePage: (Int) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onCreatePdf: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${pages.size} page(s)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = onCreatePdf,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = AppButtonPadding
            ) {
                ButtonLabel(text = "Create PDF", icon = Icons.Default.PictureAsPdf)
            }
        }
    ) { padding ->
        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(pages) { index, page ->
                val thumbnail by produceState<Bitmap?>(null, page) {
                    value = withContext(Dispatchers.IO) {
                        ScanImageOptimizer.decodeDownsampled(
                            page.jpegBytes,
                            ScanImageOptimizer.PREVIEW_LONG_EDGE_PX
                        )
                    }
                }
                Card(
                    modifier = Modifier
                        .size(160.dp, 220.dp)
                        .clickable { /* Preview */ }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val bmp = thumbnail
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier
                                .padding(4.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        IconButton(
                            onClick = { onRemovePage(index) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun captureImage(
    context: Context,
    imageCapture: ImageCapture,
    executor: java.util.concurrent.Executor,
    onResult: (ScanPage?) -> Unit
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {

                // Optimize before the callback returns: decode downsampled, rotate to
                // upright, JPEG-compress, and release the large bitmap. Only the
                // compressed bytes survive, so memory stays flat across pages.
                val page = try {
                    imageProxyToScanPage(image)
                } finally {
                    image.close()
                }
                onResult(page)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("Scanner", "Capture failed", exception)
                onResult(null)
            }
        }
    )
}

/** Decodes the captured JPEG straight into an optimized [ScanPage]. */
private fun imageProxyToScanPage(imageProxy: ImageProxy): ScanPage? {
    return try {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        ScanImageOptimizer.optimizeCapture(bytes, imageProxy.imageInfo.rotationDegrees)
    } catch (e: Exception) {
        Log.e("Scanner", "Failed to optimize captured image", e)
        null
    }
}

/**
 * Builds the scanned PDF by embedding each page's already-compressed JPEG.
 *
 * iText embeds a JPEG with its DCTDecode filter directly, so nothing is rasterized
 * or re-encoded here: the file size is the sum of the optimized page images plus
 * a small PDF overhead. [displayName] is the default file name; the result screen
 * lets the user rename it through the shared rename action.
 */
private fun generatePdfFromScanPages(
    context: Context,
    pages: List<ScanPage>,
    outputPath: String,
    displayName: String
): ProcessingResult {
    return try {
        if (pages.isEmpty()) {
            return ProcessingResult(false, message = "There are no scanned pages to save.")
        }
        val pdfDoc = ITextPdfDocument(PdfWriter(outputPath))
        // Each PDF page uses the scanned image's own aspect ratio (long edge kept at
        // A4's length) so the photo fills the page with no accidental white borders,
        // while the image is fitted — never stretched or cropped.
        for (page in pages) {
            val (pageWidth, pageHeight) = ScanPageLayout.pageSize(page.width, page.height)
            val pdfPage = pdfDoc.addNewPage(com.itextpdf.kernel.geom.PageSize(pageWidth, pageHeight))
            val canvas = com.itextpdf.kernel.pdf.canvas.PdfCanvas(pdfPage)
            canvas.addImageFittedIntoRectangle(
                ImageDataFactory.create(page.jpegBytes),
                com.itextpdf.kernel.geom.Rectangle(0f, 0f, pageWidth, pageHeight),
                false
            )
        }
        pdfDoc.close()

        val saved = FileManager.finalizeOutput(context, File(outputPath), displayName)
            ?: return ProcessingResult(false, message = "Output validation failed — the scanned PDF could not be verified.")
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
        // Never leave a half-written temp PDF behind after a failure.
        try { File(outputPath).delete() } catch (_: Exception) {}
        return ProcessingResult(false, message = "Failed to generate PDF: ${e.message}")
    }
}
