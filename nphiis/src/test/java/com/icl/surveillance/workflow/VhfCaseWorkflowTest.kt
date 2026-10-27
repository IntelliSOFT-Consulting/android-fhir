package com.icl.surveillance.workflow

import ca.uhn.fhir.context.FhirContext
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.VhfCaseOutcome
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfListMode
import com.icl.surveillance.fhir.forms.VhfSpecimens
import com.icl.surveillance.fhir.forms.latestValueOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VHF workflow one step at a time, as Settings > Simulate VHF workflow runs it on a device
 * (debug/VhfWorkflowSimulator): a flagged person investigated as a contact, three follow ups,
 * converted to a suspected case, confirmed by the lab, updated to confirmed, then died. These
 * tests cover the decisions made at each step from the records; the simulator covers saving and
 * syncing them.
 */
class VhfCaseWorkflowTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 24)
    private val dayZero: LocalDate = today.minusDays(3)
    private val sourceEpid = "KEN-BUS-BUN-2026-VHF-001"

    // ---------------------------------------------------------------- helpers

    private fun answer(code: String, value: String, at: LocalDate = today, encounter: String = "case") = Observation().apply {
        this.code.addCoding().setCode(code)
        this.value = StringType(value)
        status = Observation.ObservationStatus.FINAL
        issued = Date.from(at.atStartOfDay(ZoneId.systemDefault()).toInstant())
        this.encounter.reference = "Encounter/$encounter"
    }

    private fun followUp(day: Int, symptomatic: Boolean, temperatureAm: String): List<Observation> =
        listOfNotNull(
            answer(FormFields.Vhf.FOLLOW_UP_DAY, day.toString()),
            answer(FormFields.Vhf.FOLLOW_UP_DATE, dayZero.plusDays(day.toLong()).toString()),
            answer(FormFields.Vhf.FOLLOW_UP_CONTACT_STATUS, "Alive"),
            answer(FormFields.Vhf.FOLLOW_UP_SYMPTOMATIC, if (symptomatic) "Yes" else "No"),
            answer(FormFields.Vhf.FOLLOW_UP_SYMPTOMS, "Fever").takeIf { symptomatic },
            answer(FormFields.Vhf.FOLLOW_UP_TEMPERATURE_AM, temperatureAm),
        )

    /** The contact's state after [days] follow ups (day 3 symptomatic), with [caseType]. */
    private fun stateAfter(days: Int, caseType: String = FormFields.Vhf.CASE_TYPE_CONTACT) =
        VhfContactTracker.assemble(
            caseAnswers = listOf(
                answer(FormFields.Vhf.CASE_TYPE, caseType),
                answer(FormFields.Vhf.CONTACT_DATE, dayZero.toString()),
            ),
            registered = dayZero,
            children = (1..days).map {
                VhfContactTracker.ChildRecord("fu-$it", FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE, "2026-10-2$it 10:00:00")
            },
            childAnswers = (1..days).associate { day ->
                "fu-$day" to followUp(day, symptomatic = day == 3, temperatureAm = if (day == 3) "38.6" else "36.8")
            },
            today = today,
        )

    private fun questionnaire(file: String): Questionnaire {
        val path = listOf(Path.of("nphiis", "src", "main", "assets", file), Path.of("src", "main", "assets", file))
            .first(Files::exists)
        return FhirContext.forR4Cached().newJsonParser()
            .parseResource(Questionnaire::class.java, String(Files.readAllBytes(path)))
    }

    private fun response(questionnaire: Questionnaire, values: Map<String, String>) =
        QuestionnaireResponse().also { FormPrefill.applyTo(questionnaire, it, values) }

    private fun QuestionnaireResponse.find(linkId: String): QuestionnaireResponse.QuestionnaireResponseItemComponent? {
        fun visit(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>):
            QuestionnaireResponse.QuestionnaireResponseItemComponent? =
            items.firstNotNullOfOrNull { if (it.linkId == linkId) it else visit(it.item) }
        return visit(item)
    }

    private fun QuestionnaireResponse.codeOf(linkId: String) = (find(linkId)?.answerFirstRep?.value as? Coding)?.code

    // ---------------------------------------------------------------- 1. flagged -> contact

    @Test
    fun `a flagged person investigated as a contact is listed with contacts, not cases`() {
        assertTrue(VhfListMode.matches(VhfListMode.CONTACTS, FormFields.Vhf.CASE_TYPE_CONTACT))
        assertFalse(VhfListMode.matches(VhfListMode.CASES, FormFields.Vhf.CASE_TYPE_CONTACT))
        // Not synced yet (no Type of case on the device): the HIE's contact number decides.
        assertTrue(VhfListMode.isContact(null, "$sourceEpid-C001"))
    }

    @Test
    fun `the contact number starts from the source case EPID and the server's number wins`() {
        val prefix = VhfListMode.contactIdPrefix(sourceEpid)
        assertEquals("$sourceEpid-C", prefix)
        val patient = Patient()
        patient.addIdentifier(VhfListMode.contactIdentifier(prefix))
        assertEquals(prefix, VhfListMode.contactIdOf(patient))
        patient.addIdentifier(VhfListMode.contactIdentifier("$sourceEpid-C001"))
        assertEquals("$sourceEpid-C001", VhfListMode.contactIdOf(patient))
    }

    @Test
    fun `a contact number from an earlier exposure is not shown`() {
        val patient = Patient()
        patient.addIdentifier(VhfListMode.contactIdentifier("KEN-OLD-2026-VHF-009-C004").setUse(Identifier.IdentifierUse.OLD))
        patient.addIdentifier(VhfListMode.contactIdentifier("$sourceEpid-C001"))
        assertEquals("$sourceEpid-C001", VhfListMode.contactIdOf(patient))
    }

    @Test
    fun `the contact's case form links the source case`() {
        val form = questionnaire(FormFields.Vhf.CASE_FORM)
        val cif = response(
            form,
            mapOf(
                FormFields.Vhf.CASE_TYPE to "contact",
                FormFields.Vhf.EXPOSURE_TYPE to "human",
                FormFields.Vhf.CONTACT_DATE to dayZero.toString(),
            ),
        )
        assertEquals("contact", cif.codeOf(FormFields.Vhf.CASE_TYPE))
        assertEquals(dayZero.toString(), cif.find(FormFields.Vhf.CONTACT_DATE)?.answerFirstRep?.value?.primitiveValue())
    }

    // ---------------------------------------------------------------- 2. three follow ups

    @Test
    fun `three follow ups, symptomatic on day 3, ask for conversion`() {
        val state = stateAfter(3)
        assertTrue(state.isContact)
        assertEquals(3, state.daysSinceExposure)
        assertEquals(setOf(1, 2, 3), state.recordedDays)
        assertTrue(state.missedDays.isEmpty())
        assertFalse(state.isDueToday)
        assertEquals(3, state.firstSymptomaticVisit?.day)
        assertEquals("X", state.marks[3])
        assertEquals("0", state.marks[1])
        assertEquals("Symptomatic on day 3 - convert to case", state.summary)
    }

    @Test
    fun `before day 3 is recorded the contact is due today`() {
        val state = stateAfter(2)
        assertTrue(state.isDueToday)
        assertNull(state.firstSymptomaticVisit)
        assertEquals("Day 3/21 - due today", state.summary)
    }

    // ---------------------------------------------------------------- 3. converted -> CIF

    @Test
    fun `converting opens the CIF as a suspected case and keeps the contact answers`() {
        val form = questionnaire(FormFields.Vhf.CASE_FORM)
        val cif = response(
            form,
            mapOf(FormFields.Vhf.CASE_TYPE to "contact", FormFields.Vhf.EXPOSURE_TYPE to "human", FormFields.Vhf.CONTACT_DATE to dayZero.toString()),
        )
        // As the edit screen does for "Convert to case" (PREF_CONVERT_TO_SUSPECTED).
        FormPrefill.applyTo(
            form, cif,
            mapOf(FormFields.Vhf.CASE_TYPE to FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE, FormFields.Vhf.INVESTIGATION_DATE to today.toString()),
            overwrite = true,
        )
        assertEquals(FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE, cif.codeOf(FormFields.Vhf.CASE_TYPE))
        assertEquals(dayZero.toString(), cif.find(FormFields.Vhf.CONTACT_DATE)?.answerFirstRep?.value?.primitiveValue())
    }

    @Test
    fun `after conversion the record is a case and its follow up stops`() {
        val answers = listOf(
            answer(FormFields.Vhf.CASE_TYPE, "Contact", at = dayZero),
            answer(FormFields.Vhf.CASE_TYPE, "Suspected", at = today),
        )
        val type = answers.latestValueOf(FormFields.Vhf.CASE_TYPE)
        assertEquals("Suspected", type)
        assertTrue(VhfListMode.matches(VhfListMode.CASES, type))
        val state = stateAfter(3, caseType = type)
        assertFalse(state.isContact)
        assertFalse(state.isDueToday)
        assertEquals("", state.summary)
        assertEquals(3, state.visits.size) // the follow-up history is kept
    }

    // ---------------------------------------------------------------- 4. lab confirmation

    @Test
    fun `a final positive result confirms the case by lab`() {
        val positive = VhfSpecimens.TestResult("VHF PCR", "Positive", VhfSpecimens.interpret("Positive"), true, Date())
        val result = VhfSpecimens.resultOf(Specimen(), listOf(positive))
        assertEquals(VhfSpecimens.Result.POSITIVE, result)
        val row = VhfSpecimens.Row("s1", "Blood", "MV990JK0", null, null, null, emptyList(), emptyList(), listOf(positive), result, null, null)
        assertEquals("Confirmed by lab", VhfSpecimens.summarize(listOf(row))?.classification)
    }

    @Test
    fun `a preliminary positive result does not confirm the case yet`() {
        val preliminary = VhfSpecimens.TestResult("VHF PCR", "Positive", VhfSpecimens.Result.POSITIVE, false, Date())
        assertFalse(VhfSpecimens.resultOf(Specimen(), listOf(preliminary)) == VhfSpecimens.Result.POSITIVE)
    }

    // ---------------------------------------------------------------- 5. suspected -> confirmed

    @Test
    fun `the newest Type of case wins and moves the record to Confirmed`() {
        val answers = listOf(
            answer(FormFields.Vhf.CASE_TYPE, "Contact", at = dayZero),
            answer(FormFields.Vhf.CASE_TYPE, "Suspected", at = today.minusDays(1)),
            answer(FormFields.Vhf.CASE_TYPE, "Confirmed", at = today),
        )
        val type = answers.latestValueOf(FormFields.Vhf.CASE_TYPE)
        assertEquals(FormFields.Vhf.CASE_TYPE_CONFIRMED, type)
        assertTrue(VhfListMode.matches(VhfListMode.CONFIRMED, type))
        assertFalse(VhfListMode.matches(VhfListMode.CASES, type))
    }

    @Test
    fun `a suspected case with a final lab result, positive or negative, is listed as Confirmed`() {
        listOf("Positive", "Negative").forEach { lab ->
            assertTrue(lab, VhfListMode.matches(VhfListMode.CONFIRMED, "Suspected", labResults = lab))
            assertFalse(lab, VhfListMode.matches(VhfListMode.CASES, "Suspected", labResults = lab))
        }
        listOf("Pending", "Preliminary: Positive", "Rejected").forEach { lab ->
            assertTrue(lab, VhfListMode.matches(VhfListMode.CASES, "Suspected", labResults = lab))
            assertFalse(lab, VhfListMode.matches(VhfListMode.CONFIRMED, "Suspected", labResults = lab))
        }
        // A contact stays on the contact list until it is converted to a case.
        assertTrue(VhfListMode.matches(VhfListMode.CONTACTS, "Contact", labResults = "Negative"))
        assertFalse(VhfListMode.matches(VhfListMode.CONFIRMED, "Contact", labResults = "Negative"))
    }

    // ---------------------------------------------------------------- 6. outcome: died

    @Test
    fun `the outcome is written into the CIF under Clinical Care, in form order`() {
        val caseForm = questionnaire(FormFields.Vhf.CASE_FORM)
        val cif = response(caseForm, mapOf(FormFields.Vhf.CASE_TYPE to "confirmed", "vhf-care-type" to "inpatient"))
        val outcome = response(
            questionnaire(FormFields.Vhf.CASE_OUTCOME_FORM),
            mapOf(
                FormFields.Vhf.OUTCOME to FormFields.Vhf.OUTCOME_DEAD_CODE,
                FormFields.Vhf.DEATH_DATE to today.toString(),
                FormFields.Vhf.DEATH_PLACE to "health-facility",
                FormFields.Vhf.CASE_STATUS to "dead",
            ),
        )
        VhfCaseOutcome.mergeInto(cif, caseForm, outcome)

        assertEquals(FormFields.Vhf.OUTCOME_DEAD_CODE, cif.codeOf(FormFields.Vhf.OUTCOME))
        assertEquals(today.toString(), cif.find(FormFields.Vhf.DEATH_DATE)?.answerFirstRep?.value?.primitiveValue())
        assertEquals("dead", cif.codeOf(FormFields.Vhf.CASE_STATUS))
        assertEquals("confirmed", cif.codeOf(FormFields.Vhf.CASE_TYPE)) // nothing else changes
        val care = cif.find("vhf-care")!!.item.map { it.linkId }
        fun group(items: List<Questionnaire.QuestionnaireItemComponent>): Questionnaire.QuestionnaireItemComponent? =
            items.firstNotNullOfOrNull { if (it.linkId == "vhf-care") it else group(it.item) }
        val formOrder = group(caseForm.item)!!.item.map { it.linkId }
        assertEquals(care.sortedBy { formOrder.indexOf(it) }, care)
    }

    @Test
    fun `correcting the outcome to alive removes the death details`() {
        val caseForm = questionnaire(FormFields.Vhf.CASE_FORM)
        val outcomeForm = questionnaire(FormFields.Vhf.CASE_OUTCOME_FORM)
        val cif = response(caseForm, mapOf(FormFields.Vhf.CASE_TYPE to "confirmed"))
        VhfCaseOutcome.mergeInto(
            cif, caseForm,
            response(outcomeForm, mapOf(FormFields.Vhf.OUTCOME to "dead", FormFields.Vhf.DEATH_DATE to today.toString())),
        )
        VhfCaseOutcome.mergeInto(
            cif, caseForm,
            response(outcomeForm, mapOf(FormFields.Vhf.OUTCOME to "alive", FormFields.Vhf.CASE_STATUS to "active")),
        )
        assertEquals("alive", cif.codeOf(FormFields.Vhf.OUTCOME))
        assertNull(cif.find(FormFields.Vhf.DEATH_DATE))
        assertEquals("active", cif.codeOf(FormFields.Vhf.CASE_STATUS))
    }

    @Test
    fun `the outcome form asks the case form's questions with the same codes`() {
        val caseForm = questionnaire(FormFields.Vhf.CASE_FORM)
        val outcomeForm = questionnaire(FormFields.Vhf.CASE_OUTCOME_FORM)
        fun leaves(items: List<Questionnaire.QuestionnaireItemComponent>): List<Questionnaire.QuestionnaireItemComponent> =
            items.flatMap { if (it.type == Questionnaire.QuestionnaireItemType.GROUP) leaves(it.item) else listOf(it) + leaves(it.item) }
        val inCase = leaves(caseForm.item).associateBy { it.linkId }
        VhfCaseOutcome.CODES.forEach { code ->
            val asked = leaves(outcomeForm.item).firstOrNull { it.linkId == code }
            val original = inCase[code]
            assertTrue("$code is on both forms", asked != null && original != null)
            assertEquals(code, original!!.type, asked!!.type)
            assertEquals(code, original.answerOption.map { it.valueCoding.code }, asked.answerOption.map { it.valueCoding.code })
        }
    }
}
