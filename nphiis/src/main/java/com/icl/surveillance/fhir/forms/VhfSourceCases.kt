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
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Contact Link to Case" on the VHF case form a contact names the
 * case it was in contact with by that case's EPID number. The EPID number is assigned by the server,
 * so only cases already synced to this device can be offered; any other can be typed in.
 * Suspected, probable and confirmed cases are offered (latest "Type of case"), plus any record
 * confirmed by the lab (latest final result Positive); contacts are not.
 */
object VhfSourceCases {

    data class SourceOption(val epid: String, val patientId: String, val label: String)

    /** The full EPID number of [patient] (server-assigned), or null before it is synced. */
    fun epidOf(patient: Patient): String? =
        patient.identifier.firstOrNull { it.type.codingFirstRep.code == FhirSystems.EPID }
            ?.value?.trim()?.takeIf { it.isNotBlank() && !it.endsWith("-") }

    /** "Type of case" answers that make a record a possible source of contacts. */
    private val SOURCE_CASE_TYPES = setOf("suspected", "probable", "confirmed")

    /**
     * Suspected, probable and confirmed VHF cases on this device that have an EPID number,
     * newest first, labelled "EPID  Name (Type)". Starts from the case-type and final-result
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
                val epid = epidOf(patient) ?: return@mapNotNull null
                val name = patient.nameFirstRep.nameAsSingleString.trim()
                val type = types[patient.logicalId]?.let { " ($it)" }.orEmpty()
                SourceOption(
                    epid, patient.logicalId,
                    (if (name.isBlank()) epid else "$epid  $name") + type,
                )
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
                val type = latest?.value?.asStringValue()?.trim().orEmpty()
                if (patientId.isNullOrBlank() || type.lowercase() !in SOURCE_CASE_TYPES) null
                else patientId to type.replaceFirstChar { it.uppercase() }
            }
            .toMap()

    /**
     * Ids of patients whose most recent VHF final lab result is Positive ("Confirmed by lab").
     * A later Negative result (or one marked entered-in-error by an edit) removes the case.
     */
    private suspend fun confirmedPatientIds(fhirEngine: FhirEngine): Set<String> =
        fhirEngine.search<Observation> {
            filter(Observation.CODE, { value = of(Coding().apply { code = FormFields.Vhf.FINAL_RESULT }) })
        }
            .map { it.resource }
            .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR && it.hasValue() }
            .groupBy { obs -> obs.subject.reference?.let { IdType(it).idPart } }
            .mapNotNull { (patientId, results) ->
                val latest = results.maxByOrNull { maxOf(it.issued?.time ?: 0L, it.meta.lastUpdated?.time ?: 0L) }
                val positive = FormFields.Vhf.finalClassification(latest?.value?.asStringValue()) ==
                    FormFields.Vhf.finalClassification("positive")
                patientId?.takeIf { positive && it.isNotBlank() }
            }
            .toSet()

    /** Sets the choices of the source-case question in [questionnaireJson]. */
    fun withOptions(questionnaireJson: String, options: List<SourceOption>): String {
        if (options.isEmpty()) return questionnaireJson
        val root = JSONObject(questionnaireJson)
        val answers = JSONArray()
        options.forEach { option ->
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
     * The EPID number in an answer: a picked case's answer reads "EPID  Name" (or is the code),
     * a typed one is the EPID number itself. EPID numbers contain no spaces.
     */
    fun epidFrom(answer: String?): String? =
        answer?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** The VHF case on this device with EPID number [epid], if any. */
    suspend fun findByEpid(fhirEngine: FhirEngine, epid: String): Patient? =
        fhirEngine.search<Patient> {}
            .map { it.resource }
            .firstOrNull { patient -> epidOf(patient).equals(epid, ignoreCase = true) }
}
