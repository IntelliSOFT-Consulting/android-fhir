package com.icl.surveillance.fhir

import ca.uhn.fhir.context.FhirContext
import java.nio.file.Files
import java.nio.file.Path
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdcTemplateExtractBundleExtractorTest {

  private val parser = FhirContext.forR4Cached().newJsonParser()

  @Test
  fun `extracts bundle resources from template bundle`() {
    val questionnaire =
      parser.parseResource(Questionnaire::class.java, TEMPLATE_QUESTIONNAIRE_JSON)
    val questionnaireResponse =
      parser.parseResource(QuestionnaireResponse::class.java, QUESTIONNAIRE_RESPONSE_JSON)

    val bundle = SdcTemplateExtractBundleExtractor().extract(questionnaire, questionnaireResponse)

    assertEquals(Bundle.BundleType.TRANSACTION, bundle.type)
    assertEquals(3, bundle.entry.size)

    val patient = bundle.entry.mapNotNull { it.resource }.filterIsInstance<Patient>().single()
    assertEquals("Otieno", patient.nameFirstRep.family)

    val observations =
      bundle.entry.mapNotNull { it.resource }.filterIsInstance<Observation>().associateBy {
        it.code.codingFirstRep.code
      }
    assertTrue(observations.containsKey("family"))
    assertTrue(observations.containsKey("fever"))
    assertEquals("Otieno", observations.getValue("family").valueStringType.value)
    assertEquals("Yes", observations.getValue("fever").valueStringType.value)
  }

  @Test
  fun `extracts resources from add-case questionnaire template`() {
    val questionnaire =
      parser.parseResource(
        Questionnaire::class.java,
        loadQuestionnaireAsset("add-case.json"),
      )
    val questionnaireResponse =
      parser.parseResource(QuestionnaireResponse::class.java, ADD_CASE_RESPONSE_JSON)

    val bundle = SdcTemplateExtractBundleExtractor().extract(questionnaire, questionnaireResponse)

    val resources = bundle.entry.mapNotNull { it.resource }
    assertTrue(resources.any { it is Patient })
    assertTrue(resources.any { it.fhirType() == "Encounter" })
    val observations = resources.filterIsInstance<Observation>()
    val observationCodes = observations.map { it.code.codingFirstRep.code }.toSet()
    val answeredLinkIds = questionnaireResponse.answeredLinkIds()

    assertTrue(observationCodes.contains("EPID"))
    assertTrue(observationCodes.containsAll(answeredLinkIds))
    assertEquals(answeredLinkIds.size + 1, observations.size)
    assertEquals(1, resources.count { it.fhirType() == "Specimen" })
  }

  private fun QuestionnaireResponse.answeredLinkIds(): Set<String> {
    val linkIds = mutableSetOf<String>()

    fun collect(items: List<QuestionnaireResponseItemComponent>) {
      items.forEach { item ->
        if (item.answer.isNotEmpty() && item.linkId != null) {
          linkIds += item.linkId
        }
        if (item.item.isNotEmpty()) {
          collect(item.item)
        }
      }
    }

    collect(item)
    return linkIds
  }

  private fun loadQuestionnaireAsset(fileName: String): String {
    val candidates =
      listOf(
        Path.of("nphiis", "src", "main", "assets", fileName),
        Path.of("src", "main", "assets", fileName),
      )

    return candidates
      .firstOrNull(Files::exists)
      ?.let { String(Files.readAllBytes(it)) }
      ?: error("Unable to locate questionnaire asset $fileName")
  }

  companion object {
    private const val TEMPLATE_QUESTIONNAIRE_JSON =
      """
      {
        "resourceType": "Questionnaire",
        "extension": [
          {
            "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractBundle",
            "valueReference": {
              "reference": "#bundle-template"
            }
          }
        ],
        "contained": [
          {
            "resourceType": "Bundle",
            "id": "bundle-template",
            "type": "transaction",
            "entry": [
              {
                "resource": {
                  "resourceType": "Patient",
                  "name": [
                    {
                      "family": "",
                      "_family": {
                        "extension": [
                          {
                            "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                            "valueString": "descendants().where(linkId='family').answer.value.first()"
                          }
                        ]
                      }
                    }
                  ]
                }
              },
              {
                "extension": [
                  {
                    "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext",
                    "valueString": "descendants().where(linkId.exists() and answer.exists())"
                  }
                ],
                "resource": {
                  "resourceType": "Observation",
                  "status": "final",
                  "code": {
                    "coding": [
                      {
                        "code": "",
                        "_code": {
                          "extension": [
                            {
                              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                              "valueString": "linkId"
                            }
                          ]
                        }
                      }
                    ]
                  },
                  "_valueString": {
                    "extension": [
                      {
                        "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue",
                        "valueString": "(answer.value.display | answer.value.code | answer.value).join(', ')"
                      }
                    ]
                  }
                }
              }
            ]
          }
        ]
      }
      """

    private const val QUESTIONNAIRE_RESPONSE_JSON =
      """
      {
        "resourceType": "QuestionnaireResponse",
        "status": "completed",
        "item": [
          {
            "linkId": "family",
            "text": "Surname",
            "answer": [
              {
                "valueString": "Otieno"
              }
            ]
          },
          {
            "linkId": "fever",
            "text": "Fever",
            "answer": [
              {
                "valueCoding": {
                  "code": "Yes",
                  "display": "Yes"
                }
              }
            ]
          }
        ]
      }
      """

    private const val ADD_CASE_RESPONSE_JSON =
      """
      {
        "resourceType": "QuestionnaireResponse",
        "status": "completed",
        "item": [
          {
            "linkId": "873240407472",
            "text": "First name",
            "answer": [
              {
                "valueString": "Achieng"
              }
            ]
          },
          {
            "linkId": "246751846436",
            "text": "Middle name",
            "answer": [
              {
                "valueString": "Atieno"
              }
            ]
          },
          {
            "linkId": "486402457213",
            "text": "Surname/Family name",
            "answer": [
              {
                "valueString": "Otieno"
              }
            ]
          },
          {
            "linkId": "929966324957",
            "text": "Sex",
            "answer": [
              {
                "valueCoding": {
                  "code": "male",
                  "display": "Male"
                }
              }
            ]
          },
          {
            "linkId": "257830485990",
            "text": "Date of Birth",
            "answer": [
              {
                "valueDate": "2018-01-01"
              }
            ]
          },
          {
            "linkId": "parent",
            "text": "Parent / Guardian Name",
            "answer": [
              {
                "valueString": "Grace Akinyi"
              }
            ]
          },
          {
            "linkId": "a4-county",
            "text": "County",
            "answer": [
              {
                "valueReference": {
                  "reference": "Location/county-1",
                  "display": "Nairobi"
                }
              }
            ]
          },
          {
            "linkId": "a3-sub-county",
            "text": "Sub County",
            "answer": [
              {
                "valueReference": {
                  "reference": "Location/subcounty-1",
                  "display": "Westlands"
                }
              }
            ]
          },
          {
            "linkId": "a2-ward",
            "text": "Ward",
            "answer": [
              {
                "valueReference": {
                  "reference": "Location/ward-1",
                  "display": "Parklands"
                }
              }
            ]
          },
          {
            "linkId": "242811643559",
            "text": "Residence or Village",
            "answer": [
              {
                "valueString": "Village A"
              }
            ]
          },
          {
            "linkId": "946232932304",
            "text": "Neighborhood major landmark",
            "answer": [
              {
                "valueString": "Near Market"
              }
            ]
          },
          {
            "linkId": "424111786438",
            "text": "Street/Plot/Estate/S. location",
            "answer": [
              {
                "valueString": "Plot 12"
              }
            ]
          },
          {
            "linkId": "754217593839",
            "text": "Telephone No of parent/guardian",
            "answer": [
              {
                "valueString": "0712345678"
              }
            ]
          },
          {
            "linkId": "412689284625",
            "text": "Please select specimen collected",
            "answer": [
              {
                "valueCoding": {
                  "code": "Blood",
                  "display": "Blood"
                }
              }
            ]
          },
          {
            "linkId": "8962468583341",
            "text": "Date of specimen collection",
            "answer": [
              {
                "valueDate": "2026-05-20"
              }
            ]
          }
        ]
      }
      """
  }
}
