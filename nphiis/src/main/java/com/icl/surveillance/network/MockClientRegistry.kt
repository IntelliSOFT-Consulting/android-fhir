package com.icl.surveillance.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.icl.surveillance.models.CrDependantGroup
import com.icl.surveillance.models.CrPatient

/**
 * Client Registry test data, in the CR's own reply format (CrPatient), used while
 * Constants.CLIENT_LOOKUP_USE_MOCK is true. Search by ID type + number (adult), or by the
 * parent's / guardian's ID (minor: their dependants are listed). Anything else is "not found".
 *
 * | ID type     | Number      | Person (DOB)                     | Dependants                                   |
 * |-------------|-------------|----------------------------------|----------------------------------------------|
 * | National ID | 1000000235  | Rose D Chebet (1967-02-21)       | David A Were (2026-02-28) — real CR sample   |
 * | National ID | 12345678    | Jane Wanjiku Kamau (1990-04-12)  | Liam Kamau (2016-01-15)                      |
 * | National ID | 23456789    | John Ochieng Otieno (1985-11-03) | —                                            |
 * | Alien ID    | AK1234567   | Amina Hassan (1998-02-27)        | —                                            |
 * | National ID | 34567890    | Grace Achieng Mutua (1989-03-14) | Brian Mutua (2018-06-20), Faith Mutua (2021-09-02) |
 */
object MockClientRegistry {

    /** A reply received from the CR (DHA UAT), kept verbatim. */
    private const val SAMPLE_REPLY = """
{
    "id": "CR-2026-000235",
    "resourceType": "Patient",
    "meta": {"versionId": "1", "creationTime": "2026-08-24 17:01:13.289136", "lastUpdated": "2026-09-17 14:06:01.594071", "source": "https://cr-nrb.tiberbu.health"},
    "originSystem": {"system": "SAFARICOM-CONSORTIUM-SANDBOX", "record_id": "HH-2026-000059"},
    "first_name": "ROSE",
    "middle_name": "D",
    "last_name": "CHEBET",
    "gender": "Male",
    "date_of_birth": "1967-02-21",
    "citizenship": "KENYAN",
    "employment_type": "Unemployed",
    "civil_status": "Married",
    "identification_type": "National ID",
    "identification_number": "1000000235",
    "other_identifications": [
        {"identification_type": "Household Number", "identification_number": "HH-2026-000059"},
        {"identification_type": "SHA Number", "identification_number": "SHA-2026-000235"}
    ],
    "dependants": [
        {
            "date_added": "2026-08-24 17:01:13.289136",
            "relationship": "Child",
            "total": 1,
            "result": [
                {
                    "id": "CR-2026-000236",
                    "resourceType": "Patient",
                    "first_name": "DAVID",
                    "middle_name": "A",
                    "last_name": "WERE",
                    "gender": "Female",
                    "date_of_birth": "2026-02-28",
                    "citizenship": "KENYAN",
                    "identification_type": "Birth Certificate",
                    "identification_number": "1000000236",
                    "other_identifications": [
                        {"identification_type": "Household Number", "identification_number": "HH-2026-000059"},
                        {"identification_type": "SHA Number", "identification_number": "SHA-2026-000236"}
                    ],
                    "phone": "070003281486",
                    "country": "Kenya",
                    "county": "NYERI",
                    "sub_county": "SOUTH",
                    "ward": "TALA",
                    "village_estate": "Estate 237",
                    "postal_address": "10236",
                    "id_serial": "700000236"
                }
            ]
        }
    ],
    "phone": "070003281486",
    "country": "Kenya",
    "county": "KISII",
    "sub_county": "KISUMU CENTRAL",
    "ward": "CBD",
    "village_estate": "Estate 236",
    "postal_address": "10235",
    "id_serial": "700000235"
}
"""

    private fun children(vararg kids: CrPatient) =
        listOf(CrDependantGroup(relationship = "Child", total = kids.size, result = kids.toList()))

    /** Raw replies, as the CR would send them. */
    private val records: List<JsonObject> by lazy {
        val gson = Gson()
        listOf(gson.fromJson(SAMPLE_REPLY, JsonObject::class.java)) + listOf(
            CrPatient(
                id = "CR-TEST-0001", firstName = "JANE", middleName = "WANJIKU", lastName = "KAMAU",
                gender = "Female", dateOfBirth = "1990-04-12", citizenship = "KENYAN",
                employmentType = "Nurse", civilStatus = "Married",
                identificationType = "National ID", identificationNumber = "12345678",
                phone = "0712345678", country = "Kenya",
                county = "NAIROBI", subCounty = "KAMUKUNJI", ward = "EASTLEIGH NORTH", villageEstate = "Eastleigh",
                dependants = children(
                    CrPatient(
                        id = "CR-TEST-0002", firstName = "LIAM", lastName = "KAMAU", gender = "Male",
                        dateOfBirth = "2016-01-15", citizenship = "KENYAN",
                        identificationType = "Birth Certificate", identificationNumber = "2016011501",
                        country = "Kenya", county = "NAIROBI", subCounty = "KAMUKUNJI",
                        ward = "EASTLEIGH NORTH", villageEstate = "Eastleigh",
                    )
                ),
            ),
            CrPatient(
                id = "CR-TEST-0003", firstName = "JOHN", middleName = "OCHIENG", lastName = "OTIENO",
                gender = "Male", dateOfBirth = "1985-11-03", citizenship = "KENYAN",
                employmentType = "Fisherman", civilStatus = "Single",
                identificationType = "National ID", identificationNumber = "23456789",
                phone = "0733456789", country = "Kenya",
                county = "KISUMU", subCounty = "KISUMU CENTRAL", ward = "KONDELE", villageEstate = "Kondele",
            ),
            CrPatient(
                id = "CR-TEST-0004", firstName = "AMINA", lastName = "HASSAN",
                gender = "Female", dateOfBirth = "1998-02-27", citizenship = "SOMALI",
                employmentType = "Unemployed", civilStatus = "Single",
                identificationType = "Alien ID", identificationNumber = "AK1234567",
                phone = "0755678901", country = "Kenya",
                county = "NAIROBI", subCounty = "DAGORETTI SOUTH", ward = "NGANDO", villageEstate = "Uthiru",
            ),
            CrPatient(
                id = "CR-TEST-0005", firstName = "GRACE", middleName = "ACHIENG", lastName = "MUTUA",
                gender = "Female", dateOfBirth = "1989-03-14", citizenship = "KENYAN",
                employmentType = "Teacher", civilStatus = "Married",
                identificationType = "National ID", identificationNumber = "34567890",
                phone = "0777890123", country = "Kenya",
                county = "KISUMU", subCounty = "MUHORONI", ward = "CHEMELIL", villageEstate = "Chemelil",
                dependants = children(
                    CrPatient(
                        id = "CR-TEST-0006", firstName = "BRIAN", lastName = "MUTUA", gender = "Male",
                        dateOfBirth = "2018-06-20", citizenship = "KENYAN",
                        identificationType = "Birth Certificate", identificationNumber = "1234567890",
                        country = "Kenya", county = "KISUMU", subCounty = "MUHORONI", ward = "CHEMELIL",
                        villageEstate = "Chemelil",
                    ),
                    CrPatient(
                        id = "CR-TEST-0007", firstName = "FAITH", lastName = "MUTUA", gender = "Female",
                        dateOfBirth = "2021-09-02", citizenship = "KENYAN",
                        identificationType = "Birth Notification", identificationNumber = "BN20210902",
                        country = "Kenya",
                    ),
                ),
            ),
        ).map { gson.toJsonTree(it).asJsonObject }
    }

    /** The raw record with this CR identification type (e.g. "National ID") and number, or null. */
    fun find(identificationNumber: String, crIdentificationType: String): JsonObject? =
        records.firstOrNull {
            it.get("identification_number")?.asString.equals(identificationNumber.trim(), ignoreCase = true) &&
                it.get("identification_type")?.asString.equals(crIdentificationType, ignoreCase = true)
        }
}
