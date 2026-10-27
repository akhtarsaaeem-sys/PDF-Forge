package com.pdforge.app.converter

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import java.io.File

/**
 * HTML → PDF.
 *
 * This is intentionally a LIMITED converter, not a browser engine: it supports
 * headings, paragraphs, bold/italic runs, unordered/ordered list items, `<br>`,
 * simple `<table>` structures and local `<img>` files, plus basic CSS-induced
 * block separation. Everything is offline and uses the project's existing iText.
 *
 * It replaces the previous regex-only renderer (which dropped tables completely
 * and could not be unit-tested because it drew onto an Android canvas) with a
 * parse step that produces structured blocks and an iText render step. Unknown
 * tags are treated as text containers, so their text is still preserved.
 *
 * Returns the number of PDF pages produced.
 */
internal fun convertHtmlToPdf(inputPath: String, outputPath: String): Int {
    val file = File(inputPath)
    if (!file.exists() || file.length() <= 0L) {
        throw IllegalArgumentException("The selected HTML file is empty or could not be read.")
    }
    val html = file.readText()
    val blocks = parseHtmlBlocks(html, file.parentFile)

    val pdfDoc = ITextPdfDocument(PdfWriter(outputPath))
    try {
        val fonts = PdfFonts(pdfDoc)
        val layout = Document(pdfDoc, PageSize.A4)
        layout.setMargins(50f, 50f, 50f, 50f)
        layout.setFont(fonts.regular)

        for (block in blocks) {
            when (block) {
                is HtmlBlock.Heading -> layout.add(
                    Paragraph(block.text)
                        .setFont(fonts.bold)
                        .setFontSize(headingSize(block.level))
                        .setMarginTop(8f)
                        .setMarginBottom(4f)
                )
                is HtmlBlock.ListItem -> layout.add(
                    Paragraph("• ${block.text}").setMarginLeft(14f).setMarginBottom(2f)
                )
                is HtmlBlock.Paragraph -> {
                    val paragraph = Paragraph()
                    paragraph.setTextAlignment(TextAlignment.LEFT)
                    paragraph.setMarginTop(2f)
                    paragraph.setMarginBottom(4f)
                    block.runs.forEach { run ->
                        paragraph.add(
                            Text(run.text)
                                .setFont(fonts.forStyle(run.bold, run.italic))
                                .setFontSize(11f)
                        )
                    }
                    if (block.runs.any { it.text.isNotBlank() }) layout.add(paragraph)
                }
                is HtmlBlock.TableBlock -> layout.add(renderTable(block.rows, fonts))
                is HtmlBlock.ImageBlock -> {
                    block.image?.let { layout.add(it.setAutoScale(true).setMarginTop(4f).setMarginBottom(4f)) }
                    if (block.image == null && block.altText.isNotBlank()) {
                        layout.add(Paragraph("[Image: ${block.altText}]").setFont(fonts.italic))
                    }
                }
            }
        }
        layout.close()
    } catch (e: Throwable) {
        try { File(outputPath).delete() } catch (_: Exception) {}
        throw e
    }

    val check = ITextPdfDocument(PdfReader(File(outputPath).absolutePath))
    return try {
        check.numberOfPages
    } finally {
        check.close()
    }
}

private fun headingSize(level: Int): Float = when (level) {
    1 -> 22f
    2 -> 19f
    3 -> 16f
    4 -> 14f
    else -> 12.5f
}

private fun renderTable(rows: List<List<String>>, fonts: PdfFonts): Table {
    val columns = rows.maxOfOrNull { it.size } ?: 0
    if (columns == 0) return Table(UnitValue.createPercentArray(floatArrayOf(1f))).useAllAvailableWidth()
    val table = Table(UnitValue.createPercentArray(FloatArray(columns) { 1f })).useAllAvailableWidth()
    table.setMarginTop(4f)
    table.setMarginBottom(6f)
    rows.forEachIndexed { rowIndex, row ->
        val isHeader = rowIndex == 0
        for (column in 0 until columns) {
            val value = row.getOrNull(column) ?: ""
            table.addCell(
                Cell().add(
                    Paragraph(value)
                        .setFont(if (isHeader) fonts.bold else fonts.regular)
                        .setFontSize(10f)
                        .setMargin(0f)
                )
            )
        }
    }
    return table
}

// ── HTML parsing ─────────────────────────────────────────────────────────────

internal data class HtmlRun(val text: String, val bold: Boolean, val italic: Boolean)

internal sealed interface HtmlBlock {
    data class Heading(val level: Int, val text: String) : HtmlBlock
    data class Paragraph(val runs: List<HtmlRun>) : HtmlBlock
    data class ListItem(val text: String) : HtmlBlock
    data class TableBlock(val rows: List<List<String>>) : HtmlBlock
    data class ImageBlock(val image: Image?, val altText: String) : HtmlBlock
}

private val HEAD_REGEX = Regex("(?is)<head[^>]*>.*?</head>")
private val STYLE_REGEX = Regex("(?is)<style[^>]*>.*?</style>")
private val SCRIPT_REGEX = Regex("(?is)<script[^>]*>.*?</script>")
private val TABLE_REGEX = Regex("(?is)<table\\b[^>]*>(.*?)</table>")
private val ROW_REGEX = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>")
private val CELL_REGEX = Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>")
private val BLOCK_SPLIT_REGEX = Regex("(?is)<br\\b[^>]*>|</p>|</div>|</h[1-6]>|</li>|</tr>|</table>|<hr\\b[^>]*>")
private val HEADING_OPEN_REGEX = Regex("(?is)<h([1-6])\\b[^>]*>")
private val LIST_ITEM_REGEX = Regex("(?is)<li\\b[^>]*>")
private val IMG_REGEX = Regex("(?is)<img\\b([^>]*)>")
private val ATTRIBUTE_REGEX = Regex("(?is)(\\w+)\\s*=\\s*\"([^\"]*)\"|(\\w+)\\s*=\\s*'([^']*)'")
private val PLACEHOLDER_REGEX = Regex("\u0000T(\\d+)\u0000")

/**
 * Parses HTML into ordered blocks. Table contents are extracted first and left in
 * place as placeholders so the table keeps its position relative to surrounding
 * text.
 */
internal fun parseHtmlBlocks(html: String, baseDir: File?): List<HtmlBlock> {
    val cleaned = html
        .replace(HEAD_REGEX, "")
        .replace(STYLE_REGEX, "")
        .replace(SCRIPT_REGEX, "")

    val tables = mutableListOf<List<List<String>>>()
    val withPlaceholders = TABLE_REGEX.replace(cleaned) { match ->
        tables.add(parseHtmlTable(match.groupValues[1]))
        "\u0000T${tables.size - 1}\u0000"
    }

    val blocks = mutableListOf<HtmlBlock>()
    val segments = withPlaceholders.split(BLOCK_SPLIT_REGEX)
    for (segment in segments) {
        // Emit any tables referenced in this segment, in order.
        val placeholders = PLACEHOLDER_REGEX.findAll(segment).toList()
        for (placeholder in placeholders) {
            val index = placeholder.groupValues[1].toIntOrNull() ?: continue
            tables.getOrNull(index)?.let { blocks.add(HtmlBlock.TableBlock(it)) }
        }
        val withoutTables = PLACEHOLDER_REGEX.replace(segment, " ")

        // Images (local files only).
        for (img in IMG_REGEX.findAll(withoutTables)) {
            val attrs = parseAttributes(img.groupValues[1])
            val src = attrs["src"]
            val alt = attrs["alt"] ?: ""
            val image = loadLocalImage(src, baseDir)
            blocks.add(HtmlBlock.ImageBlock(image, alt))
        }
        val withoutImages = IMG_REGEX.replace(withoutTables, " ")

        val headingMatch = HEADING_OPEN_REGEX.find(withoutImages)
        val isListItem = LIST_ITEM_REGEX.containsMatchIn(withoutImages)
        val runs = parseInlineRuns(withoutImages)
        val plainText = runs.joinToString("") { it.text }.trim()
        val hasContent = plainText.isNotBlank() || runs.any { it.text.isNotBlank() }

        when {
            !hasContent -> {}
            headingMatch != null -> {
                val level = headingMatch.groupValues[1].toIntOrNull() ?: 1
                blocks.add(HtmlBlock.Heading(level, plainText))
            }
            isListItem && placeholders.isEmpty() -> blocks.add(HtmlBlock.ListItem(plainText))
            else -> blocks.add(HtmlBlock.Paragraph(runs.filter { it.text.isNotEmpty() }))
        }
    }
    return blocks
}

private fun parseHtmlTable(inner: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    for (rowMatch in ROW_REGEX.findAll(inner)) {
        val cells = mutableListOf<String>()
        for (cellMatch in CELL_REGEX.findAll(rowMatch.groupValues[1])) {
            cells.add(htmlToPlainText(cellMatch.groupValues[1]))
        }
        if (cells.isNotEmpty()) rows.add(cells)
    }
    return rows
}

private fun parseInlineRuns(html: String): List<HtmlRun> {
    val runs = mutableListOf<HtmlRun>()
    var bold = false
    var italic = false
    val tokens = html.split(Regex("(?is)(<b\\b[^>]*>|</b>|<strong\\b[^>]*>|</strong>|<i\\b[^>]*>|</i>|<em\\b[^>]*>|</em>)"))
    for (token in tokens) {
        val lower = token.lowercase()
        when {
            lower.startsWith("<b") || lower.startsWith("<strong") -> bold = true
            lower == "</b>" || lower == "</strong>" -> bold = false
            lower.startsWith("<i") || lower.startsWith("<em") -> italic = true
            lower == "</i>" || lower == "</em>" -> italic = false
            else -> {
                val text = htmlToPlainText(token)
                if (text.isNotEmpty()) runs.add(HtmlRun(text, bold, italic))
            }
        }
    }
    return runs
}

/** Strips tags and decodes the common HTML entities. */
internal fun htmlToPlainText(html: String): String {
    return html
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun parseAttributes(raw: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (match in ATTRIBUTE_REGEX.findAll(raw)) {
        val key = (match.groupValues[1].ifBlank { match.groupValues[3] }).lowercase()
        val value = match.groupValues[2].ifBlank { match.groupValues[4] }
        if (key.isNotEmpty()) result[key] = value
    }
    return result
}

private fun loadLocalImage(src: String?, baseDir: File?): Image? {
    if (src.isNullOrBlank() || baseDir == null) return null
    if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:")) return null
    return try {
        val candidate = File(baseDir, src)
        if (!candidate.isFile) return null
        Image(com.itextpdf.io.image.ImageDataFactory.create(candidate.absolutePath))
    } catch (e: Exception) {
        null
    }
}
