package com.icl.surveillance.fhir.forms

import ca.uhn.fhir.context.FhirContext
import com.icl.surveillance.fhir.forms.VhfSurvivorMonitoring.Record
import java.io.File
import java.time.LocalDate
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VhfSurvivorMonitoringTest {

    private fun answer(code: String, value: Type) = Observation().apply {
        this.code = CodeableConcept(Coding().setCode(code))
        this.value = value
    }

    /** A choice answer as saved: a CodeableConcept with the option's coding. */
    private fun option(code: String, display: String) = CodeableConcept(Coding().setCode(code).setDisplay(display))

    private fun record(title: String, createdAt: String, vararg answers: Pair<String, Type>) =
        Record("enc-$createdAt", title, createdAt, answers.map { (code, value) -> answer(code, value) })

    private fun semen(createdAt: String, date: String, result: String) = record(
        FormFields.Epesm.PERSISTENCE_TITLE, createdAt,
        FormFields.Epesm.SAMPLE_TYPE to option("semen", "Semen"),
        FormFields.Epesm.SEMEN_DATE to StringType(date),
        FormFields.Epesm.SEMEN_RESULT to option(result, result.replaceFirstChar { it.uppercase() }),
    )

    @Test
    fun `semen clears after two negatives in a row, counted from the latest`() {
        val status = VhfSurvivorMonitoring.Status(listOf(
            semen("2026-04-01 10:00:00", "2026-04-01", "negative"),
            semen("2026-05-01 10:00:00", "2026-05-01", "positive"),
            semen("2026-06-01 10:00:00", "2026-06-01", "negative"),
        ))
        assertEquals(1, status.semen.consecutiveNegatives)
        assertFalse(status.semen.cleared)

        val cleared = VhfSurvivorMonitoring.Status(status.records +
            semen("2026-07-01 10:00:00", "2026-07-01", "negative") +
            semen("2026-08-01 10:00:00", "2026-08-01", "negative"))
        assertEquals(3, cleared.semen.consecutiveNegatives)
        assertTrue(cleared.semen.cleared)
        // The second negative in a row cleared it.
        assertEquals(LocalDate.parse("2026-07-01"), cleared.semen.clearedOn)
        assertEquals(0, cleared.breastMilk.results.size)
    }

    @Test
    fun `results are ordered by sample date, not by when they were entered`() {
        val status = VhfSurvivorMonitoring.Status(listOf(
            semen("2026-08-02 10:00:00", "2026-06-01", "negative"),
            semen("2026-08-01 10:00:00", "2026-07-01", "negative"),
            semen("2026-08-03 10:00:00", "2026-05-01", "positive"),
        ))
        assertEquals(2, status.semen.consecutiveNegatives)
        assertEquals(LocalDate.parse("2026-07-01"), status.semen.clearedOn)
    }

    private fun followUp(createdAt: String, returned: Boolean) = record(
        FormFields.Epesm.FOLLOW_UP_TITLE, createdAt,
        FormFields.Epesm.FOLLOW_UP_DATE to StringType(createdAt.take(10)),
        FormFields.Epesm.SYMPTOMS_RETURNED to if (returned) option("yes", "Yes") else option("no", "No"),
    )

    @Test
    fun `symptoms returning raise a relapse alert until relapse testing is recorded`() {
        val visit = followUp("2026-09-01 09:00:00", returned = true)
        assertEquals(visit, VhfSurvivorMonitoring.Status(listOf(followUp("2026-08-01 09:00:00", false), visit)).relapseAlert)

        val relapse = record(FormFields.Epesm.RELAPSE_TITLE, "2026-09-01 12:00:00",
            FormFields.Epesm.RELAPSE_SUSPECTED to option("yes", "Yes"))
        assertNull(VhfSurvivorMonitoring.Status(listOf(visit, relapse)).relapseAlert)

        // Symptoms back again after that relapse record: a new alert.
        val later = followUp("2026-10-01 09:00:00", returned = true)
        assertEquals(later, VhfSurvivorMonitoring.Status(listOf(visit, relapse, later)).relapseAlert)

        val clearance = record(FormFields.Epesm.CLEARANCE_TITLE, "2026-10-02 09:00:00")
        assertNull(VhfSurvivorMonitoring.Status(listOf(visit, clearance)).relapseAlert)
    }

    @Test
    fun `follow-up visits run from day 22 after discharge for 14 days`() {
        val discharge = record(FormFields.Epesm.DISCHARGE_TITLE, "2026-09-05 10:00:00",
            FormFields.Vhf.DATE_CHECKED to StringType("2026-09-01"))
        val visit = followUp("2026-09-23 09:00:00", returned = false)
        val status = VhfSurvivorMonitoring.Status(listOf(discharge, visit))
        assertEquals(LocalDate.parse("2026-09-01"), status.dischargeDate)
        assertEquals(LocalDate.parse("2026-09-23"), status.followUpStart)
        assertEquals(LocalDate.parse("2026-10-06"), status.followUpEnd)
        assertEquals(22L, status.dayAfterDischarge(visit))

        // Without the official date, the day the assessment was saved.
        val saved = record(FormFields.Epesm.DISCHARGE_TITLE, "2026-09-05 10:00:00")
        assertEquals(LocalDate.parse("2026-09-27"), VhfSurvivorMonitoring.Status(listOf(saved)).followUpStart)
        assertNull(VhfSurvivorMonitoring.Status(emptyList()).followUpStart)
    }

    @Test
    fun `answers match option codes by display`() {
        assertTrue(VhfSurvivorMonitoring.matches("Breast milk", "breast-milk"))
        assertTrue(VhfSurvivorMonitoring.matches("Negative", "negative"))
        assertFalse(VhfSurvivorMonitoring.matches("", "negative"))
    }

    @Test
    fun `release prefill confirms a full 21 days without symptoms`() {
        val visits = (1..21).map { day ->
            ContactVisit("v$day", day, null, symptomatic = false, recordedAt = "", temperatures = listOf(36.8, 36.9))
        }
        val state = ContactFollowUpState("Contact", LocalDate.now().minusDays(23), LocalDate.now().minusDays(23), 23, visits, "")
        val prefill = VhfSurvivorMonitoring.releasePrefill(state)
        assertEquals("yes", prefill[FormFields.Epesm.MONITORING_COMPLETED])
        assertEquals("no", prefill[FormFields.Epesm.TEMPERATURE_GAP])
        assertEquals("yes", prefill[FormFields.Epesm.SYMPTOM_FREE])
        assertEquals("21-day", prefill[FormFields.Epesm.RELEASE_TYPE])

        // Day 5 with only the morning temperature, day 9 missing: a record gap.
        val gaps = state.copy(visits = visits.filter { it.day != 9 }
            .map { if (it.day == 5) it.copy(temperatures = listOf(36.8)) else it })
        assertEquals("yes", VhfSurvivorMonitoring.releasePrefill(gaps)[FormFields.Epesm.TEMPERATURE_GAP])
    }

    @Test
    fun `a contact is ready for release once the 21 days are over, until the release is recorded`() {
        val visits = (1..21).map { day ->
            ContactVisit("v$day", day, null, symptomatic = false, recordedAt = "", temperatures = listOf(36.8, 36.9))
        }
        val done = ContactFollowUpState("Contact", LocalDate.now().minusDays(23), LocalDate.now().minusDays(23), 23, visits, "")
        val none = VhfSurvivorMonitoring.Status(emptyList())
        assertTrue(VhfSurvivorMonitoring.readyForRelease(done, none))

        val released = VhfSurvivorMonitoring.Status(listOf(record(FormFields.Epesm.CONTACT_RELEASE_TITLE, "2026-10-01 10:00:00")))
        assertFalse(VhfSurvivorMonitoring.readyForRelease(done, released))

        val dayTen = done.copy(dayZero = LocalDate.now().minusDays(10), daysSinceExposure = 10, visits = visits.take(10))
        assertFalse(VhfSurvivorMonitoring.readyForRelease(dayTen, none))
    }

    @Test
    fun `every EPESM form parses and its prefilled answers are options or questions in it`() {
        val parser = FhirContext.forR4Cached().newJsonParser()
        FormFields.Epesm.TITLES.forEach { (file, title) ->
            val questionnaire = parser.parseResource(Questionnaire::class.java, File("src/main/assets/$file").readText())
            assertEquals(file, title, questionnaire.title)
        }
        val release = parser.parseResource(Questionnaire::class.java,
            File("src/main/assets/${FormFields.Epesm.CONTACT_RELEASE_FORM}").readText())
        fun items(list: List<Questionnaire.QuestionnaireItemComponent>): List<Questionnaire.QuestionnaireItemComponent> =
            list.flatMap { listOf(it) + items(it.item) }
        val releaseType = items(release.item).single { it.linkId == FormFields.Epesm.RELEASE_TYPE }
        assertTrue(releaseType.answerOption.any { it.valueCoding.code == "21-day" })
    }
}
