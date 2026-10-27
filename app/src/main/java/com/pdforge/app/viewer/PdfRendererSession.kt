package com.pdforge.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pdforge.app.storage.FileManager
import java.io.Closeable
import java.io.File

/**
 * Owns a single open [PdfRenderer] for the whole viewer session.
 *
 * Handles every supported reference type (content:// URI, file:// URI, raw
 * filesystem path). PdfRenderer requires a seekable file descriptor; when a
 * content provider returns a non-seekable descriptor (or cannot be opened
 * directly), the document is copied once into a temporary cache file that is
 * used solely for rendering and deleted on [close].
 *
 * All page access is synchronized because PdfRenderer is not thread-safe and
 * multiple pages may be requested while the user scrolls.
 */
class PdfRendererSession(
    private val context: Context,
    private val input: String
) : Closeable {

    companion object {
        private const val TAG = "PdfRendererSession"
    }

    private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private var cacheFile: File? = null

    /** True when the document was opened successfully. */
    val isOpen: Boolean get() = renderer != null

    /**
     * Opens the document. Must be called once, off the main thread.
     * Returns false when the reference cannot be opened at all.
     */
    fun open(): Boolean {
        if (renderer != null) return true
        val pair = openRenderer(input) ?: return false
        renderer = pair.first
        fd = pair.second
        return true
    }

    val pageCount: Int
        get() = synchronized(this) { renderer?.pageCount ?: 0 }

    /**
     * Page dimensions in PDF points (72 per inch), matching the renderer's
     * unrotated page box. Returns (width, height) or (0, 0) on failure.
     */
    fun pageSize(index: Int): Pair<Int, Int> {
        synchronized(this) {
            val r = renderer ?: return 0 to 0
            return try {
                if (index < 0 || index >= r.pageCount) return 0 to 0
                val page = r.openPage(index)
                val size = page.width to page.height
                page.close()
                size
            } catch (e: Exception) {
                Log.w(TAG, "pageSize($index) failed: ${e.message}")
                0 to 0
            }
        }
    }

    /**
     * Renders page [index] fit to [targetWidth] pixels, preserving aspect ratio.
     * Returns null when the page cannot be rendered.
     */
    fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        synchronized(this) {
            val r = renderer ?: return null
            return try {
                if (index < 0 || index >= r.pageCount) return null
                val page = r.openPage(index)
                val scale = targetWidth.toFloat() / page.width
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap
            } catch (e: Exception) {
                Log.w(TAG, "renderPage($index) failed: ${e.message}")
                null
            }
        }
    }

    override fun close() {
        synchronized(this) {
            try { renderer?.close() } catch (_: Exception) {}
            try { fd?.close() } catch (_: Exception) {}
            renderer = null
            fd = null
            cacheFile?.let { file ->
                try { if (file.exists()) file.delete() } catch (_: Exception) {}
            }
            cacheFile = null
        }
    }

    private fun openRenderer(reference: String): Pair<PdfRenderer, ParcelFileDescriptor>? {
        val direct = openDescriptor(reference)
        if (direct != null) {
            try {
                return PdfRenderer(direct) to direct
            } catch (e: Exception) {
                Log.w(TAG, "PdfRenderer rejected direct descriptor, falling back: ${e.message}")
                try { direct.close() } catch (_: Exception) {}
            }
        }
        // Non-file-backed providers: copy to a seekable cache file for rendering only.
        if (reference.startsWith("content://")) {
            val file = FileManager.createTempFile(context, "viewer_${System.currentTimeMillis()}")
            if (FileManager.copyUriToFile(context, Uri.parse(reference), file)) {
                try {
                    val f = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    cacheFile = file
                    return PdfRenderer(f) to f
                } catch (e: Exception) {
                    Log.w(TAG, "PdfRenderer failed on cached copy: ${e.message}")
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun openDescriptor(reference: String): ParcelFileDescriptor? {
        return try {
            when {
                reference.startsWith("content://") ->
                    context.contentResolver.openFileDescriptor(Uri.parse(reference), "r")
                reference.startsWith("file://") ->
                    ParcelFileDescriptor.open(
                        File(Uri.parse(reference).path ?: return null),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                else ->
                    ParcelFileDescriptor.open(File(reference), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "openDescriptor failed: ${e.message}")
            null
        }
    }
}