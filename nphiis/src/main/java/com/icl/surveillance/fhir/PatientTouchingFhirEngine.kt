package com.icl.surveillance.fhir

import com.google.android.fhir.FhirEngine
import org.hl7.fhir.r4.model.Resource

/**
 * The app's FhirEngine: when the app creates or edits a child record of a patient (Encounter,
 * Observation, Specimen, form response, lab report), the Patient it references is stamped with
 * the current time (see [PatientDataLastUpdated]). Everything else, downloads included, goes
 * straight to [delegate].
 */
class PatientTouchingFhirEngine(private val delegate: FhirEngine) : FhirEngine by delegate {

    override suspend fun create(vararg resource: Resource): List<String> =
        delegate.create(*resource).also { touchPatientsOf(resource) }

    override suspend fun update(vararg resource: Resource) {
        delegate.update(*resource)
        touchPatientsOf(resource)
    }

    /** Saves made inside a transaction (e.g. LocalWins edits) stamp their Patient too. */
    override suspend fun withTransaction(block: suspend FhirEngine.() -> Unit) {
        delegate.withTransaction { this@PatientTouchingFhirEngine.block() }
    }

    private suspend fun touchPatientsOf(resources: Array<out Resource>) {
        // Through [delegate]: saving the Patient must not go through this again.
        resources.mapNotNull { PatientDataLastUpdated.patientOf(it) }.distinct()
            .forEach { PatientDataLastUpdated.touch(delegate, it) }
    }
}
