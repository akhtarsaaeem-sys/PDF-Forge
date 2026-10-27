package com.pdforge.app.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.listener.SimpleTextExtractionStrategy
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue
import java.io.File
import java.nio.file.Files

/**
 * Shared helpers for the conversion regression tests.
 *
 * All fixtures live in `app/src/test/resources/fixtures` (small and deterministic;
 * they are never packaged into the production APK) or are generated in-memory with
 * the same libraries the app uses.
 */
object TestSupport {

    fun resourceBytes(name: String): ByteArray {
        val stream = TestSupport::class.java.getResourceAsStream(name)
            ?: error("missing test resource $name")
        return stream.use { it.readBytes() }
    }

    fun resourceText(name: String): String =
        resourceBytes(name).toString(Charsets.UTF_8)

    fun copyResourceToFile(name: String, target: File) {
        target.parentFile?.mkdirs()
        target.writeBytes(resourceBytes(name))
    }

    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    // ── PDF fixtures ─────────────────────────────────────────────────────────

    fun createTextPdf(path: String, pageTexts: List<String>) {
        val doc = PdfDocument(PdfWriter(path))
        val layout = Document(doc, PageSize.A4)
        pageTexts.forEachIndexed { index, text ->
            if (index > 0) layout.add(AreaBreak())
            if (text.isNotEmpty()) layout.add(Paragraph(text))
        }
        layout.close()
    }

    fun createTablePdf(path: String, rows: List<List<String>>) {
        val doc = PdfDocument(PdfWriter(path))
        val layout = Document(doc, PageSize.A4)
        val columns = rows.maxOfOrNull { it.size } ?: 1
        val table = Table(UnitValue.createPercentArray(FloatArray(columns) { 1f })).useAllAvailableWidth()
        rows.forEach { row ->
            for (column in 0 until columns) {
                table.addCell(Cell().add(Paragraph(row.getOrNull(column) ?: "").setMargin(0f).setFontSize(11f)))
            }
        }
        layout.add(table)
        layout.close()
    }

    fun pdfPageCount(path: String): Int {
        val doc = PdfDocument(PdfReader(path))
        return try {
            doc.numberOfPages
        } finally {
            doc.close()
        }
    }

    fun pdfPageSizes(path: String): List<Pair<Float, Float>> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            (1..doc.numberOfPages).map { i ->
                val size = doc.getPage(i).pageSize
                size.width to size.height
            }
        } finally {
            doc.close()
        }
    }

    fun pdfText(path: String): String {
        val doc = PdfDocument(PdfReader(path))
        return try {
            val builder = StringBuilder()
            for (i in 1..doc.numberOfPages) {
                val strategy = SimpleTextExtractionStrategy()
                PdfCanvasProcessor(strategy).processPageContent(doc.getPage(i))
                builder.append(strategy.resultantText).append('\n')
            }
            builder.toString()
        } finally {
            doc.close()
        }
    }

    fun pdfTextOfPage(path: String, pageNumber: Int): String {
        val doc = PdfDocument(PdfReader(path))
        return try {
            val strategy = SimpleTextExtractionStrategy()
            PdfCanvasProcessor(strategy).processPageContent(doc.getPage(pageNumber))
            strategy.resultantText
        } finally {
            doc.close()
        }
    }
}
