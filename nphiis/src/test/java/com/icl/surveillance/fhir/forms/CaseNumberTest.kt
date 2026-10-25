package com.icl.surveillance.fhir.forms

import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Patient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaseNumberTest {

    private fun alertId(value: String) = Identifier().apply {
        use = Identifier.IdentifierUse.OFFICIAL
        type = CodeableConcept().addCoding(Coding(FhirSystems.ALERT_LOG_ID_SYSTEM, FhirSystems.ALERT_LOG_ID, "Alert Log ID"))
        system = FhirSystems.ALERT_LOG_ID_SYSTEM
        this.value = value
    }

    private fun epid(value: String) = Identifier().apply {
        type = CodeableConcept().addCoding(Coding().setCode(FhirSystems.EPID))
        this.value = value
    }

    @Test
    fun `an alert shows the HIE's alert number`() {
        val patient = Patient()
        patient.addIdentifier(Identifier().setSystem("alert-rumor-log").setValue("47600573-a7e6-4fb8-9b2a-de3b59e155d9"))
        patient.addIdentifier(alertId("KEN-GAR-DAC-2026-ALR-001"))
        assertEquals("KEN-GAR-DAC-2026-ALR-001", FhirSystems.caseNumberOf(patient)?.value)
    }

    @Test
    fun `a case shows its EPID number`() {
        val patient = Patient()
        patient.addIdentifier(alertId("KEN-GAR-DAC-2026-ALR-001"))
        patient.addIdentifier(epid("KEN-GAR-DAC-2026-VHF-004"))
        assertEquals("KEN-GAR-DAC-2026-VHF-004", FhirSystems.caseNumberOf(patient)?.value)
    }

    @Test
    fun `no number before the server assigns one`() {
        assertNull(FhirSystems.caseNumberOf(Patient()))
    }
}
