package com.icl.surveillance.clients

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientLookupValidationTest {
    @Test fun nationalIdRequiresSevenOrEightAsciiDigits() {
        listOf("1234567", "12345678").forEach { assertTrue(ClientLookupValidation.validNationalId(it)) }
        listOf("123456", "123456789", "1234A67", "").forEach {
            assertFalse(ClientLookupValidation.validNationalId(it))
        }
    }

    @Test fun firstNameMustMatchReturnedFirstName() {
        assertTrue(ClientLookupValidation.matchesFirstName("  jane  ", "JANE"))
        assertTrue(ClientLookupValidation.matchesFirstName("MARY  JANE", "Mary Jane"))
        listOf("Mary", "", "Jane Doe").forEach {
            assertFalse(ClientLookupValidation.matchesFirstName(it, "Jane"))
        }
        assertFalse(ClientLookupValidation.matchesFirstName("Jane", null))
        assertFalse(ClientLookupValidation.matchesFirstName("Jane", "  "))
    }
}
