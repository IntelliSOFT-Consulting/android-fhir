package com.icl.surveillance.models

import com.google.gson.annotations.SerializedName
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill

/**
 * A person found by a lookup (Client Registry: CrPatient.toDemographics; IPRS:
 * IprsPerson.toDemographics), shaped like the Demographics section of the VHF case form
 * (vhf-case.json). Choice fields carry the form's answer codes; dates are yyyy-MM-dd; locations
 * carry the Location ids used by the app's location bundles. Any field may be null.
 */
data class ClientDemographics(
    /** Family name (surname) — 486402457213 */
    val surname: String? = null,
    /** Given names — 873240407472 */
    val givenNames: String? = null,
    /** male | female | other — 929966324957 */
    val sex: String? = null,
    /** Other (specify) — vhf-sex-other */
    val sexOther: String? = null,
    /** yyyy-MM-dd — 257830485990 (sets "Date of birth known" to known) */
    val dateOfBirth: String? = null,
    /** national-id | passport | birth-certificate — vhf-id-type / vhf-id-type-minor */
    val identificationType: String? = null,
    /** vhf-national-id */
    val identificationNumber: String? = null,
    /** vhf-phone */
    val phone: String? = null,
    /** na-child | single | married | divorced | widowed — vhf-marital-status */
    val maritalStatus: String? = null,
    /** employed | unemployed — vhf-occupation */
    val occupation: String? = null,
    /** Employed as (specify) — vhf-occupation-specify */
    val occupationSpecify: String? = null,
    /** none | primary | secondary | tertiary — vhf-education */
    val education: String? = null,
    /** yes | no — vhf-insurance */
    val insurance: String? = null,
    /** vhf-current-residence */
    val currentResidence: String? = null,
    /** rural | urban | camp — vhf-residence-type */
    val residenceType: String? = null,
    /** vhf-country */
    val country: String? = null,
    /** a4-county */
    val county: LocationRef? = null,
    /** a3-sub-county */
    val subCounty: LocationRef? = null,
    /** a2-ward */
    val ward: LocationRef? = null,
    /** Town / village / camp — vhf-village */
    val village: String? = null,
    /** vhf-landmark */
    val landmark: String? = null,
    /** Parent / guardian / next of kin — vhf-next-of-kin */
    val nextOfKin: String? = null,
    /** vhf-next-of-kin-phone */
    val nextOfKinPhone: String? = null,
    /** For display only: every field the API returned, as (label, value), in the API's order. */
    val recordDetails: List<Pair<String, String>> = emptyList(),
    /** For a dependant: their relationship to the principal member, e.g. "Child". */
    val relationship: String? = null,
) {
    val fullName: String
        get() = listOfNotNull(givenNames, surname).filter { it.isNotBlank() }.joinToString(" ")

    /** Case-form answers keyed by linkId, for [FormPrefill.stage]. Blank fields are left out. */
    fun toPrefill(): Map<String, String> = buildMap {
        fun add(linkId: String, value: String?) {
            if (!value.isNullOrBlank()) put(linkId, value.trim())
        }
        add(FormFields.Person.SURNAME, surname)
        add(FormFields.Person.FIRST_NAME, givenNames)
        add(FormFields.Person.SEX, sex)
        add("vhf-sex-other", sexOther)
        if (!dateOfBirth.isNullOrBlank()) {
            add(DOB_KNOWN, "known")
            add(FormFields.Person.DATE_OF_BIRTH, dateOfBirth)
        }
        // Adults and minors have separate "Type of identification" questions; only the one
        // offering the code takes it.
        add(FormFields.Vhf.ID_TYPE, identificationType)
        add(FormFields.Vhf.ID_TYPE_MINOR, identificationType)
        add(FormFields.Vhf.NATIONAL_ID, identificationNumber)
        add(FormFields.Vhf.PHONE, phone)
        add("vhf-marital-status", maritalStatus)
        add("vhf-occupation", occupation)
        add("vhf-occupation-specify", occupationSpecify)
        add("vhf-education", education)
        add("vhf-insurance", insurance)
        add("vhf-current-residence", currentResidence)
        add("vhf-residence-type", residenceType)
        add("vhf-country", country)
        add(FormFields.Residence.COUNTY, county?.toPrefill())
        add(FormFields.Residence.SUB_COUNTY, subCounty?.toPrefill())
        add(FormFields.Residence.WARD, ward?.toPrefill())
        add(FormFields.Vhf.VILLAGE, village)
        add("vhf-landmark", landmark)
        add(FormFields.Vhf.NEXT_OF_KIN, nextOfKin)
        add(FormFields.Vhf.NEXT_OF_KIN_PHONE, nextOfKinPhone)
    }

    companion object {
        /** "Date of birth known or unknown" on the VHF case form. */
        const val DOB_KNOWN = "442636360588"
    }
}

/** A Location resource by id (e.g. "47") and display name (e.g. "NAIROBI"). */
data class LocationRef(val id: String, val name: String? = null) {
    fun toPrefill(): String = FormPrefill.locationReference(id, name)
}

sealed class ClientLookupOutcome {
    data class Found(val client: ClientDemographics, val source: String?) : ClientLookupOutcome() {
        val sourceLabel: String
            get() = when (source?.trim()?.uppercase()) {
                "IPRS" -> "IPRS"
                "CR", "CLIENT_REGISTRY", "CLIENT REGISTRY" -> "Client Registry"
                null, "" -> "Client Registry"
                else -> source
            }
    }
    /** Minor search: the guardian and the children registered under them (may be empty). */
    data class Dependents(
        val guardian: ClientDemographics,
        val children: List<ClientDemographics>,
        val source: String?,
    ) : ClientLookupOutcome()

    object NotFound : ClientLookupOutcome()
    object Offline : ClientLookupOutcome()
    data class Failed(val message: String) : ClientLookupOutcome()
}

/** Where a lookup goes. */
enum class LookupSource(val label: String) {
    CLIENT_REGISTRY("Client Registry"),
    IPRS("IPRS"),
}

/** Reply of the IPRS lookup (Constants.IPRS_LOOKUP_URL). */
data class IprsLookupResponse(
    @SerializedName("data") val data: IprsPerson? = null,
)

data class IprsPerson(
    @SerializedName("full_name") val fullName: String? = null,
    @SerializedName("id_number") val idNumber: String? = null,
    /** "M" or "F" */
    @SerializedName("sex") val sex: String? = null,
    /** yyyy-MM-dd */
    @SerializedName("date_of_birth") val dateOfBirth: String? = null,
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    @SerializedName("other_names") val otherNames: String? = null,
    @SerializedName("village") val village: String? = null,
    @SerializedName("serial_number") val serialNumber: String? = null,
    @SerializedName("place_of_birth") val placeOfBirth: String? = null,
    @SerializedName("nationality") val nationality: String? = null,
    @SerializedName("home_sub_location") val homeSubLocation: String? = null,
    @SerializedName("home_location") val homeLocation: String? = null,
    @SerializedName("home_division") val homeDivision: String? = null,
    @SerializedName("home_district") val homeDistrict: String? = null,
    @SerializedName("home_county") val homeCounty: String? = null,
    @SerializedName("district_of_birth") val districtOfBirth: String? = null,
) {
    /**
     * As the form's demographics. Names are title-cased (IPRS sends capitals). IPRS has no
     * Location ids, so county / sub-county / ward are left for the user; the home area, if sent,
     * goes into "Current residence".
     */
    fun toDemographics(): ClientDemographics {
        val given = listOfNotNull(firstName, otherNames)
            .filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { fullName?.substringBeforeLast(" ").orEmpty() }
        val home = listOfNotNull(homeSubLocation, homeLocation, homeDivision, homeDistrict, homeCounty)
            .filter { it.isNotBlank() }.joinToString(", ")
        val received = listOf(
            "Full name" to fullName, "ID number" to idNumber, "Serial number" to serialNumber,
            "First name" to firstName, "Other names" to otherNames, "Last name" to lastName,
            "Sex" to sex, "Date of birth" to dateOfBirth, "Nationality" to nationality,
            "Place of birth" to placeOfBirth, "District of birth" to districtOfBirth,
            "Village" to village, "Home sub-location" to homeSubLocation, "Home location" to homeLocation,
            "Home division" to homeDivision, "Home district" to homeDistrict, "Home county" to homeCounty,
        ).mapNotNull { (label, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { label to it } }
        return ClientDemographics(
            recordDetails = received,
            surname = lastName?.titleCase()
                ?: fullName?.substringAfterLast(" ")?.titleCase(),
            givenNames = given.titleCase().ifBlank { null },
            sex = when (sex?.trim()?.uppercase()) {
                "M", "MALE" -> "male"
                "F", "FEMALE" -> "female"
                null, "" -> null
                else -> "other"
            },
            dateOfBirth = dateOfBirth?.take(10),
            identificationType = "national-id",
            identificationNumber = idNumber,
            village = village?.titleCase(),
            currentResidence = home.titleCase().ifBlank { null },
            country = nationality?.let { if (it.trim().uppercase().startsWith("KENYA")) "Kenya" else it.titleCase() },
        )
    }

    private fun String.titleCase(): String =
        trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
}
