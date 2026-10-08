package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.QuestionnaireResponse

/**
 * Finds the case form's QuestionnaireResponse for a patient. A case also has responses for its
 * child forms (lab results, follow ups, monitoring) with the same subject, so "the first response
 * of the patient" can be a follow up: editing the case then opened a blank case form, and saving
 * it overwrote that follow up. The case response is the one on the case encounter.
 */
object CaseResponse {

    suspend fun find(
        fhirEngine: FhirEngine,
        patientId: String,
        caseEncounterId: String? = null,
    ): QuestionnaireResponse? {
        val responses = fhirEngine.search<QuestionnaireResponse> {
            filter(QuestionnaireResponse.SUBJECT, { value = "Patient/$patientId" })
        }.map { it.resource }
        if (responses.isEmpty()) return null

        fun encounterOf(response: QuestionnaireResponse): String? =
            response.encounter?.referenceElement?.idPart?.takeIf { it.isNotBlank() }

        // 1. The response on the case encounter the record was opened with.
        caseEncounterId?.takeIf { it.isNotBlank() }?.let { id ->
            responses.firstOrNull { encounterOf(it) == id }?.let { return it }
        }
        // 2. A response on a top-level encounter (child forms' encounters are partOf the case).
        responses.firstOrNull { response ->
            val encounterId = encounterOf(response) ?: return@firstOrNull false
            val encounter = runCatching { fhirEngine.get<Encounter>(encounterId) }.getOrNull()
            encounter != null && !encounter.hasPartOf()
        }?.let { return it }
        // 3. A response without an encounter, else the oldest one.
        return responses.firstOrNull { encounterOf(it) == null }
            ?: responses.minByOrNull { it.meta.lastUpdated?.time ?: Long.MAX_VALUE }
    }

    suspend fun findId(fhirEngine: FhirEngine, patientId: String, caseEncounterId: String? = null): String =
        find(fhirEngine, patientId, caseEncounterId)?.logicalId ?: ""
}
