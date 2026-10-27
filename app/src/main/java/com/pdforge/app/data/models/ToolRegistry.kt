package com.pdforge.app.data.models

import com.pdforge.app.ui.theme.*

object ToolRegistry {

    val categories = listOf(
        ToolCategory(
            id = "page_management",
            name = "Page Management",
            description = "Merge, split, extract, remove, reorder, rotate and scan PDF pages.",
            icon = "menu_book",
            color = CategoryPageManagement,
            tools = listOf(
                Tool("merge_pdf", "Merge PDF", "Combine multiple PDF files into one document.", "merge", listOf("merge", "combine", "join", "pdf"), ToolType.MULTI_FILE),
                Tool("split_pdf", "Split PDF", "Divide a PDF into multiple documents or individual pages.", "split", listOf("split", "divide", "separate", "pdf"), ToolType.PAGE_SELECTION),
                Tool("remove_pages", "Remove Pages", "Delete selected pages from a PDF.", "delete", listOf("remove", "delete", "pages", "pdf"), ToolType.PAGE_SELECTION),
                Tool("extract_pages", "Extract Pages", "Create a new PDF from selected pages.", "extract", listOf("extract", "pages", "pdf"), ToolType.PAGE_SELECTION),
                Tool("organize_pdf", "Organize PDF", "Reorder, rotate or delete pages in a PDF.", "organize", listOf("organize", "reorder", "rotate", "pages", "pdf"), ToolType.PAGE_SELECTION),
                Tool("scan_to_pdf", "Scan to PDF", "Scan physical documents and save them as PDF files.", "scan", listOf("scan", "camera", "document", "pdf"), ToolType.CAMERA)
            )
        ),
        ToolCategory(
            id = "optimization",
            name = "Optimization",
            description = "Improve PDF size, readability and recoverability.",
            icon = "speed",
            color = CategoryOptimization,
            tools = listOf(
                Tool("compress_pdf", "Compress PDF", "Reduce PDF file size while balancing quality.", "compress", listOf("compress", "reduce", "size", "pdf"), ToolType.SINGLE_FILE),
                Tool("repair_pdf", "Repair PDF", "Recover readable pages from damaged PDF files.", "repair", listOf("repair", "recover", "fix", "damaged", "pdf"), ToolType.SINGLE_FILE),
                Tool("ocr_pdf", "OCR PDF", "Make scanned text searchable and selectable.", "ocr", listOf("ocr", "text", "recognition", "scanned", "pdf"), ToolType.SINGLE_FILE)
            )
        ),
        ToolCategory(
            id = "convert_to",
            name = "Convert to PDF",
            description = "Convert images, documents and web pages into PDFs.",
            icon = "picture_as_pdf",
            color = CategoryConvertTo,
            tools = listOf(
                Tool("jpg_to_pdf", "JPG to PDF", "Convert one or more images into a PDF.", "image", listOf("jpg", "image", "photo", "picture", "pdf"), ToolType.MULTI_FILE),
                Tool("word_to_pdf", "Word to PDF", "Convert Word documents into PDF files.", "document", listOf("word", "doc", "docx", "pdf"), ToolType.SINGLE_FILE),
                Tool("ppt_to_pdf", "PowerPoint to PDF", "Convert PowerPoint presentations into PDF files.", "slideshow", listOf("powerpoint", "ppt", "pptx", "pdf"), ToolType.SINGLE_FILE),
                Tool("excel_to_pdf", "Excel to PDF", "Convert spreadsheets into PDF files.", "table_chart", listOf("excel", "xls", "xlsx", "spreadsheet", "pdf"), ToolType.SINGLE_FILE),
                Tool("html_to_pdf", "HTML to PDF", "Convert HTML documents into PDF files.", "code", listOf("html", "web", "pdf"), ToolType.SINGLE_FILE)
            )
        ),
        ToolCategory(
            id = "convert_from",
            name = "Convert from PDF",
            description = "Convert PDFs into other document formats.",
            icon = "description",
            color = CategoryConvertFrom,
            tools = listOf(
                Tool("pdf_to_jpg", "PDF to JPG", "Convert PDF pages into JPG images.", "image", listOf("pdf", "jpg", "image", "picture"), ToolType.PAGE_SELECTION),
                Tool("pdf_to_word", "PDF to Word", "Convert PDF content into an editable Word document.", "document", listOf("pdf", "word", "doc", "docx"), ToolType.SINGLE_FILE),
                Tool("pdf_to_ppt", "PDF to PowerPoint", "Convert PDF pages into a PowerPoint presentation.", "slideshow", listOf("pdf", "powerpoint", "ppt", "pptx"), ToolType.SINGLE_FILE),
                Tool("pdf_to_excel", "PDF to Excel", "Extract tables from PDFs into spreadsheet cells.", "table_chart", listOf("pdf", "excel", "xls", "xlsx", "table"), ToolType.SINGLE_FILE),
                Tool("pdf_to_pdfa", "PDF to PDF/A", "Create a PDF/A-compatible archival copy. Not certified ISO PDF/A-2b compliance.", "archive", listOf("pdf", "pdfa", "archive"), ToolType.SINGLE_FILE)
            )
        ),
        ToolCategory(
            id = "edit_modify",
            name = "Edit & Modify",
            description = "Add text, annotations, watermarks and more to PDFs.",
            icon = "edit",
            color = CategoryEditModify,
            tools = listOf(
                Tool("edit_pdf", "Edit PDF", "Add text, drawings, annotations and images to a PDF.", "edit", listOf("edit", "text", "draw", "annotate", "pdf"), ToolType.EDITING),
                Tool("pdf_forms", "PDF Forms", "Fill and save interactive PDF forms.", "check_box", listOf("form", "fill", "checkbox", "pdf"), ToolType.EDITING),
                Tool("rotate_pdf", "Rotate PDF", "Rotate selected PDF pages.", "rotate_right", listOf("rotate", "pages", "pdf"), ToolType.PAGE_SELECTION),
                Tool("page_numbers", "Add Page Numbers", "Add page numbers to a PDF.", "pin", listOf("page", "numbers", "add", "pdf"), ToolType.PAGE_SELECTION),
                Tool("watermark", "Add Watermark", "Add a text or image watermark to PDF pages.", "water_drop", listOf("watermark", "text", "image", "pdf"), ToolType.PAGE_SELECTION),
                Tool("crop_pdf", "Crop PDF", "Trim unwanted margins from PDF pages.", "crop", listOf("crop", "trim", "margin", "pdf"), ToolType.PAGE_SELECTION)
            )
        ),
        ToolCategory(
            id = "security",
            name = "Security & Privacy",
            description = "Sign, unlock, protect, redact and compare PDFs.",
            icon = "security",
            color = CategorySecurity,
            tools = listOf(
                Tool("sign_pdf", "Sign PDF", "Add a visual signature to a PDF (an annotation, not a cryptographic signature).", "draw", listOf("sign", "signature", "pdf"), ToolType.EDITING),
                Tool("unlock_pdf", "Unlock PDF", "Remove password protection from a PDF when authorized.", "lock_open", listOf("unlock", "password", "protect", "pdf"), ToolType.SINGLE_FILE),
                Tool("protect_pdf", "Protect PDF", "Protect a PDF with a password and encryption.", "lock", listOf("protect", "password", "encrypt", "pdf"), ToolType.SINGLE_FILE),
                Tool("redact_pdf", "Redact PDF", "Permanently remove sensitive content from selected areas.", "delete_sweep", listOf("redact", "remove", "sensitive", "pdf"), ToolType.EDITING),
                Tool("compare_pdf", "Compare PDF", "Compare two PDF files and identify their differences.", "compare", listOf("compare", "difference", "pdf"), ToolType.MULTI_FILE)
            )
        )
    )

    fun getAllTools(): List<Tool> = categories.flatMap { it.tools }

    fun searchTools(query: String): List<Tool> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return getAllTools().filter { tool ->
            tool.name.lowercase().contains(lowerQuery) ||
                    tool.description.lowercase().contains(lowerQuery) ||
                    tool.keywords.any { it.contains(lowerQuery) }
        }
    }

    fun getToolById(id: String): Tool? = getAllTools().find { it.id == id }

    fun getCategoryById(id: String): ToolCategory? = categories.find { it.id == id }
}
