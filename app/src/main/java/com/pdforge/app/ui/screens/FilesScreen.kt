package com.pdforge.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pdforge.app.data.models.RecentFile
import com.pdforge.app.data.repository.RecentFilesRepository
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.AppTextButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Opens a generated output. PDFs open in the in-app viewer; other formats
 * (images, Office documents) open with an external app.
 */
fun openOutputInAppOrExternal(
    context: android.content.Context,
    mimeType: String?,
    target: String,
    onOpenInApp: (String) -> Unit
) {
    val mime = mimeType ?: FileManager.getMimeType(target)
    if (mime == "application/pdf") {
        onOpenInApp(target)
    } else {
        try {
            val uri = if (target.startsWith("content://")) {
                Uri.parse(target)
            } else {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", File(target))
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "No compatible app found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Opens a Recent File.
 *
 * PDFs open in the in-app viewer using the stable internal copy
 * (filesDir/output) — it is always present and seekable, unlike a published
 * MediaStore URI which can go stale. The published URI is still used for
 * share, delete, and external apps. Other formats open with an external app.
 */
fun openRecentFile(
    context: android.content.Context,
    recentFile: RecentFile,
    onOpenInApp: (String) -> Unit
) {
    val mime = recentFile.type.ifBlank { "application/pdf" }
    if (mime == "application/pdf") {
        onOpenInApp(recentFile.path)
    } else {
        openOutputInAppOrExternal(context, mime, recentFile.uri.ifBlank { recentFile.path }, onOpenInApp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    recentFilesRepository: RecentFilesRepository,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val recentFiles by recentFilesRepository.recentFiles.collectAsState()
    val context = LocalContext.current

    // Delete workflow: the file is only removed after explicit confirmation, and
    // the UI entry is never removed while the underlying file still exists.
    var filePendingDelete by remember { mutableStateOf<RecentFile?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Files",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (recentFiles.isEmpty()) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No files yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Processed documents will appear here\nand in Documents/PDF Forge",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recentFiles, key = { it.id }) { file ->
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    val dateStr = dateFormat.format(Date(file.lastModified))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openRecentFile(context, file, onOpenFile)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "$dateStr • ${FileManager.formatFileSize(file.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                try {
                                    val uri: Uri = if (file.uri.startsWith("content://")) {
                                        Uri.parse(file.uri)
                                    } else {
                                        FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.provider",
                                            File(file.path)
                                        )
                                    }
                                    val mime = if (file.type.isBlank()) "application/pdf" else file.type
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = mime
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share file"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Cannot share file", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { filePendingDelete = file }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog — never delete on a single accidental tap.
    filePendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { filePendingDelete = null },
            title = { Text("Delete this file?") },
            text = {
                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This removes the file from PDF Forge and from Documents/PDF Forge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                AppTextButton(
                    text = "Delete",
                    onClick = {
                        val deleted = recentFilesRepository.deleteFile(file.path)
                        if (!deleted) {
                            deleteError = "Unable to delete file"
                        }
                        filePendingDelete = null
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            },
            dismissButton = {
                AppTextButton(text = "Cancel", onClick = { filePendingDelete = null })
            }
        )
    }

    // Surface deletion errors instead of silently keeping a stale entry.
    deleteError?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            deleteError = null
        }
    }
}