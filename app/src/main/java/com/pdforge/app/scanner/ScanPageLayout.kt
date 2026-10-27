package com.pdforge.app.scanner

import kotlin.math.max

/**
 * Page-layout maths for scanned PDFs.
 *
 * WHY: the scanner used to place every photo on a fixed A4 portrait page with the
 * default margins and `scaleToFit`. A landscape photo (the usual case when a sheet
 * is photographed with the phone held normally) was shrunk to fit the narrower
 * content width, then vertically centred — leaving large accidental white bands
 * top and bottom. The photographed document occupied only a fraction of the page.
 *
 * The fix is to give each PDF page the SAME aspect ratio as the scanned image, so
 * the image fills the page edge-to-edge and no white border appears, while the
 * image is never stretched or cropped. The page's long edge stays at a standard
 * document length (A4's 842 pt by default), so pages remain document-sized.
 *
 * Pure functions so the layout is unit-testable without a camera or a renderer.
 */
object ScanPageLayout {

    /** A4 long edge in PDF points (the standard long edge used for scan pages). */
    const val DEFAULT_LONG_EDGE_PT = 842f

    /**
     * PDF page size in points for an image of [imageWidth] x [imageHeight] pixels:
     * same aspect ratio as the image, with [longEdgePt] on the long edge.
     *
     * Returns a square [longEdgePt] page for degenerate (non-positive) sizes rather
     * than producing a zero-area page.
     */
    fun pageSize(imageWidth: Int, imageHeight: Int, longEdgePt: Float = DEFAULT_LONG_EDGE_PT): Pair<Float, Float> {
        if (imageWidth <= 0 || imageHeight <= 0 || longEdgePt <= 0f) {
            return longEdgePt to longEdgePt
        }
        val scale = longEdgePt / max(imageWidth, imageHeight).toFloat()
        return (imageWidth * scale) to (imageHeight * scale)
    }
}
