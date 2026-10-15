package com.icl.surveillance.fhir.forms

import android.content.Context
import java.util.Locale
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Specimen

/**
 * Specimen ID: the ID on the tube label, written to `Specimen.identifier` as
 * `{ "system": "http://nphiis.health.go.ke/fhir/identifiers/specimen-id", "value": "<ID>" }`.
 *
 * The ID is 8 upper-case alphanumeric characters: the current time in milliseconds, in base 36,
 * left-padded with zeros (8 base-36 digits last until the year 2059). IDs never repeat on a
 * device: each new ID is at least one millisecond after the last one issued, which is kept
 * across restarts, so several specimens saved in the same millisecond (or after the clock was
 * set back) still get distinct IDs.
 */
object SpecimenId {

    const val SYSTEM = "http://nphiis.health.go.ke/fhir/identifiers/specimen-id"
    const val LENGTH = 8

    private const val PREFS = "specimen_id"
    private const val KEY_LAST = "last_issued_ms"
    private const val MAX = 2_821_109_907_455L // 36^8 - 1, "ZZZZZZZZ"

    private val lock = Any()

    /** A new, unique specimen ID. */
    fun next(context: Context, now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        val issued = maxOf(now, last + 1)
        prefs.edit().putLong(KEY_LAST, issued).commit()
        format(issued)
    }

    /** [millis] as an 8-character upper-case base-36 ID. */
    fun format(millis: Long): String {
        require(millis in 0..MAX) { "Timestamp $millis does not fit in $LENGTH base-36 characters" }
        return java.lang.Long.toString(millis, 36).uppercase(Locale.ROOT).padStart(LENGTH, '0')
    }

    fun of(specimen: Specimen): String? =
        specimen.identifier.firstOrNull { it.system == SYSTEM }?.value?.takeIf { it.isNotBlank() }

    /** Adds a new specimen ID unless the specimen already has one. Returns the specimen's ID. */
    fun ensure(specimen: Specimen, context: Context): String =
        of(specimen) ?: next(context).also { id ->
            specimen.addIdentifier(Identifier().setSystem(SYSTEM).setValue(id))
        }
}
