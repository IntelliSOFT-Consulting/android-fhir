package com.icl.surveillance.fhir

import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource

/**
 * Fills in the elements FHIR R4 requires (cardinality 1..1) that the app's forms do not capture,
 * so resources pass validation on servers that enforce the base specification. Only missing
 * values are set; anything already present is left unchanged.
 *
 * Applied when resources are created and again just before upload, so records saved before this
 * existed are corrected too.
 */
object FhirConformance {

    /** v3 ActCode "FLD" (field): encounters are investigations and follow ups in the community. */
    private val FIELD_ENCOUNTER = Coding(
        "http://terminology.hl7.org/CodeSystem/v3-ActCode", "FLD", "field"
    )

    fun <T : Resource> ensureRequired(resource: T): T {
        when (resource) {
            // Encounter.status and Encounter.class are both 1..1.
            is Encounter -> {
                if (!resource.hasStatus()) resource.status = Encounter.EncounterStatus.FINISHED
                if (!resource.hasClass_()) resource.class_ = FIELD_ENCOUNTER.copy()
            }

            // Observation.status is 1..1; answers are final once the form is submitted.
            is Observation ->
                if (!resource.hasStatus()) resource.status = Observation.ObservationStatus.FINAL

            // QuestionnaireResponse.status is 1..1; a submitted form is completed (drafts set
            // in-progress themselves and are left as they are).
            is QuestionnaireResponse ->
                if (!resource.hasStatus()) {
                    resource.status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
                }

            // Patient.link.type is 1..1 whenever a link is present.
            is Patient -> resource.link.forEach { link ->
                if (!link.hasType()) link.type = Patient.LinkType.SEEALSO
            }
        }
        return resource
    }
}
