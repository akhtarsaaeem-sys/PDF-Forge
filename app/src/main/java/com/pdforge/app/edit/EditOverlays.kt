package com.pdforge.app.edit

import java.util.UUID

/**
 * Pure editing model for the Edit PDF tool.
 *
 * Everything here is plain Kotlin on purpose: the overlays, the style presets and
 * the draw plan are what the Android renderer consumes when it writes the edited
 * PDF, so the properties a user picks can be verified in a JVM unit test instead
 * of only being checked on a device.
 *
 * All geometry is stored as FRACTIONS (0..1) of the page, so the on-screen
 * preview and the saved PDF share one coordinate system regardless of screen
 * size, density or render resolution. Line widths, text sizes and font sizes are
 * fractions of the page WIDTH for the same reason.
 */

enum class EditTool { NONE, TEXT, DRAW, IMAGE, SHAPE }

/** Shapes the tool already supported — no new shape types are introduced. */
enum class ShapeType { RECTANGLE, CIRCLE, LINE }

/** One selectable colour in the shared drawing / shape / text palette. */
data class EditColorOption(val label: String, val argb: Int)

/**
 * The palette offered by the drawing, shape and text tools.
 *
 * Black, white, red, blue and green are required; the remaining entries are
 * conveniences drawn from the same list so every tool offers the same choice.
 */
val EDIT_COLOR_PALETTE: List<EditColorOption> = listOf(
    EditColorOption("Black", 0xFF000000.toInt()),
    EditColorOption("White", 0xFFFFFFFF.toInt()),
    EditColorOption("Red", 0xFFE53935.toInt()),
    EditColorOption("Blue", 0xFF1E88E5.toInt()),
    EditColorOption("Green", 0xFF43A047.toInt()),
    EditColorOption("Orange", 0xFFFB8C00.toInt()),
    EditColorOption("Yellow", 0xFFFDD835.toInt()),
    EditColorOption("Purple", 0xFF8E24AA.toInt())
)

val EDIT_COLOR_BLACK: Int = EDIT_COLOR_PALETTE.first { it.label == "Black" }.argb

/** A stroke-width preset, expressed as a fraction of the page width. */
data class EditWidthPreset(val label: String, val fraction: Float)

/** Stroke widths shared by the pen and the shape tools. */
val EDIT_STROKE_WIDTHS: List<EditWidthPreset> = listOf(
    EditWidthPreset("Thin", 0.002f),
    EditWidthPreset("Medium", 0.004f),
    EditWidthPreset("Thick", 0.009f),
    EditWidthPreset("Extra", 0.018f)
)

/** Default pen/shape stroke width — the value the old code hard-coded. */
const val EDIT_DEFAULT_STROKE_FRACTION = 0.004f

/** A text-size preset, expressed as a fraction of the page width. */
data class EditTextSizePreset(val label: String, val fraction: Float)

val EDIT_TEXT_SIZES: List<EditTextSizePreset> = listOf(
    EditTextSizePreset("Small", 0.025f),
    EditTextSizePreset("Medium", 0.04f),
    EditTextSizePreset("Large", 0.06f),
    EditTextSizePreset("X-Large", 0.09f)
)

/** Default text size — the value the old code hard-coded. */
const val EDIT_DEFAULT_TEXT_FRACTION = 0.04f

/** One piece of text placed on a page. [fontSize] is a fraction of page width. */
data class TextOverlay(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float = EDIT_DEFAULT_TEXT_FRACTION,
    val color: Int = EDIT_COLOR_BLACK
)

/** One freehand stroke. [strokeWidth] is a fraction of page width. */
data class DrawingStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Pair<Float, Float>>,
    val strokeWidth: Float = EDIT_DEFAULT_STROKE_FRACTION,
    val color: Int = EDIT_COLOR_BLACK
)

/** One shape. [strokeWidth] is a fraction of page width. */
data class ShapeOverlay(
    val id: String = UUID.randomUUID().toString(),
    val type: ShapeType,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val color: Int = EDIT_COLOR_BLACK,
    val strokeWidth: Float = EDIT_DEFAULT_STROKE_FRACTION,
    /** When true the shape is filled with [color] instead of outlined. */
    val fill: Boolean = false
)

/**
 * The properties currently selected in the editor toolbar. Applied to every new
 * overlay, and read back by the UI so the active choice is always visible.
 */
data class EditStyle(
    val penColor: Int = EDIT_COLOR_BLACK,
    val penWidth: Float = EDIT_DEFAULT_STROKE_FRACTION,
    val shapeColor: Int = EDIT_COLOR_BLACK,
    val shapeWidth: Float = EDIT_DEFAULT_STROKE_FRACTION,
    val shapeFill: Boolean = false,
    val textColor: Int = EDIT_COLOR_BLACK,
    val textSize: Float = EDIT_DEFAULT_TEXT_FRACTION
)

/** Builds a stroke that carries the currently selected pen colour and thickness. */
fun newStroke(points: List<Pair<Float, Float>>, style: EditStyle): DrawingStroke =
    DrawingStroke(points = points, strokeWidth = style.penWidth, color = style.penColor)

/** Builds a shape that carries the currently selected colour, width and fill. */
fun newShape(
    type: ShapeType,
    x1: Float, y1: Float, x2: Float, y2: Float,
    style: EditStyle
): ShapeOverlay = ShapeOverlay(
    type = type,
    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
    color = style.shapeColor,
    strokeWidth = style.shapeWidth,
    fill = style.shapeFill
)

/** Builds a text overlay that carries the currently selected colour and size. */
fun newText(text: String, x: Float, y: Float, style: EditStyle): TextOverlay =
    TextOverlay(text = text, x = x, y = y, fontSize = style.textSize, color = style.textColor)

// ── Draw plan (what the renderer actually paints) ────────────────────────

/** A stroke to paint: [widthFraction] and [colorArgb] come straight from the overlay. */
data class StrokeCommand(
    val id: String,
    val points: List<Pair<Float, Float>>,
    val colorArgb: Int,
    val widthFraction: Float
)

/** Text to paint. [sizeFraction] is a fraction of the page width. */
data class TextCommand(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val colorArgb: Int,
    val sizeFraction: Float
)

/** A shape to paint, including its colour, width and fill mode. */
data class ShapeCommand(val shape: ShapeOverlay)

/**
 * The full set of paint instructions for one page, grouped by layer. The
 * renderer draws strokes, then text, then images (handled by the screen), then
 * shapes — the same z-order the tool always used.
 */
data class EditPagePlan(
    val strokes: List<StrokeCommand>,
    val texts: List<TextCommand>,
    val shapes: List<ShapeCommand>
)

/**
 * Turns the page's overlays into the exact paint instructions the renderer
 * executes. Keeping this pure means the "did my colour/thickness/size reach the
 * output?" question is answerable without Android graphics.
 *
 * Strokes with fewer than two points cannot be painted and are dropped, matching
 * the renderer's own guard.
 */
fun buildEditPagePlan(
    strokes: List<DrawingStroke>,
    texts: List<TextOverlay>,
    shapes: List<ShapeOverlay>
): EditPagePlan = EditPagePlan(
    strokes = strokes
        .filter { it.points.size >= 2 }
        .map { StrokeCommand(it.id, it.points, it.color, it.strokeWidth) },
    texts = texts.map { TextCommand(it.id, it.text, it.x, it.y, it.color, it.fontSize) },
    shapes = shapes.map { ShapeCommand(it) }
)
