package com.pdforge.app.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit PDF — drawing / shape / text properties.
 *
 * The overlays are what the Android renderer consumes when it writes the edited
 * PDF, and [buildEditPagePlan] is the exact instruction list it executes. These
 * tests therefore prove that the property the user selects (colour, thickness,
 * text size, fill) reaches the paint instructions — the pixel result itself still
 * has to be eyeballed on a device.
 */
class EditOverlayModelTest {

    private val black = EDIT_COLOR_PALETTE.first { it.label == "Black" }.argb
    private val white = EDIT_COLOR_PALETTE.first { it.label == "White" }.argb
    private val red = EDIT_COLOR_PALETTE.first { it.label == "Red" }.argb
    private val blue = EDIT_COLOR_PALETTE.first { it.label == "Blue" }.argb
    private val green = EDIT_COLOR_PALETTE.first { it.label == "Green" }.argb

    private val points = listOf(0.1f to 0.1f, 0.2f to 0.25f, 0.3f to 0.2f)

    @Test
    fun palette_offers_at_least_black_white_red_blue_and_green() {
        val labels = EDIT_COLOR_PALETTE.map { it.label }
        for (required in listOf("Black", "White", "Red", "Blue", "Green")) {
            assertTrue("palette must offer $required", labels.contains(required))
        }
        assertEquals(
            "palette colours must be distinct",
            EDIT_COLOR_PALETTE.size,
            EDIT_COLOR_PALETTE.map { it.argb }.distinct().size
        )
    }

    @Test
    fun pen_colour_and_thickness_reach_the_stroke() {
        val style = EditStyle(penColor = red, penWidth = 0.009f)
        val stroke = newStroke(points, style)
        assertEquals(red, stroke.color)
        assertEquals(0.009f, stroke.strokeWidth, 0.0001f)
    }

    @Test
    fun changing_the_pen_colour_changes_what_is_rendered() {
        val blackPlan = buildEditPagePlan(listOf(newStroke(points, EditStyle(penColor = black))), emptyList(), emptyList())
        val redPlan = buildEditPagePlan(listOf(newStroke(points, EditStyle(penColor = red))), emptyList(), emptyList())

        assertEquals(black, blackPlan.strokes.single().colorArgb)
        assertEquals(red, redPlan.strokes.single().colorArgb)
        assertNotEquals(blackPlan.strokes.single().colorArgb, redPlan.strokes.single().colorArgb)

        // Every colour in the palette is accepted and survives into the plan.
        for (colour in listOf(black, white, red, blue, green)) {
            val plan = buildEditPagePlan(
                listOf(newStroke(points, EditStyle(penColor = colour))), emptyList(), emptyList()
            )
            assertEquals(colour, plan.strokes.single().colorArgb)
        }
    }

    @Test
    fun changing_the_pen_thickness_changes_the_rendered_width() {
        val thin = buildEditPagePlan(
            listOf(newStroke(points, EditStyle(penWidth = EDIT_STROKE_WIDTHS.first().fraction))),
            emptyList(), emptyList()
        ).strokes.single()
        val thick = buildEditPagePlan(
            listOf(newStroke(points, EditStyle(penWidth = EDIT_STROKE_WIDTHS.last().fraction))),
            emptyList(), emptyList()
        ).strokes.single()

        assertTrue("a thicker preset must produce a thicker stroke", thick.widthFraction > thin.widthFraction)
        assertEquals(EDIT_STROKE_WIDTHS.first().fraction, thin.widthFraction, 0.0001f)
        assertEquals(EDIT_STROKE_WIDTHS.last().fraction, thick.widthFraction, 0.0001f)
    }

    @Test
    fun shape_colour_thickness_and_fill_reach_the_rendered_shape() {
        val style = EditStyle(shapeColor = blue, shapeWidth = 0.018f, shapeFill = true)
        val shape = newShape(ShapeType.RECTANGLE, 0.1f, 0.1f, 0.4f, 0.4f, style)

        assertEquals(blue, shape.color)
        assertEquals(0.018f, shape.strokeWidth, 0.0001f)
        assertTrue(shape.fill)

        val planned = buildEditPagePlan(emptyList(), emptyList(), listOf(shape)).shapes.single().shape
        assertEquals(blue, planned.color)
        assertEquals(0.018f, planned.strokeWidth, 0.0001f)
        assertTrue(planned.fill)
    }

    @Test
    fun changing_shape_colour_changes_the_saved_shape() {
        val outline = newShape(ShapeType.CIRCLE, 0f, 0f, 1f, 1f, EditStyle(shapeColor = black, shapeFill = false))
        val filled = newShape(ShapeType.CIRCLE, 0f, 0f, 1f, 1f, EditStyle(shapeColor = green, shapeFill = true))

        assertEquals(black, outline.color)
        assertEquals(green, filled.color)
        assertTrue("fill must be carried alongside the colour", filled.fill)
    }

    @Test
    fun text_size_and_colour_reach_the_rendered_text() {
        val style = EditStyle(textSize = 0.09f, textColor = red)
        val overlay = newText("Invoice", 0.25f, 0.5f, style)

        assertEquals(0.09f, overlay.fontSize, 0.0001f)
        assertEquals(red, overlay.color)

        val planned = buildEditPagePlan(emptyList(), listOf(overlay), emptyList()).texts.single()
        assertEquals(0.09f, planned.sizeFraction, 0.0001f)
        assertEquals(red, planned.colorArgb)
        assertEquals("Invoice", planned.text)
    }

    @Test
    fun changing_text_size_changes_the_saved_text_size() {
        val small = newText("Hello", 0.1f, 0.1f, EditStyle(textSize = EDIT_TEXT_SIZES.first().fraction))
        val large = newText("Hello", 0.1f, 0.1f, EditStyle(textSize = EDIT_TEXT_SIZES.last().fraction))
        assertTrue(large.fontSize > small.fontSize)
        assertEquals(EDIT_TEXT_SIZES.first().fraction, small.fontSize, 0.0001f)
        assertEquals(EDIT_TEXT_SIZES.last().fraction, large.fontSize, 0.0001f)
    }

    @Test
    fun defaults_match_the_previous_behaviour_so_existing_edits_are_unchanged() {
        val style = EditStyle()
        assertEquals(EDIT_DEFAULT_STROKE_FRACTION, style.penWidth, 0.0001f)
        assertEquals(EDIT_DEFAULT_STROKE_FRACTION, style.shapeWidth, 0.0001f)
        assertEquals(EDIT_DEFAULT_TEXT_FRACTION, style.textSize, 0.0001f)
        assertEquals(EDIT_COLOR_BLACK, style.penColor)
        assertEquals(EDIT_COLOR_BLACK, style.textColor)
    }

    @Test
    fun strokes_without_enough_points_are_not_painted() {
        val single = DrawingStroke(points = listOf(0.5f to 0.5f))
        val plan = buildEditPagePlan(listOf(single), emptyList(), emptyList())
        assertTrue("a one-point stroke cannot be drawn", plan.strokes.isEmpty())
    }

    @Test
    fun presets_are_thin_to_thick_and_positive() {
        val widths = EDIT_STROKE_WIDTHS.map { it.fraction }
        assertEquals(widths.sorted(), widths)
        assertTrue(widths.all { it > 0f })
        val sizes = EDIT_TEXT_SIZES.map { it.fraction }
        assertEquals(sizes.sorted(), sizes)
        assertTrue(sizes.all { it > 0f })
    }
}
