package com.icl.surveillance.fhir.forms

import ca.uhn.fhir.context.FhirContext
import com.icl.surveillance.models.QuestionnaireAnswer
import java.nio.file.Files
import java.nio.file.Path
import org.hl7.fhir.r4.model.Questionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FVD Alert Notification Form (EOC module). */
class FvdAlertFormTest {

    private val form: Questionnaire by lazy {
        val json = listOf(
            Path.of("nphiis", "src", "main", "assets", FormFields.FvdAlert.FORM),
            Path.of("src", "main", "assets", FormFields.FvdAlert.FORM),
        ).first { Files.exists(it) }.let { String(Files.readAllBytes(it)) }
        FhirContext.forR4Cached().newJsonParser().parseResource(Questionnaire::class.java, json)
    }

    private fun items(list: List<Questionnaire.QuestionnaireItemComponent>): List<Questionnaire.QuestionnaireItemComponent> =
        list.flatMap { listOf(it) + items(it.item) }

    @Test
    fun `sections run A to H and every linkId is used once`() {
        assertEquals(('A'..'H').map { it.toString() }, form.item.map { it.text.substringBefore(".") })
        val ids = items(form.item).map { it.linkId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `form has the linkIds the app uses`() {
        val ids = items(form.item).map { it.linkId }.toSet()
        (FormFields.FvdAlert.LIST_CODES + listOf(
            FormFields.Residence.COUNTY, FormFields.Residence.SUB_COUNTY, FormFields.Person.SEX,
            FormFields.FvdAlert.RECEIVED_BY, FormFields.FvdAlert.NOTIFY_COUNTY, FormFields.FvdAlert.NOTIFY_SUB_COUNTY,
            FormFields.FvdAlert.VILLAGE, FormFields.FvdAlert.FACILITY, FormFields.FvdAlert.CASE_NAME,
            FormFields.FvdAlert.OFFICER_NAME, FormFields.FvdAlert.OFFICER_DATE,
        )).forEach { assertTrue("missing $it", it in ids) }
    }

    @Test
    fun `conditions name an answer of an earlier question`() {
        val all = items(form.item)
        all.forEachIndexed { index, item ->
            item.enableWhen.forEach { condition ->
                val question = all.take(index).single { it.linkId == condition.question }
                assertTrue(item.linkId, question.answerOption.any { it.valueCoding.equalsDeep(condition.answerCoding) })
            }
        }
    }

    @Test
    fun `a record is named after the case, else the place`() {
        val case = FvdAlerts.nameOf(listOf(QuestionnaireAnswer(FormFields.FvdAlert.CASE_NAME, "", "Jane  Akinyi Otieno")))
        assertEquals("Otieno", case.family)
        assertEquals("Jane Akinyi", case.givenAsSingleString)
        val event = FvdAlerts.nameOf(listOf(QuestionnaireAnswer(FormFields.FvdAlert.VILLAGE, "", "Kibra")))
        assertEquals("Kibra Alert", "${event.givenAsSingleString} ${event.family}")
    }

    @Test
    fun `case slug matches the case type`() {
        assertEquals(CaseSlugs.FVD_ALERT, CaseTypes.FVD_ALERT.toCaseSlug())
    }
}
