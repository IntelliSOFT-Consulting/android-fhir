package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.utils.FormatterClass

/**
 * VHF module lists: "Suspected Cases" (suspected / probable / confirmed) and "Contact List" are
 * listed separately so contacts are not mixed in with cases. Set when a list is opened from the
 * VHF menu; VHF Training lists show everything (no split).
 */
object VhfListMode {
    const val CASES = "cases"
    const val CONTACTS = "contacts"
    const val CONFIRMED = "confirmed"
    const val NONE = ""
    private const val PREF = "vhfListMode"

    fun set(context: Context, mode: String) = FormatterClass().saveSharedPref(PREF, mode, context)

    fun get(context: Context): String = FormatterClass().getSharedPref(PREF, context).orEmpty()

    /**
     * The contact number the HIE assigned to [patient] (identifier CONTACT_ID), or null. A number
     * kept from an earlier exposure (use old) is skipped, and a complete number wins over the
     * device's "-C" prefix.
     */
    fun contactIdOf(patient: org.hl7.fhir.r4.model.Patient): String? =
        patient.identifier.filter { identifier ->
            identifier.use != org.hl7.fhir.r4.model.Identifier.IdentifierUse.OLD &&
                (identifier.system == FhirSystems.CONTACT_ID_SYSTEM ||
                    identifier.type?.coding?.any { it.code == FhirSystems.CONTACT_ID } == true)
        }.mapNotNull { it.value?.trim()?.takeIf { value -> value.isNotBlank() } }
            .let { ids -> ids.lastOrNull { !isIncompleteContactId(it) } ?: ids.firstOrNull() }

    /** "<source case EPID>-C": the contact number before the server adds the sequence. */
    fun contactIdPrefix(sourceEpid: String): String = sourceEpid.trim().trimEnd('-') + "-C"

    /** True for a contact number the server has not completed yet (ends with "-C"). */
    fun isIncompleteContactId(value: String?): Boolean = value?.trim()?.endsWith("-C") == true

    /** A CONTACT_ID identifier as the HIE writes it (use official, type and system CONTACT_ID). */
    fun contactIdentifier(value: String): org.hl7.fhir.r4.model.Identifier =
        org.hl7.fhir.r4.model.Identifier().apply {
            use = org.hl7.fhir.r4.model.Identifier.IdentifierUse.OFFICIAL
            type = org.hl7.fhir.r4.model.CodeableConcept().addCoding(
                org.hl7.fhir.r4.model.Coding(FhirSystems.CONTACT_ID_SYSTEM, FhirSystems.CONTACT_ID, "Contact ID")
            )
            system = FhirSystems.CONTACT_ID_SYSTEM
            this.value = value
        }

    /**
     * A contact: "Type of case" = Contact. A record with no Type of case answer (e.g. not synced
     * yet) counts as a contact when the HIE gave it a contact number.
     */
    fun isContact(caseType: String?, contactId: String?): Boolean =
        if (caseType.isNullOrBlank()) !contactId.isNullOrBlank() else isContact(caseType)

    fun isContact(caseType: String?): Boolean =
        caseType?.trim().equals(FormFields.Vhf.CASE_TYPE_CONTACT, ignoreCase = true)

    /** Whether a row of "Type of case" [caseType] belongs in the list [mode]. */
    /** "Type of case" = Confirmed. */
    fun isConfirmed(caseType: String?): Boolean =
        caseType?.trim().equals(FormFields.Vhf.CASE_TYPE_CONFIRMED, ignoreCase = true)

    /** The case's lab result is final, positive or negative ([VhfSpecimens.Summary.labResults]). */
    fun hasFinalLabResult(labResults: String?): Boolean =
        labResults == VhfSpecimens.Result.POSITIVE.label || labResults == VhfSpecimens.Result.NEGATIVE.label

    /**
     * VHF lists: Suspected (suspected, probable, or not set, with no final lab result), Confirmed
     * (Type of case Confirmed, or a final lab result whether positive or negative), and Contacts.
     * Each record is in exactly one of them.
     */
    fun matches(
        mode: String,
        caseType: String?,
        contactId: String? = null,
        labResults: String? = null,
    ): Boolean {
        val confirmed = isConfirmed(caseType) || hasFinalLabResult(labResults)
        return when (mode) {
            CASES -> !isContact(caseType, contactId) && !confirmed
            CONFIRMED -> !isContact(caseType, contactId) && confirmed
            CONTACTS -> isContact(caseType, contactId)
            else -> true
        }
    }
}
