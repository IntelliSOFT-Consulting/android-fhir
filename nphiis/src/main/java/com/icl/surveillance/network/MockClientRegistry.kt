package com.icl.surveillance.network

import com.icl.surveillance.models.ClientDemographics
import com.icl.surveillance.models.ClientLookupResponse
import com.icl.surveillance.models.LocationRef

/**
 * Test data standing in for the lookup API while Constants.CLIENT_LOOKUP_USE_MOCK is true.
 * Look up any of these (ID type + number); anything else is "not found".
 *
 * | ID type           | Number      | Person                         | DOB        |
 * |-------------------|-------------|--------------------------------|------------|
 * | National ID       | 12345678    | Jane Wanjiku Kamau (F)         | 1990-04-12 |
 * | National ID       | 23456789    | John Ochieng Otieno (M)        | 1985-11-03 |
 * | Passport          | AK1234567   | Amina Hassan (F)               | 1998-02-27 |
 * | Birth certificate | 1234567890  | Brian Mutua (M, child)         | 2018-06-20 |
 *
 * Location ids come from the app's bundled location files, so the county / sub-county / ward
 * pickers resolve.
 */
object MockClientRegistry {

    private val clients = listOf(
        ClientDemographics(
            surname = "Kamau",
            givenNames = "Jane Wanjiku",
            sex = "female",
            dateOfBirth = "1990-04-12",
            identificationType = "national-id",
            identificationNumber = "12345678",
            phone = "0712345678",
            maritalStatus = "married",
            occupation = "employed",
            occupationSpecify = "Nurse",
            education = "tertiary",
            insurance = "yes",
            currentResidence = "Eastleigh, 1st Avenue",
            residenceType = "urban",
            country = "Kenya",
            county = LocationRef("47", "NAIROBI"),
            subCounty = LocationRef("KAMUKUNJI", "KAMUKUNJI"),
            ward = LocationRef("EASTLEIGH-NORTH", "EASTLEIGH NORTH"),
            village = "Eastleigh",
            landmark = "Near Garissa Lodge",
            nextOfKin = "Peter Kamau",
            nextOfKinPhone = "0722345678",
        ),
        ClientDemographics(
            surname = "Otieno",
            givenNames = "John Ochieng",
            sex = "male",
            dateOfBirth = "1985-11-03",
            identificationType = "national-id",
            identificationNumber = "23456789",
            phone = "0733456789",
            maritalStatus = "single",
            occupation = "employed",
            occupationSpecify = "Fisherman",
            education = "secondary",
            insurance = "no",
            currentResidence = "Kondele estate",
            residenceType = "urban",
            country = "Kenya",
            county = LocationRef("42", "KISUMU"),
            subCounty = LocationRef("KISUMU-CENTRAL", "KISUMU CENTRAL"),
            ward = LocationRef("KONDELE", "KONDELE"),
            village = "Kondele",
            landmark = "Kondele market",
            nextOfKin = "Mary Achieng",
            nextOfKinPhone = "0744567890",
        ),
        ClientDemographics(
            surname = "Hassan",
            givenNames = "Amina",
            sex = "female",
            dateOfBirth = "1998-02-27",
            identificationType = "passport",
            identificationNumber = "AK1234567",
            phone = "0755678901",
            maritalStatus = "single",
            occupation = "unemployed",
            education = "tertiary",
            insurance = "yes",
            currentResidence = "Uthiru",
            residenceType = "urban",
            country = "Kenya",
            county = LocationRef("47", "NAIROBI"),
            subCounty = LocationRef("DAGORETTI-SOUTH", "DAGORETTI SOUTH"),
            ward = LocationRef("NGANDO", "NGANDO"),
            village = "Ngando",
            nextOfKin = "Fatuma Hassan",
            nextOfKinPhone = "0766789012",
        ),
        ClientDemographics(
            surname = "Mutua",
            givenNames = "Brian",
            sex = "male",
            dateOfBirth = "2018-06-20",
            identificationType = "birth-certificate",
            identificationNumber = "1234567890",
            maritalStatus = "na-child",
            education = "primary",
            insurance = "no",
            currentResidence = "Muhoroni town",
            residenceType = "rural",
            country = "Kenya",
            county = LocationRef("42", "KISUMU"),
            subCounty = LocationRef("MUHORONI", "MUHORONI"),
            ward = LocationRef("CHEMELIL", "CHEMELIL"),
            village = "Chemelil",
            nextOfKin = "Grace Mutua (mother)",
            nextOfKinPhone = "0777890123",
        ),
    )

    fun search(identificationNumber: String, identificationType: String): ClientLookupResponse {
        val match = clients.firstOrNull {
            it.identificationNumber.equals(identificationNumber.trim(), ignoreCase = true) &&
                it.identificationType == identificationType
        }
        return ClientLookupResponse(found = match != null, source = match?.let { "CR" }, client = match)
    }
}
