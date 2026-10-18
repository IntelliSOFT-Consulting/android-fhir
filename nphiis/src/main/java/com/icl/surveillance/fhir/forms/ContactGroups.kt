package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Reference
import timber.log.Timber

/**
 * VHF contacts of a case as a FHIR Group: one Group per source (index) case, its contacts as
 * members. Replaces Patient.link, which FHIR reserves for records of the same person.
 * Design: "VHF Contacts as FHIR Group — Design & Migration".
 *
 * - id `vhf-contacts-<source Patient id>`: every device updates the same Group;
 * - identifier [IDENTIFIER_SYSTEM] = the source Patient id (searchable);
 * - characteristic `index-case` = Reference to the source case (a Group has no subject);
 * - member.period.start = the contact's day 0; period.end + inactive once its follow-up ends;
 * - meta tag / extension: the source case's managing location, so it syncs with the case.
 *
 * Lists also read old Patient.links until Settings > Migrate contacts has moved them.
 */
object ContactGroups {
    const val TAG = "CONTACT_GROUP"
    const val IDENTIFIER_SYSTEM = "http://nphiis.health.go.ke/fhir/identifiers/vhf-contact-list"
    const val CODE_SYSTEM = "http://nphiis.health.go.ke/fhir/CodeSystem/vhf-contact-list"
    private const val CODE_CONTACTS = "contacts"
    private const val CODE_INDEX_CASE = "index-case"
    private const val ID_PREFIX = "vhf-contacts-"

    fun groupId(sourcePatientId: String) = ID_PREFIX + sourcePatientId

    /** One contact to add: its Patient id and day 0 of its follow-up (null: today). */
    data class NewMember(val contactId: String, val dayZero: LocalDate?)

    /** The Group of [sourcePatientId]'s contacts, or null when it has none yet. */
    suspend fun groupOf(fhirEngine: FhirEngine, sourcePatientId: String): Group? =
        runCatching { fhirEngine.get<Group>(groupId(sourcePatientId)) }.getOrNull()

    /** Adds [contactId] to the contacts of [sourcePatientId]. True when the Group changed. */
    suspend fun addMember(
        fhirEngine: FhirEngine,
        sourcePatientId: String,
        contactId: String,
        dayZero: LocalDate?,
    ): Boolean = addMembers(fhirEngine, sourcePatientId, listOf(NewMember(contactId, dayZero))) > 0

    /**
     * Adds [members] to [sourcePatientId]'s Group (created when missing), skipping contacts
     * already in it and the case itself. One save for all. Returns how many were added.
     */
    suspend fun addMembers(fhirEngine: FhirEngine, sourcePatientId: String, members: List<NewMember>): Int {
        val toAdd = members.filter { it.contactId.isNotBlank() && it.contactId != sourcePatientId }
            .distinctBy { it.contactId }
        if (toAdd.isEmpty()) return 0
        val source = runCatching { fhirEngine.get<Patient>(sourcePatientId) }.getOrNull()
        val existing = groupOf(fhirEngine, sourcePatientId)
        val group = existing ?: newGroup(sourcePatientId, source)
        val present = group.member.mapNotNull { memberId(it) }.toSet()
        var added = 0
        toAdd.filter { it.contactId !in present }.forEach { member ->
            group.addMember()
                .setEntity(Reference("Patient/${member.contactId}"))
                .setPeriod(Period().setStart(toDate(member.dayZero ?: LocalDate.now())))
            added++
        }
        if (added == 0 && existing != null) return 0
        refresh(group, source)
        if (existing == null) fhirEngine.create(group) else LocalWins.update(fhirEngine, group)
        Timber.tag(TAG).d("Group %s: +%d members (%d in all)", group.idElement.idPart, added, group.member.size)
        return added
    }

    /**
     * Ends [contactId]'s membership (follow-up over: released, died, lost, became a case):
     * period.end and inactive, in every Group it is in. The member stays listed.
     */
    suspend fun closeMember(fhirEngine: FhirEngine, contactId: String, end: LocalDate) {
        groupsWithMember(fhirEngine, contactId).forEach { group ->
            var changed = false
            group.member.filter { memberId(it) == contactId && !it.inactive }.forEach { member ->
                member.inactive = true
                val period = member.period ?: Period()
                period.end = toDate(end)
                member.period = period
                changed = true
            }
            if (changed) {
                refresh(group, null)
                LocalWins.update(fhirEngine, group)
                Timber.tag(TAG).d("Group %s: closed member %s", group.idElement.idPart, contactId)
            }
        }
    }

    /**
     * Patient ids of [sourcePatientId]'s contacts: the Group's members, plus contacts still linked
     * the old way (Patient.link) until the migration has moved them.
     */
    suspend fun contactIds(fhirEngine: FhirEngine, sourcePatientId: String): List<String> {
        val members = groupOf(fhirEngine, sourcePatientId)?.member.orEmpty().mapNotNull { memberId(it) }
        val linked = legacyLinkedIds(fhirEngine, sourcePatientId)
        return (members + linked).distinct().filter { it != sourcePatientId }
    }

    /** The source case [contactId] was listed under (its Group's index case), else its old link. */
    suspend fun sourceOf(fhirEngine: FhirEngine, contact: Patient): String? {
        val id = contact.logicalId
        groupsWithMember(fhirEngine, id).firstNotNullOfOrNull { indexCaseOf(it) }
            ?.takeIf { it != id }?.let { return it }
        return legacySourceOf(contact)
    }

    /** The source case of a Group (its `index-case` characteristic, else its identifier). */
    fun indexCaseOf(group: Group): String? =
        group.characteristic.firstOrNull { c -> c.code.coding.any { it.code == CODE_INDEX_CASE } }
            ?.let { (it.value as? Reference)?.reference }
            ?.let { IdType(it).idPart }
            ?: group.identifier.firstOrNull { it.system == IDENTIFIER_SYSTEM }?.value

    suspend fun groupsWithMember(fhirEngine: FhirEngine, contactId: String): List<Group> =
        fhirEngine.search<Group> {
            filter(Group.MEMBER, { value = "Patient/$contactId" })
        }.map { it.resource }
            .filter { group -> group.member.any { memberId(it) == contactId } }

    /**
     * Puts back any contact missing from its case's Group: each contact's "Source case EPID"
     * answer is the source of truth. Covers a Group overwritten by another device's upload (two
     * phones adding contacts to the same case offline). Runs before every sync. Returns how many
     * memberships were restored.
     */
    suspend fun repair(fhirEngine: FhirEngine): Int {
        val answers = fhirEngine.search<Observation> {
            filter(Observation.CODE, { value = of(FormFields.Vhf.SOURCE_CASE_EPID) })
        }.map { it.resource }
            .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR }
        if (answers.isEmpty()) return 0
        val epidByContact = answers.mapNotNull { obs ->
            val contactId = obs.subject?.reference?.let { IdType(it).idPart } ?: return@mapNotNull null
            val epid = VhfSourceCases.epidFrom(obs.value?.asStringValue()) ?: return@mapNotNull null
            contactId to epid
        }.toMap()
        val sourceByEpid = HashMap<String, String?>()
        var restored = 0
        epidByContact.entries.groupBy { (_, epid) -> epid }.forEach { (epid, entries) ->
            val sourceId = sourceByEpid.getOrPut(epid) { VhfSourceCases.findByEpid(fhirEngine, epid)?.logicalId }
                ?: return@forEach
            val present = groupOf(fhirEngine, sourceId)?.member.orEmpty().mapNotNull { memberId(it) }.toSet()
            val missing = entries.map { it.key }.filter { it !in present && it != sourceId }
            if (missing.isNotEmpty()) {
                restored += addMembers(fhirEngine, sourceId, missing.map { NewMember(it, dayZeroOf(fhirEngine, it)) })
            }
        }
        if (restored > 0) Timber.tag(TAG).i("Repair: %d contacts put back in their case's group", restored)
        return restored
    }

    /** Day 0 of a contact: its "Date of last contact" answer. */
    suspend fun dayZeroOf(fhirEngine: FhirEngine, contactId: String): LocalDate? =
        fhirEngine.search<Observation> {
            filter(Observation.SUBJECT, { value = "Patient/$contactId" })
            filter(Observation.CODE, { value = of(FormFields.Vhf.CONTACT_DATE) })
        }.map { it.resource }
            .firstNotNullOfOrNull { obs -> VhfContactTracker.parseDate(obs.value?.asStringValue()) }

    // ---- Old links (Patient.link, type see-also), read until migrated.

    suspend fun legacyLinkedIds(fhirEngine: FhirEngine, sourcePatientId: String): List<String> =
        fhirEngine.search<Patient> {
            filter(Patient.LINK, { value = "Patient/$sourcePatientId" })
        }.map { it.resource }
            .filter { patient -> legacySourceOf(patient) == sourcePatientId }
            .map { it.logicalId }

    /** The case [patient] is linked to the old way (see-also link to another Patient). */
    fun legacySourceOf(patient: Patient): String? =
        patient.link
            .filter { it.type == Patient.LinkType.SEEALSO || !it.hasType() }
            .mapNotNull { it.other?.reference }
            .filter { it.startsWith("Patient/") }
            .map { IdType(it).idPart }
            .firstOrNull { it.isNotBlank() && it != patient.logicalId }

    // ---- Building the Group

    private fun memberId(member: Group.GroupMemberComponent): String? =
        member.entity?.reference?.takeIf { it.startsWith("Patient/") }?.let { IdType(it).idPart }

    private fun newGroup(sourcePatientId: String, source: Patient?): Group = Group().apply {
        id = groupId(sourcePatientId)
        active = true
        type = Group.GroupType.PERSON
        actual = true
        addIdentifier(Identifier().setSystem(IDENTIFIER_SYSTEM).setValue(sourcePatientId))
        code = CodeableConcept().addCoding(Coding(CODE_SYSTEM, CODE_CONTACTS, "VHF contacts of a case"))
        addCharacteristic()
            .setCode(CodeableConcept().addCoding(Coding(CODE_SYSTEM, CODE_INDEX_CASE, "Index (source) case")))
            .setValue(Reference("Patient/$sourcePatientId"))
            .setExclude(false)
        copyPlaceAndTags(this, source)
    }

    /** Name (by EPID, never a person's name), member count and the case's place. */
    private fun refresh(group: Group, source: Patient?) {
        val sourceId = indexCaseOf(group).orEmpty()
        val label = source?.let { VhfSourceCases.epidOf(it) } ?: group.name?.removePrefix("Contacts of ")
            ?.takeIf { it.isNotBlank() } ?: sourceId
        group.name = "Contacts of $label"
        group.quantity = group.member.size
        if (source != null && ManagingLocation.of(group) == null) copyPlaceAndTags(group, source)
    }

    /** The source case's managing location (as a Group tag + extension) and its other tags. */
    private fun copyPlaceAndTags(group: Group, source: Patient?) {
        source ?: return
        val groupSystem = FhirSystems.managingLocation("group")
        val tags = source.meta.tag.map { tag ->
            if (tag.system?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true) {
                tag.copy().apply { system = groupSystem }
            } else tag.copy()
        }
        group.meta.tag = (group.meta.tag.filter { t -> tags.none { it.system == t.system } } + tags).toMutableList()
        ManagingLocation.of(source)?.let { place ->
            group.extension = (group.extension.filter { it.url != groupSystem } + Extension(
                groupSystem, Reference("Location/${place.id}").apply { display = place.name }
            )).toMutableList()
        }
    }

    private fun toDate(day: LocalDate): Date = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant())
}
