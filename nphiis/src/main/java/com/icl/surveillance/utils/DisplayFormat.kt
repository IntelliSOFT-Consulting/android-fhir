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

    /**
     * A multi-answer value, stored as "A, B, C", shown one answer per line. [choices] are the
     * question's option labels, so an option that itself has a comma ("bats, bushmeat, or wild
     * animals") stays on one line. Without [choices] the value is shown as [value] does.
     */
    @JvmStatic
    fun answers(raw: String?, choices: List<String>): String? {
        if (raw.isNullOrBlank() || choices.isEmpty()) return value(raw)
        val parts = raw.split(", ")
        val known = choices.toSet()
        val lines = ArrayList<String>()
        var i = 0
        while (i < parts.size) {
            // The longest run of parts that makes up one option; otherwise the part alone.
            val end = (parts.size downTo i + 1).firstOrNull { j ->
                parts.subList(i, j).joinToString(", ") in known
            } ?: (i + 1)
            lines += parts.subList(i, end).joinToString(", ").trim()
            i = end
        }
        return lines.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Non-null variant for views that take a String. */
    fun text(raw: String): String = value(raw) ?: raw
}
