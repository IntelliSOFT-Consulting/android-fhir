package com.icl.surveillance.models

import com.google.gson.annotations.SerializedName
import com.icl.surveillance.utils.Constants
import com.icl.surveillance.utils.LocationResolver
import java.time.LocalDate
import java.time.Period

/**
 * Client Registry patient, as returned by GET /api/v1/patients?identification_number=&identification_type=
 * (DHA middleware). The children registered under the person come in [dependants].
 */
data class CrPatient(
    @SerializedName("id") val id: String? = null,
    @SerializedName("resourceType") val resourceType: String? = null,
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("middle_name") val middleName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    /** "Male" / "Female" */
    @SerializedName("gender") val gender: String? = null,
    /** yyyy-MM-dd */
    @SerializedName("date_of_birth") val dateOfBirth: String? = null,
    /** e.g. "KENYAN" */
    @SerializedName("citizenship") val citizenship: String? = null,
    /** e.g. "Unemployed", "Employed", "Self Employed" */
    @SerializedName("employment_type") val employmentType: String? = null,
    /** e.g. "Married", "Single", "Divorced", "Widowed" */
    @SerializedName("civil_status") val civilStatus: String? = null,
    /** CR spelling, e.g. "National ID", "Birth Certificate" */
    @SerializedName("identification_type") val identificationType: String? = null,
    @SerializedName("identification_number") val identificationNumber: String? = null,
    @SerializedName("other_identifications") val otherIdentifications: List<CrIdentification>? = null,
    @SerializedName("dependants") val dependants: List<CrDependantGroup>? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("country") val country: String? = null,
    /** Names, e.g. "NYERI"; resolved to the app's Location ids by LocationResolver. */
    @SerializedName("county") val county: String? = null,
    @SerializedName("sub_county") val subCounty: String? = null,
    @SerializedName("ward") val ward: String? = null,
    @SerializedName("village_estate") val villageEstate: String? = null,
    @SerializedName("postal_address") val postalAddress: String? = null,
    @SerializedName("id_serial") val idSerial: String? = null,
) {
    /** A real record (the CR answers with an empty or error object when there is none). */
    val isRecord: Boolean
        get() = !id.isNullOrBlank() || !firstName.isNullOrBlank() || !lastName.isNullOrBlank()

    /** Every child / dependant in all relationship groups. */
    val allDependants: List<CrPatient>
        get() = dependants.orEmpty().flatMap { it.result.orEmpty() }

    /**
     * As the case form's demographics (vhf-case.json, Demographics section):
     *
     * | CR field                     | Form question                                   |
     * |------------------------------|-------------------------------------------------|
     * | last_name                    | Family name (surname)                           |
     * | first_name + middle_name     | Given names                                     |
     * | gender                       | Sex (male / female)                             |
     * | date_of_birth                | Date of birth (+ "Date of birth known")         |
     * | identification_type / number | Type of identification / Identification number  |
     * | phone                        | Phone number                                    |
     * | civil_status                 | Marital status (N/A - child when under 18)      |
     * | employment_type              | Occupation (+ Employed as)                      |
     * | country / citizenship        | Country                                         |
     * | county / sub_county / ward   | County / Sub County / Ward (matched by name)    |
     * | village_estate               | Town / village / camp                           |
     * | village_estate, ward, ...    | Current residence (as text)                     |
     *
     * [guardian], for a dependant, becomes the next of kin.
     */
    fun toDemographics(
        locations: LocationResolver?,
        guardian: CrPatient? = null,
        relationship: String? = null,
    ): ClientDemographics {
        val resolved = locations?.resolve(county, subCounty, ward)
        val child = isUnder18()
        return ClientDemographics(
            relationship = relationship?.trim()?.ifBlank { null },
            surname = lastName.titleCase(),
            givenNames = listOfNotNull(firstName, middleName).filter { it.isNotBlank() }
                .joinToString(" ").titleCase(),
            sex = when (gender?.trim()?.lowercase()) {
                "male", "m" -> "male"
                "female", "f" -> "female"
                null, "" -> null
                else -> "other"
            },
            dateOfBirth = dateOfBirth?.take(10)?.takeIf { it.length == 10 },
            identificationType = identificationType?.let { crType ->
                Constants.CLIENT_LOOKUP_ID_TYPES.entries
                    .firstOrNull { it.value.equals(crType.trim(), ignoreCase = true) }?.key
            },
            identificationNumber = identificationNumber?.trim()?.ifBlank { null },
            phone = phone?.trim()?.ifBlank { null },
            maritalStatus = when (civilStatus?.trim()?.lowercase()) {
                "married" -> "married"
                "single", "never married" -> "single"
                "divorced", "separated" -> "divorced"
                "widowed", "widow", "widower" -> "widowed"
                else -> if (child) "na-child" else null
            },
            occupation = when {
                employmentType.isNullOrBlank() -> null
                employmentType.contains("unemployed", ignoreCase = true) -> "unemployed"
                else -> "employed"
            },
            occupationSpecify = employmentType?.trim()
                ?.takeIf { !it.contains("unemployed", true) && !it.equals("employed", true) },
            country = country?.trim()?.ifBlank { null }
                ?: citizenship?.let { if (it.trim().uppercase().startsWith("KENYA")) "Kenya" else it.titleCase() },
            county = resolved?.county,
            subCounty = resolved?.subCounty,
            ward = resolved?.ward,
            village = villageEstate.titleCase(),
            currentResidence = listOfNotNull(villageEstate, ward, subCounty, county)
                .filter { it.isNotBlank() }.joinToString(", ").titleCase(),
            nextOfKin = guardian?.let { g ->
                listOfNotNull(g.firstName, g.middleName, g.lastName).filter { it.isNotBlank() }
                    .joinToString(" ").titleCase()
            },
            nextOfKinPhone = guardian?.phone?.trim()?.ifBlank { null },
        )
    }

    private fun isUnder18(): Boolean = runCatching {
        Period.between(LocalDate.parse(dateOfBirth!!.take(10)), LocalDate.now()).years < 18
    }.getOrDefault(false)

    private fun String?.titleCase(): String? =
        this?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            ?.joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
            ?.ifBlank { null }
}

data class CrIdentification(
    @SerializedName("identification_type") val identificationType: String? = null,
    @SerializedName("identification_number") val identificationNumber: String? = null,
)

/** One relationship group under "dependants", e.g. relationship "Child" with its records. */
data class CrDependantGroup(
    @SerializedName("date_added") val dateAdded: String? = null,
    @SerializedName("relationship") val relationship: String? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("result") val result: List<CrPatient>? = null,
)
