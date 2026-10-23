package com.icl.surveillance.fhir.forms

import com.icl.surveillance.fhir.FhirConformance
import android.content.Context
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.DomainResource
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import timber.log.Timber

/**
 * The facility a record belongs to: its "*-managingLocation" meta tag (code "Location/{id}") and
 * extension (valueReference "Location/{id}"). The server resolves these references, so they must
 * always carry an id; "Location/" with no id made the server fail with HTTP 500.
 */
object ManagingLocation {

    data class Place(val id: String, val name: String?)

    /** Outcome of a repair pass: records checked and corrected, or the error that stopped it. */
    data class RepairResult(val scanned: Int, val fixed: Int, val error: Throwable? = null)

    // V7: also "Location/unknown" and "Location/null" (the server answers 404 -> HTTP 500).
    private const val REPAIR_DONE_PREF = "managingLocationRepairV7"

    /** Placeholder ids that are not Locations on the server. */
    private val NOT_IDS = setOf("unknown", "null", "undefined")

    /** The Location id in "Location/{id}", or null when it is missing or not a plain id. */
    fun idOf(reference: String?): String? =
        reference?.trim()?.removePrefix("Location/")
            ?.takeIf { it.isNotBlank() && '/' !in it && '?' !in it && it.lowercase() !in NOT_IDS }

    /**
     * Where a record of this user belongs when its reporting facility cannot be found: the user's
     * facility, else their ward, sub county or county (all real Locations on the server).
     */
    fun fallbackFor(context: Context): Place? {
        val formatter = FormatterClass()
        fun place(idKey: String, nameKey: String): Place? =
            idOf(formatter.getSharedPref(idKey, context))?.let { id ->
                Place(id, formatter.getSharedPref(nameKey, context)
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) })
            }
        return place("facility", "facilityName") ?: place("ward", "wardName")
            ?: place("subCounty", "subCountyName") ?: place("county", "countyName")
    }

    /** The user's own facility, when the user has one. */
    fun ofUser(context: Context): Place? {
        val formatter = FormatterClass()
        val id = idOf(formatter.getSharedPref("facility", context)) ?: return null
        return Place(id, formatter.getSharedPref("facilityName", context)?.takeIf { it.isNotBlank() })
    }

    /** The facility recorded on [resource], if it has a valid one. */
    fun of(resource: DomainResource): Place? {
        resource.extension
            .filter { it.url?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true }
            .forEach { extension ->
                val reference = extension.value as? Reference ?: return@forEach
                idOf(reference.reference)?.let { return Place(it, reference.display) }
            }
        resource.meta.tag
            .filter { it.system?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true }
            .forEach { tag -> idOf(tag.code)?.let { return Place(it, tag.display) } }
        return null
    }

    /**
     * The facility of encounter [encounterId]: its own, else its patient's, else its parent
     * encounter's (a lab result or follow up belongs to the facility of its case).
     */
    suspend fun ofEncounter(fhirEngine: FhirEngine, encounterId: String, depth: Int = 0): Place? {
        val encounter = runCatching { fhirEngine.get<Encounter>(encounterId) }.getOrNull()
            ?: return null
        of(encounter)?.let { return it }
        encounter.subject?.reference?.removePrefix("Patient/")?.takeIf { it.isNotBlank() }
            ?.let { patientId -> runCatching { fhirEngine.get<Patient>(patientId) }.getOrNull() }
            ?.let { of(it) }
            ?.let { return it }
        val parent = encounter.partOf?.reference?.removePrefix("Encounter/")?.takeIf { it.isNotBlank() }
        return if (parent != null && depth < 3) ofEncounter(fhirEngine, parent, depth + 1) else null
    }

    /** True when a managing-location tag or extension has no usable Location id. */
    fun isBroken(resource: DomainResource): Boolean =
        resource.meta.tag.any {
            it.system?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true && idOf(it.code) == null
        } || resource.extension.any {
            it.url?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true &&
                idOf((it.value as? Reference)?.reference) == null
        }

    /**
     * Points broken managing-location entries at [place], or removes them when no facility is
     * known. Returns true when [resource] changed.
     */
    fun repair(resource: DomainResource, place: Place?): Boolean {
        if (!isBroken(resource)) return false
        val tags = resource.meta.tag.mapNotNull { tag ->
            val broken = tag.system?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true &&
                idOf(tag.code) == null
            when {
                !broken -> tag
                place == null -> null
                else -> tag.copy().apply {
                    code = "Location/${place.id}"
                    display = place.name
                }
            }
        }
        resource.meta.tag = tags.toMutableList()
        val extensions = resource.extension.mapNotNull { extension ->
            val broken = extension.url?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true &&
                idOf((extension.value as? Reference)?.reference) == null
            when {
                !broken -> extension
                place == null -> null
                else -> extension.copy().apply {
                    setValue(Reference("Location/${place.id}").apply { display = place.name })
                }
            }
        }
        resource.extension = extensions.toMutableList()
        return true
    }

    /**
     * Repairs every Encounter, QuestionnaireResponse, Observation and Patient saved with
     * "Location/" (no id), in the stored copy or the pending upload: each takes its case's facility. Runs from Settings > Update Data. Logged under "ManagingLocation".
     */
    suspend fun repairLocalRecords(
        context: Context,
        fhirEngine: FhirEngine,
        force: Boolean = false,
    ): RepairResult? {
        val formatter = FormatterClass()
        if (!force && formatter.getSharedPref(REPAIR_DONE_PREF, context) == "true") {
            Timber.tag(TAG).i("Repair already done; skipped")
            return null
        }
        Timber.tag(TAG).i("Repair v6 started (force=$force): checking every Encounter, QuestionnaireResponse, Observation and Patient")
        var checked = 0
        var fixed = 0
        try {
            // Collect first, then fix: replacing a record changes the paging order.
            val broken = mutableListOf<DomainResource>()
            checked += collectBroken<Encounter>(fhirEngine, broken)
            checked += collectBroken<QuestionnaireResponse>(fhirEngine, broken)
            checked += collectBroken<Observation>(fhirEngine, broken)
            checked += collectBroken<Patient>(fhirEngine, broken)
            Timber.tag(TAG).i("Found ${broken.size} record(s) with an empty Location: " +
                broken.groupingBy { it.resourceType.name }.eachCount())
            broken.forEach { resource ->
                val place = placeFor(fhirEngine, resource) ?: fallbackFor(context)
                if (fix(fhirEngine, resource, place)) {
                    fixed++
                    Timber.tag(TAG).i("${resource.resourceType}/${resource.logicalId} -> " +
                        (place?.let { "Location/${it.id}" } ?: "tag removed (no facility found)"))
                }
            }
            if (fixed == 0) formatter.saveSharedPref(REPAIR_DONE_PREF, "true", context)
            Timber.tag(TAG).i("Checked $checked record(s); repaired $fixed")
            return RepairResult(checked, fixed)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Repair stopped after $checked record(s), $fixed repaired")
            return RepairResult(checked, fixed, e)
        }
    }

    /** Adds every record of type [R] that is broken (stored or pending upload) to [into]. */
    private suspend inline fun <reified R : DomainResource> collectBroken(
        fhirEngine: FhirEngine,
        into: MutableList<DomainResource>,
    ): Int {
        var from = 0
        var checked = 0
        while (true) {
            val page = fhirEngine.search<R> {
                count = PAGE_SIZE
                this.from = from
            }.map { it.resource }
            if (page.isEmpty()) break
            checked += page.size
            page.forEach { if (needsRepair(fhirEngine, it)) into += it }
            from += PAGE_SIZE
        }
        return checked
    }

    /**
     * The facility a record should carry: an encounter takes its case's (parent's), a response or
     * observation its encounter's, a contact patient the patient it is linked to.
     */
    private suspend fun placeFor(fhirEngine: FhirEngine, resource: DomainResource): Place? = when (resource) {
        is Encounter -> resource.partOf?.reference?.removePrefix("Encounter/")
            ?.let { ofEncounter(fhirEngine, it) }
            ?: ofEncounter(fhirEngine, resource.logicalId)
        is QuestionnaireResponse -> resource.encounter?.reference?.removePrefix("Encounter/")
            ?.let { ofEncounter(fhirEngine, it) }
        is Observation -> resource.encounter?.reference?.removePrefix("Encounter/")
            ?.let { ofEncounter(fhirEngine, it) }
        // A contact takes the facility of its source case (contact Group, else an old link).
        is Patient -> ContactGroups.sourceOf(fhirEngine, resource)
            ?.let { runCatching { fhirEngine.get<Patient>(it) }.getOrNull() }
            ?.let { of(it) }
        else -> null
    }

    /** An empty Location reference as it appears in a record's JSON. */
    private const val EMPTY_LOCATION_JSON = "\"Location/\""
    private val PLACEHOLDER_LOCATION_JSON = listOf("\"Location/unknown\"", "\"Location/null\"")

    /** The record's pending creation (never uploaded yet), if any. */
    private suspend fun pendingInsert(fhirEngine: FhirEngine, resource: DomainResource): LocalChange? =
        fhirEngine.getLocalChanges(resource.resourceType, resource.logicalId)
            .firstOrNull { it.type == LocalChange.Type.INSERT }

    /** Broken in the stored copy, or in the version waiting to be uploaded. */
    private suspend fun needsRepair(fhirEngine: FhirEngine, resource: DomainResource): Boolean =
        isBroken(resource) ||
            pendingInsert(fhirEngine, resource)?.payload?.let { payload ->
                payload.contains(EMPTY_LOCATION_JSON) || PLACEHOLDER_LOCATION_JSON.any { payload.contains(it) }
            } == true

    /**
     * Repairs [resource] and saves it so the correction is uploaded. The SDK does not record
     * edits under /meta (where the managing-location tag lives), so a record that has never
     * been uploaded is replaced instead: purged with its pending creation and created again with
     * the same id and the corrected content. Records already on the server are updated.
     */
    private suspend fun fix(fhirEngine: FhirEngine, resource: DomainResource, place: Place?): Boolean {
        if (!needsRepair(fhirEngine, resource)) return false
        repair(resource, place)
        if (pendingInsert(fhirEngine, resource) != null) {
            fhirEngine.purge(resource.resourceType, resource.logicalId, forcePurge = true)
            fhirEngine.create(FhirConformance.ensureRequired(resource))
        } else {
            LocalWins.update(fhirEngine, resource)
        }
        return true
    }

    private const val TAG = "ManagingLocation"
    private const val PAGE_SIZE = 200
}
