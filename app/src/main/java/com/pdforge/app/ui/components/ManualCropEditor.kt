package com.pdforge.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pdforge.app.pdf.CropFractions
import com.pdforge.app.pdf.CropHandle
import com.pdforge.app.pdf.adjusted
import kotlin.math.roundToInt

/**
 * Interactive manual crop editor.
 *
 * Shows the page as the user actually sees it (the bitmap comes from the shared
 * renderer, which applies the page's /Rotate) with a visible crop rectangle on
 * top of it:
 *
 *  - the excluded area is shaded, the kept area is left clear and outlined,
 *  - dragging inside the rectangle moves it,
 *  - dragging a corner handle resizes it,
 *  - the rectangle can never leave the page or collapse to zero area (the
 *    clamping lives in the pure [CropFractions] helpers).
 *
 * Because the crop is stored as fractions of the displayed page, the caller can
 * apply the same selection to pages of different sizes and rotations.
 */
@Composable
fun ManualCropEditor(
    pageBitmap: Bitmap?,
    crop: CropFractions,
    onCropChange: (CropFractions) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
    ) {
        val bitmap = pageBitmap
        if (bitmap == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val fitW = if (containerW > 0f && containerH > 0f) minOf(containerW, containerH * aspect) else 0f
        val fitH = if (fitW > 0f) fitW / aspect else 0f
        if (fitW <= 0f) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val offsetX = (containerW - fitW) / 2f
        val offsetY = (containerH - fitH) / 2f
        val handleRadius = with(density) { 22.dp.toPx() }

        // The gesture layer must NOT be re-keyed on every crop change: restarting
        // pointerInput mid-drag cancels the drag. The latest crop is read through
        // must-be-updated state instead.
        val currentCrop by rememberUpdatedState(crop)
        val currentOnChange by rememberUpdatedState(onCropChange)
        var handle by remember { mutableStateOf(CropHandle.MOVE) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(with(density) { fitW.toDp() }, with(density) { fitH.toDp() })
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page to crop",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val selected = currentCrop
                val left = selected.left * size.width
                val top = selected.top * size.height
                val right = (1f - selected.right) * size.width
                val bottom = (1f - selected.bottom) * size.height
                val keptW = (right - left).coerceAtLeast(0f)
                val keptH = (bottom - top).coerceAtLeast(0f)

                // Shade everything that will be removed.
                val shade = Color.Black.copy(alpha = 0.45f)
                drawRect(shade, Offset.Zero, Size(size.width, top))
                drawRect(shade, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
                drawRect(shade, Offset(0f, top), Size(left, keptH))
                drawRect(shade, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), keptH))

                // Outline the kept area.
                drawRect(
                    Color.White,
                    topLeft = Offset(left, top),
                    size = Size(keptW, keptH),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Touch-friendly corner handles.
                val handleColor = Color(0xFF2196F3)
                listOf(
                    Offset(left, top), Offset(right, top),
                    Offset(left, bottom), Offset(right, bottom)
                ).forEach { corner ->
                    drawCircle(handleColor, radius = 9.dp.toPx(), center = corner)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = corner, style = Stroke(2.dp.toPx()))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color(0x22000000), RoundedCornerShape(2.dp))
                    .pointerInput(fitW, fitH) {
                        detectDragGestures(
                            onDragStart = { start ->
                                handle = pickCropHandle(start, currentCrop, fitW, fitH, handleRadius)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentOnChange(
                                    currentCrop.adjusted(handle, dragAmount.x / fitW, dragAmount.y / fitH)
                                )
                            }
                        )
                    }
            )
        }
    }
}

/**
 * Decides which part of the crop rectangle a drag that started at [start]
 * manipulates: a corner handle when the touch is close enough to it, otherwise
 * the whole rectangle (so dragging anywhere on the page moves the selection).
 */
internal fun pickCropHandle(
    start: Offset,
    crop: CropFractions,
    widthPx: Float,
    heightPx: Float,
    handleRadiusPx: Float
): CropHandle {
    if (widthPx <= 0f || heightPx <= 0f) return CropHandle.MOVE
    val left = crop.left * widthPx
    val top = crop.top * heightPx
    val right = (1f - crop.right) * widthPx
    val bottom = (1f - crop.bottom) * heightPx
    val corners = listOf(
        CropHandle.TOP_LEFT to Offset(left, top),
        CropHandle.TOP_RIGHT to Offset(right, top),
        CropHandle.BOTTOM_LEFT to Offset(left, bottom),
        CropHandle.BOTTOM_RIGHT to Offset(right, bottom)
    )
    for ((candidate, center) in corners) {
        if ((start - center).getDistance() <= handleRadiusPx) return candidate
    }
    return CropHandle.MOVE
}
