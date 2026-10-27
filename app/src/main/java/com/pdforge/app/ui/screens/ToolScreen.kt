package com.pdforge.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pdforge.app.converter.DocumentConverter
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.data.models.ToolRegistry
import com.pdforge.app.data.models.ToolType
import com.pdforge.app.pdf.*
import com.pdforge.app.security.ComparisonResult
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScreen(
    toolId: String,
    onBack: () -> Unit,
    onResult: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tool = remember(toolId) { ToolRegistry.getToolById(toolId) }
    val context = LocalContext.current
    val pdfEngine = remember { PdfEngine(context) }
    val docConverter = remember { DocumentConverter(context) }
    val scope = rememberCoroutineScope()

    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("Processing…") }
    var processingResult by remember { mutableStateOf<ProcessingResult?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fileName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var tempFilePath by remember { mutableStateOf<String?>(null) }

    // The shared page selection is the single source of truth for every
    // page-based tool (Split / Remove / Extract / Rotate / Page Numbers).
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var rotateAngle by remember { mutableIntStateOf(90) }
    var compressionQuality by remember { mutableStateOf(CompressQuality.RECOMMENDED) }
    var watermarkText by remember { mutableStateOf("") }
    var signatureText by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    // Crop PDF: per-edge crop as a percentage of each page (0..100).
    var cropTop by remember { mutableStateOf("5") }
    var cropBottom by remember { mutableStateOf("5") }
    var cropLeft by remember { mutableStateOf("5") }
    var cropRight by remember { mutableStateOf("5") }
    // Crop PDF: "percent" keeps the existing percentage controls, "manual" adds
    // the interactive, visual crop rectangle.
    var cropMode by remember { mutableStateOf("percent") }
    var manualCrop by remember { mutableStateOf(com.pdforge.app.pdf.CropFractions()) }
    var manualCropPage by remember { mutableIntStateOf(0) }
    var manualCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var splitMode by remember { mutableStateOf("ranges") } // "ranges" or "every"
    var splitRanges by remember { mutableStateOf("") }

    // Page numbering options (Add Page Numbers)
    var pageNumberStart by remember { mutableStateOf("1") }
    var pageNumberPosition by remember { mutableStateOf(PageNumberPosition.BOTTOM_CENTER) }
    var pageNumberPositionExpanded by remember { mutableStateOf(false) }
    var pageNumberFrom by remember { mutableStateOf("") }
    var pageNumberTo by remember { mutableStateOf("") }
    val settingsRepo = remember { com.pdforge.app.settings.SettingsRepository(context.applicationContext) }
    var ocrLanguage by remember { mutableStateOf("eng") }
    LaunchedEffect(Unit) {
        settingsRepo.defaultOcrLanguage.collect { lang -> ocrLanguage = lang }
    }

    // Protect PDF permission states
    var allowPrinting by remember { mutableStateOf(true) }
    var allowCopying by remember { mutableStateOf(false) }
    var allowEditing by remember { mutableStateOf(false) }

    // Watermark options
    var watermarkOpacity by remember { mutableStateOf(0.3f) }
    var watermarkFontSize by remember { mutableStateOf(50f) }
    // Slant in degrees. Positive slants rise to the right (the conventional
    // watermark direction) and is the same direction in preview and output.
    var watermarkRotation by remember { mutableStateOf(45f) }
    var watermarkPosition by remember { mutableStateOf(WatermarkPosition.CENTER) }
    var watermarkPositionExpanded by remember { mutableStateOf(false) }
    var watermarkPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkPreviewSize by remember { mutableStateOf(0 to 0) }

    // Renders page 1 once for the live watermark preview (no PDF regeneration).
    LaunchedEffect(tempFilePath) {
        watermarkPreviewBitmap = null
        watermarkPreviewSize = 0 to 0
        val path = tempFilePath
        if (path != null) {
            val (size, bmp) = withContext(Dispatchers.IO) {
                val s = pdfEngine.getPageSize(path, 0)
                val b = pdfEngine.renderPage(path, 0, 900)
                s to b
            }
            watermarkPreviewSize = size
            watermarkPreviewBitmap = bmp
        }
    }

    // Renders the page shown in the manual crop editor. Only one page is held in
    // memory at a time; the renderer applies the page's own rotation so the crop
    // rectangle always corresponds to what the user sees.
    LaunchedEffect(cropMode, manualCropPage, tempFilePath) {
        if (cropMode == "manual" && tempFilePath != null) {
            manualCropBitmap = withContext(Dispatchers.IO) {
                pdfEngine.renderPage(tempFilePath!!, manualCropPage, 900)
            }
        }
    }

    /**
     * Consumes a single-file selection: records metadata and copies the input to
     * temp storage. The copy + page count run off the main thread so large files
     * don't freeze the picker flow.
     */
    fun selectSingleInput(uris: List<Uri>) {
        val uri = uris.firstOrNull() ?: return
        selectedUri = uri
        fileName = FileManager.getFileName(context, uri)
        pageCount = 0
        tempFilePath = null
        selectedPages = emptySet()
        val ext = FileManager.getExtension(fileName)
        scope.launch {
            val tempFile = FileManager.createTempFile(context, "input_${System.currentTimeMillis()}", ext)
            val copied = withContext(Dispatchers.IO) {
                FileManager.copyUriToFile(context, uri, tempFile)
            }
            if (copied) {
                tempFilePath = tempFile.absolutePath
                pageCount = withContext(Dispatchers.IO) { pdfEngine.getPageCount(uri) }
            } else {
                tempFilePath = null
                Toast.makeText(context, "Could not read the selected file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tool?.name ?: "Tool") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (isProcessing) {
            ProcessingIndicator(
                message = processingMessage,
                modifier = Modifier.padding(padding)
            )
        } else if (processingResult != null) {
            val result = processingResult!!
            ResultScreen(
                result = result,
                toolName = tool?.name ?: "",
                onOpenInApp = onOpenInApp,
                onBack = {
                    processingResult = null
                    selectedUri = null
                    selectedUris = emptyList()
                    tempFilePath = null
                    selectedPages = emptySet()
                    pageCount = 0
                },
                modifier = Modifier.padding(padding)
            )
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (tool != null) {
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // File selection based on tool type
                when (tool?.type) {
                    ToolType.SINGLE_FILE, ToolType.PAGE_SELECTION, ToolType.EDITING -> {
                        if (selectedUri == null) {
                            val mimeTypes = when {
                                toolId == "word_to_pdf" -> arrayOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                toolId == "ppt_to_pdf" -> arrayOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                                toolId == "excel_to_pdf" -> arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                toolId == "html_to_pdf" -> arrayOf("text/html")
                                toolId == "redact_pdf" -> arrayOf("application/pdf")
                                toolId == "compare_pdf" -> arrayOf("application/pdf")
                                toolId == "pdf_forms" -> arrayOf("application/pdf")
                                else -> arrayOf("application/pdf")
                            }
                            val text = when (toolId) {
                                "word_to_pdf" -> "Select Word Document"
                                "ppt_to_pdf" -> "Select PowerPoint"
                                "excel_to_pdf" -> "Select Spreadsheet"
                                "html_to_pdf" -> "Select HTML File"
                                else -> "Select PDF"
                            }
                            FilePickerButton(
                                text = text,
                                mimeTypes = mimeTypes,
                                onFilesSelected = { uris -> selectSingleInput(uris) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            SelectedFileItem(
                                name = fileName,
                                onRemove = {
                                    selectedUri = null
                                    tempFilePath = null
                                    pageCount = 0
                                    selectedPages = emptySet()
                                }
                            )
                        }
                    }
                    ToolType.MULTI_FILE -> {
                        if (toolId == "jpg_to_pdf") {
                            // Images to PDF — needs an image picker, not a PDF picker.
                            if (selectedUris.isEmpty()) {
                                FilePickerButton(
                                    text = "Select Images",
                                    mimeTypes = arrayOf("image/jpeg", "image/png"),
                                    multipleAllowed = true,
                                    onFilesSelected = { uris -> selectedUris = uris },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                selectedUris.forEachIndexed { index, uri ->
                                    val name = FileManager.getFileName(context, uri)
                                    SelectedFileItem(
                                        name = name,
                                        onRemove = { selectedUris = selectedUris.toMutableList().apply { removeAt(index) } }
                                    )
                                    if (index < selectedUris.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        } else if (toolId == "compare_pdf") {
                            // Compare needs exactly 2 files
                            when {
                                selectedUris.isEmpty() -> {
                                    Text(
                                        text = "Select the first PDF to compare",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    FilePickerButton(
                                        text = "Select First PDF",
                                        mimeTypes = arrayOf("application/pdf"),
                                        onFilesSelected = { uris ->
                                            if (uris.isNotEmpty()) selectedUris = listOf(uris.first())
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                selectedUris.size == 1 -> {
                                    Text(
                                        text = "Now select the second PDF to compare",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    FilePickerButton(
                                        text = "Select Second PDF",
                                        mimeTypes = arrayOf("application/pdf"),
                                        onFilesSelected = { uris ->
                                            if (uris.isNotEmpty()) selectedUris = selectedUris + uris.first()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                else -> {
                                    selectedUris.forEachIndexed { index, uri ->
                                        val name = FileManager.getFileName(context, uri)
                                        SelectedFileItem(
                                            name = name,
                                            onRemove = {
                                                selectedUris = selectedUris.toMutableList().apply { removeAt(index) }
                                            }
                                        )
                                        if (index < selectedUris.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        } else {
                            if (selectedUris.isEmpty()) {
                                FilePickerButton(
                                    text = "Select PDF Files",
                                    mimeTypes = arrayOf("application/pdf"),
                                    multipleAllowed = true,
                                    onFilesSelected = { uris -> selectedUris = uris },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = "Selected PDFs (merge order)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                selectedUris.forEachIndexed { index, uri ->
                                    val name = FileManager.getFileName(context, uri)
                                    SelectedFileItem(
                                        name = name,
                                        position = index + 1,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < selectedUris.lastIndex,
                                        onMoveUp = {
                                            if (index > 0) {
                                                selectedUris = selectedUris.toMutableList().apply {
                                                    add(index - 1, removeAt(index))
                                                }
                                            }
                                        },
                                        onMoveDown = {
                                            if (index < selectedUris.lastIndex) {
                                                selectedUris = selectedUris.toMutableList().apply {
                                                    add(index + 1, removeAt(index))
                                                }
                                            }
                                        },
                                        onRemove = {
                                            selectedUris = selectedUris.toMutableList().apply { removeAt(index) }
                                        }
                                    )
                                    if (index < selectedUris.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                    ToolType.CONVERSION -> {
                        if (toolId.contains("jpg") || toolId.contains("image")) {
                            if (selectedUris.isEmpty()) {
                                FilePickerButton(
                                    text = "Select Images",
                                    mimeTypes = arrayOf("image/jpeg", "image/png"),
                                    multipleAllowed = true,
                                    onFilesSelected = { uris -> selectedUris = uris },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                selectedUris.forEachIndexed { index, _ ->
                                    val name = FileManager.getFileName(context, selectedUris[index])
                                    SelectedFileItem(
                                        name = name,
                                        onRemove = { selectedUris = selectedUris.toMutableList().apply { removeAt(index) } }
                                    )
                                    if (index < selectedUris.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        } else {
                            if (selectedUri == null) {
                                val mimeTypes = when {
                                    toolId.contains("word") -> arrayOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                    toolId.contains("ppt") -> arrayOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
                                    toolId.contains("excel") -> arrayOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                    toolId.contains("html") -> arrayOf("text/html")
                                    else -> arrayOf("*/*")
                                }
                                FilePickerButton(
                                    text = "Select File",
                                    mimeTypes = mimeTypes,
                                    onFilesSelected = { uris -> selectSingleInput(uris) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                SelectedFileItem(
                                    name = fileName,
                                    onRemove = { selectedUri = null; tempFilePath = null }
                                )
                            }
                        }
                    }
                    ToolType.CAMERA -> {
                        Text(
                            text = "Camera scanning requires camera permission. Tap below to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Camera launch handled by the dedicated scanner screen
                    }
                    null -> { }
                }

                // Tool-specific configuration UI.
                //
                // The page count is NOT available for a password-protected PDF:
                // the Android renderer used to count pages cannot open one, so an
                // encrypted document reports 0 pages. Gating the configuration on
                // pageCount > 0 therefore hid the Unlock PDF password field
                // entirely — the user could never type the password, and Unlock
                // silently ran with an empty one. Password tools are allowed
                // through without a known page count.
                if (showsToolConfiguration(toolId, tempFilePath != null, pageCount)) {
                    when (toolId) {
                        "split_pdf" -> {
                            Text("Split Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = splitMode == "ranges", onClick = { splitMode = "ranges" }, label = { Text("By Ranges") }, modifier = Modifier.weight(1f))
                                FilterChip(selected = splitMode == "every", onClick = { splitMode = "every" }, label = { Text("Every Page") }, modifier = Modifier.weight(1f))
                            }
                            if (splitMode == "ranges") {
                                OutlinedTextField(
                                    value = splitRanges,
                                    onValueChange = { splitRanges = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Page ranges") },
                                    placeholder = { Text("e.g. 1-3, 4-6, 7-8") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    isError = splitRanges.isNotBlank() && parseSplitRanges(splitRanges, pageCount).isEmpty()
                                )
                                val splitGroups = parseSplitRanges(splitRanges, pageCount)
                                Text(
                                    text = if (splitGroups.isEmpty()) {
                                        "Enter one or more ranges separated by commas. Each range becomes its own PDF. For an 8-page PDF: 1-3, 4-6, 7-8."
                                    } else {
                                        "Will create ${splitGroups.size} PDF${if (splitGroups.size == 1) "" else "s"}: " +
                                            splitGroups.joinToString(", ") {
                                                if (it.first == it.last) "page ${it.first}" else "pages ${it.first}-${it.last}"
                                            }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (splitGroups.isEmpty() && splitRanges.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "Each of the $pageCount pages becomes its own PDF.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        "rotate_pdf" -> {
                            Text("Rotation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(90 to "90°", 180 to "180°", 270 to "270°").forEach { (degrees, label) ->
                                    FilterChip(
                                        selected = rotateAngle == degrees,
                                        onClick = { rotateAngle = degrees },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        "remove_pages" -> {
                            Text("Tap the pages to remove", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            if (selectedPages.isNotEmpty()) {
                                Text(
                                    text = "Keeps ${pageCount - selectedPages.size} of $pageCount pages.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        "extract_pages" -> {
                            Text("Tap the pages to extract", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        }

                        "page_numbers" -> {
                            Text("Start Number", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            OutlinedTextField(
                                value = pageNumberStart,
                                onValueChange = { pageNumberStart = it.filter { c -> c.isDigit() }.take(6) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("First page number") },
                                placeholder = { Text("1") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Text("Position", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            ExposedDropdownMenuBox(
                                expanded = pageNumberPositionExpanded,
                                onExpandedChange = { pageNumberPositionExpanded = !pageNumberPositionExpanded }
                            ) {
                                OutlinedTextField(
                                    value = pageNumberPosition.label,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pageNumberPositionExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    label = { Text("Placement") },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = pageNumberPositionExpanded,
                                    onDismissRequest = { pageNumberPositionExpanded = false }
                                ) {
                                    PageNumberPosition.entries.forEach { pos ->
                                        DropdownMenuItem(
                                            text = { Text(pos.label) },
                                            onClick = {
                                                pageNumberPosition = pos
                                                pageNumberPositionExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            // Which pages get a number: pick them visually (or type a
                            // range in the panel). Numbers run from the first selected
                            // page through the last, so a selection spans a continuous
                            // range. Nothing selected = every page is numbered.
                            Text("Pages to number", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text(
                                "Selection must be a continuous range; numbering runs from the first to the last selected page. Leave empty to number all $pageCount pages.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        "compress_pdf" -> {
                            Text("Compression Quality", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = compressionQuality == CompressQuality.HIGH_QUALITY, onClick = { compressionQuality = CompressQuality.HIGH_QUALITY }, label = { Text("High Quality") }, modifier = Modifier.weight(1f))
                                FilterChip(selected = compressionQuality == CompressQuality.RECOMMENDED, onClick = { compressionQuality = CompressQuality.RECOMMENDED }, label = { Text("Recommended") }, modifier = Modifier.weight(1f))
                                FilterChip(selected = compressionQuality == CompressQuality.SMALLER_FILE, onClick = { compressionQuality = CompressQuality.SMALLER_FILE }, label = { Text("Smaller") }, modifier = Modifier.weight(1f))
                            }
                        }

                        "watermark" -> {
                            // Live preview — updates as settings change without generating a new PDF.
                            Text("Preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            WatermarkPreview(
                                pageBitmap = watermarkPreviewBitmap,
                                pageWidthPt = watermarkPreviewSize.first,
                                pageHeightPt = watermarkPreviewSize.second,
                                watermarkText = watermarkText,
                                opacity = watermarkOpacity,
                                fontSizePt = watermarkFontSize,
                                rotation = watermarkRotation,
                                position = watermarkPosition,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = watermarkText,
                                onValueChange = { watermarkText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Watermark Text") },
                                placeholder = { Text("Enter watermark text") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenuBox(
                                expanded = watermarkPositionExpanded,
                                onExpandedChange = { watermarkPositionExpanded = !watermarkPositionExpanded }
                            ) {
                                OutlinedTextField(
                                    value = watermarkPosition.label,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = watermarkPositionExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    label = { Text("Position") },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = watermarkPositionExpanded,
                                    onDismissRequest = { watermarkPositionExpanded = false }
                                ) {
                                    WatermarkPosition.entries.forEach { pos ->
                                        DropdownMenuItem(
                                            text = { Text(pos.label) },
                                            onClick = {
                                                watermarkPosition = pos
                                                watermarkPositionExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text("Opacity: ${(watermarkOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(value = watermarkOpacity, onValueChange = { watermarkOpacity = it }, valueRange = 0.1f..0.8f)
                            Text("Font Size: ${watermarkFontSize.toInt()}", style = MaterialTheme.typography.bodySmall)
                            Slider(value = watermarkFontSize, onValueChange = { watermarkFontSize = it }, valueRange = 20f..100f)
                            Text(
                                "Slant: ${watermarkRotation.toInt()}° " +
                                    if (watermarkRotation > 0f) "(rising to the right)" else "(falling to the right)",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = watermarkRotation,
                                onValueChange = { watermarkRotation = it },
                                valueRange = -90f..90f
                            )
                            Text(
                                "Long text is automatically reduced to fit inside the page instead of " +
                                    "running off the edge. The preview shows exactly what will be saved.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        "sign_pdf" -> {
                            OutlinedTextField(value = signatureText, onValueChange = { signatureText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Signature Text") }, placeholder = { Text("Enter your name or signature") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                        }

                        "protect_pdf" -> {
                            OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, placeholder = { Text("Enter password to open") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            OutlinedTextField(value = ownerPassword, onValueChange = { ownerPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Owner Password (optional)") }, placeholder = { Text("For permission changes") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Permissions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = allowPrinting, onCheckedChange = { allowPrinting = it }); Text("Allow Printing") }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = allowCopying, onCheckedChange = { allowCopying = it }); Text("Allow Copying") }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = allowEditing, onCheckedChange = { allowEditing = it }); Text("Allow Editing") }
                        }

                        "unlock_pdf" -> {
                            OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, placeholder = { Text("Enter document password") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            Text(
                                "Enter the password this PDF asks for when it is opened. " +
                                    "Password-protected PDFs report no page count until they are unlocked — " +
                                    "that is expected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        "crop_pdf" -> {
                            Text("Crop Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = cropMode == "percent",
                                    onClick = { cropMode = "percent" },
                                    label = { Text("Percentages") },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = cropMode == "manual",
                                    onClick = { cropMode = "manual" },
                                    label = { Text("Manual") },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (cropMode == "percent") {
                                // The original percentage workflow, unchanged.
                                Text("Crop each edge (%)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = cropTop, onValueChange = { cropTop = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                                        modifier = Modifier.weight(1f), label = { Text("Top") }, singleLine = true, shape = RoundedCornerShape(12.dp)
                                    )
                                    OutlinedTextField(
                                        value = cropBottom, onValueChange = { cropBottom = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                                        modifier = Modifier.weight(1f), label = { Text("Bottom") }, singleLine = true, shape = RoundedCornerShape(12.dp)
                                    )
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = cropLeft, onValueChange = { cropLeft = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                                        modifier = Modifier.weight(1f), label = { Text("Left") }, singleLine = true, shape = RoundedCornerShape(12.dp)
                                    )
                                    OutlinedTextField(
                                        value = cropRight, onValueChange = { cropRight = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                                        modifier = Modifier.weight(1f), label = { Text("Right") }, singleLine = true, shape = RoundedCornerShape(12.dp)
                                    )
                                }
                                Text(
                                    "Percent of each page. Top+Bottom and Left+Right must each stay below 100%.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                // Manual, visual cropping: preview the page, drag the crop
                                // rectangle, keep it inside the page.
                                if (pageCount > 0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = { if (manualCropPage > 0) manualCropPage-- }, enabled = manualCropPage > 0) {
                                            Icon(Icons.Default.ChevronLeft, "Previous page")
                                        }
                                        Text("Page ${manualCropPage + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                                        IconButton(onClick = { if (manualCropPage < pageCount - 1) manualCropPage++ }, enabled = manualCropPage < pageCount - 1) {
                                            Icon(Icons.Default.ChevronRight, "Next page")
                                        }
                                    }
                                }
                                ManualCropEditor(
                                    pageBitmap = manualCropBitmap,
                                    crop = manualCrop,
                                    onCropChange = { manualCrop = it },
                                    modifier = Modifier.fillMaxWidth().height(300.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppOutlinedButton(
                                        text = "Reset",
                                        icon = Icons.Default.Refresh,
                                        onClick = { manualCrop = CropFractions() },
                                        modifier = Modifier.weight(1f),
                                        minWidth = 0.dp
                                    )
                                    Text(
                                        "Keeps ${(manualCrop.keptWidth * 100).roundToInt()}% × " +
                                            "${(manualCrop.keptHeight * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    "Drag inside the rectangle to move it, or a corner handle to resize it. " +
                                        "The same crop is applied to the selected pages, or to every page when none are selected. " +
                                        "It is converted to each page's own size and rotation, so one crop works across mixed documents.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        "ocr_pdf" -> {
                            Text("OCR Language", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            val ocrEngine = remember { com.pdforge.app.ocr.OcrEngine(context) }
                            // Only languages actually bundled in assets/tessdata (or already
                            // extracted) are offered — never advertise an absent language.
                            val languages = remember { ocrEngine.getAvailableLanguages() }
                            var expanded by remember { mutableStateOf(false) }
                            if (languages.isEmpty()) {
                                Text(
                                    "No OCR language data is bundled. Add traineddata files to assets/tessdata/ and rebuild the app.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text(
                                    "Available: ${languages.joinToString { it.second }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                                    OutlinedTextField(
                                        value = languages.find { it.first == ocrLanguage }?.second ?: languages.first().second,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        languages.forEach { (code, name) ->
                                            DropdownMenuItem(text = { Text(name) }, onClick = { ocrLanguage = code; expanded = false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Shared visual page selection for every page-based tool. It sits
                // above the Process/Save button so the user selects pages first and
                // then starts the operation.
                if (tempFilePath != null && pageCount > 0 && toolId in VISUAL_PAGE_SELECTION_TOOLS) {
                    if (toolId == "page_numbers") {
                        Text(
                            text = if (pageNumberFrom.isNotBlank() && pageNumberTo.isNotBlank()) {
                                "Numbering pages $pageNumberFrom-$pageNumberTo"
                            } else {
                                "Numbering all pages"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    PageSelectionPanel(
                        filePath = tempFilePath ?: "",
                        pageCount = pageCount,
                        selectedPages = selectedPages,
                        onSelectionChange = { selection ->
                            selectedPages = selection
                            // Add Page Numbers works on a continuous range, so the
                            // first/last selected page define the range it numbers.
                            pageNumberFrom = selection.minOrNull()?.toString() ?: ""
                            pageNumberTo = selection.maxOrNull()?.toString() ?: ""
                        },
                        selectionLabel = { count ->
                            when (toolId) {
                                "remove_pages" ->
                                    if (count == 1) "1 page selected for removal" else "$count pages selected for removal"
                                "crop_pdf" ->
                                    if (count == 0) "Cropping every page"
                                    else if (count == 1) "1 page selected for cropping"
                                    else "$count pages selected for cropping"
                                else ->
                                    if (count == 1) "1 page selected" else "$count pages selected"
                            }
                        },
                        emptySelectionHint = when (toolId) {
                            "remove_pages" -> "Select at least one page to remove."
                            "extract_pages" -> "Select at least one page to extract."
                            "rotate_pdf" -> "Nothing selected — every page will be rotated."
                            else -> null
                        }
                    )
                }

                // Process button
                if ((selectedUri != null || selectedUris.isNotEmpty()) && !isProcessing) {
                    Button(
                        onClick = {
                            // Set the busy flag synchronously and guard against
                            // double-firing before recomposition disables the button.
                            if (isProcessing) return@Button
                            isProcessing = true
                            processingMessage = "Processing…"
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        processTool(
                                            toolId = toolId, context = context, pdfEngine = pdfEngine,
                                            docConverter = docConverter, selectedUri = selectedUri,
                                            selectedUris = selectedUris, tempFilePath = tempFilePath,
                                            fileName = fileName, compressionQuality = compressionQuality,
                                            watermarkText = watermarkText, signatureText = signatureText,
                                            password = password, ownerPassword = ownerPassword,
                                            selectedPages = selectedPages,
                                            cropTop = cropTop, cropBottom = cropBottom,
                                            cropLeft = cropLeft, cropRight = cropRight,
                                            splitMode = splitMode, splitRanges = splitRanges,
                                            ocrLanguage = ocrLanguage, allowPrinting = allowPrinting,
                                            allowCopying = allowCopying, allowEditing = allowEditing,
                                            rotateAngle = rotateAngle,
                                            watermarkOpacity = watermarkOpacity,
                                            watermarkFontSize = watermarkFontSize,
                                            watermarkPosition = watermarkPosition,
                                            watermarkRotation = watermarkRotation,
                                            cropMode = cropMode,
                                            manualCrop = manualCrop,
                                            pageNumberStart = pageNumberStart,
                                            pageNumberPosition = pageNumberPosition,
                                            pageNumberFrom = pageNumberFrom,
                                            pageNumberTo = pageNumberTo,
                                            onProgress = { processingMessage = it }
                                        )
                                    }
                                    processingResult = result
                                } catch (e: Throwable) {
                                    // Never let a converter failure crash the app. A
                                    // failed PDF→PowerPoint conversion, for example,
                                    // may surface as an Error (OOM / missing AWT),
                                    // which "catch (Exception)" would not catch.
                                    processingResult = ProcessingResult(
                                        success = false,
                                        message = "An unexpected error occurred: ${e.message ?: e.javaClass.simpleName}"
                                    )
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = AppButtonPadding
                    ) {
                        Text(
                            text = "Process",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                if (pageCount > 0) {
                    Text(text = "$pageCount pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Tools whose UI is driven by the shared page selector: they always need a
 * readable input copy and a known page count.
 */
private val PAGE_BASED_TOOLS = setOf(
    "split_pdf", "remove_pages", "extract_pages", "rotate_pdf", "page_numbers"
)

/**
 * The subset of page-based tools driven by the shared visual page selector.
 * Split PDF is excluded: it divides the document by explicit page RANGES (or one
 * file per page), which is a different workflow from extracting selected pages.
 */
private val VISUAL_PAGE_SELECTION_TOOLS = setOf(
    "remove_pages", "extract_pages", "rotate_pdf", "page_numbers", "crop_pdf"
)

/**
 * Tools whose configuration must be reachable without a known page count.
 *
 * A password-protected PDF cannot be opened by the page-count renderer, so it
 * reports 0 pages; the password field still has to be shown for these tools.
 */
private val PASSWORD_ONLY_TOOLS = setOf("unlock_pdf", "protect_pdf")

/**
 * Whether a tool's configuration controls must be shown.
 *
 * A tool needs a readable input copy, and normally also a known page count — but
 * the password tools must be usable when the page count is UNKNOWN, because a
 * password-protected PDF reports 0 pages (the renderer used to count pages has
 * no way to supply a password). Hiding the controls in that case removed the
 * Unlock PDF password field completely.
 */
internal fun showsToolConfiguration(toolId: String, hasInputCopy: Boolean, pageCount: Int): Boolean =
    hasInputCopy && (pageCount > 0 || toolId in PASSWORD_ONLY_TOOLS)

/**
 * Central finalization step: every tool writes its raw output to a temp file,
 * which is validated, copied into the internal output dir, and published to the
 * user-visible Documents/PDF Forge location.
 */
private fun finalizeResult(
    context: android.content.Context,
    result: ProcessingResult,
    displayName: String? = null,
    encryptionPassword: String? = null
): ProcessingResult {
    if (!result.success || result.outputPath == null) return result
    return try {
        val outFile = File(result.outputPath)
        if (outFile.isDirectory) {
            val outputs = FileManager.finalizeOutputs(context, outFile, displayName)
            if (outputs.isEmpty()) {
                return ProcessingResult(false, message = "Output validation failed — no valid files were produced.")
            }
            val first = outputs.first()
            result.copy(
                outputPath = first.localPath,
                outputUri = first.publicUri,
                fileName = outputs.joinToString(", ") { it.name },
                mimeType = first.mimeType,
                outputSize = outputs.sumOf { it.size },
                fileCount = outputs.size,
                outputPaths = outputs.map { it.localPath }
            )
        } else {
            // Use the type-aware finalizer so a DOCX/XLSX/PPTX failure names the
            // real output type instead of always blaming a PDF.
            val finalized = FileManager.finalizeOutputResult(
                context, outFile, displayName,
                mimeType = result.mimeType,
                encryptionPassword = encryptionPassword
            )
            val saved = finalized.output ?: return ProcessingResult(
                success = false,
                message = finalized.error ?: "Output validation failed — the generated file could not be verified."
            )
            result.copy(
                outputPath = saved.localPath,
                outputUri = saved.publicUri,
                fileName = saved.name,
                mimeType = saved.mimeType,
                outputSize = saved.size,
                outputPaths = listOf(saved.localPath)
            )
        }
    } catch (e: Throwable) {
        // Catch Throwable (not just Exception) so a converter failure that surfaces
        // as an Error — e.g. OutOfMemoryError or a missing java.awt LinkageError
        // from a document library on Android — is reported to the user as a
        // controlled failure instead of crashing the app.
        ProcessingResult(false, message = "Failed to save output: ${e.message ?: e.javaClass.simpleName}")
    }
}

// Open With / Share are implemented once in ui.components.OutputActions and used
// by the shared ResultScreen, so every tool and the result screen agree on the
// current (post-rename) output reference.

private suspend fun processTool(
    toolId: String,
    context: android.content.Context,
    pdfEngine: PdfEngine,
    docConverter: DocumentConverter,
    selectedUri: Uri?,
    selectedUris: List<Uri>,
    tempFilePath: String?,
    fileName: String,
    compressionQuality: CompressQuality,
    watermarkText: String,
    signatureText: String,
    password: String,
    ownerPassword: String,
    selectedPages: Set<Int>,
    cropTop: String,
    cropBottom: String,
    cropLeft: String,
    cropRight: String,
    splitMode: String,
    splitRanges: String = "",
    ocrLanguage: String,
    allowPrinting: Boolean,
    allowCopying: Boolean,
    allowEditing: Boolean,
    rotateAngle: Int = 90,
    watermarkOpacity: Float,
    watermarkFontSize: Float,
    watermarkPosition: WatermarkPosition = WatermarkPosition.CENTER,
    watermarkRotation: Float = 45f,
    cropMode: String = "percent",
    manualCrop: CropFractions = CropFractions(),
    pageNumberStart: String = "1",
    pageNumberPosition: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
    pageNumberFrom: String = "",
    pageNumberTo: String = "",
    onProgress: (String) -> Unit = {}
): ProcessingResult {
    return withContext(Dispatchers.IO) {
        try {
            // Safety net for the page-based tools: if the picked file could not be
            // copied/read (tempFilePath == null, pageCount == 0), fail with a clear
            // message instead of feeding an empty path to a PDF reader.
            if (toolId in PAGE_BASED_TOOLS && tempFilePath == null) {
                return@withContext ProcessingResult(
                    success = false,
                    message = "Could not read the selected PDF. Please select it again."
                )
            }
            val rawResult = when (toolId) {
                // ── PAGE MANAGEMENT ──
                "merge_pdf" -> {
                    val paths = mutableListOf<String>()
                    for (uri in selectedUris) {
                        val tempFile = FileManager.createTempFile(context, "merge_input_${System.currentTimeMillis()}")
                        FileManager.copyUriToFile(context, uri, tempFile)
                        paths.add(tempFile.absolutePath)
                    }
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "merged"))
                    pdfEngine.mergePdfs(paths, output.absolutePath)
                }

                "split_pdf" -> {
                    val outputDir = File(FileManager.getTempDir(context), "split_${System.currentTimeMillis()}")
                    outputDir.mkdirs()
                    val totalPages = tempFilePath?.let { pdfEngine.getPageCount(it) } ?: 0
                    if (totalPages <= 0) {
                        return@withContext ProcessingResult(
                            success = false,
                            message = "Could not read the selected PDF. Please select it again."
                        )
                    }
                    // Split semantics: "By Ranges" divides the document into one PDF per
                    // explicit range; "Every Page" produces one PDF per page.
                    val ranges: List<Pair<Int, Int>> = if (splitMode == "every") {
                        (1..totalPages).map { it to it }
                    } else {
                        val parsed = parseSplitRanges(splitRanges, totalPages)
                        if (parsed.isEmpty()) {
                            return@withContext ProcessingResult(
                                success = false,
                                message = "Enter at least one valid page range, for example 1-3, 4-6, 7-8."
                            )
                        }
                        parsed.map { it.first to it.last }
                    }
                    pdfEngine.splitPdf(tempFilePath ?: "", outputDir.absolutePath, ranges)
                }

                "remove_pages" -> {
                    if (selectedPages.isEmpty()) {
                        return@withContext ProcessingResult(
                            success = false,
                            message = "Select at least one page to remove."
                        )
                    }
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "cleaned"))
                    pdfEngine.removePages(tempFilePath ?: "", output.absolutePath, selectedPages.sorted())
                }

                "extract_pages" -> {
                    if (selectedPages.isEmpty()) {
                        return@withContext ProcessingResult(
                            success = false,
                            message = "Select at least one page to extract."
                        )
                    }
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "extracted"))
                    // Output keeps the selected pages in page order.
                    pdfEngine.extractPages(tempFilePath ?: "", output.absolutePath, selectedPages.sorted())
                }

                "organize_pdf" -> {
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "organized"))
                    val total = tempFilePath?.let { pdfEngine.getPageCount(it) } ?: 1
                    val pages = (1..total).map { com.pdforge.app.data.models.PdfPage(number = it) }
                    pdfEngine.organizePdf(tempFilePath ?: "", output.absolutePath, pages)
                }

                "scan_to_pdf" -> {
                    ProcessingResult(
                        success = false,
                        message = "Use the dedicated scanner screen accessible from the Home or Category screen."
                    )
                }

                // ── OPTIMIZATION ──
                "compress_pdf" -> {
                    onProgress("Compressing PDF…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "compressed"))
                    pdfEngine.compressPdf(tempFilePath ?: "", output.absolutePath, compressionQuality)
                }

                "repair_pdf" -> {
                    onProgress("Attempting repair…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "repaired"))
                    pdfEngine.repairPdf(tempFilePath ?: "", output.absolutePath)
                }

                "ocr_pdf" -> {
                    onProgress("Initializing OCR engine…")
                    val ocrEngine = com.pdforge.app.ocr.OcrEngine(context)
                    val initialized = ocrEngine.initialize(ocrLanguage)
                    if (!initialized) {
                        ocrEngine.destroy()
                        return@withContext ProcessingResult(
                            success = false,
                            message = "OCR language data for '$ocrLanguage' is not available. Place the tessdata file in assets/tessdata/ and rebuild the app."
                        )
                    }
                    onProgress("Running OCR…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "ocr"))
                    val result = ocrEngine.createSearchablePdf(
                        inputPdfPath = tempFilePath ?: "",
                        outputPath = output.absolutePath,
                        language = ocrLanguage,
                        onProgress = { msg -> onProgress(msg) }
                    )
                    ocrEngine.destroy()
                    result
                }

                // ── CONVERT TO PDF ──
                "jpg_to_pdf" -> {
                    val imagePaths = mutableListOf<String>()
                    for (uri in selectedUris) {
                        val name = FileManager.getFileName(context, uri)
                        val tempFile = FileManager.createTempFile(context, "img_${System.currentTimeMillis()}", FileManager.getExtension(name))
                        FileManager.copyUriToFile(context, uri, tempFile)
                        imagePaths.add(tempFile.absolutePath)
                    }
                    // JPG → PDF takes images (not a single named input), so base the
                    // output name on the first image rather than on the empty
                    // `fileName`, which used to produce "_converted.pdf".
                    val firstImageName = selectedUris.firstOrNull()
                        ?.let { FileManager.getFileName(context, it) }
                        ?: "images"
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(firstImageName, "converted"))
                    pdfEngine.createPdfFromImages(imagePaths, output.absolutePath)
                }

                "word_to_pdf" -> {
                    onProgress("Converting Word document…")
                    val inputPath = tempFilePath ?: ""
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "converted"))
                    docConverter.wordToPdf(inputPath, output.absolutePath)
                }

                "ppt_to_pdf" -> {
                    onProgress("Converting presentation…")
                    val inputPath = tempFilePath ?: ""
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "converted"))
                    docConverter.pptToPdf(inputPath, output.absolutePath)
                }

                "excel_to_pdf" -> {
                    onProgress("Converting spreadsheet…")
                    val inputPath = tempFilePath ?: ""
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "converted"))
                    docConverter.excelToPdf(inputPath, output.absolutePath)
                }

                "html_to_pdf" -> {
                    onProgress("Converting HTML…")
                    val inputPath = tempFilePath ?: ""
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "converted"))
                    docConverter.htmlToPdf(inputPath, output.absolutePath)
                }

                // ── CONVERT FROM PDF ──
                "pdf_to_jpg" -> {
                    onProgress("Converting to images…")
                    val outputDir = File(FileManager.getTempDir(context), "pdf_to_jpg_${System.currentTimeMillis()}")
                    outputDir.mkdirs()
                    pdfEngine.pdfToImages(tempFilePath ?: "", outputDir.absolutePath)
                }

                "pdf_to_word" -> {
                    onProgress("Converting to Word…")
                    val output = FileManager.createTempFile(context, convertedBaseName(fileName, "converted"), ".docx")
                    pdfEngine.pdfToWord(tempFilePath ?: "", output.absolutePath)
                }

                "pdf_to_ppt" -> {
                    onProgress("Converting to PowerPoint…")
                    val output = FileManager.createTempFile(context, convertedBaseName(fileName, "converted"), ".pptx")
                    pdfEngine.pdfToPpt(tempFilePath ?: "", output.absolutePath)
                }

                "pdf_to_excel" -> {
                    onProgress("Converting to Excel…")
                    val output = FileManager.createTempFile(context, convertedBaseName(fileName, "converted"), ".xlsx")
                    pdfEngine.pdfToExcel(tempFilePath ?: "", output.absolutePath)
                }

                "pdf_to_pdfa" -> {
                    onProgress("Converting to PDF/A…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "pdfa"))
                    pdfEngine.pdfToPdfA(tempFilePath ?: "", output.absolutePath)
                }

                // ── EDIT & MODIFY ──
                "edit_pdf" -> {
                    ProcessingResult(
                        success = false,
                        message = "Use the dedicated editor accessible from the Home or Category screen."
                    )
                }

                "pdf_forms" -> {
                    ProcessingResult(
                        success = false,
                        message = "Use the dedicated form filler accessible from the Home or Category screen."
                    )
                }

                "rotate_pdf" -> {
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "rotated"))
                    val total = tempFilePath?.let { pdfEngine.getPageCount(it) } ?: 1
                    val pagesToRotate = if (selectedPages.isNotEmpty()) {
                        selectedPages.sorted()
                    } else {
                        (1..total).toList()
                    }
                    pdfEngine.rotatePages(tempFilePath ?: "", output.absolutePath, pagesToRotate, rotateAngle)
                }

                "page_numbers" -> {
                    onProgress("Adding page numbers…")
                    val total = tempFilePath?.let { pdfEngine.getPageCount(it) } ?: 0
                    val from = pageNumberFrom.toIntOrNull()
                    val to = pageNumberTo.toIntOrNull()
                    if (from != null && to != null && from > to) {
                        return@withContext ProcessingResult(
                            success = false,
                            message = "Invalid page range: 'From' must be less than or equal to 'To'."
                        )
                    }
                    val pagesToNumber = resolvePageNumberRange(from, to, total)
                    val start = pageNumberStart.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "numbered"))
                    pdfEngine.addPageNumbers(
                        tempFilePath ?: "", output.absolutePath,
                        position = pageNumberPosition,
                        startNumber = start,
                        pagesToNumber = pagesToNumber
                    )
                }

                "watermark" -> {
                    onProgress("Adding watermark…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "watermarked"))
                    pdfEngine.addTextWatermark(
                        tempFilePath ?: "", output.absolutePath,
                        watermarkText.ifBlank { "CONFIDENTIAL" },
                        opacity = watermarkOpacity, fontSize = watermarkFontSize,
                        rotation = watermarkRotation,
                        position = watermarkPosition
                    )
                }

                "crop_pdf" -> {
                    // Which pages the crop applies to: the shared selection, or every
                    // page when nothing is selected.
                    val cropPages = selectedPages.sorted().takeIf { it.isNotEmpty() }
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "cropped"))
                    if (cropMode == "manual") {
                        if (!manualCrop.isValid) {
                            return@withContext ProcessingResult(
                                success = false,
                                message = "The crop area is empty. Leave part of the page visible."
                            )
                        }
                        pdfEngine.cropPdfNormalized(
                            tempFilePath ?: "", output.absolutePath,
                            manualCrop.left, manualCrop.top, manualCrop.right, manualCrop.bottom,
                            pages = cropPages
                        )
                    } else {
                        val top = cropTop.toFloatOrNull() ?: 0f
                        val bottom = cropBottom.toFloatOrNull() ?: 0f
                        val left = cropLeft.toFloatOrNull() ?: 0f
                        val right = cropRight.toFloatOrNull() ?: 0f
                        if (top < 0f || bottom < 0f || left < 0f || right < 0f) {
                            return@withContext ProcessingResult(false, message = "Crop percentages cannot be negative.")
                        }
                        if (left + right >= 100f || top + bottom >= 100f) {
                            return@withContext ProcessingResult(
                                success = false,
                                message = "Invalid crop: Top+Bottom and Left+Right must each total less than 100%."
                            )
                        }
                        pdfEngine.cropPdfByPercent(
                            tempFilePath ?: "", output.absolutePath, top, bottom, left, right,
                            pages = cropPages
                        )
                    }
                }

                // ── SECURITY ──
                "sign_pdf" -> {
                    ProcessingResult(
                        success = false,
                        message = "Use the dedicated signing screen accessible from the Home or Category screen."
                    )
                }

                "unlock_pdf" -> {
                    onProgress("Unlocking PDF…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "unlocked"))
                    pdfEngine.unlockPdf(tempFilePath ?: "", output.absolutePath, password)
                }

                "protect_pdf" -> {
                    onProgress("Protecting PDF…")
                    val output = FileManager.createTempFile(context, FileManager.generateOutputName(fileName, "protected"))
                    pdfEngine.protectPdf(
                        tempFilePath ?: "", output.absolutePath,
                        password.ifBlank { "password" },
                        ownerPassword.ifBlank { password.ifBlank { "password" } },
                        allowPrinting, allowCopying, allowEditing
                    )
                }

                "redact_pdf" -> {
                    ProcessingResult(
                        success = false,
                        message = "Use the dedicated redaction screen accessible from the Home or Category screen."
                    )
                }

                "compare_pdf" -> {
                    onProgress("Comparing PDFs…")
                    if (selectedUris.size < 2) {
                        return@withContext ProcessingResult(
                            success = false,
                            message = "Please select two PDF files to compare."
                        )
                    }
                    val path1File = FileManager.createTempFile(context, "compare_1_${System.currentTimeMillis()}")
                    val path2File = FileManager.createTempFile(context, "compare_2_${System.currentTimeMillis()}")
                    FileManager.copyUriToFile(context, selectedUris[0], path1File)
                    FileManager.copyUriToFile(context, selectedUris[1], path2File)
                    val security = com.pdforge.app.security.PdfSecurity(context)
                    val comparison = security.comparePdfs(path1File.absolutePath, path2File.absolutePath)
                    val nameA = FileManager.getFileName(context, selectedUris[0])
                    val nameB = FileManager.getFileName(context, selectedUris[1])
                    ProcessingResult(
                        success = true,
                        message = formatComparisonSummary(comparison, nameA, nameB),
                        outputPath = null,
                        pageCount = 0
                    )
                }

                else -> {
                    ProcessingResult(
                        success = false,
                        message = "This feature is not yet implemented."
                    )
                }
            }
            finalizeResult(
                context, rawResult,
                // Protect PDF produces an encrypted file: validate it with the same
                // password used to encrypt it instead of the normal parse.
                encryptionPassword = if (toolId == "protect_pdf") password.ifBlank { "password" } else null
            )
        } catch (e: Throwable) {
            ProcessingResult(
                success = false,
                message = "An error occurred: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}

/**
 * Builds the structured Compare PDF result shown to the user: document summary,
 * page add/remove/change counts, line counts, then the individual differences.
 */
internal fun formatComparisonSummary(
    comparison: ComparisonResult,
    nameA: String,
    nameB: String
): String {
    if (comparison.areIdentical) {
        return "The two documents are identical.\n\nA: $nameA (${comparison.pagesA} page(s))\nB: $nameB (${comparison.pagesB} page(s))"
    }
    val pagesAdded = (comparison.pagesB - comparison.pagesA).coerceAtLeast(0)
    val pagesRemoved = (comparison.pagesA - comparison.pagesB).coerceAtLeast(0)
    return buildString {
        appendLine("Document A: $nameA — ${comparison.pagesA} page(s), ${comparison.totalWordsA} word(s)")
        appendLine("Document B: $nameB — ${comparison.pagesB} page(s), ${comparison.totalWordsB} word(s)")
        appendLine()
        appendLine("Pages added: $pagesAdded")
        appendLine("Pages removed: $pagesRemoved")
        appendLine("Pages changed: ${comparison.changedPages}")
        appendLine("Lines added: ${comparison.addedLines}")
        appendLine("Lines removed: ${comparison.removedLines}")
        appendLine()
        appendLine("Differences (${comparison.differences.size}):")
        comparison.differences.forEach { appendLine("• $it") }
    }.trimEnd()
}

/**
 * Resolves the optional "from/to" page range for Add Page Numbers.
 *
 * Returns the 1-based pages to number, or null when every page should be
 * numbered. Out-of-range bounds are clamped to the document; an inverted range
 * yields an empty list (validated by the caller before this point).
 */
/**
 * Base name for a "convert from PDF" output (PDF → DOCX/XLSX/PPTX).
 *
 * The source name has its own extension dropped so the converter's real
 * extension is applied exactly once. Previously the name was built with a
 * hard-coded ".pdf" and then the converter's suffix was appended, producing
 * confusing names like "report_converted.pdf.docx".
 */
internal fun convertedBaseName(originalName: String, suffix: String): String {
    val base = originalName.substringBeforeLast('.', originalName)
    return "${base.ifBlank { "document" }}_$suffix"
}

internal fun resolvePageNumberRange(from: Int?, to: Int?, totalPages: Int): List<Int>? {
    if (totalPages <= 0) return null
    if (from == null && to == null) return null
    val start = (from ?: 1).coerceIn(1, totalPages)
    val end = (to ?: totalPages).coerceIn(1, totalPages)
    if (start > end) return emptyList()
    return (start..end).toList()
}