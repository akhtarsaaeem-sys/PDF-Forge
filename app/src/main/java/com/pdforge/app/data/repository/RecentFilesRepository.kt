package com.pdforge.app.data.repository

import android.content.Context
import com.pdforge.app.data.models.RecentFile
import com.pdforge.app.storage.FileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tracks generated output files.
 *
 * The canonical record is the internal copy in filesDir/output (stable, survives
 * cache clears). A sidecar JSON index stores the published content:// (or file://)
 * URI for each output so Recent Files can reopen and delete the user-visible copy.
 */
class RecentFilesRepository(private val context: Context) {

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class IndexEntry(val uri: String = "", val mime: String = "application/pdf")

    @Serializable
    private data class FileIndex(val files: Map<String, IndexEntry> = emptyMap())

    private val indexFile: File
        get() = File(FileManager.getOutputDir(context), ".index.json")

    init {
        loadRecentFiles()
    }

    private fun readIndex(): FileIndex {
        return try {
            if (indexFile.exists()) {
                json.decodeFromString<FileIndex>(indexFile.readText())
            } else {
                FileIndex()
            }
        } catch (e: Exception) {
            FileIndex()
        }
    }

    private fun writeIndex(index: FileIndex) {
        try {
            indexFile.writeText(json.encodeToString(FileIndex.serializer(), index))
        } catch (_: Exception) {}
    }

    fun loadRecentFiles() {
        val outputDir = FileManager.getOutputDir(context)
        val index = readIndex()
        if (outputDir.exists()) {
            val files = outputDir.listFiles()?.filter {
                it.isFile && !it.name.startsWith(".") && it.length() > 0
            }?.sortedByDescending { it.lastModified() }?.take(30) ?: emptyList()

            _recentFiles.value = files.map { file ->
                val entry = index.files[file.name]
                RecentFile(
                    id = file.absolutePath.hashCode().toString(),
                    name = file.name,
                    path = file.absolutePath,
                    type = FileManager.getMimeType(file.name),
                    size = file.length(),
                    lastModified = file.lastModified(),
                    uri = entry?.uri ?: ""
                )
            }
        } else {
            _recentFiles.value = emptyList()
        }
    }

    /** Records a finalized output in Recent Files. */
    fun addRecentFile(file: File, uri: String? = null, mimeType: String? = null) {
        val index = readIndex().toMutableMap()
        index[file.name] = IndexEntry(
            uri = uri ?: "",
            mime = mimeType ?: FileManager.getMimeType(file.name)
        )
        writeIndex(FileIndex(index))
        loadRecentFiles()
    }

    /** Records multiple finalized outputs. */
    fun addRecentFiles(files: List<File>, uris: List<String?> = emptyList(), mimeType: String? = null) {
        val index = readIndex().toMutableMap()
        files.forEachIndexed { i, file ->
            index[file.name] = IndexEntry(
                uri = uris.getOrNull(i) ?: "",
                mime = mimeType ?: FileManager.getMimeType(file.name)
            )
        }
        writeIndex(FileIndex(index))
        loadRecentFiles()
    }

    /**
     * Moves an index entry from [oldName] to [newName] after a centralized rename,
     * keeping the published URI so the renamed file still opens, shares and deletes.
     * When the new name is already indexed, the old entry is simply removed.
     */
    fun renameEntry(oldName: String, newName: String, fallbackUri: String? = null) {
        val index = readIndex().toMutableMap()
        val entry = index.remove(oldName)
        if (entry != null) {
            index[newName] = entry
        } else if (fallbackUri != null) {
            index[newName] = IndexEntry(uri = fallbackUri, mime = FileManager.getMimeType(newName))
        }
        writeIndex(FileIndex(index))
        loadRecentFiles()
    }

    /** The published user-visible URI recorded for an internal output file, if any. */
    fun uriForName(name: String): String? =
        readIndex().files[name]?.uri?.takeIf { it.isNotBlank() }

    fun refresh() {
        loadRecentFiles()
    }

    /**
     * Deletes a recent file: the internal copy in filesDir/output plus the
     * published user-visible copy (MediaStore / legacy public dir) when known.
     *
     * Returns true when the internal file was deleted (or was already absent).
     * When deletion fails, the index entry is restored and the file stays listed
     * so the UI never shows a deleted entry while the file still exists.
     */
    fun deleteFile(path: String): Boolean {
        val file = File(path)
        val index = readIndex().toMutableMap()
        val entry = index.remove(file.name)
        writeIndex(FileIndex(index))

        val fileDeleted = if (file.exists()) file.delete() else true
        if (fileDeleted) {
            // Best effort: the user-visible copy may already be gone (e.g. the
            // user removed it from the Documents app) — that is not a failure.
            FileManager.deletePublishedFile(context, entry?.uri)
        } else {
            // Could not delete — restore the index entry so the file remains listed.
            val restored = readIndex().toMutableMap()
            if (entry != null) restored[file.name] = entry
            writeIndex(FileIndex(restored))
        }
        loadRecentFiles()
        return fileDeleted
    }

    private fun FileIndex.toMutableMap(): MutableMap<String, IndexEntry> = files.toMutableMap()
}