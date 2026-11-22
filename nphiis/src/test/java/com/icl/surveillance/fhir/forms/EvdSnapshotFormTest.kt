package com.icl.surveillance.fhir.forms

import ca.uhn.fhir.context.FhirContext
import java.nio.file.Files
import java.nio.file.Path
import org.hl7.fhir.r4.model.Questionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The EVD daily snapshot form parses and carries the linkIds the app reads and pre-fills. */
class EvdSnapshotFormTest {

    private val questionnaire: Questionnaire by lazy {
        val json = listOf(
            Path.of("nphiis", "src", "main", "assets", FormFields.EvdSnapshot.FORM),
            Path.of("src", "main", "assets", FormFields.EvdSnapshot.FORM),
        ).first { Files.exists(it) }.let { String(Files.readAllBytes(it)) }
        FhirContext.forR4Cached().newJsonParser().parseResource(Questionnaire::class.java, json)
    }

    private fun items(list: List<Questionnaire.QuestionnaireItemComponent>): List<Questionnaire.QuestionnaireItemComponent> =
        list.flatMap { listOf(it) + items(it.item) }

    @Test
    fun `form has the linkIds the app uses`() {
        val ids = items(questionnaire.item).map { it.linkId }.toSet()
        listOf(
            FormFields.Residence.COUNTY,
            FormFields.Residence.SUB_COUNTY,
            FormFields.EvdSnapshot.REPORTING_DATE,
            FormFields.EvdSnapshot.FOCAL_PERSON,
            FormFields.EvdSnapshot.PHONE,
            FormFields.EvdSnapshot.EMAIL,
            FormFields.EvdSnapshot.ALERTS,
            FormFields.EvdSnapshot.SUSPECTED,
        ).forEach { assertTrue("missing $it", it in ids) }
    }

    @Test
    fun `all 17 counts are required whole numbers`() {
        val counts = items(questionnaire.item).filter { it.type == Questionnaire.QuestionnaireItemType.INTEGER }
        assertEquals(17, counts.size)
        assertTrue(counts.all { it.required })
        assertEquals(counts.size, counts.map { it.linkId }.toSet().size)
    }

    @Test
    fun `certification is a required single checkbox`() {
        val item = items(questionnaire.item).single { it.linkId == "evd-snap-certified" }
        assertEquals(Questionnaire.QuestionnaireItemType.CHOICE, item.type)
        assertTrue(item.required)
        assertEquals(1, item.answerOption.size)
        val control = item.getExtensionByUrl("http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl")
        assertEquals("check-box", (control.value as org.hl7.fhir.r4.model.CodeableConcept).codingFirstRep.code)
    }

    @Test
    fun `count checks compare with an earlier count on the form`() {
        val all = items(questionnaire.item)
        val order = all.map { it.linkId }
        val rule = Regex("%context\\.answer\\.value <= %resource\\.descendants\\(\\)\\.where\\(linkId='([^']+)'\\)\\.answer\\.value")
        val checks = all.flatMap { item ->
            item.extension.filter { it.url.endsWith("questionnaire-constraint") }.map { c ->
                val expression = c.getExtensionByUrl("expression").value.primitiveValue()
                item.linkId to rule.find(expression)!!.groupValues[1]
            }
        }
        assertTrue(("evd-snap-contacts-followed" to "evd-snap-contacts-due") in checks)
        assertEquals(7, checks.size)
        checks.forEach { (item, other) -> assertTrue("$item vs $other", order.indexOf(other) in 0 until order.indexOf(item)) }
    }

    @Test
    fun `case slug matches the case type`() {
        assertEquals(CaseSlugs.EVD_SNAPSHOT, CaseTypes.EVD_SNAPSHOT.toCaseSlug())
    }
}
