package com.icl.surveillance.fhir

import ca.uhn.fhir.model.api.TemporalPrecisionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.icl.surveillance.fhir.forms.LocalWins
import java.util.Date
import java.util.TimeZone
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DiagnosticReport
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Specimen
import timber.log.Timber

/**
 * Patient `patient-data-last-updated` extension, as the backend sets it: the time a child record
 * of the patient last changed. Devices download only changed Patients (Patient?_lastUpdated,
 * then Patient/$everything), so a changed Encounter, Observation, Specimen, form response or lab
 * report reaches other devices only once its Patient changes too.
 *
 * When the app saves such a record ([PatientTouchingFhirEngine]), the Patient it references is
 * stamped with the current time, unless the Patient already has a change of its own waiting to
 * upload (it changes on the server then anyway). Downloaded records never stamp it.
 */
object PatientDataLastUpdated {
    const val TAG = "PATIENT_DATA_UPDATED"
    const val EXTENSION_URL = "http://nphiis.health.go.ke/fhir/StructureDefinition/patient-data-last-updated"

    /** The Patient id [resource] references as its subject, for the child records above. */
    fun patientOf(resource: Resource): String? {
        val subject = when (resource) {
            is Observation -> resource.subject
            is Encounter -> resource.subject
            is Specimen -> resource.subject
            is QuestionnaireResponse -> resource.subject
            is DiagnosticReport -> resource.subject
            else -> return null
        }
        val reference = subject?.reference?.takeIf { it.startsWith("Patient/") } ?: return null
        return IdType(reference).idPart?.takeIf { it.isNotBlank() }
    }

    /** Sets the extension on [patient] to [at], replacing an earlier value. */
    fun stamp(patient: Patient, at: Date = Date()) {
        patient.extension.removeAll { it.url == EXTENSION_URL }
        patient.addExtension(
            Extension(EXTENSION_URL, DateTimeType(at, TemporalPrecisionEnum.MILLI, TimeZone.getDefault()))
        )
    }

    /**
     * Stamps Patient [patientId] with the current time after a child record of it changed. Not
     * when the Patient has a change of its own waiting to upload, or is not on this device.
     */
    suspend fun touch(fhirEngine: FhirEngine, patientId: String) {
        val pending = runCatching { fhirEngine.getLocalChanges(ResourceType.Patient, patientId) }
            .getOrDefault(emptyList())
        if (pending.isNotEmpty()) return
        val patient = runCatching { fhirEngine.get<Patient>(patientId) }.getOrNull() ?: return
        runCatching {
            stamp(patient)
            LocalWins.update(fhirEngine, patient)
        }.onFailure { Timber.tag(TAG).e(it, "Could not stamp patient %s", patientId) }
    }
}
