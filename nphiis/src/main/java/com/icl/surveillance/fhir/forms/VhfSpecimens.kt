package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import java.util.Date
import org.hl7.fhir.r4.model.BaseDateTimeType
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DiagnosticReport
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import timber.log.Timber

/**
 * VHF laboratory results come only from the case's Specimens (no lab results form).
 *
 * For each Specimen: the DiagnosticReports that reference it (DiagnosticReport.specimen), then
 * each report's result Observations; the result shown is the Observation's value[x] as text.
 * Entered-in-error / cancelled reports and observations are ignored. A specimen is positive or
 * negative only from final (final, amended, corrected, appended) results; preliminary ones are
 * shown but leave it pending. Specimen.status: entered-in-error specimens are left out,
 * unsatisfactory ones are shown as rejected.
 *
 * Case rule: any positive specimen -> Positive ("Confirmed by lab"); every specimen negative ->
 * Negative ("Discarded"); otherwise Pending ("Pending Results").
 */
object VhfSpecimens {

    /** How specimens saved before the sent date had its own extension recorded it. */
    private const val LEGACY_SENT_NOTE = "Sent to the laboratory:"

    enum class Result(val label: String) {
        POSITIVE("Positive"),
        NEGATIVE("Negative"),
        PENDING("Pending"),
        REJECTED("Rejected"),
    }

    /** One test result: an Observation in a DiagnosticReport for the specimen. */
    data class TestResult(
        val name: String,
        /** Observation.value[x] as text. */
        val value: String,
        /** What [value] means, when it reads as positive or negative. */
        val interpretation: Result?,
        val isFinal: Boolean,
        val date: Date?,
    )

    data class Row(
        val logicalId: String,
        val type: String,
        val specimenId: String?,
        val collected: Date?,
        val sent: Date?,
        val resultDate: Date?,
        val notes: List<String>,
        val tests: List<TestResult>,
        val result: Result,
        /** Positive/negative from preliminary results only, while [result] is pending. */
        val preliminary: Result?,
        val lastUpdated: Date?,
    ) {
        val statusLabel: String
            get() = if (result == Result.PENDING && preliminary != null) "Preliminary: ${preliminary.label}"
            else result.label
    }

    data class Summary(
        val result: Result,
        /** Text for the case list "Lab results" column. */
        val labResults: String,
        /** Final classification (same labels as [FormFields.Vhf.finalClassification]). */
        val classification: String,
    )

    /** The specimens collected for a case (Specimen.subject = the patient, linked to the case). */
    suspend fun forCase(fhirEngine: FhirEngine, patientId: String, caseEncounterId: String?): List<Row> {
        val all = fhirEngine.search<Specimen> {
            filter(Specimen.SUBJECT, { value = "Patient/$patientId" })
        }.map { it.resource }
        val forCase = all
            .filter { it.status != Specimen.SpecimenStatus.ENTEREDINERROR }
            .filter { belongsTo(it, caseEncounterId) }
        Timber.tag("SPECIMEN").d(
            "Patient/%s case %s: %d specimens, %d for this case %s",
            patientId, caseEncounterId, all.size, forCase.size,
            all.map { s -> "${s.idElement.idPart}->${(s.getExtensionByUrl(StandardSpecimen.ENCOUNTER_EXTENSION)?.value as? Reference)?.reference}" },
        )
        return forCase.map { toRow(fhirEngine, it) }
            .sortedWith(compareByDescending<Row> { it.collected?.time ?: 0L }.thenBy { it.type })
    }

    /** Case lab result and classification from its specimens; null when none were collected. */
    fun summarize(rows: List<Row>): Summary? {
        if (rows.isEmpty()) return null
        val result = when {
            rows.any { it.result == Result.POSITIVE } -> Result.POSITIVE
            rows.all { it.result == Result.NEGATIVE } -> Result.NEGATIVE
            else -> Result.PENDING
        }
        val labResults = when {
            result != Result.PENDING -> result.label
            rows.any { it.preliminary == Result.POSITIVE } -> "Preliminary: Positive"
            rows.all { it.result == Result.NEGATIVE || it.preliminary == Result.NEGATIVE } -> "Preliminary: Negative"
            else -> result.label
        }
        return Summary(
            result = result,
            labResults = labResults,
            classification = FormFields.Vhf.finalClassification(result.label),
        )
    }

    suspend fun summaryForCase(fhirEngine: FhirEngine, patientId: String, caseEncounterId: String?): Summary? =
        summarize(forCase(fhirEngine, patientId, caseEncounterId))

    /** Patients with at least one specimen with a final positive result. */
    suspend fun patientsWithPositiveSpecimen(fhirEngine: FhirEngine): Set<String> =
        fhirEngine.search<Specimen> {}
            .map { it.resource }
            .filter { it.status != Specimen.SpecimenStatus.ENTEREDINERROR }
            .filter { toRow(fhirEngine, it).result == Result.POSITIVE }
            .mapNotNull { specimen -> specimen.subject.reference?.let { IdType(it).idPart } }
            .filter { it.isNotBlank() }
            .toSet()

    /** Specimen result from its test results (see the class comment). */
    fun resultOf(specimen: Specimen, tests: List<TestResult>): Result {
        if (specimen.status == Specimen.SpecimenStatus.UNSATISFACTORY) return Result.REJECTED
        return combine(tests.filter { it.isFinal }) ?: Result.PENDING
    }

    /** Any positive -> positive; all negative -> negative; null when nothing decides it. */
    private fun combine(tests: List<TestResult>): Result? = when {
        tests.any { it.interpretation == Result.POSITIVE } -> Result.POSITIVE
        tests.isNotEmpty() && tests.all { it.interpretation == Result.NEGATIVE } -> Result.NEGATIVE
        else -> null
    }

    /** Reads a result text as positive / negative; null when it is neither (e.g. a number). */
    fun interpret(text: String?): Result? {
        val value = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val negative = listOf("not detected", "undetected", "negative", "non-reactive", "nonreactive", "non reactive")
        val positive = listOf("positive", "detected", "reactive")
        return when {
            negative.any { value.contains(it) } || value in setOf("neg", "nd", "260415000") -> Result.NEGATIVE
            positive.any { value.contains(it) } || value in setOf("pos", "det", "260373001", "10828004") -> Result.POSITIVE
            else -> null
        }
    }

    /** Observation.value[x] as display text. */
    fun valueText(value: Type?): String? = when (value) {
        null -> null
        is CodeableConcept -> value.text ?: value.coding.firstNotNullOfOrNull { it.display ?: it.code }
        is Quantity -> listOfNotNull(value.value?.stripTrailingZeros()?.toPlainString(), value.unit ?: value.code)
            .joinToString(" ")
        is StringType -> value.value
        is BooleanType -> if (value.booleanValue()) "Yes" else "No"
        is IntegerType -> value.value?.toString()
        is BaseDateTimeType -> value.valueAsString
        else -> value.primitiveValue()
    }?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun testsFor(fhirEngine: FhirEngine, specimenId: String): List<TestResult> {
        if (specimenId.isBlank()) return emptyList()
        val reports = fhirEngine.search<DiagnosticReport> {
            filter(DiagnosticReport.SPECIMEN, { value = "Specimen/$specimenId" })
        }.map { it.resource }
            .filter {
                it.status != DiagnosticReport.DiagnosticReportStatus.ENTEREDINERROR &&
                    it.status != DiagnosticReport.DiagnosticReportStatus.CANCELLED
            }
        return reports.flatMap { report ->
            val reportFinal = report.status in FINAL_REPORT
            val reportDate = report.issued ?: report.takeIf { it.hasEffectiveDateTimeType() }?.effectiveDateTimeType?.value
            report.result.mapNotNull { ref ->
                val id = ref.reference?.let { IdType(it).idPart } ?: return@mapNotNull null
                val observation = runCatching { fhirEngine.get<Observation>(id) }.getOrNull()
                    ?: return@mapNotNull null
                if (observation.status == Observation.ObservationStatus.ENTEREDINERROR ||
                    observation.status == Observation.ObservationStatus.CANCELLED
                ) return@mapNotNull null
                val value = valueText(observation.value) ?: return@mapNotNull null
                TestResult(
                    name = observation.code.text
                        ?: observation.code.coding.firstNotNullOfOrNull { it.display ?: it.code }
                        ?: report.code.text
                        ?: "Result",
                    value = value,
                    interpretation = interpret(value) ?: interpretationOf(observation),
                    isFinal = reportFinal && observation.status in FINAL_OBSERVATION,
                    date = observation.issued
                        ?: observation.takeIf { it.hasEffectiveDateTimeType() }?.effectiveDateTimeType?.value
                        ?: reportDate,
                )
            }
        }
    }

    /** Observation.interpretation (HL7 v3 ObservationInterpretation: POS/DET, NEG/ND). */
    private fun interpretationOf(observation: Observation): Result? =
        observation.interpretation.flatMap { it.coding }.firstNotNullOfOrNull { coding ->
            when (coding.code?.uppercase()) {
                "POS", "DET" -> Result.POSITIVE
                "NEG", "ND" -> Result.NEGATIVE
                else -> null
            }
        }

    private val FINAL_REPORT = setOf(
        DiagnosticReport.DiagnosticReportStatus.FINAL,
        DiagnosticReport.DiagnosticReportStatus.AMENDED,
        DiagnosticReport.DiagnosticReportStatus.CORRECTED,
        DiagnosticReport.DiagnosticReportStatus.APPENDED,
    )

    private val FINAL_OBSERVATION = setOf(
        Observation.ObservationStatus.FINAL,
        Observation.ObservationStatus.AMENDED,
        Observation.ObservationStatus.CORRECTED,
    )

    /** Specimens saved with the case Encounter belong to that case; older ones without it, too. */
    private fun belongsTo(specimen: Specimen, caseEncounterId: String?): Boolean {
        if (caseEncounterId.isNullOrBlank()) return true
        val encounter = specimen.getExtensionByUrl(StandardSpecimen.ENCOUNTER_EXTENSION)?.value
            as? Reference ?: return true
        val reference = encounter.reference ?: return true
        return IdType(reference).idPart == caseEncounterId
    }

    private fun dateOf(value: Type?): Date? = when (value) {
        is BaseDateTimeType -> value.value
        is StringType -> value.value?.let { runCatching { DateTimeType(it).value }.getOrNull() }
        else -> null
    }

    private suspend fun toRow(fhirEngine: FhirEngine, specimen: Specimen): Row {
        val logicalId = specimen.idElement.idPart.orEmpty()
        val tests = testsFor(fhirEngine, logicalId)
        val result = resultOf(specimen, tests)
        val decided = tests.filter { it.isFinal }.takeIf { result != Result.PENDING && it.isNotEmpty() } ?: tests
        return Row(
            logicalId = logicalId,
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
            resultDate = decided.mapNotNull { it.date }.maxOrNull(),
            notes = specimen.note.mapNotNull { note ->
                note.text?.takeIf { it.isNotBlank() && !it.startsWith(LEGACY_SENT_NOTE) }
            },
            tests = tests,
            result = result,
            preliminary = if (result == Result.PENDING) combine(tests.filter { !it.isFinal }) else null,
            lastUpdated = specimen.meta.lastUpdated,
        )
    }
}
