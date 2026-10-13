package com.icl.surveillance.utils

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Values shown when viewing a record. A date-and-time answer is stored in FHIR form
 * ("2026-09-20T14:15:00+03:00" or "...Z"); it is shown as "20 Sep 2026, 2:15 PM" in the phone's
 * time zone. Every other value (including plain dates) is shown unchanged.
 */
object DisplayFormat {
    private val DATE_TIME = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})?$""")
    private val ZONED = Regex("""(Z|[+-]\d{2}:\d{2})$""")
    private val OUT = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

    @JvmStatic
    fun value(raw: String?): String? {
        val text = raw?.trim() ?: return raw
        if (!DATE_TIME.matches(text)) return raw
        return runCatching {
            val local = if (ZONED.containsMatchIn(text)) {
                OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            } else {
                LocalDateTime.parse(text)
            }
            local.format(OUT)
        }.getOrDefault(raw)
    }

    /** Non-null variant for views that take a String. */
    fun text(raw: String): String = value(raw) ?: raw
}
