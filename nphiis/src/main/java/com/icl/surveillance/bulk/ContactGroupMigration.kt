package com.icl.surveillance.bulk

import android.content.Context
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.filter.TokenParamFilterCriterion
import com.google.android.fhir.search.search
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.ContactGroups
import com.icl.surveillance.fhir.forms.LocalWins
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfSourceCases
import com.icl.surveillance.ui.patients.CaseIdentifierIndex
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import timber.log.Timber

/**
 * Settings > Migrate contacts: moves VHF contacts linked to their source case the old way
 * (Patient.link, see-also) into the case's contact Group (ContactGroups), then removes the link.
 * Safe to run again: contacts already in the Group only lose the old link. Links to a case
 * that is not on this device are left in place and reported.
 */
class ContactGroupMigration(private val context: Context, private val fhirEngine: FhirEngine) {

    data class Item(
        val contact: Patient,
        val sourceId: String,
        /** The source case on this device (null: not on this device, the link is kept). */
        val source: Patient?,
    ) {
        val contactId: String get() = contact.logicalId
        val contactEpid: String get() = VhfSourceCases.epidOf(contact) ?: contact.logicalId
        val sourceEpid: String get() = source?.let { VhfSourceCases.epidOf(it) } ?: sourceId
    }

    data class Plan(val items: List<Item>) {
        val movable get() = items.filter { it.source != null }
        val sourceCases get() = movable.map { it.sourceId }.distinct().size
        val missingSource get() = items.filter { it.source == null }
    }

    enum class Outcome(val label: String) {
        MOVED("Moved to the case's contact group"),
        ALREADY_IN_GROUP("Already in the group; old link removed"),
        SKIPPED("Skipped: source case not on this device (link kept)"),
        FAILED("Failed"),
    }

    data class Summary(val counts: Map<Outcome, Int>, val logFile: File)

    /** VHF records still linked to a VHF case with Patient.link. */
    suspend fun plan(): Plan {
        val vhfIds = CaseIdentifierIndex.patientsWith(fhirEngine, setOf(CaseSlugs.VHF)).keys.toList()
        val patients = vhfIds.chunked(CHUNK).flatMap { chunk ->
            fhirEngine.search<Patient> {
                filter(Resource.RES_ID, *chunk.map<String, TokenParamFilterCriterion.() -> Unit> { id ->
                    { value = of(id) }
                }.toTypedArray())
            }.map { it.resource }
        }
        val byId = patients.associateBy { it.logicalId }
        val items = patients.mapNotNull { patient ->
            val sourceId = ContactGroups.legacySourceOf(patient) ?: return@mapNotNull null
            Item(patient, sourceId, byId[sourceId])
        }
        Timber.tag(ContactGroups.TAG).i(
            "Migration plan: %d linked contacts, %d movable, %d with source not on device",
            items.size, items.count { it.source != null }, items.count { it.source == null },
        )
        return Plan(items)
    }

    /** Moves [plan]'s contacts; [onProgress] gets (done, total). Writes a CSV log. */
    suspend fun migrate(plan: Plan, onProgress: suspend (Int, Int) -> Unit): Summary {
        val log = newLog()
        log.appendText("contact_epid,contact_record,source_case_epid,source_record,result,detail\n")
        val counts = Outcome.values().associateWith { 0 }.toMutableMap()
        fun record(item: Item, outcome: Outcome, detail: String = "") {
            counts[outcome] = (counts[outcome] ?: 0) + 1
            log.appendText(listOf(item.contactEpid, item.contactId, item.sourceEpid, item.sourceId, outcome.label, detail)
                .joinToString(",") { csv(it) } + "\n")
        }

        var done = 0
        val total = plan.items.size
        plan.missingSource.forEach { item ->
            record(item, Outcome.SKIPPED)
            onProgress(++done, total)
        }
        plan.movable.groupBy { it.sourceId }.forEach { (sourceId, items) ->
            val before = ContactGroups.groupOf(fhirEngine, sourceId)?.member.orEmpty()
                .mapNotNull { it.entity?.referenceElement?.idPart }.toSet()
            // One Group save per case for all its contacts.
            val added = runCatching {
                ContactGroups.addMembers(
                    fhirEngine, sourceId,
                    items.map { ContactGroups.NewMember(it.contactId, ContactGroups.dayZeroOf(fhirEngine, it.contactId)) },
                )
            }
            items.forEach { item ->
                val outcome = runCatching {
                    added.getOrThrow()
                    // Follow-up already over: close the membership.
                    val caseEncounter = item.contact.identifier.firstOrNull { it.system == CaseSlugs.VHF }?.value
                    if (caseEncounter != null) {
                        val state = VhfContactTracker.load(fhirEngine, item.contactId, caseEncounter)
                        if (state?.isClosed == true) ContactGroups.closeMember(fhirEngine, item.contactId, LocalDate.now())
                    }
                    removeLink(item.contact, sourceId)
                    if (item.contactId in before) Outcome.ALREADY_IN_GROUP else Outcome.MOVED
                }
                outcome.onSuccess { record(item, it) }
                    .onFailure { error ->
                        Timber.tag(ContactGroups.TAG).e(error, "Migration failed for %s", item.contactId)
                        record(item, Outcome.FAILED, error.message ?: error.javaClass.simpleName)
                    }
                onProgress(++done, total)
            }
        }
        Timber.tag(ContactGroups.TAG).i("Migration done: %s", counts)
        return Summary(counts, log)
    }

    private suspend fun removeLink(contact: Patient, sourceId: String) {
        val kept = contact.link.filterNot { link ->
            link.other?.referenceElement?.idPart == sourceId &&
                (link.type == Patient.LinkType.SEEALSO || !link.hasType())
        }
        if (kept.size == contact.link.size) return
        contact.link = kept.toMutableList()
        LocalWins.update(fhirEngine, contact)
        CaseIdentifierIndex.put(contact)
    }

    private fun newLog(): File {
        val dir = File(context.cacheDir, "contact-migration").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "contact-migration-$stamp.csv")
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private companion object {
        const val CHUNK = 200
    }
}
