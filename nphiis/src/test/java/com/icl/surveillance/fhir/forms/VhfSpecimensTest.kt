package com.icl.surveillance.fhir.forms

import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Specimen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VhfSpecimensTest {

    private fun row(result: VhfSpecimens.Result) =
        VhfSpecimens.Row("id", "Blood", "MV990JK0", null, null, null, emptyList(), result, null)

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
    fun `reads the result extension and respects the specimen status`() {
        val specimen = Specimen().apply { status = Specimen.SpecimenStatus.AVAILABLE }
        assertEquals(VhfSpecimens.Result.PENDING, VhfSpecimens.resultOf(specimen))

        specimen.addExtension(
            VhfSpecimens.RESULT_EXTENSION,
            CodeableConcept().addCoding(Coding("http://nphiis.health.go.ke/fhir/CodeSystem/lab-result", "positive", "Positive"))
        )
        assertEquals(VhfSpecimens.Result.POSITIVE, VhfSpecimens.resultOf(specimen))

        specimen.status = Specimen.SpecimenStatus.UNSATISFACTORY
        assertEquals(VhfSpecimens.Result.REJECTED, VhfSpecimens.resultOf(specimen))
    }
}
