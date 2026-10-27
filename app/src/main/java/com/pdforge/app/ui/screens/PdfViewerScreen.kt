package com.pdforge.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.pdforge.app.storage.FileManager
import com.pdforge.app.viewer.PdfViewer
import java.io.File

/**
 * Full-screen in-app PDF viewer. Opens content:// URIs, file:// URIs,
 * or raw file paths, and offers an "Open With" action for external viewers.
 *
 * The toolbar shows the filename and a live page indicator that updates as the
 * user scrolls through the continuous document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    input: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }

    val displayName = remember(input) {
        when {
            input.startsWith("content://") ->
                FileManager.getFileName(context, Uri.parse(input))
            input.startsWith("file://") -> {
                val path = Uri.parse(input).path
                File(path ?: input).name
            }
            else -> File(input).name
        }
    }

    fun openExternally() {
        try {
            val uri: Uri = if (input.startsWith("content://")) {
                Uri.parse(input)
            } else {
                val path = if (input.startsWith("file://")) {
                    Uri.parse(input).path ?: input
                } else {
                    input
                }
                FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No compatible app found", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (totalPages > 0) {
                            Text(
                                text = "${currentPage.coerceIn(1, totalPages)} / $totalPages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { openExternally() }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open with another app")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        PdfViewer(
            input = input,
            onPageInfo = { current, total ->
                currentPage = current
                totalPages = total
            },
            onBack = onBack,
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}