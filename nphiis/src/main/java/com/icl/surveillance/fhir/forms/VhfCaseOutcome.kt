package com.icl.surveillance.fhir.forms

import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.fhir.FhirConformance
import com.icl.surveillance.models.QuestionnaireAnswer
import java.util.Date
import java.util.UUID
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import timber.log.Timber

/**
 * "Record outcome" (vhf-case-outcome.json) for a suspected / confirmed VHF case: the outcome is
 * saved as its own record (a child Encounter of the case, who and when), then copied onto the
 * case record so everything that reads the case form sees it:
 * - the case Encounter's answer Observations: one per code, updated in place;
 * - the case form's QuestionnaireResponse, so editing the case form shows the outcome;
 * - Patient.deceased (date of death) when the outcome is Dead.
 * The form's questions have the case form's linkIds, so each answer has a single code.
 */
object VhfCaseOutcome {
    const val TAG = "CASE_OUTCOME"

    /** The case form's outcome questions (Clinical Care & Treatment), in form order. */
    val CODES = listOf(
        FormFields.Vhf.OUTCOME,
        FormFields.Vhf.DEATH_DATE,
        FormFields.Vhf.DEATH_PLACE,
        FormFields.Vhf.DEATH_PLACE_OTHER,
        FormFields.Vhf.CASE_STATUS,
        FormFields.Vhf.DISCHARGE_DATE,
    )

    /** The case's current outcome answers, to pre-fill the form (dates as yyyy-MM-dd). */
    suspend fun prefill(fhirEngine: FhirEngine, caseEncounterId: String): Map<String, String> =
        caseAnswers(fhirEngine, caseEncounterId)
            .mapNotNull { (code, obs) ->
                val value = obs.value?.primitiveValue()?.trim().orEmpty()
                val shown = if (code == FormFields.Vhf.DEATH_DATE || code == FormFields.Vhf.DISCHARGE_DATE) {
                    VhfContactTracker.parseDate(value)?.toString()
                } else value
                shown?.takeIf { it.isNotBlank() }?.let { code to it }
            }.toMap()

    /**
     * Copies the outcome in [response] / [answers] (one save of the outcome form) onto the case
     * record [caseEncounterId] of [patientId]. Each step runs on its own, so one failing does not
     * stop the others.
     */
    suspend fun applyToCase(
        fhirEngine: FhirEngine,
        caseQuestionnaireJson: String,
        patientId: String,
        caseEncounterId: String,
        response: QuestionnaireResponse,
        answers: List<QuestionnaireAnswer>,
    ) {
        val byCode = answers.filter { it.linkId in CODES && it.answer.isNotBlank() }.associateBy { it.linkId }
        runCatching { updateObservations(fhirEngine, patientId, caseEncounterId, byCode) }
            .onFailure { Timber.tag(TAG).e(it, "Could not update the case's outcome answers") }
        runCatching { updateCaseResponse(fhirEngine, caseQuestionnaireJson, patientId, caseEncounterId, response) }
            .onFailure { Timber.tag(TAG).e(it, "Could not update the case form's outcome") }
        runCatching { updatePatient(fhirEngine, patientId, byCode) }
            .onFailure { Timber.tag(TAG).e(it, "Could not mark the patient deceased") }
    }

    /**
     * The case encounter's answers for [CODES]: the newest Observation of each code, left out when
     * that one is entered-in-error (the question no longer applies), so an older copy never wins.
     */
    private suspend fun caseAnswers(fhirEngine: FhirEngine, caseEncounterId: String): Map<String, Observation> =
        caseObservations(fhirEngine, caseEncounterId)
            .filter { it.code.codingFirstRep.code in CODES }
            .groupBy { it.code.codingFirstRep.code }
            .mapValues { (_, list) -> list.maxBy { latest(it) } }
            .filterValues { it.status != Observation.ObservationStatus.ENTEREDINERROR }

    private suspend fun caseObservations(fhirEngine: FhirEngine, caseEncounterId: String) =
        fhirEngine.search<Observation> {
            filter(Observation.ENCOUNTER, { value = "Encounter/$caseEncounterId" })
        }.map { it.resource }

    private fun latest(obs: Observation) = maxOf(obs.issued?.time ?: 0L, obs.meta.lastUpdated?.time ?: 0L)

    /**
     * One Observation per outcome code on the case encounter: updated in place, created when the
     * case has none yet, or marked entered-in-error when the question no longer applies (e.g.
     * the date of death once the outcome is Alive).
     */
    private suspend fun updateObservations(
        fhirEngine: FhirEngine,
        patientId: String,
        caseEncounterId: String,
        byCode: Map<String, QuestionnaireAnswer>,
    ) {
        val all = caseObservations(fhirEngine, caseEncounterId)
        val template = all.firstOrNull()
        CODES.forEach { code ->
            val answer = byCode[code]
            val copies = all.filter { it.code.codingFirstRep.code == code }.sortedByDescending { latest(it) }
            val existing = copies.firstOrNull()
            // Older copies of the code (e.g. from two devices) are retired: one answer per code.
            copies.drop(1).filter { it.status != Observation.ObservationStatus.ENTEREDINERROR }.forEach { old ->
                old.status = Observation.ObservationStatus.ENTEREDINERROR
                old.value = null
                old.code.text = null
                LocalWins.save(fhirEngine, old)
            }
            when {
                existing != null && answer != null -> {
                    if (existing.value?.primitiveValue() == answer.answer &&
                        existing.status != Observation.ObservationStatus.ENTEREDINERROR
                    ) return@forEach
                    existing.value = StringType(answer.answer)
                    existing.code.text = answer.answer
                    existing.status = Observation.ObservationStatus.FINAL
                    existing.issued = Date()
                    LocalWins.save(fhirEngine, existing)
                }

                existing != null -> if (existing.status != Observation.ObservationStatus.ENTEREDINERROR) {
                    existing.status = Observation.ObservationStatus.ENTEREDINERROR
                    existing.value = null
                    existing.code.text = null
                    LocalWins.save(fhirEngine, existing)
                }

                answer != null -> {
                    val obs = com.icl.surveillance.utils.QuestionnaireHelper()
                        .codingQuestionnaire(code, answer.text, answer.answer)
                    obs.id = UUID.randomUUID().toString()
                    obs.subject = Reference("Patient/$patientId")
                    obs.encounter = Reference("Encounter/$caseEncounterId")
                    obs.issued = Date()
                    template?.let { copyFrom ->
                        obs.meta.tag = copyFrom.meta.tag.mapTo(ArrayList()) { it.copy() }
                        obs.extension = copyFrom.extension.mapTo(ArrayList()) { it.copy() }
                        obs.performer = copyFrom.performer.mapTo(ArrayList()) { it.copy() }
                    }
                    fhirEngine.create(FhirConformance.ensureRequired(obs))
                }
            }
        }
    }

    /**
     * Puts the outcome answers into the case form's response, under the same groups and in the
     * order of the case form; a question with no answer (no longer enabled) is removed.
     */
    private suspend fun updateCaseResponse(
        fhirEngine: FhirEngine,
        caseQuestionnaireJson: String,
        patientId: String,
        caseEncounterId: String,
        outcome: QuestionnaireResponse,
    ) {
        val caseResponse = CaseResponse.find(fhirEngine, patientId, caseEncounterId) ?: run {
            Timber.tag(TAG).w("No case form response for %s; answers saved on the record only", patientId)
            return
        }
        val questionnaire = FhirContext.forR4Cached().newJsonParser()
            .parseResource(Questionnaire::class.java, caseQuestionnaireJson)
        mergeInto(caseResponse, questionnaire, outcome)
        LocalWins.save(fhirEngine, caseResponse)
    }

    /**
     * Puts the outcome answers of [outcome] into [caseResponse] (a response to the case form
     * [questionnaire]), under the same groups and in the form's order; a question with no answer
     * (no longer enabled) is removed. Changes nothing else.
     */
    fun mergeInto(caseResponse: QuestionnaireResponse, questionnaire: Questionnaire, outcome: QuestionnaireResponse) {
        val outcomeItems = flatten(outcome.item).filter { it.linkId in CODES }.associateBy { it.linkId }
        CODES.forEach { code ->
            val path = pathTo(questionnaire.item, code) ?: return@forEach
            val answered = outcomeItems[code]?.takeIf { it.hasAnswer() }
            if (answered != null) {
                var siblings = caseResponse.item
                path.dropLast(1).forEach { group ->
                    siblings = itemIn(siblings, group).item
                }
                itemIn(siblings, path.last()).apply {
                    text = answered.text ?: text
                    answer = answered.answer.mapTo(ArrayList()) { it.copy() }
                }
            } else {
                remove(caseResponse.item, code)
            }
        }
    }

    /** Patient.deceased: the date of death when the outcome is Dead; false when it is Alive. */
    private suspend fun updatePatient(fhirEngine: FhirEngine, patientId: String, byCode: Map<String, QuestionnaireAnswer>) {
        val outcome = byCode[FormFields.Vhf.OUTCOME]?.answer?.trim() ?: return
        val patient = fhirEngine.get<Patient>(patientId)
        val dead = outcome.equals(FormFields.Vhf.OUTCOME_DEAD_CODE, ignoreCase = true)
        if (dead) {
            val day = VhfContactTracker.parseDate(byCode[FormFields.Vhf.DEATH_DATE]?.answer)
            patient.deceased = day?.let { DateTimeType(it.toString()) } ?: BooleanType(true)
        } else if (patient.hasDeceased()) {
            patient.deceased = BooleanType(false)
        } else return
        LocalWins.update(fhirEngine, patient)
        Timber.tag(TAG).d("Patient %s deceased = %s", patientId, patient.deceased?.primitiveValue())
    }

    // ---- QuestionnaireResponse items

    private fun flatten(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>):
        List<QuestionnaireResponse.QuestionnaireResponseItemComponent> =
        items.flatMap { listOf(it) + flatten(it.item) + it.answer.flatMap { a -> flatten(a.item) } }

    /** linkIds from the top of [items] down to [linkId], with each level's questionnaire items. */
    private fun pathTo(items: List<Questionnaire.QuestionnaireItemComponent>, linkId: String): List<Level>? {
        items.forEach { item ->
            if (item.linkId == linkId) return listOf(Level(item.linkId, items.map { it.linkId }))
            pathTo(item.item, linkId)?.let { return listOf(Level(item.linkId, items.map { it.linkId })) + it }
        }
        return null
    }

    /** One level of a path: the item's linkId and its siblings' linkIds in form order. */
    private data class Level(val linkId: String, val order: List<String>)

    /** The item [level] in [items], added at its place in the form's order when missing. */
    private fun itemIn(
        items: MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
        level: Level,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent {
        items.firstOrNull { it.linkId == level.linkId }?.let { return it }
        val item = QuestionnaireResponse.QuestionnaireResponseItemComponent().setLinkId(level.linkId)
        val rank = level.order.indexOf(level.linkId)
        val at = items.indexOfFirst { level.order.indexOf(it.linkId) > rank }
        if (at < 0) items.add(item) else items.add(at, item)
        return item
    }

    private fun remove(items: MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent>, linkId: String) {
        items.removeAll { it.linkId == linkId }
        items.forEach { item ->
            remove(item.item, linkId)
            item.answer.forEach { remove(it.item, linkId) }
        }
    }
}
