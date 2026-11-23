package com.icl.surveillance.fhir.forms

import ca.uhn.fhir.context.FhirContext
import java.nio.file.Files
import java.nio.file.Path
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The alert triage form (Annex 1) and the earlier Alert Log kept as its backup. */
class AlertTriageFormTest {

    private fun load(form: String): Questionnaire {
        val json = listOf(
            Path.of("nphiis", "src", "main", "assets", form),
            Path.of("src", "main", "assets", form),
        ).first { Files.exists(it) }.let { String(Files.readAllBytes(it)) }
        return FhirContext.forR4Cached().newJsonParser().parseResource(Questionnaire::class.java, json)
    }

    private fun items(list: List<Questionnaire.QuestionnaireItemComponent>): List<Questionnaire.QuestionnaireItemComponent> =
        list.flatMap { listOf(it) + items(it.item) }

    private fun responseTo(form: String) = QuestionnaireResponse().apply {
        load(form).item.forEach { addItem().linkId = it.linkId }
    }

    @Test
    fun `form has the title and Annex 1's sections A to G`() {
        val form = load(FormFields.AlertLog.FORM)
        assertEquals(FormFields.AlertLog.TITLE, form.title)
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), form.item.map { it.text.substringBefore(".") })
    }

    @Test
    fun `form has the linkIds the app uses, each once, and conditions name its questions`() {
        val all = items(load(FormFields.AlertLog.FORM).item)
        val ids = all.map { it.linkId }
        assertEquals(ids.size, ids.toSet().size)
        listOf(
            FormFields.Residence.COUNTY, FormFields.Residence.SUB_COUNTY, FormFields.AlertLog.DATE,
            FormFields.AlertLog.FACILITY, FormFields.AlertLog.RISK_LEVEL, FormFields.AlertLog.TRIAGE_SECTION,
            FormFields.AlertLog.IDENTIFIER, FormFields.AlertLog.REPORTER_NAME, FormFields.AlertLog.REPORTER_MOBILE,
        ).forEach { assertTrue("missing $it", it in ids) }
        all.forEach { item ->
            item.enableWhen.forEach { condition ->
                val question = all.single { it.linkId == condition.question }
                val answer = condition.answerCoding
                assertTrue(item.linkId, question.answerOption.any {
                    it.valueCoding.code == answer.code && it.valueCoding.display == answer.display
                })
            }
        }
        val levels = all.single { it.linkId == FormFields.AlertLog.RISK_LEVEL }.answerOption.map { it.valueCoding.display }
        assertEquals(FormFields.AlertLog.RISK_LEVELS, levels.toSet())
    }

    @Test
    fun `an alert opens in the form it was saved with`() {
        assertEquals(FormFields.AlertLog.FORM, AlertForms.formFor(responseTo(FormFields.AlertLog.FORM)))
        assertEquals(FormFields.AlertLog.BACKUP_FORM, AlertForms.formFor(responseTo(FormFields.AlertLog.BACKUP_FORM)))
        assertEquals(FormFields.AlertLog.FORM, AlertForms.formFor(null))
    }

    @Test
    fun `the backup form is the earlier Alert Log`() {
        val ids = items(load(FormFields.AlertLog.BACKUP_FORM).item).map { it.linkId }
        assertTrue(FormFields.AlertLog.SAMPLE_COLLECTED in ids)
        assertFalse(FormFields.AlertLog.TRIAGE_SECTION in ids)
    }
}
