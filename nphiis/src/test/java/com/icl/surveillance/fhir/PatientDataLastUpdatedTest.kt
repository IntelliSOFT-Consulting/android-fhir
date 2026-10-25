package com.icl.surveillance.fhir

import java.util.Date
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DiagnosticReport
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Specimen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatientDataLastUpdatedTest {

    private val subject = Reference("Patient/p1")

    @Test
    fun `a child record names the patient it references`() {
        listOf(
            Observation().setSubject(subject),
            Encounter().setSubject(subject),
            Specimen().setSubject(subject),
            QuestionnaireResponse().setSubject(subject),
            DiagnosticReport().setSubject(subject),
        ).forEach { assertEquals(it.resourceType.name, "p1", PatientDataLastUpdated.patientOf(it)) }
    }

    @Test
    fun `patients, contact lists and records of no patient name none`() {
        assertNull(PatientDataLastUpdated.patientOf(Patient().apply { id = "p1" }))
        assertNull(PatientDataLastUpdated.patientOf(Group().apply { addMember().entity = subject }))
        assertNull(PatientDataLastUpdated.patientOf(Location()))
        assertNull(PatientDataLastUpdated.patientOf(Observation().setSubject(Reference("Group/g1"))))
        assertNull(PatientDataLastUpdated.patientOf(Observation()))
    }

    @Test
    fun `the stamp is one extension with the time to the millisecond`() {
        val patient = Patient()
        PatientDataLastUpdated.stamp(patient, Date(1_000))
        val now = Date()
        PatientDataLastUpdated.stamp(patient, now)

        val stamps = patient.extension.filter { it.url == PatientDataLastUpdated.EXTENSION_URL }
        assertEquals(1, stamps.size)
        val value = stamps.single().value as DateTimeType
        assertEquals(now, value.value)
        // As the backend writes it, e.g. 2026-10-24T10:39:40.272+03:00
        assertTrue(value.valueAsString, value.valueAsString.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}([+-]\d\d:\d\d|Z)""")))
    }
}
