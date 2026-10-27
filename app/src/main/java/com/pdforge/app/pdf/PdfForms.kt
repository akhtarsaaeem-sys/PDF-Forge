package com.pdforge.app.pdf

import com.itextpdf.forms.PdfAcroForm
import com.itextpdf.forms.fields.PdfFormField
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter

/**
 * AcroForm detection and filling for the PDF Forms tool.
 *
 * This lives outside the Compose screen so the whole Detect → Fill → Reopen round
 * trip runs in a JVM unit test against a real generated form (text field +
 * checkbox), instead of only being observable on a device.
 *
 * The tool only ever fills fields that ALREADY EXIST in an interactive AcroForm.
 * A PDF that merely has blank lines drawn on it has no AcroForm fields, and must
 * not be turned into a "filled form" by overlaying text — that is a different
 * feature and would be misleading.
 */

enum class FormFieldType(val label: String) {
    TEXT("Text field"),
    CHECKBOX("Checkbox"),
    RADIO("Radio group"),
    DROPDOWN("Choice field"),
    OTHER("Field")
}

/** One interactive field found in a document's AcroForm. */
data class AcroFormField(
    val name: String,
    val type: FormFieldType,
    val value: String = "",
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false,
    val pageNumber: Int = 1,
    val rect: Rectangle? = null,
    /** The export value a checkbox uses for its "on" state (never "Off"). */
    val onValue: String? = null
)

/** Result of scanning a document for interactive fields. */
data class FormDetection(
    val fields: List<AcroFormField>,
    val hasAcroForm: Boolean
) {
    val hasFields: Boolean get() = fields.isNotEmpty()

    companion object {
        val EMPTY = FormDetection(emptyList(), false)
    }
}

/** The field's kind, resolved from its /FT entry. */
internal fun formFieldTypeOf(field: PdfFormField): FormFieldType = when (field.formType) {
    PdfName.Tx -> FormFieldType.TEXT
    PdfName.Btn -> {
        // A button with more than one kid is a radio group; a single kid (or none)
        // is a checkbox.
        val kids = field.kids
        if (kids != null && kids.size() > 1) FormFieldType.RADIO else FormFieldType.CHECKBOX
    }
    PdfName.Ch -> FormFieldType.DROPDOWN
    else -> FormFieldType.OTHER
}

/** The "on" export value of a checkbox, or null when it cannot be resolved. */
private fun onValueOf(field: PdfFormField): String? = try {
    field.appearanceStates?.firstOrNull { it.isNotBlank() && it != "Off" }
} catch (e: Exception) {
    null
}

/**
 * The selectable options of a choice/radio field.
 *
 * A dropdown lists its own appearance states; a radio group lists the distinct
 * "on" states of its kid widgets, which are exactly the values that can be set.
 */
private fun optionsOf(field: PdfFormField, type: FormFieldType): List<String> = when (type) {
    FormFieldType.DROPDOWN -> try {
        field.appearanceStates?.filter { it.isNotBlank() && it != "Off" }?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
    FormFieldType.RADIO -> try {
        // Each kid widget carries its own "on" state under /AP /N; those state
        // names are the values a radio group can be set to.
        val names = mutableListOf<String>()
        val kids = field.kids
        if (kids != null) {
            for (i in 0 until kids.size()) {
                val kid = kids.getAsDictionary(i) ?: continue
                val states = kid.getAsDictionary(PdfName.AP)?.getAsDictionary(PdfName.N) ?: continue
                for (state in states.keySet()) {
                    if (state.value.isNotBlank() && state.value != "Off") names.add(state.value)
                }
            }
        }
        names.distinct()
    } catch (e: Exception) {
        emptyList()
    }
    else -> emptyList()
}

/**
 * Scans [pdfPath] for interactive AcroForm fields.
 *
 * Widgets and their page numbers are resolved through the form field's own
 * widgets rather than by hashing page annotation dictionaries, so a field's page
 * is the page it is really on.
 *
 * Never throws: an unreadable document yields [FormDetection.EMPTY].
 */
fun detectAcroFormFields(pdfPath: String): FormDetection {
    return try {
        val reader = PdfReader(pdfPath)
        val pdfDoc = PdfDocument(reader)
        try {
            val acroForm = PdfAcroForm.getAcroForm(pdfDoc, false)
            if (acroForm == null) return FormDetection.EMPTY
            val rootFields = acroForm.rootFormFields
            if (rootFields.isEmpty()) return FormDetection.EMPTY

            val fields = mutableListOf<AcroFormField>()
            for ((name, field) in rootFields) {
                if (field == null) continue
                val type = formFieldTypeOf(field)

                val widget = try {
                    field.widgets?.firstOrNull()
                } catch (e: Exception) {
                    null
                }

                val rect = try {
                    val array = widget?.rectangle
                    if (array != null && array.size() == 4) {
                        val x = array.getAsNumber(0)?.floatValue() ?: 0f
                        val y = array.getAsNumber(1)?.floatValue() ?: 0f
                        val w = array.getAsNumber(2)?.floatValue() ?: 0f
                        val h = array.getAsNumber(3)?.floatValue() ?: 0f
                        Rectangle(x, y, w, h)
                    } else null
                } catch (e: Exception) {
                    null
                }

                val pageNumber = try {
                    val page = widget?.page
                    if (page != null) pdfDoc.getPageNumber(page).coerceAtLeast(1) else 1
                } catch (e: Exception) {
                    1
                }

                fields.add(
                    AcroFormField(
                        name = name,
                        type = type,
                        value = try { field.valueAsString ?: "" } catch (e: Exception) { "" },
                        options = optionsOf(field, type),
                        isRequired = try { field.isRequired } catch (e: Exception) { false },
                        pageNumber = pageNumber,
                        rect = rect,
                        onValue = onValueOf(field)
                    )
                )
            }
            FormDetection(fields, true)
        } finally {
            try { pdfDoc.close() } catch (_: Exception) {}
        }
    } catch (e: Exception) {
        FormDetection.EMPTY
    }
}

/** True when [value] represents a checked checkbox in the UI's encoding. */
internal fun isCheckedValue(value: String): Boolean =
    value == "Yes" || value == "On" || value == "true" || value == "1"

/**
 * Writes [values] into the existing AcroForm fields of [inputPath] and saves the
 * result to [outputPath].
 *
 * Unknown field names are ignored. Returns the page count of the written file.
 * Throws when the document cannot be read or written — the caller turns that into
 * a user-visible failure and publishes nothing.
 */
fun fillAcroForm(inputPath: String, outputPath: String, values: Map<String, String>): Int {
    val reader = PdfReader(inputPath)
    val pdfDoc = PdfDocument(reader, PdfWriter(outputPath))
    try {
        val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
            ?: return pdfDoc.numberOfPages
        val rootFields = acroForm.rootFormFields

        for ((name, rawValue) in values) {
            val field = rootFields[name] ?: continue
            when (formFieldTypeOf(field)) {
                FormFieldType.TEXT, FormFieldType.DROPDOWN -> field.setValue(rawValue)
                FormFieldType.CHECKBOX -> {
                    if (isCheckedValue(rawValue)) {
                        field.setValue(onValueOf(field) ?: "Yes")
                    } else {
                        field.setValue("Off")
                    }
                }
                FormFieldType.RADIO -> {
                    if (rawValue.isBlank() || rawValue == "Off") field.setValue("Off")
                    else field.setValue(rawValue)
                }
                FormFieldType.OTHER -> try { field.setValue(rawValue) } catch (_: Exception) {}
            }
        }

        // Ask viewers to regenerate widget appearances from the new values.
        acroForm.setNeedAppearances(true)
        return pdfDoc.numberOfPages
    } finally {
        pdfDoc.close()
    }
}
