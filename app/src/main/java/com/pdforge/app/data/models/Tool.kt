package com.pdforge.app.data.models

import androidx.compose.ui.graphics.Color

data class ToolCategory(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val color: Color,
    val tools: List<Tool>
)

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val keywords: List<String> = emptyList(),
    val type: ToolType = ToolType.SINGLE_FILE
)

enum class ToolType {
    SINGLE_FILE,
    MULTI_FILE,
    PAGE_SELECTION,
    CONVERSION,
    EDITING,
    CAMERA
}

data class RecentFile(
    val id: String,
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val lastModified: Long,
    val uri: String = ""
)

data class ProcessingResult(
    val success: Boolean,
    val outputPath: String? = null,
    val originalSize: Long = 0,
    val outputSize: Long = 0,
    val message: String? = null,
    val pageCount: Int = 0,
    val fileCount: Int = 0,
    val outputUri: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    /**
     * Every finalized internal output file (canonical copies in filesDir/output).
     * A single-output tool has one entry; a multi-output tool (Split, PDF → JPG)
     * has one per generated file. The shared rename action renames this whole set.
     */
    val outputPaths: List<String> = emptyList()
)

data class PdfPage(
    val number: Int,
    val isSelected: Boolean = false,
    val rotation: Int = 0
)
