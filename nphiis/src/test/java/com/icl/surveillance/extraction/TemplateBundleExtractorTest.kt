package com.icl.surveillance.extraction

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateBundleExtractorTest {
    private val parser = FhirContext.forR4Cached().newJsonParser()

    @Test
    fun extractsPatientBundleFromTemplateAssets() {
        val questionnaire =
            parser.parseResource(readAsset("templates/patient.json")) as Questionnaire
        val bundle =
            TemplateBundleExtractor().extract(
                questionnaireResponse = buildQuestionnaireResponse(),
                questionnaire = questionnaire,
            )

        println("Template bundle entry count: ${bundle.entry.size}")
        bundle.entry.forEachIndexed { index, entry ->
            println("Entry #$index ${parser.encodeResourceToString(entry.resource)}")
        }

        assertEquals(1, bundle.entry.size)

        val patient = bundle.entry.first().resource as Patient
        assertEquals("Doe", patient.nameFirstRep.family)
        assertEquals("Jane", patient.nameFirstRep.given.first().value)
        assertEquals("female", patient.gender?.toCode())
        assertEquals("1990-01-02", patient.birthDateElement.asStringValue())
        assertTrue(patient.telecom.any { it.system.toCode() == "phone" && it.value == "+254700000001" })
        assertTrue(patient.identifier.any { it.value == "ID-12345" })
    }

    @Test
    fun extractsMultipleResourcesFromTemplateList() {
        val bundle =
            TemplateBundleExtractor().extract(
                questionnaireResponse = buildQuestionnaireResponse(),
                templateJsons =
                    listOf(
                        """
                        {
                          "resourceType": "Patient",
                          "name": [
                            {
                              "given": [
                                "{{ QuestionnaireResponse.item.where(linkId='name').item.where(linkId='name.given').answer.valueString }}"
                              ],
                              "family": "{{ QuestionnaireResponse.item.where(linkId='name').item.where(linkId='name.family').answer.valueString }}"
                            }
                          ]
                        }
                        """.trimIndent(),
                        """
                        {
                          "resourceType": "Observation",
                          "status": "final",
                          "code": {
                            "text": "Identifier Observation"
                          },
                          "valueString": "{{ QuestionnaireResponse.item.where(linkId='identifier').answer.valueString }}"
                        }
                        """.trimIndent(),
                        """
                        {
                          "resourceType": "Procedure",
                          "status": "completed",
                          "code": {
                            "text": "Registration Procedure"
                          },
                          "performedDateTime": "{{ QuestionnaireResponse.item.where(linkId='birthDate').answer.valueDate }}"
                        }
                        """.trimIndent(),
                    ),
            )

        assertEquals(
            listOf("Patient", "Observation", "Procedure"),
            bundle.entry.mapNotNull { it.resource?.fhirType() },
        )
    }

    private fun buildQuestionnaireResponse(): QuestionnaireResponse =
        QuestionnaireResponse().apply {
            status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
            addItem(
                groupItem(
                    "name",
                    stringItem("name.given", "Jane"),
                    stringItem("name.family", "Doe"),
                ),
            )
            addItem(dateItem("birthDate", "1990-01-02"))
            addItem(codingItem("gender", "female", "Female"))
            addItem(
                groupItem(
                    "telecom",
                    stringItem("telecom.phone", "+254700000001"),
                    stringItem("telecom.email", "jane@example.org"),
                ),
            )
            addItem(
                groupItem(
                    "address",
                    stringItem("address.line", "123 Example Street"),
                    stringItem("address.city", "Nairobi"),
                    stringItem("address.postalCode", "00100"),
                    stringItem("address.country", "Kenya"),
                ),
            )
            addItem(stringItem("identifier", "ID-12345"))
        }

    private fun groupItem(
        linkId: String,
        vararg children: QuestionnaireResponse.QuestionnaireResponseItemComponent,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            children.forEach(::addItem)
        }

    private fun stringItem(
        linkId: String,
        value: String,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            answerFirstRep.value = StringType(value)
        }

    private fun dateItem(
        linkId: String,
        value: String,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            answerFirstRep.value = DateType(value)
        }

    private fun codingItem(
        linkId: String,
        code: String,
        display: String,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            answerFirstRep.value = Coding().apply {
                this.code = code
                this.display = display
            }
        }

    private fun readAsset(path: String): String =
        listOf(
            File("nphiis/src/main/assets/$path"),
            File("src/main/assets/$path"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("Unable to locate asset '$path' from test runtime.")
}
