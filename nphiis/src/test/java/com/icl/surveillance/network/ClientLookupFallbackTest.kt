package com.icl.surveillance.network

import com.icl.surveillance.models.ClientDemographics
import com.icl.surveillance.models.ClientLookupOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClientLookupFallbackTest {
    private val person = ClientDemographics(givenNames = "Test", surname = "Client")
    private val found = ClientLookupOutcome.Found(person, "IPRS")

    @Test fun crZeroResultsResponseAllowsIprsFallback() = runBlocking {
        val raw = """{"error":"Bad Request","message":"failed to get patient record: zero results found in client registry fetch patient search response"}"""
        assertTrue(ClientLookupRepository.isCrNoMatch(400, raw))
        val result = ClientLookupRepository.lookupCrThenIprs("national-id", false,
            crLookup = {
                if (ClientLookupRepository.isCrNoMatch(400, raw)) ClientLookupOutcome.NotFound
                else ClientLookupOutcome.Failed("Request failed")
            },
            iprsLookup = { found })
        assertSame(found, result)
        for (status in listOf(200, 401, 403, 500)) {
            assertFalse(ClientLookupRepository.isCrNoMatch(status, raw))
        }
    }

    @Test fun otherBadRequestsAreNotTreatedAsNoMatch() {
        for (raw in listOf(null, "", "not json", "{}", """{"message":"Invalid identification type"}""",
            """{"message":{}}""")) {
            assertFalse(ClientLookupRepository.isCrNoMatch(400, raw))
        }
    }
    @Test fun callsCrBeforeIprsOnlyWhenNotFound() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ClientLookupRepository.lookupCrThenIprs("national-id", false,
            crLookup = { calls.add("CR"); ClientLookupOutcome.NotFound },
            iprsLookup = { calls.add("IPRS"); found })
        assertEquals(listOf("CR", "IPRS"), calls)
        assertSame(found, result)
    }

    @Test fun preservesCrMatchesAndErrorsWithoutCallingIprs() = runBlocking {
        val outcomes = listOf(ClientLookupOutcome.Found(person, "CR"),
            ClientLookupOutcome.Dependents(person, emptyList(), "CR"),
            ClientLookupOutcome.Offline, ClientLookupOutcome.Failed("Unavailable"))
        for (outcome in outcomes) {
            val result = ClientLookupRepository.lookupCrThenIprs("national-id", false,
                crLookup = { outcome }, iprsLookup = { error("Unexpected IPRS call") })
            assertSame(outcome, result)
        }
    }

    @Test fun unsupportedIdTypesDoNotCallIprs() = runBlocking {
        val result = ClientLookupRepository.lookupCrThenIprs("passport", false,
            crLookup = { ClientLookupOutcome.NotFound },
            iprsLookup = { error("Unexpected IPRS call") })
        assertSame(ClientLookupOutcome.NotFound, result)
    }

    @Test fun preservesIprsNoMatchAndErrors() = runBlocking {
        for (outcome in listOf(ClientLookupOutcome.NotFound, ClientLookupOutcome.Offline,
            ClientLookupOutcome.Failed("Unavailable"))) {
            val result = ClientLookupRepository.lookupCrThenIprs("national-id", false,
                crLookup = { ClientLookupOutcome.NotFound }, iprsLookup = { outcome })
            assertSame(outcome, result)
        }
    }

    @Test fun minorFallbackKeepsTheMatchedAdultAsGuardian() = runBlocking {
        val result = ClientLookupRepository.lookupCrThenIprs("national-id", true,
            crLookup = { ClientLookupOutcome.NotFound }, iprsLookup = { found })
        assertEquals(ClientLookupOutcome.Dependents(person, emptyList(), "IPRS"), result)
    }
}