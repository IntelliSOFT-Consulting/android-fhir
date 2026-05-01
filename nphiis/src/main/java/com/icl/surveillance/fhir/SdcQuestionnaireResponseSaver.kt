package com.icl.surveillance.fhir

import android.content.Context
import android.util.Log
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator
import com.icl.surveillance.models.FacilityInfo
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.QuestionnaireHelper
import com.icl.surveillance.utils.toSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.DomainResource
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import java.util.Date
import java.util.Locale
import java.util.UUID

class SdcQuestionnaireResponseSaver(
    private val context: Context,
    private val fhirEngine: FhirEngine = FhirApplication.fhirEngine(context.applicationContext)
) {

    data class SaveResult(
        val isSuccess: Boolean,
        val userMessage: String? = null
    )

    suspend fun save(
        questionnaire: Questionnaire,
        questionnaireResponse: QuestionnaireResponse,
        extractedBundle: Bundle
    ): SaveResult = withContext(Dispatchers.IO) {
        if (QuestionnaireResponseValidator.validateQuestionnaireResponse(
                questionnaire,
                questionnaireResponse,
                context.applicationContext,
            ).values.flatten().any { it is Invalid }
        ) {
            return@withContext SaveResult(
                isSuccess = false,
                userMessage = "Please Enter all Required Fields."
            )
        }

        if (extractedBundle.entry.isEmpty()) {
            Log.w(TAG, "SDC extraction bundle is empty.")
            return@withContext SaveResult(
                isSuccess = false,
                userMessage = "No resources were extracted from this questionnaire response."
            )
        }

        return@withContext try {
            persistExtractedResources(
                questionnaireResponse = questionnaireResponse,
                extractedBundle = extractedBundle,
            )
            SaveResult(isSuccess = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save SDC extracted resources", e)
            SaveResult(
                isSuccess = false,
                userMessage = "Failed to save the extracted SDC resources."
            )
        }
    }

    private suspend fun persistExtractedResources(
        questionnaireResponse: QuestionnaireResponse,
        extractedBundle: Bundle,
    ) {
        val formatter = FormatterClass()
        val currentCase = formatter.getSharedPref("currentCase", context)
        val facility = formatter.getSharedPref("facility", context)
        val practitionerId = formatter.getSharedPref("fhirPractitionerId", context)
        val questionnaireHelper = QuestionnaireHelper()
        val extractedAnswers = extractStructuredAnswers(questionnaireResponse)

        val extractedResources = extractedBundle.entry.mapNotNull { it.resource }
        val patient = extractedResources.filterIsInstance<Patient>().firstOrNull()
            ?: throw IllegalStateException("SDC extraction did not produce a Patient resource.")

        if (!patient.hasId()) {
            patient.id = generateUuid()
        }
        patient.active = true

        val systemCreationIdentifier = createSystemCreationIdentifier()
        val caseSlug = currentCase?.toSlug() ?: "case-info"
        patient.identifier.add(systemCreationIdentifier)
        patient.identifier.add(createCaseIdentifier(caseSlug, patient.idPart))

        if (practitionerId != null) {
            patient.generalPractitionerFirstRep.reference = "Practitioner/$practitionerId"
        }
        applyManagedLocation(patient, "patient", facility, extractedAnswers)

        val subjectReference = Reference("Patient/${patient.idPart}")
        val extractedEncounter = extractedResources.filterIsInstance<Encounter>().firstOrNull()
        val encounter = extractedEncounter ?: questionnaireHelper.generalEncounter(null, generateUuid())
        if (!encounter.hasId()) {
            encounter.id = generateUuid()
        }
        encounter.subject = subjectReference
        encounter.reasonCodeFirstRep.codingFirstRep.apply {
            code = currentCase ?: "Case Information"
            display = currentCase ?: "Case Information"
            system = caseSlug
        }
        encounter.identifier.add(systemCreationIdentifier.copy())
        encounter.identifier.add(createCaseIdentifier(caseSlug, encounter.idPart))
        if (practitionerId != null) {
            encounter.participantFirstRep.individual = Reference("Practitioner/$practitionerId")
        }
        if (encounter.reasonCode.none { reason ->
                reason.coding.any { it.code == "case-information" }
            }
        ) {
            encounter.addReasonCode(
                CodeableConcept().apply {
                    codingFirstRep.code = "case-information"
                    codingFirstRep.display = "case-information"
                    codingFirstRep.system = "case-information"
                    text = "case-information"
                }
            )
        }
        applyManagedLocation(encounter, "encounter", facility, extractedAnswers)

        val encounterReference = Reference("Encounter/${encounter.idPart}")

        fhirEngine.create(patient)
        fhirEngine.create(encounter)

        extractedResources.forEach { resource ->
            when (resource) {
                patient, encounter -> Unit
                is Observation -> {
                    if (!resource.hasId()) {
                        resource.id = generateUuid()
                    }
                    if (!resource.hasSubject()) {
                        resource.subject = subjectReference
                    }
                    if (!resource.hasEncounter()) {
                        resource.encounter = encounterReference
                    }
                    if (!resource.hasStatus()) {
                        resource.status = Observation.ObservationStatus.FINAL
                    }
                    if (!resource.hasIssued()) {
                        resource.issued = Date()
                    }
                    if (practitionerId != null && resource.performer.isEmpty()) {
                        resource.performerFirstRep.reference = "Practitioner/$practitionerId"
                    }
                    applyManagedLocation(resource, "observation", facility, extractedAnswers)
                    fhirEngine.create(resource)
                }

                is Specimen -> {
                    if (!resource.hasId()) {
                        resource.id = generateUuid()
                    }
                    if (!resource.hasSubject()) {
                        resource.subject = subjectReference
                    }
                    applyManagedLocation(resource, "specimen", facility, extractedAnswers)
                    fhirEngine.create(resource)
                }

                else -> {
                    if (!resource.hasId()) {
                        resource.id = generateUuid()
                    }
                    if (resource is DomainResource) {
                        applyManagedLocation(
                            resource,
                            resource.fhirType().lowercase(Locale.ROOT),
                            facility,
                            extractedAnswers
                        )
                    }
                    fhirEngine.create(resource)
                }
            }
        }

        questionnaireResponse.id = generateUuid()
        questionnaireResponse.subject = subjectReference
        questionnaireResponse.encounter = encounterReference
        if (practitionerId != null) {
            questionnaireResponse.author = Reference("Practitioner/$practitionerId")
        }
        applyManagedLocation(questionnaireResponse, "questionnaire", facility, extractedAnswers)
        fhirEngine.create(questionnaireResponse)
    }

    private suspend fun applyManagedLocation(
        resource: DomainResource,
        resourceName: String,
        facility: String?,
        extractedAnswers: List<QuestionnaireAnswer>
    ) {
        resource.meta = Meta().apply {
            tag = listOf(sourceMetaTag(resourceName, facility, extractedAnswers))
        }
        resource.addExtension(sourceExtension(resourceName, extractedAnswers))
    }

    private suspend fun sourceMetaTag(
        resource: String,
        fallbackFacilityId: String?,
        extractedAnswers: List<QuestionnaireAnswer>
    ): Coding {
        val info = resolveManagedLocationInfo(extractedAnswers, fallbackFacilityId)
            ?: FacilityInfo(name = "Unknown Facility", code = "unknown")
        return Coding().apply {
            system = "http://example.org/fhir/StructureDefinition/$resource-managingLocation"
            code = "Location/${info.code}"
            display = info.name
        }
    }

    private suspend fun sourceExtension(
        resource: String,
        extractedAnswers: List<QuestionnaireAnswer>
    ): Extension {
        val info = resolveManagedLocationInfo(extractedAnswers, null)
            ?: FacilityInfo(name = "Unknown Facility", code = "unknown")
        return Extension().apply {
            url = "http://example.org/fhir/StructureDefinition/$resource-managingLocation"
            setValue(
                Reference().apply {
                    reference = "Location/${info.code}"
                    display = info.name
                }
            )
        }
    }

    private fun resolveManagedLocationInfo(
        extractedAnswers: List<QuestionnaireAnswer>,
        fallbackFacilityId: String?
    ): FacilityInfo? {
        val normalizedLocation = listOf(
            "reporting_site_facility" to "reporting_site_facility_display",
            "reporting_site_ward" to "reporting_site_ward_display",
            "reporting_site_sub_county" to "reporting_site_sub_county_display",
            "reporting_site_county" to "reporting_site_county_display"
        ).firstNotNullOfOrNull { (referenceLinkId, displayLinkId) ->
            val locationReference = extractedAnswers.firstOrNull { it.linkId == referenceLinkId }?.answer
            val locationId = parseLocationId(locationReference)
            if (locationId.isNullOrBlank()) {
                null
            } else {
                val locationDisplay = extractedAnswers.firstOrNull { it.linkId == displayLinkId }?.answer
                FacilityInfo(
                    name = locationDisplay ?: locationId,
                    code = locationId
                )
            }
        }

        if (normalizedLocation != null) {
            return normalizedLocation
        }

        val formatter = FormatterClass()
        val storedLocation = listOf(
            formatter.getSharedPref("facility", context) to formatter.getSharedPref("facilityName", context),
            formatter.getSharedPref("ward", context) to formatter.getSharedPref("wardName", context),
            formatter.getSharedPref("subCounty", context) to formatter.getSharedPref("subCountyName", context),
            formatter.getSharedPref("county", context) to formatter.getSharedPref("countyName", context)
        ).firstOrNull { (locationId, _) -> !locationId.isNullOrBlank() }

        if (storedLocation != null) {
            return FacilityInfo(
                name = storedLocation.second ?: storedLocation.first.orEmpty(),
                code = storedLocation.first.orEmpty()
            )
        }

        return fallbackFacilityId?.takeIf { it.isNotBlank() }?.let {
            FacilityInfo(name = it, code = it)
        }
    }

    private fun parseLocationId(reference: String?): String? {
        val value = reference?.trim().orEmpty()
        if (value.isBlank()) {
            return null
        }
        return when {
            value.startsWith("Location/") -> value.removePrefix("Location/")
            value.contains("Location/") -> value.substringAfter("Location/")
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun createSystemCreationIdentifier(): Identifier {
        val formattedNow = FormatterClass().formatDateTime(Date())
        return Identifier().apply {
            system = "system-creation"
            value = formattedNow
            type = CodeableConcept().apply {
                coding = arrayListOf(
                    Coding().apply {
                        system = "system-creation"
                        code = "system_creation"
                        display = "System Creation"
                    }
                )
                text = formattedNow
            }
        }
    }

    private fun createCaseIdentifier(system: String, value: String): Identifier {
        return Identifier().apply {
            this.system = system
            this.value = value
            type = CodeableConcept().apply {
                coding = arrayListOf(
                    Coding().apply {
                        this.system = system
                        code = system
                        display = system
                    }
                )
                text = value
            }
        }
    }

    private fun extractStructuredAnswers(
        questionnaireResponse: QuestionnaireResponse
    ): List<QuestionnaireAnswer> {
        val results = mutableListOf<QuestionnaireAnswer>()

        fun processItem(item: QuestionnaireResponse.QuestionnaireResponseItemComponent) {
            val linkId = item.linkId ?: ""
            val text = item.text ?: ""

            if (item.answer.isNotEmpty()) {
                val values = item.answer.mapNotNull { extractAnswerValue(it) }
                if (values.isNotEmpty()) {
                    results.add(QuestionnaireAnswer(linkId, text, values.joinToString(", ")))
                }
            }

            if (item.item.isNotEmpty()) {
                item.item.forEach { child -> processItem(child) }
            }
        }

        questionnaireResponse.item.forEach { processItem(it) }
        return results
    }

    private fun extractAnswerValue(
        answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
    ): String? {
        val value = answer.value ?: return null
        return when (value) {
            is StringType -> value.value
            is IntegerType -> value.value?.toString()
            is DateType -> value.valueAsString
            is DateTimeType -> value.valueAsString
            is BooleanType -> value.booleanValue()?.toString()
            is DecimalType -> value.value?.toString()
            is Coding -> value.code ?: value.display
            is Reference -> value.reference ?: value.display
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun generateUuid(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "SdcQuestionnaireResponseSaver"
    }
}
