package com.pdforge.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.pdforge.app.data.models.ProcessingResult
import com.pdforge.app.storage.FileManager
import java.io.File

/**
 * Shared output-action helpers used by the result screen.
 *
 * The result screen passes its CURRENT result (updated after a rename) to these
 * helpers, so open/share always target the file that actually exists now. The
 * previous implementation captured a frozen result in the caller, which made
 * Open/Open With/Share point at the old path (and therefore fail) after a rename.
 */

/** Prefer the stable internal copy for the in-app viewer; fall back to the published URI. */
fun resolveInAppReference(result: ProcessingResult): String? =
    result.outputPath?.takeIf { it.isNotBlank() } ?: result.outputUri?.takeIf { it.isNotBlank() }

/** Prefer the published content:// URI for external apps; fall back to the internal path. */
fun resolveExternalReference(result: ProcessingResult): String? {
    val uri = result.outputUri
    val path = result.outputPath
    return when {
        uri != null && uri.startsWith("content://") -> uri
        path != null && path.isNotBlank() -> path
        uri != null && uri.isNotBlank() -> uri
        else -> null
    }
}

/** Turns a raw path / file:// URI / content:// URI into a shareable Uri. */
private fun referenceToUri(context: Context, reference: String): Uri {
    return if (reference.startsWith("content://")) {
        Uri.parse(reference)
    } else {
        val path = if (reference.startsWith("file://")) {
            Uri.parse(reference).path ?: reference
        } else {
            reference
        }
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))
    }
}

/**
 * Opens the output with another app (ACTION_VIEW).
 *
 * Never swallows the failure: when no app can open the file (or the file is
 * gone) the user gets a clear message instead of nothing happening.
 */
fun openOutputExternally(context: Context, result: ProcessingResult) {
    val mime = result.mimeType ?: FileManager.getMimeType(result.outputPath ?: "output.pdf")
    val reference = resolveExternalReference(result)
    if (reference == null) {
        Toast.makeText(context, "No output file is available to open.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = referenceToUri(context, reference)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app found that can open this file.", Toast.LENGTH_SHORT).show()
    }
}

/** Shares the output using the current (post-rename) reference. */
fun shareOutput(context: Context, result: ProcessingResult) {
    val mime = result.mimeType ?: FileManager.getMimeType(result.outputPath ?: "output.pdf")
    val reference = resolveExternalReference(result)
    if (reference == null) {
        Toast.makeText(context, "No output file is available to share.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = referenceToUri(context, reference)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share file"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share this file.", Toast.LENGTH_SHORT).show()
    }
}
