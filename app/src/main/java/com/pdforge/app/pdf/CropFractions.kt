package com.pdforge.app.pdf

/**
 * A crop rectangle expressed as FRACTIONS of the page's displayed size:
 * [left]/[right] are the fractions trimmed from the left/right edge and
 * [top]/[bottom] the fractions trimmed from the top/bottom edge (top measured
 * downward, as the user sees it).
 *
 * Fractions — not pixels — are what the manual crop UI edits, which is what lets
 * one crop be applied to pages with different sizes and orientations.
 *
 * The move/resize helpers here are pure, so the interactive drag behaviour is
 * unit-testable without a device.
 */
data class CropFractions(
    val left: Float = DEFAULT_MARGIN,
    val top: Float = DEFAULT_MARGIN,
    val right: Float = DEFAULT_MARGIN,
    val bottom: Float = DEFAULT_MARGIN
) {
    /** Fraction of the page width that survives the crop. */
    val keptWidth: Float get() = 1f - left - right

    /** Fraction of the page height that survives the crop. */
    val keptHeight: Float get() = 1f - top - bottom

    /** False when this rectangle would produce a zero-sized (or worse) page. */
    val isValid: Boolean get() = keptWidth > 0f && keptHeight > 0f

    companion object {
        /** Default trim shown when the manual editor opens (5% per edge). */
        const val DEFAULT_MARGIN = 0.05f

        /** Smallest kept region per axis, so a crop can never erase the page. */
        const val MIN_KEPT_FRACTION = 0.1f
    }
}

/** Which part of the crop rectangle a drag is manipulating. */
enum class CropHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private fun clampEdge(value: Float, opposite: Float): Float =
    value.coerceIn(0f, (1f - opposite - CropFractions.MIN_KEPT_FRACTION).coerceAtLeast(0f))

/**
 * Clamps every edge so the rectangle stays inside the page and keeps a minimum
 * area, i.e. it can never create a zero-sized page.
 */
fun CropFractions.clamped(): CropFractions {
    var left = clampEdge(left, right)
    var right = clampEdge(right, left)
    var top = clampEdge(top, bottom)
    var bottom = clampEdge(bottom, top)
    if (1f - left - right < CropFractions.MIN_KEPT_FRACTION) {
        val excess = CropFractions.MIN_KEPT_FRACTION - (1f - left - right)
        if (right >= left) right = (right - excess).coerceAtLeast(0f)
        else left = (left - excess).coerceAtLeast(0f)
    }
    if (1f - top - bottom < CropFractions.MIN_KEPT_FRACTION) {
        val excess = CropFractions.MIN_KEPT_FRACTION - (1f - top - bottom)
        if (bottom >= top) bottom = (bottom - excess).coerceAtLeast(0f)
        else top = (top - excess).coerceAtLeast(0f)
    }
    return CropFractions(left, top, right, bottom)
}

/**
 * Moves the whole rectangle by ([dx], [dy]) fractions without changing its size,
 * keeping it inside the page bounds.
 */
fun CropFractions.moved(dx: Float, dy: Float): CropFractions {
    val keptW = keptWidth.coerceIn(0f, 1f)
    val keptH = keptHeight.coerceIn(0f, 1f)
    val newLeft = (left + dx).coerceIn(0f, (1f - keptW).coerceAtLeast(0f))
    val newTop = (top + dy).coerceIn(0f, (1f - keptH).coerceAtLeast(0f))
    return CropFractions(
        left = newLeft,
        top = newTop,
        right = (1f - keptW - newLeft).coerceAtLeast(0f),
        bottom = (1f - keptH - newTop).coerceAtLeast(0f)
    ).clamped()
}

/**
 * Drags the [handle] corner by ([dx], [dy]) fractions. Positive [dx] moves right
 * and positive [dy] moves down, so the top edge grows when the user drags it down.
 */
fun CropFractions.adjusted(handle: CropHandle, dx: Float, dy: Float): CropFractions {
    val next = when (handle) {
        CropHandle.MOVE -> return moved(dx, dy)
        CropHandle.TOP_LEFT -> copy(left = left + dx, top = top + dy)
        CropHandle.TOP_RIGHT -> copy(right = right - dx, top = top + dy)
        CropHandle.BOTTOM_LEFT -> copy(left = left + dx, bottom = bottom - dy)
        CropHandle.BOTTOM_RIGHT -> copy(right = right - dx, bottom = bottom - dy)
    }
    return next.clamped()
}

/**
 * The crop in raw PDF points for one page, using the page's own size and /Rotate.
 * Returns null when the rectangle would produce a zero-sized page.
 */
internal fun CropFractions.toCropBox(pageWidth: Float, pageHeight: Float, rotation: Int): CropBox? =
    computeCropBoxNormalized(pageWidth, pageHeight, rotation, left, top, right, bottom)
