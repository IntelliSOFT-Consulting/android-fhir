package com.icl.surveillance.clients

import java.util.Locale

internal object ClientLookupValidation {
    private val nationalId = Regex("[0-9]{7,8}")

    fun validNationalId(value: String): Boolean = nationalId.matches(value)

    fun matchesFirstName(entered: String, returned: String?): Boolean {
        val expected = returned?.trim()?.replace(Regex("\\s+"), " ")?.uppercase(Locale.ROOT)
        val actual = entered.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)
        return !expected.isNullOrEmpty() && actual.isNotEmpty() && actual == expected
    }
}
