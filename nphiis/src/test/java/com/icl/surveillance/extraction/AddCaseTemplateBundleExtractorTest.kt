package com.icl.surveillance.extraction

import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DomainResource
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Procedure
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AddCaseTemplateBundleExtractorTest {
    @Test
    fun extractsAddCaseResourcesFromExplicitTemplateList() {
        val bundle =
            TemplateBundleExtractor().extract(
                questionnaireResponse = buildQuestionnaireResponse(),
                templateJsons =
                    listOf(
                        readAsset("templates/add-case/patient.json"),
                        readAsset("templates/add-case/encounter.json"),
                        readAsset("templates/add-case/observation.json"),
                        readAsset("templates/add-case/specimen.json"),
                        readAsset("templates/add-case/procedure.json"),
                    ),
            )

        val resourceTypes = bundle.entry.mapNotNull { it.resource?.fhirType() }
        assertTrue(resourceTypes.contains("Patient"))
        assertTrue(resourceTypes.contains("Encounter"))
        assertTrue(resourceTypes.contains("Observation"))
        assertTrue(resourceTypes.contains("Specimen"))
        assertTrue(resourceTypes.contains("Procedure"))

        val domainResources = bundle.entry.mapNotNull { it.resource as? DomainResource }
        domainResources.forEach { resource ->
            assertTrue(
                resource.meta.tag.any { tag ->
                    tag.system == "https://intellisoftkenya.com/fhir/CodeSystem/reporting-site" &&
                        tag.code == "Location/1001" &&
                        tag.display == "Parklands Health Centre"
                },
            )
            assertTrue(resource.extension.isEmpty())
        }

        val patient = bundle.entry.map { it.resource }.filterIsInstance<Patient>().first()
        assertEquals("EPID-2026-0001", patient.identifierFirstRep.value)
        assertEquals("Akinyi", patient.nameFirstRep.given.first().value)
        assertEquals("Nairobi County", patient.addressFirstRep.state)
        assertEquals("Mary Wanjiku", patient.contactFirstRep.name.text)

        val encounter = bundle.entry.map { it.resource }.filterIsInstance<Encounter>().first()
        assertEquals("IMP", encounter.class_.code)
        assertEquals("Parklands Health Centre", encounter.locationFirstRep.location.display)
        assertTrue(encounter.identifier.any { it.value == "IPOP-7788" })

        val procedure = bundle.entry.map { it.resource }.filterIsInstance<Procedure>().first()
        assertEquals("Home contact investigation visit", procedure.code.text)
        assertEquals("2026-04-15", procedure.performedDateTimeType.asStringValue())

        val specimens = bundle.entry.map { it.resource }.filterIsInstance<Specimen>()
        assertEquals(3, specimens.size)
        assertEquals(
            setOf("Blood", "Urine", "CSF"),
            specimens.map { it.type.text }.toSet(),
        )

        val serialized = FhirContext.forR4Cached().newJsonParser().encodeResourceToString(bundle)
        assertTrue(serialized.contains("\"code\":\"745196148424\""))
        assertTrue(serialized.contains("\"display\":\"Fever\""))
        assertTrue(serialized.contains("\"display\":\"Rash\""))
        assertTrue(serialized.contains("\"code\":\"483042281962\""))
    }

    private fun buildQuestionnaireResponse(): QuestionnaireResponse =
        QuestionnaireResponse().apply {
            status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
            addItem(
                groupItem(
                    "151479012557",
                    stringItem("user_role", "VACCINATOR"),
                    stringItem("user_county", "47"),
                    stringItem("user_sub_county", "4701"),
                    stringItem("user_ward", "470101"),
                    stringItem("user_facility", "1001"),
                    groupItem(
                        "facility_level",
                        referenceItem("294367770999", "Location/47", "Nairobi County"),
                        referenceItem("819946803642", "Location/4701", "Westlands"),
                        referenceItem("819943434", "Location/470101", "Parklands"),
                        referenceItem("819946803677", "Location/1001", "Parklands Health Centre"),
                        codingItem("438862163919", "Public", "Public"),
                    ),
                    stringItem("992818778559", "EPID-2026-0001"),
                ),
            )
            addItem(
                groupItem(
                    "670954892057",
                    stringItem("873240407472", "Akinyi"),
                    stringItem("246751846436", "Grace"),
                    stringItem("486402457213", "Otieno"),
                    codingItem("929966324957", "female", "Female"),
                    codingItem("442636360588", "known", "Known"),
                    dateItem("257830485990", "2017-08-20"),
                    codingItem("residence", "Urban", "Urban"),
                    stringItem("parent", "Mary Wanjiku"),
                    referenceItem("a4-county", "Location/47", "Nairobi County"),
                    referenceItem("a3-sub-county", "Location/4701", "Westlands"),
                    referenceItem("a2-ward", "Location/470101", "Parklands"),
                    stringItem("242811643559", "Ngara"),
                    stringItem("946232932304", "Near City Market"),
                    stringItem("424111786438", "Plot 12"),
                    stringItem("754217593839", "0712345678"),
                ),
            )
            addItem(
                groupItem(
                    "216343227137",
                    dateItem("554231819382", "2026-04-12"),
                    codingItem("483042281962", "yes", "Yes"),
                    dateItem("340908984116", "2026-04-13"),
                    stringItem("755731625544", "IPOP-7788"),
                    codingItem("508745697175", "Alive", "Alive"),
                ),
            )
            addItem(
                groupItem(
                    "477144604557",
                    dateItem("728034137219", "2026-04-10"),
                    codingAnswersItem(
                        "745196148424",
                        Coding().apply {
                            code = "Fever"
                            display = "Fever"
                        },
                        Coding().apply {
                            code = "Rash"
                            display = "Rash"
                        },
                    ),
                    dateItem("576528567552", "2026-04-11"),
                    codingItem("704922081985", "Maculopapular", "Maculopapular"),
                    codingItem("207408507040", "yes", "Yes"),
                    dateItem("566661890668", "2026-04-15"),
                    codingItem("865158268604", "Case", "Case"),
                ),
            )
            addItem(
                groupItem(
                    "736291402384",
                    codingItem("517772812375", "yes", "Yes"),
                    integerItem("886125589225", 2),
                    codingItem("308128177300", "yes", "Yes"),
                    dateItem("544290619304", "2026-04-01"),
                ),
            )
            addItem(
                groupItem(
                    "271053545237",
                    codingAnswersItem(
                        "412689284625",
                        Coding().apply {
                            code = "Blood"
                            display = "Blood"
                        },
                        Coding().apply {
                            code = "Urine"
                            display = "Urine"
                        },
                    ),
                    dateItem("8962468583341", "2026-04-16"),
                    codingItem("258912872921", "yes", "Yes"),
                    stringItem("340507649387", "CSF"),
                    dateItem("699353598445", "2026-04-16"),
                    dateItem("718251724172", "2026-04-17"),
                ),
            )
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

    private fun integerItem(
        linkId: String,
        value: Int,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            answerFirstRep.value = IntegerType(value)
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

    private fun referenceItem(
        linkId: String,
        reference: String,
        display: String,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            answerFirstRep.value = Reference(reference).apply {
                this.display = display
            }
        }

    private fun codingAnswersItem(
        linkId: String,
        vararg codings: Coding,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            this.linkId = linkId
            codings.forEach { coding ->
                addAnswer(
                    QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                        value = coding
                    },
                )
            }
        }

    private fun readAsset(path: String): String =
        listOf(
            File("nphiis/src/main/assets/$path"),
            File("src/main/assets/$path"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("Unable to locate asset '$path' from test runtime.")
}
