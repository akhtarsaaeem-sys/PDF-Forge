package com.pdforge.app.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pdforge.app.pdf.WatermarkPosition
import com.pdforge.app.pdf.fitWatermarkFontSize
import com.pdforge.app.pdf.watermarkAnchor
import com.pdforge.app.pdf.watermarkPreviewAngleDegrees
import com.pdforge.app.pdf.watermarkUsableWidth

/**
 * Live watermark preview: renders a representative page with the current
 * watermark settings drawn on top, without generating a new PDF.
 *
 * Coordinate system is identical to the final output:
 *  - anchor comes from [watermarkAnchor] (fractions of page width/height,
 *    y measured from the bottom — iText space), flipped for bitmap (y-down) space
 *  - font size is in PDF points and scaled by the same factor as the page
 *
 * This guarantees the preview shows the watermark in exactly the position, size
 * and opacity the generated PDF will have.
 */
@Composable
fun WatermarkPreview(
    pageBitmap: Bitmap?,
    pageWidthPt: Int,
    pageHeightPt: Int,
    watermarkText: String,
    opacity: Float,
    fontSizePt: Float,
    rotation: Float,
    position: WatermarkPosition,
    modifier: Modifier = Modifier
) {
    val text = watermarkText.ifBlank { "CONFIDENTIAL" }
    val aspect = if (pageWidthPt > 0 && pageHeightPt > 0) {
        pageWidthPt.toFloat() / pageHeightPt.toFloat()
    } else {
        1f
    }

    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = pageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Watermark preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (pageWidthPt > 0 && pageHeightPt > 0 && text.isNotBlank()) {
                    val scaleX = size.width / pageWidthPt
                    val scaleY = size.height / pageHeightPt
                    val (ax, ayFromBottom) = watermarkAnchor(position, pageWidthPt.toFloat(), pageHeightPt.toFloat())
                    // Bitmap coordinates have y pointing down, so flip the anchor.
                    val cx = ax * scaleX
                    val cy = (pageHeightPt - ayFromBottom) * scaleY

                    // Long text is fitted the same way the exporter fits it, so the
                    // preview never shows text running off the page either.
                    val measure = Paint().apply {
                        textSize = fontSizePt
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val usableWidth = watermarkUsableWidth(
                        pageWidthPt.toFloat(), pageHeightPt.toFloat(), rotation
                    )
                    val fittedSizePt = fitWatermarkFontSize(
                        fontSizePt, measure.measureText(text), usableWidth
                    )

                    val paint = Paint().apply {
                        color = android.graphics.Color.GRAY
                        alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
                        textSize = fittedSizePt * scaleX
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val native = drawContext.canvas.nativeCanvas
                    native.save()
                    // PDF space rotates counter-clockwise for a positive slant while
                    // this canvas rotates clockwise, so the sign is flipped here —
                    // the saved PDF and the preview then slant the same way.
                    native.rotate(watermarkPreviewAngleDegrees(rotation), cx, cy)
                    val metrics = paint.fontMetrics
                    native.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
                    native.restore()
                }
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}