package com.icl.surveillance.clients

import com.icl.surveillance.R
import com.icl.surveillance.models.ClientDemographics
import org.junit.Assert.*
import org.junit.Test

class ClientLookupSummaryTest {
    @Test fun summaryOnlyIncludesAllowedFields() {
        val person = ClientDemographics(givenNames = "Test", surname = "Client", sex = "FEMALE",
            phone = "0700000000", identificationNumber = "TEST001", dateOfBirth = "1990-01-02",
            currentResidence = "Private address", occupation = "employed",
            recordDetails = listOf("Secret field" to "Private value"))
        assertEquals(listOf(R.string.client_lookup_gender to "Female",
            R.string.client_lookup_phone to "0700000000",
            R.string.client_lookup_id_number to "TEST001",
            R.string.client_lookup_dob to "1990-01-02"), ClientLookupSummary.rows(person))
    }
    @Test fun missingValuesAreOmitted() {
        assertTrue(ClientLookupSummary.rows(ClientDemographics(sex = " ", phone = "")).isEmpty())
    }
    @Test fun dependantUsesOwnValues() {
        val child = ClientDemographics(givenNames = "Test Child", relationship = "Child",
            sex = "male", dateOfBirth = "2018-03-04", nextOfKinPhone = "0700000000")
        assertEquals(listOf(R.string.client_lookup_gender to "Male",
            R.string.client_lookup_dob to "2018-03-04"), ClientLookupSummary.rows(child))
    }
    @Test fun renderingDoesNotChangePrefill() {
        val person = ClientDemographics(givenNames = "Test", surname = "Client", sex = "female",
            phone = "254700000000", currentResidence = "Private address", village = "Test village")
        val before = person.toPrefill()
        ClientLookupSummary.rows(person)
        assertEquals(before, person.toPrefill())
        assertTrue(person.toPrefill().values.contains("Private address"))
        assertTrue(person.toPrefill().values.contains("Test village"))
    }
}
