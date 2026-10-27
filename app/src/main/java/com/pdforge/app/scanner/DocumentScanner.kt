package com.pdforge.app.scanner

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.pdforge.app.data.models.ProcessingResult
import java.io.File
import java.io.FileOutputStream

class DocumentScanner(private val context: Context) {

    data class ScannedPage(
        val bitmap: Bitmap,
        val rotation: Int = 0,
        var isSelected: Boolean = true
    )

    private val scannedPages = mutableListOf<ScannedPage>()

    fun addPage(bitmap: Bitmap) {
        scannedPages.add(ScannedPage(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)))
    }

    fun removePage(index: Int) {
        if (index in scannedPages.indices) {
            scannedPages[index].bitmap.recycle()
            scannedPages.removeAt(index)
        }
    }

    fun rotatePage(index: Int, degrees: Int = 90) {
        if (index in scannedPages.indices) {
            val page = scannedPages[index]
            val matrix = Matrix()
            matrix.postRotate(degrees.toFloat())
            val rotated = Bitmap.createBitmap(
                page.bitmap, 0, 0,
                page.bitmap.width, page.bitmap.height,
                matrix, true
            )
            page.bitmap.recycle()
            scannedPages[index] = page.copy(bitmap = rotated, rotation = (page.rotation + degrees) % 360)
        }
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        if (fromIndex in scannedPages.indices && toIndex in scannedPages.indices) {
            val page = scannedPages.removeAt(fromIndex)
            scannedPages.add(toIndex, page)
        }
    }

    fun getPageCount(): Int = scannedPages.size

    fun getPages(): List<ScannedPage> = scannedPages.toList()

    fun clearPages() {
        scannedPages.forEach { it.bitmap.recycle() }
        scannedPages.clear()
    }

    fun enhanceImage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint()

        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)

        val contrast = 1.5f
        val brightness = -30f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        colorMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return enhanced
    }

    fun cropToContent(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var top = 0
        var bottom = height - 1
        var left = 0
        var right = width - 1

        for (y in 0 until height) {
            var allWhite = true
            for (x in 0 until width) {
                if (pixels[y * width + x] != Color.WHITE) {
                    allWhite = false
                    break
                }
            }
            if (!allWhite) {
                top = y
                break
            }
        }

        for (y in height - 1 downTo 0) {
            var allWhite = true
            for (x in 0 until width) {
                if (pixels[y * width + x] != Color.WHITE) {
                    allWhite = false
                    break
                }
            }
            if (!allWhite) {
                bottom = y
                break
            }
        }

        for (x in 0 until width) {
            var allWhite = true
            for (y in top..bottom) {
                if (pixels[y * width + x] != Color.WHITE) {
                    allWhite = false
                    break
                }
            }
            if (!allWhite) {
                left = x
                break
            }
        }

        for (x in width - 1 downTo 0) {
            var allWhite = true
            for (y in top..bottom) {
                if (pixels[y * width + x] != Color.WHITE) {
                    allWhite = false
                    break
                }
            }
            if (!allWhite) {
                right = x
                break
            }
        }

        return if (left < right && top < bottom) {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } else {
            bitmap
        }
    }

    fun generatePdf(outputPath: String, pageSize: PageSize = PageSize.A4): ProcessingResult {
        return try {
            val pdfDocument = PdfDocument()
            val pageWidth = pageSize.width.toInt()
            val pageHeight = pageSize.height.toInt()

            for ((index, page) in scannedPages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = pdfDocument.startPage(pageInfo)
                val canvas = pdfPage.canvas

                val scaleX = pageWidth.toFloat() / page.bitmap.width
                val scaleY = pageHeight.toFloat() / page.bitmap.height
                val scale = minOf(scaleX, scaleY)

                val matrix = Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(
                    (pageWidth - page.bitmap.width * scale) / 2,
                    (pageHeight - page.bitmap.height * scale) / 2
                )

                canvas.drawBitmap(page.bitmap, matrix, null)
                pdfDocument.finishPage(pdfPage)
            }

            val outputStream = FileOutputStream(outputPath)
            pdfDocument.writeTo(outputStream)
            outputStream.close()
            pdfDocument.close()

            val outputSize = File(outputPath).length()
            ProcessingResult(
                success = true,
                outputPath = outputPath,
                outputSize = outputSize,
                pageCount = scannedPages.size
            )
        } catch (e: Exception) {
            ProcessingResult(
                success = false,
                message = e.message ?: "Failed to generate PDF"
            )
        }
    }
}

enum class PageSize(val width: Float, val height: Float) {
    A4(595f, 842f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f)
}
