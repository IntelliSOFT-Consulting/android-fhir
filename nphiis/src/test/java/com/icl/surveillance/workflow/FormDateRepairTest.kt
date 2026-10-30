package com.icl.surveillance.workflow

import ca.uhn.fhir.context.FhirContext
import com.icl.surveillance.debug.FormDateRepair
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A date that fails its question's rules is set to the earliest date the case form accepts. */
class FormDateRepairTest {

    private val today: LocalDate = LocalDate.now()
    private val onset = "728034137219"

    private val form: Questionnaire by lazy {
        val path = listOf(Path.of("nphiis", "src", "main", "assets", FormFields.Vhf.CASE_FORM),
            Path.of("src", "main", "assets", FormFields.Vhf.CASE_FORM)).first(Files::exists)
        FhirContext.forR4Cached().newJsonParser().parseResource(Questionnaire::class.java, String(Files.readAllBytes(path)))
    }

    private fun response(values: Map<String, String>) =
        QuestionnaireResponse().also { FormPrefill.applyTo(form, it, values) }

    private fun QuestionnaireResponse.dateOf(linkId: String): String? {
        fun find(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>): String? =
            items.firstNotNullOfOrNull { item ->
                if (item.linkId == linkId) item.answerFirstRep?.value?.primitiveValue()?.take(10)
                else find(item.item) ?: item.answer.firstNotNullOfOrNull { find(it.item) }
            }
        return find(item)
    }

    @Test
    fun `a date before its minimum becomes the minimum`() {
        val r = response(mapOf("vhf-exposure-date" to today.minusDays(40).toString()))
        val changes = FormDateRepair.repair(form, r, listOf("vhf-exposure-date"), today)
        assertEquals(today.minusDays(21).toString(), r.dateOf("vhf-exposure-date"))
        assertEquals(1, changes.size)
    }

    @Test
    fun `a date before the date it must follow becomes that date`() {
        val first = today.minusDays(5)
        val r = response(mapOf(onset to first.toString(), "vhf-onset-fever" to first.minusDays(3).toString()))
        FormDateRepair.repair(form, r, listOf("vhf-onset-fever"), today)
        assertEquals(first.toString(), r.dateOf("vhf-onset-fever"))
    }

    @Test
    fun `a future date with no lower bound becomes today`() {
        val r = response(mapOf(onset to today.plusDays(4).toString()))
        FormDateRepair.repair(form, r, listOf(onset), today)
        assertEquals(today.toString(), r.dateOf(onset))
    }

    @Test
    fun `calculated bounds and questions that are not dates`() {
        assertEquals(today.minusDays(21), FormDateRepair.relativeTo("today() - 21 days", today))
        assertEquals(today, FormDateRepair.relativeTo("now()", today))
        val r = response(mapOf(FormFields.Vhf.CASE_TYPE to "suspected"))
        assertTrue(FormDateRepair.repair(form, r, listOf(FormFields.Vhf.CASE_TYPE), today).isEmpty())
    }
}
