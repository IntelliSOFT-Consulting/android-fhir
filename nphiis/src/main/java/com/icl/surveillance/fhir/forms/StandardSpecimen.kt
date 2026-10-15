package com.icl.surveillance.fhir.forms

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.hl7.fhir.r4.model.Annotation
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Specimen

/**
 * A FHIR R4 Specimen built the same way for every module:
 *  - status available; type coded with HL7 v2-0487 (when the type is known) plus the app's own
 *    `specimen-details` coding (code = the form question it came from, display = the type);
 *  - subject = the Patient, collection.collectedDateTime, and the case Encounter through the
 *    standard workflow-encounter extension (R4 Specimen has no encounter element);
 *  - free-text details (transport, sent date, ...) as notes.
 */
object StandardSpecimen {

    const val V2_0487 = "http://terminology.hl7.org/CodeSystem/v2-0487"
    const val APP_SYSTEM = "specimen-details"
    const val ENCOUNTER_EXTENSION = "http://hl7.org/fhir/StructureDefinition/workflow-encounter"

    /** Date the specimen was sent to the laboratory (R4 Specimen has no element for it). */
    const val SENT_EXTENSION = "http://nphiis.health.go.ke/fhir/StructureDefinition/specimen-sent-date"

    /** Form answers (lower case) -> HL7 v2-0487 code and display. */
    private val V2_CODES = mapOf(
        "blood" to ("BLD" to "Whole blood"),
        "whole blood" to ("BLD" to "Whole blood"),
        "serum" to ("SER" to "Serum"),
        "plasma" to ("PLAS" to "Plasma"),
        "urine" to ("UR" to "Urine"),
        "saliva" to ("SAL" to "Saliva"),
        "tissue" to ("TISS" to "Tissue"),
        "stool" to ("STL" to "Stool = Fecal"),
        "throat swab" to ("THRT" to "Throat"),
        "nasopharyngeal swab" to ("NP" to "Nasopharyngeal"),
        "oral swab" to ("ORH" to "Other"),
        "csf" to ("CSF" to "Cerebral spinal fluid"),
        "skin lesion" to ("LES" to "Lesion"),
        "swab" to ("SWAB" to "Swab"),
    )

    /**
     * @param sourceLinkId the question the specimen was recorded on.
     * @param specimenType the specimen type as answered (e.g. "Blood").
     * @param collected the collection date/date-time answer ("yyyy-MM-dd" or ISO date-time).
     * @param sent the date it was sent to the laboratory, same formats.
     */
    fun build(
        sourceLinkId: String,
        specimenType: String,
        collected: String?,
        subjectRef: Reference,
        encounterRef: Reference?,
        notes: List<String> = emptyList(),
        sent: String? = null,
    ): Specimen {
        val specimen = Specimen()
        specimen.status = Specimen.SpecimenStatus.AVAILABLE
        specimen.subject = subjectRef
        specimen.type = CodeableConcept().also { concept ->
            concept.text = specimenType
            V2_CODES[specimenType.trim().lowercase()]?.let { (code, display) ->
                concept.addCoding(Coding(V2_0487, code, display))
            }
            concept.addCoding(Coding(APP_SYSTEM, sourceLinkId, specimenType))
        }
        specimen.collection = Specimen.SpecimenCollectionComponent().also { collection ->
            parseCollected(collected)?.let { collection.setCollected(DateTimeType(it)) }
        }
        encounterRef?.let { specimen.addExtension(Extension(ENCOUNTER_EXTENSION, it)) }
        sent?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { DateTimeType(it) }.getOrNull() }
            ?.let { specimen.addExtension(Extension(SENT_EXTENSION, it)) }
        specimen.note = notes.filter { it.isNotBlank() }.map { Annotation().setText(it) }
        return specimen
    }

    /**
     * A date-time answer as given; a date-only answer at the current time of day (as the app
     * always recorded specimen dates).
     */
    private fun parseCollected(value: String?): Date? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length > 10) return runCatching { DateTimeType(text).value }.getOrNull()
        return runCatching {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text) ?: return null
            val now = Calendar.getInstance()
            Calendar.getInstance().apply {
                time = day
                set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, now.get(Calendar.MINUTE))
                set(Calendar.SECOND, now.get(Calendar.SECOND))
            }.time
        }.getOrNull()
    }
}
