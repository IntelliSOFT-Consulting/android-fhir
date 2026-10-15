package com.icl.surveillance.fhir.forms

import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VhfSpecimensTest {

    private fun row(result: VhfSpecimens.Result, preliminary: VhfSpecimens.Result? = null) =
        VhfSpecimens.Row(
            "id", "Blood", "MV990JK0", null, null, null, emptyList(), emptyList(), result, preliminary, null
        )

    private fun test(value: String, final: Boolean = true) =
        VhfSpecimens.TestResult("PCR", value, VhfSpecimens.interpret(value), final, null)

    @Test
    fun `no specimens gives no summary`() {
        assertNull(VhfSpecimens.summarize(emptyList()))
    }

    @Test
    fun `any positive specimen makes the case positive`() {
        val summary = VhfSpecimens.summarize(
            listOf(row(VhfSpecimens.Result.NEGATIVE), row(VhfSpecimens.Result.POSITIVE), row(VhfSpecimens.Result.PENDING))
        )!!
        assertEquals(VhfSpecimens.Result.POSITIVE, summary.result)
        assertEquals("Confirmed by lab", summary.classification)
    }

    @Test
    fun `negative only when every specimen is negative`() {
        assertEquals(
            "Discarded",
            VhfSpecimens.summarize(listOf(row(VhfSpecimens.Result.NEGATIVE), row(VhfSpecimens.Result.NEGATIVE)))!!.classification
        )
        assertEquals(
            "Pending Results",
            VhfSpecimens.summarize(listOf(row(VhfSpecimens.Result.NEGATIVE), row(VhfSpecimens.Result.PENDING)))!!.classification
        )
        assertEquals(
            "Pending Results",
            VhfSpecimens.summarize(listOf(row(VhfSpecimens.Result.NEGATIVE), row(VhfSpecimens.Result.REJECTED)))!!.classification
        )
    }

    @Test
    fun `preliminary results are shown but keep the case pending`() {
        val summary = VhfSpecimens.summarize(
            listOf(row(VhfSpecimens.Result.PENDING, preliminary = VhfSpecimens.Result.POSITIVE))
        )!!
        assertEquals("Preliminary: Positive", summary.labResults)
        assertEquals("Pending Results", summary.classification)
    }

    @Test
    fun `reads result texts`() {
        assertEquals(VhfSpecimens.Result.POSITIVE, VhfSpecimens.interpret("Ebola virus RNA DETECTED"))
        assertEquals(VhfSpecimens.Result.NEGATIVE, VhfSpecimens.interpret("Not detected"))
        assertEquals(VhfSpecimens.Result.NEGATIVE, VhfSpecimens.interpret("Negative"))
        assertNull(VhfSpecimens.interpret("32.5 Ct"))
    }

    @Test
    fun `shows value x as text`() {
        assertEquals("Positive", VhfSpecimens.valueText(StringType("Positive")))
        assertEquals("Detected", VhfSpecimens.valueText(CodeableConcept().addCoding(Coding("http://snomed.info/sct", "260373001", "Detected"))))
        assertEquals("32.5 Ct", VhfSpecimens.valueText(Quantity().setValue(32.50).setUnit("Ct")))
    }

    @Test
    fun `specimen result uses final tests and respects its status`() {
        val specimen = Specimen().apply { status = Specimen.SpecimenStatus.AVAILABLE }
        assertEquals(VhfSpecimens.Result.PENDING, VhfSpecimens.resultOf(specimen, emptyList()))
        assertEquals(VhfSpecimens.Result.PENDING, VhfSpecimens.resultOf(specimen, listOf(test("Positive", final = false))))
        assertEquals(VhfSpecimens.Result.POSITIVE, VhfSpecimens.resultOf(specimen, listOf(test("Negative"), test("Detected"))))
        assertEquals(VhfSpecimens.Result.NEGATIVE, VhfSpecimens.resultOf(specimen, listOf(test("Negative"), test("Not detected"))))
        specimen.status = Specimen.SpecimenStatus.UNSATISFACTORY
        assertEquals(VhfSpecimens.Result.REJECTED, VhfSpecimens.resultOf(specimen, listOf(test("Positive"))))
    }
}
