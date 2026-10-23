package com.icl.surveillance.fhir

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.util.FhirTerser
import com.icl.surveillance.fhir.forms.ManagingLocation
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DomainResource
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import timber.log.Timber

/**
 * Fills in the elements FHIR R4 requires (cardinality 1..1) that the app's forms do not capture,
 * so resources pass validation on servers that enforce the base specification. Only missing
 * values are set; anything already present is left unchanged.
 *
 * Also removes references the server cannot resolve, which fail the whole upload bundle: a
 * managing location without a Location id ("Location/", "Location/null") takes the user's
 * facility (the server answered HTTP 500), and any other "Type/" reference without an id keeps
 * only its display.
 *
 * Applied to every resource the app creates, and to every edit saved through LocalWins.
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
        dropUnresolvableReferences(resource)
        return resource
    }

    private const val TAG = "FhirConformance"
    private val NOT_IDS = setOf("null", "unknown", "undefined")
    private val terser by lazy { FhirTerser(FhirContext.forR4Cached()) }

    private fun dropUnresolvableReferences(resource: Resource) {
        if (resource is DomainResource && ManagingLocation.isBroken(resource)) {
            val place = runCatching { ManagingLocation.fallbackFor(FhirApplication.appContext()) }
                .getOrNull()
            ManagingLocation.repair(resource, place)
            Timber.tag(TAG).w("${resource.resourceType}/${resource.idPart()}: empty managing " +
                "location -> ${place?.let { "Location/${it.id}" } ?: "removed"}")
        }
        terser.getAllPopulatedChildElementsOfType(resource, Reference::class.java)
            .filter { it.hasReference() && !hasId(it.reference) }
            .forEach {
                Timber.tag(TAG).w("${resource.resourceType}/${resource.idPart()}: dropped " +
                    "reference \"${it.reference}\" (no id)")
                it.reference = null
            }
    }

    private fun Resource.idPart(): String? = idElement?.idPart

    /** True when a "Type/{id}" reference names an id; other forms (urls, #contained) are kept. */
    private fun hasId(reference: String): Boolean {
        val parts = reference.trim().split('/')
        if (parts.size != 2 || parts[0].isEmpty() || !parts[0][0].isUpperCase()) return true
        return parts[1].isNotBlank() && parts[1].lowercase() !in NOT_IDS
    }
}
