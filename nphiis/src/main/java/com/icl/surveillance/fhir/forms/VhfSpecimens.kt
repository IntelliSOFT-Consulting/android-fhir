package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import java.util.Date
import org.hl7.fhir.r4.model.BaseDateTimeType
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type

/**
 * VHF laboratory results come only from the case's Specimens (no lab results form): each
 * specimen carries its own result, and the case's lab result and final classification are
 * derived from all of them.
 *
 * A specimen's result is read from the extension [RESULT_EXTENSION] on the Specimen, set by the
 * laboratory, as a CodeableConcept / Coding / code / string whose code or text is one of
 * positive, negative, pending (anything else counts as pending). Specimen.status is respected:
 * entered-in-error specimens are left out, unsatisfactory ones are shown as rejected.
 *
 * Case rule: any positive specimen -> Positive ("Confirmed by lab"); every specimen negative ->
 * Negative ("Discarded"); otherwise Pending ("Pending Results").
 */
object VhfSpecimens {

    const val RESULT_EXTENSION =
        "http://nphiis.health.go.ke/fhir/StructureDefinition/specimen-lab-result"

    /** Date the laboratory issued the result (valueDateTime or valueDate), set with the result. */
    const val RESULT_DATE_EXTENSION =
        "http://nphiis.health.go.ke/fhir/StructureDefinition/specimen-lab-result-date"

    /** How specimens saved before the sent date had its own extension recorded it. */
    private const val LEGACY_SENT_NOTE = "Sent to the laboratory:"

    enum class Result(val label: String) {
        POSITIVE("Positive"),
        NEGATIVE("Negative"),
        PENDING("Pending"),
        REJECTED("Rejected"),
    }

    data class Row(
        val logicalId: String,
        val type: String,
        val specimenId: String?,
        val collected: Date?,
        val sent: Date?,
        val resultDate: Date?,
        val notes: List<String>,
        val result: Result,
        val lastUpdated: Date?,
    )

    data class Summary(
        val result: Result,
        /** Text for the case list "Lab results" column. */
        val labResults: String,
        /** Final classification (same labels as [FormFields.Vhf.finalClassification]). */
        val classification: String,
    )

    /** The specimens collected for a case (Specimen.subject = the patient, linked to the case). */
    suspend fun forCase(fhirEngine: FhirEngine, patientId: String, caseEncounterId: String?): List<Row> =
        fhirEngine.search<Specimen> {
            filter(Specimen.SUBJECT, { value = "Patient/$patientId" })
        }
            .map { it.resource }
            .filter { it.status != Specimen.SpecimenStatus.ENTEREDINERROR }
            .filter { belongsTo(it, caseEncounterId) }
            .map { toRow(it) }
            .sortedWith(compareByDescending<Row> { it.collected?.time ?: 0L }.thenBy { it.type })

    /** Case lab result and classification from its specimens; null when none were collected. */
    fun summarize(rows: List<Row>): Summary? {
        if (rows.isEmpty()) return null
        val result = when {
            rows.any { it.result == Result.POSITIVE } -> Result.POSITIVE
            rows.all { it.result == Result.NEGATIVE } -> Result.NEGATIVE
            else -> Result.PENDING
        }
        return Summary(
            result = result,
            labResults = result.label,
            classification = FormFields.Vhf.finalClassification(result.label),
        )
    }

    suspend fun summaryForCase(fhirEngine: FhirEngine, patientId: String, caseEncounterId: String?): Summary? =
        summarize(forCase(fhirEngine, patientId, caseEncounterId))

    /** Patients with at least one positive specimen. */
    suspend fun patientsWithPositiveSpecimen(fhirEngine: FhirEngine): Set<String> =
        fhirEngine.search<Specimen> {}
            .map { it.resource }
            .filter { it.status != Specimen.SpecimenStatus.ENTEREDINERROR && resultOf(it) == Result.POSITIVE }
            .mapNotNull { specimen -> specimen.subject.reference?.let { IdType(it).idPart } }
            .filter { it.isNotBlank() }
            .toSet()

    fun resultOf(specimen: Specimen): Result {
        if (specimen.status == Specimen.SpecimenStatus.UNSATISFACTORY) return Result.REJECTED
        val value = specimen.getExtensionByUrl(RESULT_EXTENSION)?.value ?: return Result.PENDING
        return when (textOf(value)?.trim()?.lowercase()) {
            "positive", "pos", "detected", "260373001" -> Result.POSITIVE
            "negative", "neg", "not detected", "260415000" -> Result.NEGATIVE
            else -> Result.PENDING
        }
    }

    private fun textOf(value: Type): String? = when (value) {
        is CodeableConcept -> value.coding.firstNotNullOfOrNull { it.code ?: it.display } ?: value.text
        is Coding -> value.code ?: value.display
        is CodeType -> value.value
        is StringType -> value.value
        else -> value.primitiveValue()
    }

    private fun dateOf(value: Type?): Date? = when (value) {
        is BaseDateTimeType -> value.value
        is StringType -> value.value?.let { runCatching { DateTimeType(it).value }.getOrNull() }
        else -> null
    }

    /** Specimens saved with the case Encounter belong to that case; older ones without it, too. */
    private fun belongsTo(specimen: Specimen, caseEncounterId: String?): Boolean {
        if (caseEncounterId.isNullOrBlank()) return true
        val encounter = specimen.getExtensionByUrl(StandardSpecimen.ENCOUNTER_EXTENSION)?.value
            as? Reference ?: return true
        val reference = encounter.reference ?: return true
        return IdType(reference).idPart == caseEncounterId
    }

    private fun toRow(specimen: Specimen) = Row(
        logicalId = specimen.idElement.idPart.orEmpty(),
        type = specimen.type.text
            ?: specimen.type.coding.firstNotNullOfOrNull { it.display }
            ?: "Specimen",
        specimenId = SpecimenId.of(specimen),
        collected = specimen.collection.takeIf { it.hasCollectedDateTimeType() }
            ?.collectedDateTimeType?.value,
        sent = dateOf(specimen.getExtensionByUrl(StandardSpecimen.SENT_EXTENSION)?.value)
            ?: specimen.note.firstNotNullOfOrNull { note ->
                note.text?.takeIf { it.startsWith(LEGACY_SENT_NOTE) }
                    ?.removePrefix(LEGACY_SENT_NOTE)?.trim()
                    ?.let { runCatching { DateTimeType(it).value }.getOrNull() }
            },
        resultDate = dateOf(specimen.getExtensionByUrl(RESULT_DATE_EXTENSION)?.value),
        notes = specimen.note.mapNotNull { note ->
            note.text?.takeIf { it.isNotBlank() && !it.startsWith(LEGACY_SENT_NOTE) }
        },
        result = resultOf(specimen),
        lastUpdated = specimen.meta.lastUpdated,
    )
}
