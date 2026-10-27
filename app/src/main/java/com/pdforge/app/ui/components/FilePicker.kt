package com.pdforge.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdforge.app.storage.FileManager

@Composable
fun FilePickerButton(
    text: String = "Select File",
    mimeTypes: Array<String> = arrayOf("application/pdf"),
    multipleAllowed: Boolean = false,
    onFilesSelected: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier
) {
    if (multipleAllowed) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                onFilesSelected(uris)
            }
        }

        Button(
            onClick = { launcher.launch(mimeTypes) },
            modifier = modifier.defaultMinSize(minWidth = AppButtonMinWidth),
            shape = RoundedCornerShape(12.dp),
            contentPadding = AppButtonPadding
        ) {
            ButtonLabel(text = text, icon = Icons.Default.Add)
        }
    } else {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                onFilesSelected(listOf(uri))
            }
        }

        Button(
            onClick = { launcher.launch(mimeTypes) },
            modifier = modifier.defaultMinSize(minWidth = AppButtonMinWidth),
            shape = RoundedCornerShape(12.dp),
            contentPadding = AppButtonPadding
        ) {
            ButtonLabel(text = text, icon = Icons.Default.Add)
        }
    }
}

@Composable
fun SelectedFileItem(
    name: String,
    size: Long = 0,
    onRemove: (() -> Unit)? = null,
    position: Int? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    canMoveUp: Boolean = true,
    canMoveDown: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (position != null) {
                Text(
                    text = "$position",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp)
                )
            }
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (size > 0) {
                    Text(
                        text = FileManager.formatFileSize(size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onMoveUp != null) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp && onMoveUp != null) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
            }
            if (onMoveDown != null) {
                IconButton(onClick = onMoveDown, enabled = canMoveDown && onMoveDown != null) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.graphicsLayer(rotationZ = 45f)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
