package com.pdforge.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.pdf.AcroFormField
import com.pdforge.app.pdf.FormFieldType
import com.pdforge.app.pdf.PdfEngine
import com.pdforge.app.pdf.detectAcroFormFields
import com.pdforge.app.pdf.fillAcroForm
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.ProcessingIndicator
import com.pdforge.app.ui.components.ResultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF Forms — fills the INTERACTIVE AcroForm fields a PDF already contains.
 *
 * Detection and filling live in [com.pdforge.app.pdf.detectAcroFormFields] /
 * [com.pdforge.app.pdf.fillAcroForm] so the whole detect → fill → reopen round
 * trip is covered by JVM tests. This screen owns only the presentation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfFormsScreen(
    onBack: () -> Unit,
    onResult: (ProcessingResult) -> Unit,
    onOpenInApp: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfEngine = remember { PdfEngine(context) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var tempFilePath by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var doneResult by remember { mutableStateOf<ProcessingResult?>(null) }
    var formFields by remember { mutableStateOf<List<AcroFormField>>(emptyList()) }
    var fieldValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var detection by remember { mutableStateOf<Boolean?>(null) }

    // Visual mode state
    var viewMode by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    // Only the current page is rendered and kept in memory (multi-page PDFs must not
    // load every page as a full bitmap).
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isDetectingFields by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedUri = it
            fileName = FileManager.getFileName(context, it)
            isDetectingFields = true
            scope.launch {
                // Copying the file and parsing AcroForm fields is heavy — keep it off the main thread.
                val tempFile = FileManager.createTempFile(context, "forms_input_${System.currentTimeMillis()}")
                val copied = withContext(Dispatchers.IO) {
                    FileManager.copyUriToFile(context, it, tempFile)
                }
                if (copied) {
                    val result = withContext(Dispatchers.IO) {
                        detectAcroFormFields(tempFile.absolutePath)
                    }
                    tempFilePath = tempFile.absolutePath
                    formFields = result.fields
                    detection = result.hasAcroForm
                    fieldValues = result.fields.associate { field -> field.name to field.value }
                    pageCount = withContext(Dispatchers.IO) { pdfEngine.getPageCount(tempFile.absolutePath) }
                    currentPage = 0
                    pageBitmap = null
                } else {
                    Toast.makeText(context, "Could not read the selected PDF", Toast.LENGTH_SHORT).show()
                    selectedUri = null
                }
                isDetectingFields = false
            }
        }
    }

    // Render only the visible page for visual mode.
    LaunchedEffect(currentPage, tempFilePath, viewMode) {
        if (viewMode && tempFilePath != null) {
            pageBitmap = withContext(Dispatchers.IO) {
                pdfEngine.renderPage(tempFilePath!!, currentPage, 800, 1100)
            }
        } else {
            pageBitmap = null
        }
    }

    if (isProcessing) {
        ProcessingIndicator(message = "Saving form…", modifier = modifier)
        return
    }

    doneResult?.let { r ->
        ResultScreen(
            result = r,
            toolName = "PDF Forms",
            onOpenInApp = onOpenInApp,
            onBack = { onResult(r) },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Forms") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (formFields.isNotEmpty()) {
                        IconButton(onClick = { viewMode = !viewMode }) {
                            Icon(
                                if (viewMode) Icons.Default.ViewList else Icons.Default.PictureAsPdf,
                                contentDescription = if (viewMode) "List view" else "Visual view"
                            )
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Fill the interactive form fields a PDF already contains.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedUri == null) {
                Button(
                    onClick = { fileLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select PDF with Forms")
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    isDetectingFields -> "Checking for form fields…"
                                    formFields.isNotEmpty() -> "${formFields.size} field(s) found"
                                    detection == true -> "No fillable fields in this form"
                                    else -> "No interactive form fields found"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (formFields.isNotEmpty()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            selectedUri = null; tempFilePath = null; formFields = emptyList()
                            fieldValues = emptyMap(); detection = null; pageBitmap = null; pageCount = 0
                        }) {
                            Icon(Icons.Default.Close, "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // ── No interactive fields ────────────────────────────────────────────
            // This is NOT a failure: the tool only fills existing AcroForm fields.
            // The message explains what the tool does and why a plain PDF can look
            // like it has fields when it does not.
            if (detection != null && formFields.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("No fillable form fields in this PDF",
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This tool fills the interactive form fields a PDF already contains " +
                                "(text boxes, checkboxes, radio buttons and choice lists).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "A normal PDF that shows blank lines is not necessarily an interactive form — " +
                                "the lines can simply be drawn content. This document has no interactive fields, " +
                                "so there is nothing to fill here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "To fill in text, use Edit PDF. To create a fillable form, it needs to be authored " +
                                "with an interactive form first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (formFields.isNotEmpty()) {
                Text("Form Fields (${formFields.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                if (viewMode && pageCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                }

                if (viewMode && pageBitmap != null) {
                    // Visual mode — show the current page (rendered lazily, one page at a time)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pageBitmap!!.width.toFloat() / pageBitmap!!.height.toFloat())
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Image(
                            bitmap = pageBitmap!!.asImageBitmap(),
                            contentDescription = "PDF page",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Show fields for this page below the image
                    formFields.filter { it.pageNumber == currentPage + 1 }.forEach { field ->
                        FormFieldEditor(field, fieldValues[field.name] ?: "",
                            onValueChange = { fieldValues = fieldValues + (field.name to it) })
                    }
                    if (formFields.none { it.pageNumber == currentPage + 1 }) {
                        Text("No fields on this page.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // List mode
                    formFields.forEach { field ->
                        FormFieldEditor(field, fieldValues[field.name] ?: "",
                            onValueChange = { fieldValues = fieldValues + (field.name to it) })
                    }
                }

                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                saveFilledForm(context, tempFilePath ?: "", fieldValues)
                            }
                            isProcessing = false
                            doneResult = result
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Filled Form")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFieldEditor(field: AcroFormField, value: String, onValueChange: (String) -> Unit) {
    when (field.type) {
        FormFieldType.TEXT -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.name + if (field.isRequired) " *" else "") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
        FormFieldType.CHECKBOX -> {
            val isChecked = value == "Yes"
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isChecked, onCheckedChange = { onValueChange(if (it) "Yes" else "Off") })
                Spacer(modifier = Modifier.width(8.dp))
                Text(field.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
        FormFieldType.DROPDOWN -> {
            var expanded by remember { mutableStateOf(false) }
            Text(field.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = value.ifBlank { field.options.firstOrNull() ?: "" },
                    onValueChange = {}, readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
                    }
                }
            }
        }
        FormFieldType.RADIO -> {
            Text(field.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            field.options.forEach { option ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == option, onClick = { onValueChange(option) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(option, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FormFieldType.OTHER -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(field.name) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

// ── Save filled form ────────────────────────────────────────

/**
 * Fills the form with the production [fillAcroForm] implementation, then validates
 * and publishes the result through the shared output pipeline.
 */
private fun saveFilledForm(
    context: android.content.Context,
    inputPath: String,
    fieldValues: Map<String, String>
): ProcessingResult {
    return try {
        val output = FileManager.createTempFile(
            context,
            File(inputPath).nameWithoutExtension + "_filled"
        )
        val count = fillAcroForm(inputPath, output.absolutePath, fieldValues)

        // Validate and publish to user-visible storage
        val saved = FileManager.finalizeOutput(context, output)
            ?: return ProcessingResult(false, message = "Output validation failed — the filled form could not be verified.")
        ProcessingResult(
            success = true,
            outputPath = saved.localPath,
            outputUri = saved.publicUri,
            fileName = saved.name,
            mimeType = saved.mimeType,
            outputSize = saved.size,
            pageCount = count,
            message = "Form saved successfully."
        )
    } catch (e: Exception) {
        ProcessingResult(false, message = "Failed to save form: ${e.message}")
    }
}
