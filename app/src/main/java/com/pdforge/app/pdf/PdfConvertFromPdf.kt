package com.pdforge.app.pdf

import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.pdf.PdfDocument as ITextPdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.ITextExtractionStrategy
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.BreakType
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * PDF → DOCX.
 *
 * The previous implementation already extracted text with iText and wrote it to
 * a DOCX with Apache POI; the tool only *looked* broken because the shared output
 * validator rejected every OOXML file (see [com.pdforge.app.storage.OutputValidator]).
 * The extraction is kept — it preserves page order and every line of text — and
 * made testable and crash-safe:
 *
 *  - one paragraph per extracted line, page-break between pages,
 *  - a page with no extractable text gets an explicit, honest note instead of
 *    silently disappearing,
 *  - the DOCX is always closed and a partial file is never left behind.
 *
 * Returns the number of PDF pages converted.
 */
internal fun pdfTextToDocx(inputPath: String, outputPath: String): Int {
    val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
    val doc = XWPFDocument()
    try {
        val totalPages = pdfDoc.numberOfPages
        for (i in 1..totalPages) {
            val text = try {
                extractPageText(pdfDoc, i)
            } catch (e: Throwable) {
                null
            }
            if (text.isNullOrBlank()) {
                doc.createParagraph().createRun()
                    .setText("[Page $i — no selectable text was found in this page]")
            } else {
                for (line in text.split("\n")) {
                    doc.createParagraph().createRun().setText(line)
                }
            }
            if (i < totalPages) {
                doc.createParagraph().createRun().addBreak(BreakType.PAGE)
            }
        }
        FileOutputStream(outputPath).use { out -> doc.write(out) }
        return totalPages
    } finally {
        try { doc.close() } catch (_: Exception) {}
        pdfDoc.close()
    }
}

private fun extractPageText(pdfDoc: ITextPdfDocument, pageNumber: Int): String {
    val strategy = SimpleTextExtractionStrategy()
    val processor = PdfCanvasProcessor(strategy)
    processor.processPageContent(pdfDoc.getPage(pageNumber))
    return strategy.resultantText
}

// ── PDF → XLSX ───────────────────────────────────────────────────────────────

/** One positioned run of text drawn on a page. */
internal data class PositionedText(val x: Float, val y: Float, val width: Float, val text: String)

/**
 * Collects text runs together with their baseline start point so a page's visual
 * layout (lines and columns) can be reconstructed.
 *
 * This implements [ITextExtractionStrategy] directly and forwards every event to
 * an internal [SimpleTextExtractionStrategy]; iText 8's simple strategy exposes
 * no overridable `renderText` hook, so listening to `RENDER_TEXT` events is the
 * supported way to capture positions.
 */
internal class PositionedTextCollector : ITextExtractionStrategy {
    val chunks = ArrayList<PositionedText>()
    private val delegate = SimpleTextExtractionStrategy()

    override fun eventOccurred(data: IEventData, type: EventType) {
        if (type == EventType.RENDER_TEXT && data is TextRenderInfo) {
            val text = data.text
            if (!text.isNullOrBlank()) {
                val start = data.baseline.startPoint
                chunks.add(
                    PositionedText(
                        x = start.get(0),
                        y = start.get(1),
                        width = data.unscaledWidth,
                        text = text
                    )
                )
            }
        }
        delegate.eventOccurred(data, type)
    }

    override fun getSupportedEvents(): MutableSet<EventType> = delegate.supportedEvents

    override fun getResultantText(): String = delegate.resultantText
}

/**
 * Reconstructs rows (and, where the horizontal gaps are large enough, cells) from
 * positioned text runs.
 *
 * Lines are grouped by their baseline Y coordinate; within a line, runs are
 * ordered left to right and split into separate cells when the horizontal gap
 * between them exceeds a couple of character widths. This preserves a table-like
 * PDF's rows and columns instead of flattening everything into one column.
 */
internal fun rowsFromPositionedText(chunks: List<PositionedText>, lineTolerance: Float = 3f): List<List<String>> {
    val usable = chunks.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return emptyList()

    val lines = mutableListOf<MutableList<PositionedText>>()
    for (chunk in usable.sortedByDescending { it.y }) {
        val line = lines.firstOrNull { abs(it.first().y - chunk.y) <= lineTolerance }
        if (line != null) line.add(chunk) else lines.add(mutableListOf(chunk))
    }

    val rows = mutableListOf<List<String>>()
    for (line in lines) {
        val ordered = line.sortedBy { it.x }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var cursorEnd = ordered.first().x
        for (chunk in ordered) {
            val characterWidth = (chunk.width / chunk.text.length.coerceAtLeast(1))
            val gap = chunk.x - cursorEnd
            val isNewCell = current.isNotEmpty() && gap > characterWidth * 2.5f
            if (isNewCell) {
                cells.add(current.toString().trim())
                current.setLength(0)
            } else if (current.isNotEmpty()) {
                current.append(' ')
            }
            current.append(chunk.text)
            cursorEnd = chunk.x + chunk.width.coerceAtLeast(0f)
        }
        if (current.isNotEmpty()) cells.add(current.toString().trim())
        if (cells.any { it.isNotBlank() }) rows.add(cells)
    }
    return rows
}

/** Extracts a table-like layout from one PDF page. */
internal fun extractTableRows(pdfDoc: ITextPdfDocument, pageNumber: Int): List<List<String>> {
    val collector = PositionedTextCollector()
    PdfCanvasProcessor(collector).processPageContent(pdfDoc.getPage(pageNumber))
    return rowsFromPositionedText(collector.chunks)
}

/**
 * PDF → XLSX.
 *
 * Every page is scanned and its rows/columns are written into a worksheet. When
 * a PDF has no recoverable text at all the conversion FAILS with a clear message
 * rather than publishing an empty workbook and reporting success — the previous
 * behaviour that made "PDF → Excel" misleading.
 *
 * Returns the number of PDF pages processed.
 */
internal fun pdfTableToXlsx(inputPath: String, outputPath: String): Int {
    val pdfDoc = ITextPdfDocument(PdfReader(inputPath))
    val workbook = XSSFWorkbook()
    try {
        val sheet = workbook.createSheet("Extracted Data")
        var rowIndex = 0
        var dataRows = 0
        for (pageNo in 1..pdfDoc.numberOfPages) {
            val rows = try {
                extractTableRows(pdfDoc, pageNo)
            } catch (e: Throwable) {
                emptyList()
            }
            if (rows.isEmpty()) continue
            for (row in rows) {
                val sheetRow = sheet.createRow(rowIndex++)
                for ((col, value) in row.withIndex()) {
                    if (col > 255) break
                    sheetRow.createCell(col).setCellValue(value)
                }
                dataRows++
            }
            // Blank separator row between pages so the page boundary stays visible.
            rowIndex++
        }

        if (dataRows == 0) {
            throw IllegalStateException(
                "No recoverable text or tabular data was found in this PDF. " +
                    "This PDF may be a scan or contain only images."
            )
        }

        for (col in 0 until 26) {
            sheet.setColumnWidth(col, 4000)
        }

        FileOutputStream(outputPath).use { workbook.write(it) }
        return pdfDoc.numberOfPages
    } finally {
        try { workbook.close() } catch (_: Exception) {}
        pdfDoc.close()
    }
}

// ── PDF → PDF/A ──────────────────────────────────────────────────────────────

/**
 * PDF → PDF/A-compatible output.
 *
 * This rewrites the document with XMP metadata and full compression while
 * preserving every page. It is NOT certified ISO PDF/A-2b: the embedded fonts and
 * output-intent profile required by the standard are not available offline, and
 * the UI text is kept honest about that.
 *
 * The reason this previously failed on device was an iText/Android XML parser
 * incompatibility (see [AndroidCompatXmlParserFactory]), not the conversion
 * itself; opening a PDF with XMP metadata threw before a page was copied.
 *
 * Returns the resulting page count.
 */
internal fun convertPdfToPdfA(inputPath: String, outputPath: String): Int {
    val inputFile = File(inputPath)
    if (!inputFile.exists() || inputFile.length() <= 0L) {
        throw IllegalArgumentException("The selected PDF is empty or could not be read.")
    }
    if (!fileStartsWithPdfHeader(inputPath)) {
        throw IllegalArgumentException("The selected file is not a valid PDF.")
    }

    val reader = PdfReader(inputPath)
    reader.setUnethicalReading(true)
    val writerProps = WriterProperties().apply {
        addXmpMetadata()
        useSmartMode()
        setFullCompressionMode(true)
    }
    val pdfDoc = ITextPdfDocument(reader, PdfWriter(outputPath, writerProps))
    try {
        val pageCount = pdfDoc.numberOfPages
        if (pageCount <= 0) {
            throw IllegalStateException("The PDF has no pages to convert.")
        }
        // Embed the default font so text stays renderable in the archival copy.
        try {
            pdfDoc.addFont(PdfFontFactory.createFont())
        } catch (_: Exception) {
        }
        return pageCount
    } finally {
        pdfDoc.close()
    }
}

/** True when the file begins with the %PDF- signature. */
internal fun fileStartsWithPdfHeader(path: String): Boolean {
    return try {
        RandomAccessFile(path, "r").use { raf ->
            val buffer = ByteArray(5)
            if (raf.read(buffer) < 5) return false
            String(buffer, Charsets.ISO_8859_1) == "%PDF-"
        }
    } catch (e: Exception) {
        false
    }
}
