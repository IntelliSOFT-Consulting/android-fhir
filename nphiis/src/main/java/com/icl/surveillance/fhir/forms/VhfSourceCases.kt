package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import org.hl7.fhir.r4.model.Patient
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Contact of case (EPID number)" on the VHF case form a contact names the
 * case it was in contact with by that case's EPID number. The EPID number is assigned by the server,
 * so only cases already synced to this device can be offered; any other can be typed in.
 */
object VhfSourceCases {

    data class SourceOption(val epid: String, val patientId: String, val label: String)

    /** The full EPID number of [patient] (server-assigned), or null before it is synced. */
    fun epidOf(patient: Patient): String? =
        patient.identifier.firstOrNull { it.type.codingFirstRep.code == FhirSystems.EPID }
            ?.value?.trim()?.takeIf { it.isNotBlank() && !it.endsWith("-") }

    /** VHF records on this device that have an EPID number, newest first. */
    suspend fun options(fhirEngine: FhirEngine, excludePatientId: String? = null): List<SourceOption> =
        fhirEngine.search<Patient> {}
            .map { it.resource }
            .filter { patient -> patient.identifier.any { it.system == CaseSlugs.VHF } }
            .filter { it.logicalId != excludePatientId }
            .sortedByDescending { it.meta.lastUpdated?.time ?: 0L }
            .mapNotNull { patient ->
                val epid = epidOf(patient) ?: return@mapNotNull null
                val name = patient.nameFirstRep.nameAsSingleString.trim()
                SourceOption(epid, patient.logicalId, if (name.isBlank()) epid else "$epid  $name")
            }
            .distinctBy { it.epid }

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
