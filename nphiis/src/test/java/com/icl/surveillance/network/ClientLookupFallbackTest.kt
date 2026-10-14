package com.icl.surveillance.network

import com.google.gson.Gson
import com.icl.surveillance.models.CrPatient
import org.junit.Assert.*
import org.junit.Test

class ClientLookupFallbackTest {
    @Test fun requestIncludesDocumentTypeIdAndDob() {
        assertEquals(mapOf(
            "identification_number" to "TEST001",
            "identification_type" to "National ID",
            "date_of_birth" to "1995-12-29"),
            ClientLookupRepository.registryParams(" TEST001 ", "national-id", "1995-12-29"))
        assertEquals("Alien ID", ClientLookupRepository.registryParams("TEST002", "alien-id", "1990-01-01")["identification_type"])
    }

    @Test fun readsNormalizedPatientFromEitherBackendSource() {
        for (source in listOf("cr", "iprs")) {
            val record = ClientLookupRepository.registryRecord(
                """{"status":"success","found":true,"source":"$source","patient":{"first_name":"Test","last_name":"Person","identification_number":"TEST001","gender":"Female","date_of_birth":"1995-12-29"}}""")
            val patient = Gson().fromJson(record, CrPatient::class.java)
            assertTrue(patient.isRecord)
            assertEquals("TEST001", patient.identificationNumber)
            assertEquals("Female", patient.gender)
            assertEquals("1995-12-29", patient.dateOfBirth)
        }
    }

    @Test fun retainsGuardianAndDependants() {
        val record = ClientLookupRepository.registryRecord(
            """{"status":"success","found":true,"source":"cr","patient":{"first_name":"Guardian","dependants":[{"relationship":"Child","result":[{"first_name":"Child","date_of_birth":"2018-03-04"}]}]}}""")
        val patient = Gson().fromJson(record, CrPatient::class.java)
        assertEquals("Guardian", patient.firstName)
        assertEquals("2018-03-04", patient.allDependants.single().dateOfBirth)
    }

    @Test fun explicitNoMatchReturnsNull() {
        assertNull(ClientLookupRepository.registryRecord("""{"status":"success","found":false,"patient":null}"""))
    }

    @Test fun errorsAndMalformedRepliesAreNotNoMatches() {
        for (raw in listOf(null, "", "invalid", "{}", """{"status":"error","error":"Unavailable"}""",
            """{"status":"success","found":true,"patient":null}""",
            """{"status":"success","found":"false"}""")) {
            assertTrue(runCatching { ClientLookupRepository.registryRecord(raw) }.isFailure)
        }
    }
}