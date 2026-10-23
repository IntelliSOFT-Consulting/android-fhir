package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import java.util.Date
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import timber.log.Timber

/**
 * A VHF case confirmed by the lab (any final positive specimen, see [VhfSpecimens]) becomes a
 * Confirmed case: its case form's "Type of case" answer and the stored Type of case Observations
 * are changed from Suspected / Probable to Confirmed, so it moves from the Suspected list to the
 * Confirmed list. Each case is changed once; after that it is Confirmed and is left alone.
 * Contacts are not changed (a contact becomes a case through its own conversion flow).
 */
object VhfCaseConfirmation {
    private const val TAG = "VHF_CONFIRM"
    private val PROMOTABLE = setOf("suspected", "probable", "")

    /** Confirms every lab-confirmed case still listed as suspected. Returns how many changed. */
    suspend fun confirmLabConfirmedCases(fhirEngine: FhirEngine): Int = mutex.withLock {
        // One pass at a time: the module screen can ask twice at once (each would save the case).
        var confirmed = 0
        VhfSpecimens.patientsWithPositiveSpecimen(fhirEngine).forEach { patientId ->
            runCatching { if (confirm(fhirEngine, patientId)) confirmed++ }
                .onFailure { Timber.tag(TAG).e(it, "Could not confirm Patient/%s", patientId) }
        }
        if (confirmed > 0) Timber.tag(TAG).i("%d case(s) moved from suspected to confirmed", confirmed)
        confirmed
    }

    private val mutex = Mutex()

    private suspend fun confirm(fhirEngine: FhirEngine, patientId: String): Boolean {
        val response = CaseResponse.find(fhirEngine, patientId) ?: return false
        // Only VHF case forms have Type of case (alert logs with a specimen do not).
        val typeItem = findItem(response.item) ?: return false
        val current = typeItem.answerFirstRep.value?.answerText()?.trim()?.lowercase().orEmpty()
        if (current !in PROMOTABLE) return false
        val encounterId = response.encounter?.referenceElement?.idPart?.takeIf { it.isNotBlank() }
        // The positive specimen must belong to this case (not another case of the patient).
        val summary = VhfSpecimens.summaryForCase(fhirEngine, patientId, encounterId)
        if (summary?.result != VhfSpecimens.Result.POSITIVE) return false

        typeItem.answer = mutableListOf(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().setValue(
                Coding().setCode(CONFIRMED_CODE).setDisplay(FormFields.Vhf.CASE_TYPE_CONFIRMED)
            )
        )
        LocalWins.update(fhirEngine, response)

        val observations = encounterId?.let { id ->
            fhirEngine.search<Observation> {
                filter(Observation.ENCOUNTER, { value = "Encounter/$id" })
            }.map { it.resource }
                .filter { obs -> obs.code.coding.any { it.code == FormFields.Vhf.CASE_TYPE } }
                .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR }
        }.orEmpty()
        observations.forEach { obs ->
            obs.value = StringType(FormFields.Vhf.CASE_TYPE_CONFIRMED)
            obs.code.text = FormFields.Vhf.CASE_TYPE_CONFIRMED
            obs.issued = Date()
        }
        if (observations.isNotEmpty()) LocalWins.update(fhirEngine, *observations.toTypedArray())
        Timber.tag(TAG).d("Patient/%s: Type of case %s -> Confirmed (%d observations)", patientId, current, observations.size)
        return true
    }

    private fun findItem(
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent? =
        items.firstNotNullOfOrNull { item ->
            if (item.linkId == FormFields.Vhf.CASE_TYPE) item
            else findItem(item.item) ?: item.answer.firstNotNullOfOrNull { findItem(it.item) }
        }

    private const val CONFIRMED_CODE = "confirmed"
}
