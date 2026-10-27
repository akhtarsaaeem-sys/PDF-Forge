package com.pdforge.app.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.storage.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProcessingIndicator(
    message: String = "Processing…",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.rotate(rotation)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Shared result screen for every tool.
 *
 * It owns the output references so that Open / Open With / Share / Save As always
 * target the CURRENT file: when a rename happens the result (internal path,
 * published URI, and output name) is updated in place. The previous version kept
 * the references frozen in the caller, so after a rename Open pointed at the old
 * path and did nothing while Open With happened to keep working through the
 * published MediaStore URI.
 *
 * @param onOpenInApp navigates to the in-app viewer with the given reference
 *        (used for PDFs). Non-PDF outputs are opened externally.
 */
@Composable
fun ResultScreen(
    result: ProcessingResult,
    toolName: String,
    onOpenInApp: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Live result: updated after a rename so every action follows the new file.
    var liveResult by remember(result) {
        mutableStateOf(
            if (result.outputPaths.isEmpty() && result.outputPath != null) {
                result.copy(outputPaths = listOf(result.outputPath!!))
            } else {
                result
            }
        )
    }
    val outputPaths = liveResult.outputPaths
    val mimeType = liveResult.mimeType
        ?: FileManager.getMimeType(liveResult.outputPath ?: "output.pdf")

    var displayFileName by remember(result) {
        mutableStateOf(
            when {
                outputPaths.isEmpty() -> liveResult.fileName
                else -> liveResult.fileName?.takeIf { outputPaths.size == 1 }
                    ?: File(outputPaths.first()).name
            }
        )
    }
    var showRenameDialog by remember(result) { mutableStateOf(false) }
    var renameError by remember(result) { mutableStateOf<String?>(null) }

    fun currentDisplayName(): String = displayFileName ?: liveResult.fileName ?: "output.pdf"

    fun openOutput() {
        val reference = resolveInAppReference(liveResult)
        if (reference == null) {
            Toast.makeText(context, "No output file is available to open.", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = mimeType
        if (mime == "application/pdf") {
            // Give feedback for a stale local file instead of navigating to a
            // viewer that would show nothing useful.
            val isRawPath = !reference.startsWith("content://") && !reference.startsWith("file://")
            if (isRawPath && !File(reference).exists()) {
                Toast.makeText(context, "The generated file is no longer available.", Toast.LENGTH_SHORT).show()
                return
            }
            onOpenInApp(reference)
        } else {
            openOutputExternally(context, liveResult)
        }
    }

    if (showRenameDialog) {
        RenameOutputDialog(
            currentName = currentDisplayName(),
            fileCount = outputPaths.size,
            errorMessage = renameError,
            onDismiss = { showRenameDialog = false; renameError = null },
            onConfirm = { requestedName ->
                scope.launch {
                    val renamed = withContext(Dispatchers.IO) {
                        FileManager.renameOutputs(context, outputPaths, requestedName)
                    }
                    if (renamed.success) {
                        val outputs = renamed.outputs
                        liveResult = liveResult.copy(
                            outputPath = outputs.first().localPath,
                            outputUri = outputs.first().publicUri,
                            outputPaths = outputs.map { it.localPath },
                            fileName = if (outputs.size == 1) outputs.first().name
                            else outputs.joinToString(", ") { it.name }
                        )
                        displayFileName = if (outputs.size == 1) {
                            outputs.first().name
                        } else {
                            "${outputs.first().name} +${outputs.size - 1} more"
                        }
                        showRenameDialog = false
                        renameError = null
                        Toast.makeText(
                            context,
                            if (outputs.size == 1) "Renamed to ${outputs.first().name}"
                            else "Renamed ${outputs.size} files",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        renameError = renamed.message ?: "Could not rename the file."
                    }
                }
            }
        )
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType)
    ) { uri ->
        uri?.let { dest ->
            val source = liveResult.outputPath?.let { File(it) }
            if (source != null && source.exists()) {
                val ok = FileManager.saveToUserLocation(context, source, dest)
                Toast.makeText(
                    context,
                    if (ok) "File saved" else "Could not save file",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "No output file available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (result.success) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Success",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Completed",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Icon(
                Icons.Default.Error,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Couldn't process this PDF",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result.message ?: "An unknown error occurred.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (result.success) {
            // Output file name (kept in sync when the user renames the output)
            val displayName = displayFileName
            if (displayName != null) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Saved to Documents/PDF Forge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // File size info
            if (result.originalSize > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Original",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FileManager.formatFileSize(result.originalSize),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "New file",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FileManager.formatFileSize(result.outputSize),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // Only show a reduction when the output is genuinely smaller —
                        // never fabricate savings when the file grew or stayed the same.
                        if (result.outputSize in 1 until result.originalSize) {
                            val reduction = ((result.originalSize - result.outputSize) * 100 / result.originalSize).toInt()
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Reduction: $reduction%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            } else if (result.outputSize > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "File size",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FileManager.formatFileSize(result.outputSize),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Message (e.g. compression stats, compare results)
            result.message?.let { msg ->
                if (msg.isNotBlank() && result.outputPath == null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Stats
            if (result.pageCount > 0 || result.fileCount > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (result.fileCount > 1) {
                            Text(
                                text = "${result.fileCount} files created",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (result.pageCount > 0 && result.fileCount <= 1) {
                            Text(
                                text = "${result.pageCount} pages",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            if (outputPaths.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppButton(
                        text = "Open",
                        onClick = { openOutput() },
                        modifier = Modifier.weight(1f)
                    )
                    AppOutlinedButton(
                        text = "Open With",
                        onClick = { openOutputExternally(context, liveResult) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppOutlinedButton(
                        text = "Share",
                        onClick = { shareOutput(context, liveResult) },
                        modifier = Modifier.weight(1f)
                    )
                    AppOutlinedButton(
                        text = "Save As",
                        onClick = {
                            saveAsLauncher.launch(
                                if (outputPaths.size == 1) currentDisplayName() else "output.pdf"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                AppOutlinedButton(
                    text = "Rename",
                    icon = Icons.Default.DriveFileRenameOutline,
                    onClick = {
                        renameError = null
                        showRenameDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Message-only results (e.g. Compare)
                AppOutlinedButton(
                    text = "Done",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppOutlinedButton(
                    text = "Choose Another File",
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * The single rename dialog used by every tool's result screen.
 *
 * The user edits the base name only; the extension is preserved and re-attached
 * by [FileManager.buildOutputName]. Illegal characters are rejected up front
 * (with inline feedback) instead of producing an unusable file.
 */
@Composable
private fun RenameOutputDialog(
    currentName: String,
    fileCount: Int,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val extension = FileManager.getExtension(currentName)
    val baseName = currentName.substringBeforeLast('.', currentName)
    var draft by remember(currentName) { mutableStateOf(baseName) }
    val trimmed = draft.trim()
    val isValid = FileManager.isValidFileName("$trimmed$extension")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename PDF") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("File name") },
                    singleLine = true,
                    isError = trimmed.isNotEmpty() && !isValid,
                    supportingText = {
                        Text(
                            if (trimmed.isNotEmpty() && !isValid) {
                                "Remove any of \\ / : * ? \" < > | characters."
                            } else {
                                "Keeps the $extension extension" +
                                    if (fileCount > 1) " — $fileCount files will be numbered." else "."
                            }
                        )
                    }
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            AppTextButton(
                text = "Rename",
                enabled = isValid,
                onClick = { onConfirm("$trimmed$extension") }
            )
        },
        dismissButton = {
            AppTextButton(text = "Cancel", onClick = onDismiss)
        }
    )
}
