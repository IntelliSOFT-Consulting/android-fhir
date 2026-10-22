package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Contact Link to Case" on the VHF case form a contact names the
 * case it was in contact with by that case's EPID number. The EPID number is assigned by the server,
 * so only cases already synced to this device can be offered; the case is picked from them (it
 * cannot be typed in).
 * Suspected, probable and confirmed cases are offered (latest "Type of case"), plus any record
 * confirmed by the lab (latest final result Positive); contacts are not.
 */
object VhfSourceCases {

    data class SourceOption(val epid: String, val patientId: String, val label: String)

    /** The full EPID number of [patient] (server-assigned), or null before it is synced. */
    fun epidOf(patient: Patient): String? =
        patient.identifier.firstOrNull { it.type.codingFirstRep.code == FhirSystems.EPID }
            ?.value?.trim()?.takeIf { it.isNotBlank() && !it.endsWith("-") }

    /**
     * The EPID number of [patient] as it stands, even when not yet complete (e.g. "KEN-NBO-26-"
     * before the server adds the case number). Used so a contact still carries its source case.
     */
    fun epidOrPartialOf(patient: Patient): String? =
        patient.identifier.firstOrNull { it.type.codingFirstRep.code == FhirSystems.EPID }
            ?.value?.trim()?.takeIf { it.isNotBlank() }

    /** "Type of case" answers that make a record a possible source of contacts. */
    private val SOURCE_CASE_TYPES = setOf("suspected", "probable", "confirmed")

    /**
     * Suspected, probable and confirmed VHF cases on this device that have an EPID number,
     * newest first, labelled by EPID number only (no name, for privacy). Starts from the case-type and final-result
     * answers (indexed searches) instead of scanning every patient.
     */
    suspend fun options(
        fhirEngine: FhirEngine,
        excludePatientId: String? = null,
        training: Boolean? = null, // Trainings lists only training cases; VHF only real ones
    ): List<SourceOption> {
        val byCaseType = caseTypePatientIds(fhirEngine)
        val labConfirmed = confirmedPatientIds(fhirEngine)
        val types: Map<String, String> =
            byCaseType + labConfirmed.associateWith { "Confirmed" } // the lab result wins
        return types.keys
            .filter { it != excludePatientId }
            .mapNotNull { id -> runCatching { fhirEngine.get<Patient>(id) }.getOrNull() }
            .filter { patient -> patient.identifier.any { it.system == CaseSlugs.VHF } }
            .filter { patient -> training == null || VhfTraining.isTraining(patient) == training }
            .sortedByDescending { it.meta.lastUpdated?.time ?: 0L }
            .mapNotNull { patient ->
                // For now a case whose EPID number is not complete yet is offered too, as is.
                val epid = epidOrPartialOf(patient) ?: return@mapNotNull null
                val label = if (epidOf(patient) == null) "$epid (EPID incomplete)" else epid
                // Privacy: a source case is offered (and saved on the contact) by its EPID number
                // only; its name is never shown or stored with the contact.
                SourceOption(epid, patient.logicalId, label)
            }
            .distinctBy { it.epid }
    }

    /**
     * Patient id -> type ("Suspected" / "Probable" / "Confirmed") for records whose latest
     * "Type of case" answer is one of those (contacts are left out).
     */
    private suspend fun caseTypePatientIds(fhirEngine: FhirEngine): Map<String, String> =
        fhirEngine.search<Observation> {
            filter(Observation.CODE, { value = of(Coding().apply { code = FormFields.Vhf.CASE_TYPE }) })
        }
            .map { it.resource }
            .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR && it.hasValue() }
            .groupBy { obs -> obs.subject.reference?.let { IdType(it).idPart } }
            .mapNotNull { (patientId, answers) ->
                val latest = answers.maxByOrNull { maxOf(it.issued?.time ?: 0L, it.meta.lastUpdated?.time ?: 0L) }
                val type = latest?.value?.answerText()?.trim().orEmpty()
                if (patientId.isNullOrBlank() || type.lowercase() !in SOURCE_CASE_TYPES) null
                else patientId to type.replaceFirstChar { it.uppercase() }
            }
            .toMap()

    /** Ids of patients with a positive specimen ("Confirmed by lab"), see [VhfSpecimens]. */
    private suspend fun confirmedPatientIds(fhirEngine: FhirEngine): Set<String> =
        VhfSpecimens.patientsWithPositiveSpecimen(fhirEngine)

    /**
     * Sets the choices of the source-case question in [questionnaireJson]. [keep] are EPID numbers
     * a record already links to: they stay choices when the case is not on this device, so
     * editing the record does not drop its link.
     */
    fun withOptions(
        questionnaireJson: String,
        options: List<SourceOption>,
        keep: List<String> = emptyList(),
    ): String {
        val all = options + keep.filter { epid -> options.none { it.epid == epid } }
            .map { SourceOption(it, "", it) }
        if (all.isEmpty()) return questionnaireJson
        val root = JSONObject(questionnaireJson)
        val answers = JSONArray()
        all.forEach { option ->
            answers.put(JSONObject().put("valueCoding",
                JSONObject().put("code", option.epid).put("display", option.label)))
        }
        fun visit(items: JSONArray?) {
            if (items == null) return
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.optString("linkId") == FormFields.Vhf.SOURCE_CASE_EPID) {
                    item.put("answerOption", answers)
                    return
                }
                visit(item.optJSONArray("item"))
            }
        }
        visit(root.optJSONArray("item"))
        return root.toString()
    }

    /**
     * The source case is picked from a list (no typed EPID numbers). A number typed on an older
     * record becomes a choice answer in [response]; returns the EPID numbers [response] links to.
     */
    fun toChoiceAnswers(response: QuestionnaireResponse): List<String> {
        val epids = ArrayList<String>()
        fun visit(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                if (item.linkId == FormFields.Vhf.SOURCE_CASE_EPID) {
                    item.answer.forEach { answer ->
                        val epid = when (val value = answer.value) {
                            is Coding -> value.code
                            is StringType -> epidFrom(value.value)?.also {
                                answer.value = Coding().setCode(it).setDisplay(it)
                            }
                            else -> null
                        }
                        epid?.let { epids += it }
                    }
                }
                visit(item.item)
                item.answer.forEach { visit(it.item) }
            }
        }
        visit(response.item)
        return epids.distinct()
    }

    /**
     * The EPID number in an answer: a picked case's answer is its EPID number (older records
     * read "EPID  Name"), a typed one is the EPID number itself. EPID numbers contain no spaces.
     */
    fun epidFrom(answer: String?): String? =
        answer?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The VHF case on this device with EPID number [epid], if any. Searched by identifier value
     * (indexed) instead of loading every patient on the device.
     */
    suspend fun findByEpid(fhirEngine: FhirEngine, epid: String): Patient? {
        val value = epid.trim().takeIf { it.isNotEmpty() } ?: return null
        val candidates = listOf(value, value.uppercase()).distinct().flatMap { v ->
            fhirEngine.search<Patient> {
                filter(Patient.IDENTIFIER, { this.value = of(v) })
            }.map { it.resource }
        }
        val matches = candidates
            .filter { patient -> epidOrPartialOf(patient).equals(value, ignoreCase = true) }
            .distinctBy { it.logicalId }
        // A complete EPID number is unique. An incomplete one is used only when it names a
        // single case on the device, so a contact is never linked to the wrong case.
        return matches.firstOrNull { epidOf(it) != null } ?: matches.singleOrNull()
    }
}
