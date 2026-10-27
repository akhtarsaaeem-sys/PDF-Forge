package com.pdforge.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.pdforge.app.data.repository.RecentFilesRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized file management for PDF Forge.
 *
 * Storage strategy:
 *  - Temp/processing files  -> cacheDir/temp   (may be cleared at any time)
 *  - Final internal copies  -> filesDir/output (stable, used by Recent Files and in-app viewer)
 *  - Final user-visible     -> Documents/PDF Forge via MediaStore (API 29+)
 *                              or public Documents/PDF Forge directory (API 26-28)
 *
 * Every tool must produce its final file through [finalizeOutput] (or
 * [finalizeOutputs] for multi-file results) so that the output is validated,
 * copied into a user-accessible location, and registered with Recent Files.
 */
object FileManager {

    const val OUTPUT_SUBDIR = "output"
    const val TEMP_SUBDIR = "temp"
    const val PUBLIC_DIR_NAME = "PDF Forge"

    /** Where final internal copies live (stable, never cleared by cache cleanup). */
    fun getOutputDir(context: Context): File {
        val dir = File(context.filesDir, OUTPUT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Where temporary processing files live (cleared with cache). */
    fun getTempDir(context: Context): File {
        val dir = File(context.cacheDir, TEMP_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Creates a collision-safe temporary file in the cache temp dir. */
    fun createTempFile(context: Context, baseName: String, suffix: String = ".pdf"): File {
        return uniqueFile(getTempDir(context), sanitizeName(baseName), suffix)
    }

    /**
     * Creates a collision-safe final file in the internal output dir.
     * These files are the canonical in-app copies used by Recent Files and the in-app viewer.
     */
    fun createOutputFile(context: Context, baseName: String, suffix: String = ".pdf"): File {
        return uniqueFile(getOutputDir(context), sanitizeName(baseName), suffix)
    }

    private fun uniqueFile(dir: File, cleanBase: String, suffix: String): File {
        var candidate = File(dir, "$cleanBase$suffix")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${cleanBase}_$counter$suffix")
            counter++
        }
        return candidate
    }

    /**
     * Sanitizes a file NAME (not a path).
     *
     * Illegal filesystem characters become '_' and runs of whitespace collapse to
     * a single space, but spaces are preserved so a renamed file can read
     * naturally ("My Document.pdf"). Leading dots are stripped so a rename can
     * never create a hidden file, and the length is capped so the name plus
     * extension stays inside filesystem limits.
     */
    fun sanitizeName(name: String): String {
        return name
            .replace(ILLEGAL_FILENAME_CHARS, "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
            .trim()
            .take(MAX_NAME_LENGTH)
    }

    /**
     * True when [name] is a usable file name: non-blank, no path separators or
     * other illegal characters, not a Windows reserved device name, and not made
     * up of only dots/spaces.
     *
     * Used by the shared rename dialog so the user gets feedback before saving
     * instead of silently producing a broken file.
     */
    fun isValidFileName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        if (ILLEGAL_FILENAME_CHARS.containsMatchIn(trimmed)) return false
        // The base name (everything before the extension) must be usable. This is
        // what rejects ".pdf" and "..." — both would create a hidden file.
        val base = if (trimmed.contains('.')) trimmed.substringBeforeLast('.') else trimmed
        val cleanBase = base.trim().trimEnd('.')
        if (cleanBase.isBlank()) return false
        if (cleanBase.uppercase(Locale.US) in RESERVED_FILE_NAMES) return false
        return true
    }

    /**
     * Builds a safe output file name from arbitrary user input: keeps the requested
     * base name, guarantees the [extension] is present exactly once, and falls back
     * to [fallbackBase] when nothing usable remains.
     */
    fun buildOutputName(rawName: String, extension: String = ".pdf", fallbackBase: String = "document"): String {
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val trimmed = rawName.trim()
        // Drop exactly ONE trailing extension, then re-add ours, so "report.docx"
        // saved as PDF does not become "report.docx.pdf". Internal dots are kept:
        // "My.Doc.v2" must never collapse to "My.pdf".
        val lastDot = trimmed.lastIndexOf('.')
        val trailing = if (lastDot >= 0) trimmed.substring(lastDot + 1) else ""
        val looksLikeExtension = trailing.isNotEmpty() &&
            trailing.length <= 5 &&
            trailing.all { it.isLetterOrDigit() }
        var base = if (looksLikeExtension) trimmed.substring(0, lastDot) else trimmed
        base = sanitizeName(base)
        // "///" or ":" produce a name with no letters or digits — fall back rather
        // than saving something like "_.pdf".
        if (base.isBlank() || base.none { it.isLetterOrDigit() }) base = fallbackBase
        return "$base$ext"
    }

    /** A sensible generated default name, e.g. "scan_20260928_153012". */
    fun timestampedName(prefix: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return sanitizeName("${prefix}_$stamp")
    }

    /** Removes double extensions like "doc_rotated.pdf.pdf". */
    fun generateOutputName(originalName: String, suffix: String): String {
        val baseName = originalName.substringBeforeLast(".", originalName)
        return "${baseName}_$suffix.pdf"
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name = "document.pdf"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                }
            }
        } catch (_: Exception) {}
        return name
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {}
        return size
    }

    fun copyUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun copyFileToUri(context: Context, source: File, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Illegal characters on Windows/Android filesystems. */
    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    /** Windows reserved device names, rejected so "CON.pdf" can never be created. */
    private val RESERVED_FILE_NAMES = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { add("COM$it"); add("LPT$it") }
    }

    /** Keeps a file name well inside filesystem limits after the extension is added. */
    private const val MAX_NAME_LENGTH = 100

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".pdf", true) -> "application/pdf"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            fileName.endsWith(".doc", true) -> "application/msword"
            fileName.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            fileName.endsWith(".xls", true) -> "application/vnd.ms-excel"
            fileName.endsWith(".pptx", true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            fileName.endsWith(".ppt", true) -> "application/vnd.ms-powerpoint"
            fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) -> "text/html"
            else -> "application/octet-stream"
        }
    }

    fun getExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isBlank() || ext.length > 5) ".pdf" else ".$ext"
    }

    // ── Centralized output finalization ──────────────────────────────────

    /**
     * Validates [tempFile], copies it into the internal output dir, publishes it
     * to the user-visible Documents/PDF Forge location, deletes the temp file,
     * and returns the [SavedOutput] describing the final result.
     *
     * Returns null when the output failed validation or could not be saved.
     * Use [finalizeOutputResult] when the failure reason is needed by the UI.
     */
    fun finalizeOutput(
        context: Context,
        tempFile: File,
        displayName: String? = null,
        mimeType: String? = null,
        encryptionPassword: String? = null
    ): SavedOutput? = finalizeOutputResult(context, tempFile, displayName, mimeType, encryptionPassword).output

    /**
     * Same as [finalizeOutput] but returns a [FinalizeResult] that carries the
     * real output type in its failure message (e.g. "the generated Word document
     * (.docx) could not be verified") instead of always blaming a PDF.
     */
    fun finalizeOutputResult(
        context: Context,
        tempFile: File,
        displayName: String? = null,
        mimeType: String? = null,
        encryptionPassword: String? = null
    ): FinalizeResult {
        val effectiveName = displayName
        val mime = mimeType ?: getMimeType(effectiveName ?: tempFile.name)
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            return FinalizeResult(
                null,
                "Output validation failed — the generated ${describeOutputType(mime, tempFile.name)} is empty."
            )
        }

        // Protect PDF produces an intentionally encrypted PDF. It is validated in
        // the explicit encrypted mode (password + page count) instead of the
        // normal parse, which cannot open a password-protected document.
        if (mime == "application/pdf" && encryptionPassword != null) {
            if (!OutputValidator.validateEncryptedPdf(tempFile, encryptionPassword)) {
                return FinalizeResult(
                    null,
                    "Output validation failed — the protected PDF could not be opened with its password."
                )
            }
        } else {
            val outcome = OutputValidator.validateOutcome(tempFile, mime)
            if (!outcome.valid) {
                return FinalizeResult(null, "Output validation failed — ${outcome.message}")
            }
        }

        // A display name without an extension would lose the file type, so the
        // temp file's extension (usually .pdf) is the fallback.
        val requestedName = effectiveName ?: tempFile.name
        val withExtension = if (requestedName.contains('.')) {
            requestedName
        } else {
            "$requestedName${getExtension(tempFile.name)}"
        }
        val name = sanitizeName(withExtension)
        val internalCopy = createOutputFile(context, name.substringBeforeLast('.', name), name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" })

        return try {
            tempFile.copyTo(internalCopy, overwrite = true)
            // Publish to the user-visible location (best effort; internal copy always kept).
            val publicUri = publishToDocuments(context, internalCopy, internalCopy.name, mime)
            tempFile.delete()
            val saved = SavedOutput(
                name = internalCopy.name,
                mimeType = mime,
                size = internalCopy.length(),
                localPath = internalCopy.absolutePath,
                publicUri = publicUri
            )
            // Register with Recent Files (only after validation succeeded).
            RecentFilesRepository(context).addRecentFile(File(saved.localPath), saved.publicUri, saved.mimeType)
            FinalizeResult(saved, null)
        } catch (e: Exception) {
            FinalizeResult(null, "Failed to save the generated file: ${e.message}")
        }
    }

    /** Human-readable output type used in validation messages. */
    private fun describeOutputType(mimeType: String, fileName: String): String = when {
        mimeType == "application/pdf" || fileName.endsWith(".pdf", true) -> "PDF file"
        mimeType.contains("wordprocessingml") || fileName.endsWith(".docx", true) -> "Word document (.docx)"
        mimeType.contains("spreadsheetml") || fileName.endsWith(".xlsx", true) -> "Excel workbook (.xlsx)"
        mimeType.contains("presentationml") || fileName.endsWith(".pptx", true) -> "PowerPoint presentation (.pptx)"
        mimeType.startsWith("image/") -> "image"
        else -> "file"
    }

    /** Finalizes every file inside [tempDir] and returns the resulting outputs. */
    fun finalizeOutputs(context: Context, tempDir: File, displayName: String? = null): List<SavedOutput> {
        val files = tempDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return emptyList()
        val results = mutableListOf<SavedOutput>()
        files.forEachIndexed { index, file ->
            // A requested display name only applies directly to a single file; for a
            // set (e.g. Split) each file gets a numbered name so nothing is overwritten.
            val nameForFile = displayName?.let { requested ->
                if (files.size == 1) requested else buildOutputName(
                    "${requested.substringBeforeLast('.')}_${"%02d".format(index + 1)}",
                    getExtension(file.name)
                )
            } ?: file.name
            finalizeOutput(context, file, nameForFile)?.let { results.add(it) }
        }
        tempDir.delete()
        if (results.isNotEmpty()) {
            RecentFilesRepository(context).refresh()
        }
        return results
    }

    // ── Centralized output renaming ──────────────────────────────────────

    /**
     * Renames one or more already-finalized outputs.
     *
     * Centralized on purpose: every tool's result screen uses this instead of each
     * screen implementing its own rename logic. A rename always updates
     *  1. the internal copy in filesDir/output (the canonical record),
     *  2. the published user-visible copy in Documents/PDF Forge,
     *  3. the Recent Files index entry,
     * so the renamed file still opens, shares and deletes.
     *
     * A single output keeps the requested name; a set of outputs is renamed to
     * `base_01`, `base_02`, … preserving order. Collisions never overwrite an
     * existing file — a numeric suffix is appended instead.
     */
    fun renameOutputs(
        context: Context,
        localPaths: List<String>,
        requestedName: String
    ): RenameResult {
        if (localPaths.isEmpty()) {
            return RenameResult(false, emptyList(), "There is no file to rename.")
        }
        val targets = localPaths.map { File(it) }.filter { it.isFile }
        if (targets.isEmpty()) {
            return RenameResult(false, emptyList(), "The generated file is no longer available.")
        }
        if (!isValidFileName(requestedName)) {
            return RenameResult(false, emptyList(), "That file name is not valid. Remove any of \\ / : * ? \" < > | and try again.")
        }

        val repo = RecentFilesRepository(context)
        val renamed = mutableListOf<SavedOutput>()
        for ((index, oldFile) in targets.withIndex()) {
            val extension = getExtension(oldFile.name)
            val requestedForFile = if (targets.size == 1) {
                requestedName
            } else {
                val base = buildOutputName(requestedName, extension).substringBeforeLast('.')
                "${base}_${"%02d".format(index + 1)}$extension"
            }
            val finalName = buildOutputName(requestedForFile, extension)
            val knownUri = repo.uriForName(oldFile.name)
            val result = renameSingleOutput(context, oldFile, finalName, knownUri)
            if (result == null) {
                return RenameResult(false, renamed, "Could not rename \"${oldFile.name}\". Available storage may be full.")
            }
            renamed.add(result)
        }
        repo.refresh()
        return RenameResult(true, renamed, null)
    }

    private fun renameSingleOutput(
        context: Context,
        oldFile: File,
        requestedName: String,
        knownPublicUri: String?
    ): SavedOutput? {
        val repo = RecentFilesRepository(context)
        if (oldFile.name == requestedName) {
            return SavedOutput(
                name = oldFile.name,
                mimeType = getMimeType(oldFile.name),
                size = oldFile.length(),
                localPath = oldFile.absolutePath,
                publicUri = knownPublicUri
            )
        }

        val desiredBase = requestedName.substringBeforeLast('.', requestedName)
        val desiredExt = getExtension(requestedName)
        // uniqueFile guarantees we never clobber an unrelated output.
        val newFile = createOutputFile(context, desiredBase, desiredExt)

        try {
            if (!oldFile.renameTo(newFile)) {
                oldFile.copyTo(newFile, overwrite = false)
                if (!oldFile.delete()) return null
            }
        } catch (e: Exception) {
            return null
        }

        // Move the Recent Files index entry from the old key to the new one, keeping
        // the published URI so open/share/delete keep working after the rename.
        repo.renameEntry(oldFile.name, newFile.name, knownPublicUri)

        val newPublicUri = renamePublishedCopy(context, knownPublicUri, newFile.name) ?: knownPublicUri
        repo.addRecentFile(newFile, newPublicUri, getMimeType(newFile.name))

        return SavedOutput(
            name = newFile.name,
            mimeType = getMimeType(newFile.name),
            size = newFile.length(),
            localPath = newFile.absolutePath,
            publicUri = newPublicUri
        )
    }

    /**
     * Renames the published user-visible copy. MediaStore entries are updated in
     * place; legacy public-dir files are moved on disk. Best effort — the internal
     * copy is always the canonical one, so a failure here is not fatal.
     */
    private fun renamePublishedCopy(context: Context, publicUri: String?, newDisplayName: String): String? {
        if (publicUri.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(publicUri)
            when (uri.scheme) {
                "content" -> {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
                    }
                    context.contentResolver.update(uri, values, null, null)
                    publicUri
                }
                "file" -> {
                    val oldFile = File(uri.path ?: return null)
                    if (!oldFile.exists()) return null
                    val target = File(oldFile.parentFile, sanitizeName(newDisplayName))
                    if (target.exists() && target.absolutePath != oldFile.absolutePath) {
                        // Do not overwrite an unrelated published file.
                        return publicUri
                    }
                    if (oldFile.renameTo(target)) Uri.fromFile(target).toString() else publicUri
                }
                else -> publicUri
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Publishes [sourceFile] to the user-visible Documents/PDF Forge folder.
     *
     * API 29+: MediaStore insert under Documents/PDF Forge (scoped storage compliant).
     * API 26-28: public Documents/PDF Forge directory; falls back to app-external
     * Documents dir when the public dir is not writable.
     *
     * Returns a content:// or file:// URI string, or null on failure.
     */
    fun publishToDocuments(context: Context, sourceFile: File, displayName: String, mimeType: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, sourceFile, displayName, mimeType)
            } else {
                publishToLegacyPublicDir(context, sourceFile, displayName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun publishViaMediaStore(context: Context, sourceFile: File, displayName: String, mimeType: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + PUBLIC_DIR_NAME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null

        var success = false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
                success = true
            }
        } catch (e: IOException) {
            success = false
        }

        if (success) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } else {
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun publishToLegacyPublicDir(context: Context, sourceFile: File, displayName: String): String? {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            PUBLIC_DIR_NAME
        )
        return try {
            if (!publicDir.exists()) publicDir.mkdirs()
            if (publicDir.exists() && publicDir.canWrite()) {
                val dest = File(publicDir, sanitizeName(displayName))
                sourceFile.copyTo(dest, overwrite = true)
                Uri.fromFile(dest).toString()
            } else {
                // Fall back to app-external Documents dir (no permission needed).
                val appExternal = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), PUBLIC_DIR_NAME)
                if (!appExternal.exists()) appExternal.mkdirs()
                val dest = File(appExternal, sanitizeName(displayName))
                sourceFile.copyTo(dest, overwrite = true)
                Uri.fromFile(dest).toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies [sourceFile] to a user-chosen destination via Storage Access Framework
     * (used by the "Save As" action).
     */
    fun saveToUserLocation(context: Context, sourceFile: File, destinationUri: Uri): Boolean {
        return copyFileToUri(context, sourceFile, destinationUri)
    }

    /**
     * Deletes a previously published user-visible copy.
     *
     * - content:// URI  -> ContentResolver.delete (MediaStore / SAF)
     * - file:// URI     -> File.delete()
     *
     * Returns true when the entry was deleted (or there was nothing to delete).
     */
    fun deletePublishedFile(context: Context, publicUri: String?): Boolean {
        if (publicUri.isNullOrBlank()) return true
        return try {
            val uri = Uri.parse(publicUri)
            when (uri.scheme) {
                "content" -> context.contentResolver.delete(uri, null, null) > 0
                "file" -> File(uri.path ?: return false).delete()
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Result of [FileManager.finalizeOutputResult]: either a saved output or the
 * reason finalization failed (naming the real output type).
 */
data class FinalizeResult(
    val output: SavedOutput?,
    val error: String?
)

/** Describes a finalized output file. */
data class SavedOutput(
    val name: String,
    val mimeType: String,
    val size: Long,
    val localPath: String,
    val publicUri: String?
)

/** Result of a centralized rename ([FileManager.renameOutputs]). */
data class RenameResult(
    val success: Boolean,
    val outputs: List<SavedOutput>,
    val message: String?
)