package com.pdforge.app.pdf

import com.itextpdf.forms.PdfAcroForm
import com.itextpdf.forms.fields.CheckBoxFormFieldBuilder
import com.itextpdf.forms.fields.TextFormFieldBuilder
import com.itextpdf.forms.fields.properties.CheckBoxType
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.pdforge.app.storage.OutputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PDF Forms — detection and filling of real interactive AcroForm fields.
 *
 * A small, safe fixture (a text field and a checkbox) is generated with the same
 * iText library the app uses, then run through the production detection and fill
 * implementations and reopened to confirm the values were really saved.
 */
class PdfFormsTest {

    private fun dir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /**
     * The test fixture: a form with a text field and a checkbox. [fieldPage] moves
     * both fields onto a later page so multi-page detection can be verified.
     */
    private fun formPdf(dir: File, name: String = "form.pdf", pages: Int = 1, fieldPage: Int = 1): File {
        val file = File(dir, name)
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        val pageList = (1..pages).map { doc.addNewPage(PageSize.A4) }
        val form = PdfAcroForm.getAcroForm(doc, true)
        val target = pageList[fieldPage - 1]

        // iText 8 builds form fields through the builder classes.
        val textField = TextFormFieldBuilder(doc, "fullName")
            .setWidgetRectangle(Rectangle(50f, 700f, 200f, 20f))
            .setPage(target)
            .createText()
        form.addField(textField, target)

        val checkbox = CheckBoxFormFieldBuilder(doc, "agree")
            .setWidgetRectangle(Rectangle(50f, 650f, 18f, 18f))
            .setCheckType(CheckBoxType.CHECK)
            .setPage(target)
            .createCheckBox()
        checkbox.setValue("Yes")
        form.addField(checkbox, target)
        doc.close()
        return file
    }

    private fun plainPdf(dir: File, name: String = "plain.pdf", pages: Int = 1): File {
        val file = File(dir, name)
        val doc = PdfDocument(PdfWriter(file.absolutePath))
        repeat(pages) { doc.addNewPage(PageSize.A4) }
        doc.close()
        return file
    }

    private fun readFieldValues(path: String): Map<String, String> {
        val doc = PdfDocument(PdfReader(path))
        return try {
            val form = PdfAcroForm.getAcroForm(doc, false) ?: return emptyMap()
            form.allFormFields.mapValues { (_, field) -> field.valueAsString ?: "" }
        } finally {
            doc.close()
        }
    }

    // ── Detection ────────────────────────────────────────────────────────────

    @Test
    fun detects_a_text_field_and_a_checkbox() {
        val detection = detectAcroFormFields(formPdf(dir("forms_detect_")).absolutePath)

        assertTrue("an AcroForm must be reported", detection.hasAcroForm)
        assertTrue(detection.hasFields)
        assertEquals(2, detection.fields.size)

        val text = detection.fields.first { it.name == "fullName" }
        assertEquals(FormFieldType.TEXT, text.type)
        assertEquals(1, text.pageNumber)
        assertEquals("", text.value)

        val checkbox = detection.fields.first { it.name == "agree" }
        assertEquals(FormFieldType.CHECKBOX, checkbox.type)
        assertEquals(1, checkbox.pageNumber)
        assertEquals("Yes", checkbox.onValue)
    }

    @Test
    fun fields_are_located_on_the_page_they_really_are_on() {
        val detection = detectAcroFormFields(
            formPdf(dir("forms_page2_"), pages = 3, fieldPage = 2).absolutePath
        )
        assertTrue(detection.hasFields)
        assertTrue(
            "both fields must be reported on page 2: ${detection.fields.map { it.pageNumber }}",
            detection.fields.all { it.pageNumber == 2 }
        )
    }

    @Test
    fun a_pdf_without_interactive_fields_is_reported_as_such() {
        val detection = detectAcroFormFields(plainPdf(dir("forms_plain_"), pages = 2).absolutePath)
        assertFalse("no AcroForm must be reported", detection.hasAcroForm)
        assertFalse(detection.hasFields)
        assertTrue(detection.fields.isEmpty())
    }

    @Test
    fun an_unreadable_file_is_reported_as_no_fields_instead_of_crashing() {
        val junk = File(dir("forms_junk_"), "junk.pdf")
        junk.writeText("this is not a pdf at all")
        val detection = detectAcroFormFields(junk.absolutePath)
        assertFalse(detection.hasFields)
        assertFalse(detection.hasAcroForm)

        // A file that does not exist must be just as safe.
        assertFalse(detectAcroFormFields(File(dir("forms_missing_"), "nope.pdf").absolutePath).hasFields)
    }

    // ── Filling ──────────────────────────────────────────────────────────────

    @Test
    fun filling_and_reopening_preserves_the_entered_values() {
        val d = dir("forms_fill_")
        val input = formPdf(d)
        val output = File(d, "filled.pdf")

        val pages = fillAcroForm(
            input.absolutePath, output.absolutePath,
            mapOf("fullName" to "Ada Lovelace", "agree" to "Yes")
        )
        assertEquals(1, pages)
        assertTrue(OutputValidator.validatePdf(output))

        val values = readFieldValues(output.absolutePath)
        assertEquals("Ada Lovelace", values["fullName"])
        assertEquals("Yes", values["agree"])
    }

    @Test
    fun a_checkbox_can_be_left_off() {
        val d = dir("forms_off_")
        val input = formPdf(d)
        val output = File(d, "filled.pdf")

        fillAcroForm(input.absolutePath, output.absolutePath, mapOf("agree" to "Off"))
        assertEquals("Off", readFieldValues(output.absolutePath)["agree"])
    }

    @Test
    fun a_text_field_can_be_cleared() {
        val d = dir("forms_clear_")
        val input = formPdf(d)
        val first = File(d, "first.pdf")
        fillAcroForm(input.absolutePath, first.absolutePath, mapOf("fullName" to "Something"))
        assertEquals("Something", readFieldValues(first.absolutePath)["fullName"])

        val second = File(d, "second.pdf")
        fillAcroForm(first.absolutePath, second.absolutePath, mapOf("fullName" to ""))
        assertEquals("", readFieldValues(second.absolutePath)["fullName"])
    }

    @Test
    fun unknown_field_names_are_ignored() {
        val d = dir("forms_unknown_")
        val input = formPdf(d)
        val output = File(d, "filled.pdf")

        fillAcroForm(
            input.absolutePath, output.absolutePath,
            mapOf("doesNotExist" to "value", "fullName" to "Grace Hopper")
        )

        val values = readFieldValues(output.absolutePath)
        assertEquals("Grace Hopper", values["fullName"])
        assertFalse(values.containsKey("doesNotExist"))
    }

    @Test
    fun filling_keeps_the_page_count_and_produces_a_valid_pdf() {
        val d = dir("forms_pages_")
        val input = formPdf(d, pages = 3, fieldPage = 3)
        val output = File(d, "filled.pdf")

        val pages = fillAcroForm(input.absolutePath, output.absolutePath, mapOf("fullName" to "Multi page"))
        assertEquals(3, pages)
        assertTrue(output.exists() && output.length() > 0)
        assertTrue(OutputValidator.validatePdf(output))
        // The fields are still discoverable in the filled output.
        assertTrue(detectAcroFormFields(output.absolutePath).hasFields)
    }

    @Test
    fun detection_and_fill_agree_on_the_field_names() {
        val d = dir("forms_roundtrip_")
        val input = formPdf(d)
        val detected = detectAcroFormFields(input.absolutePath)
        val output = File(d, "filled.pdf")

        val values = detected.fields.associate { field ->
            field.name to if (field.type == FormFieldType.CHECKBOX) "Yes" else "Round trip"
        }
        fillAcroForm(input.absolutePath, output.absolutePath, values)

        val readBack = readFieldValues(output.absolutePath)
        for ((name, value) in values) {
            assertEquals("field $name", value, readBack[name])
        }
    }
}
