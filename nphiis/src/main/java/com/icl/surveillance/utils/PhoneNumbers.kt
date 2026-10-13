package com.icl.surveillance.utils

/** Kenyan phone numbers in the 10-digit local form the case forms expect (07XXXXXXXX / 01XXXXXXXX). */
object PhoneNumbers {

    /**
     * - starts with 07 / 01: kept, cut to 10 digits ("070003281486" -> "0700032814")
     * - starts with 254 / +254: the country code is replaced by 0 ("+254712345678" -> "0712345678")
     * - 9 digits starting with 7 / 1: a leading 0 is added ("712345678" -> "0712345678")
     * Spaces, dashes and brackets are ignored. Anything else is returned trimmed, unchanged.
     */
    fun toLocal(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = value.filter { it.isDigit() }
        return when {
            digits.startsWith("07") || digits.startsWith("01") -> digits.take(10)
            digits.startsWith("254") && digits.length > 3 -> ("0" + digits.drop(3)).take(10)
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "0$digits"
            else -> value
        }
    }
}
