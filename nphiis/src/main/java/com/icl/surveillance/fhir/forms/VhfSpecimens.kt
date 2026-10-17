package com.icl.surveillance.fhir.forms

import android.content.Context
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.filter.ReferenceParamFilterCriterion
import com.google.android.fhir.search.filter.TokenParamFilterCriterion
import com.google.android.fhir.search.search
import com.icl.surveillance.models.QuestionnaireAnswer
import java.util.Date
import java.util.UUID
import org.hl7.fhir.r4.model.BaseDateTimeType
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DiagnosticReport
import org.hl7.fhir.r4.model.DomainResource
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
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

    /** The sample recorded in the case form's Laboratory section. */
    data class CaseFormSample(
        val type: String,
        val collected: String?,
        val sent: String?,
        val notes: List<String>,
    )

    /** The case form's sample, or null when "Samples collected" is not Yes. */
    fun sampleFromCaseForm(answers: List<QuestionnaireAnswer>): CaseFormSample? {
        if (!answers.answerOf(FormFields.Vhf.SAMPLES_COLLECTED).equals("yes", ignoreCase = true)) return null
        val answeredType = answers.answerOf(FormFields.Vhf.SAMPLE_TYPE)
        val type = when {
            answeredType == null -> "Unspecified"
            answeredType.startsWith("Other", ignoreCase = true) ->
                answers.answerOf(FormFields.Vhf.SAMPLE_TYPE_OTHER) ?: answeredType
            else -> answeredType
        }
        val transport = answers.answerOf(FormFields.Vhf.SAMPLE_TRANSPORT)?.let { mode ->
            when {
                mode.startsWith("Other", ignoreCase = true) ->
                    answers.answerOf(FormFields.Vhf.SAMPLE_TRANSPORT_OTHER) ?: mode
                mode.equals("Road", ignoreCase = true) ->
                    answers.answerOf(FormFields.Vhf.SAMPLE_TRANSPORT_ROAD)?.let { "Road ($it)" } ?: mode
                else -> mode
            }
        }
        return CaseFormSample(
            type = type,
            collected = answers.answerOf(FormFields.Vhf.COLLECTION_DATE),
            sent = answers.answerOf(FormFields.Vhf.SENT_DATE),
            notes = listOfNotNull(
                answers.answerOf(FormFields.Vhf.SAMPLE_TISSUE_TYPE)
                    ?.takeIf { type == "Tissue" }?.let { "Tissue type: $it" },
                transport?.let { "Transport: $it" },
            ),
        )
    }

    /**
     * After the VHF case form is edited, for the sample now in its Laboratory section:
     *  - a specimen of that type was already recorded for the case -> it is updated (dates,
     *    transport...), keeping its specimen ID;
     *  - no specimen of that type yet (none at all, or the type was changed, e.g. Urine -> Blood)
     *    -> a new Specimen is added. Earlier specimens stay: each has its own lab result.
     * Nothing is removed when samples are changed to No. [template] supplies facility tags.
     */
    suspend fun syncFromCaseForm(
        context: Context,
        fhirEngine: FhirEngine,
        patientId: String,
        caseEncounterId: String?,
        answers: List<QuestionnaireAnswer>,
        template: DomainResource?,
    ) {
        val sample = sampleFromCaseForm(answers)
        Timber.tag("SPECIMEN").d("Edit of Patient/%s: case-form sample = %s", patientId, sample)
        if (sample == null) return
        val subject = Reference("Patient/$patientId")
        val encounter = caseEncounterId?.let { Reference("Encounter/$it") }
        val built = StandardSpecimen.build(
            sourceLinkId = FormFields.Vhf.SAMPLE_TYPE,
            specimenType = sample.type,
            collected = sample.collected,
            subjectRef = subject,
            encounterRef = encounter,
            notes = sample.notes,
            sent = sample.sent,
        )
        val caseSpecimens = fhirEngine.search<Specimen> {
            filter(Specimen.SUBJECT, { value = "Patient/$patientId" })
        }.map { it.resource }
            .filter { it.status != Specimen.SpecimenStatus.ENTEREDINERROR }
            .filter { belongsTo(it, caseEncounterId) }
        // The specimen of this sample type recorded from the case form, if any.
        val existing = caseSpecimens.firstOrNull { specimen ->
            specimen.type.coding.any {
                it.system == StandardSpecimen.APP_SYSTEM && it.code == FormFields.Vhf.SAMPLE_TYPE
            } && specimen.type.text.orEmpty().trim().equals(sample.type.trim(), ignoreCase = true)
        }
        Timber.tag("SPECIMEN").d(
            "Case specimens: %s; %s -> %s",
            caseSpecimens.map { it.type.text }, sample.type,
            if (existing == null) "add new" else "update ${existing.idElement.idPart}",
        )

        if (existing == null) {
            built.id = UUID.randomUUID().toString()
            SpecimenId.ensure(built, context)
            template?.let { from ->
                built.meta.tag = from.meta.tag.mapTo(ArrayList()) { it.copy() }
                from.extension
                    .filter { it.url != StandardSpecimen.ENCOUNTER_EXTENSION }
                    .forEach { built.addExtension(it.copy()) }
            }
            fhirEngine.create(built)
            Timber.tag("SPECIMEN").d("Created %s specimen %s (ID %s) on edit", sample.type, built.id, SpecimenId.of(built))
            return
        }

        val updated = existing.copy().apply {
            type = built.type
            collection = built.collection
            note = built.note
            extension.removeAll { it.url == StandardSpecimen.SENT_EXTENSION }
            built.getExtensionByUrl(StandardSpecimen.SENT_EXTENSION)?.let { addExtension(it.copy()) }
        }
        SpecimenId.ensure(updated, context)
        if (!updated.equalsDeep(existing)) {
            LocalWins.save(fhirEngine, updated)
            Timber.tag("SPECIMEN").d("Updated specimen %s on edit", updated.idElement.idPart)
        }
    }

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
        val observations = reports.flatMap { it.result }
            .mapNotNull { ref -> ref.reference?.let { IdType(it).idPart } }
            .distinct()
            .mapNotNull { id -> runCatching { fhirEngine.get<Observation>(id) }.getOrNull() }
            .associateBy { it.idElement.idPart }
        return testsFrom(reports, observations)
    }

    /** Test results of a specimen from its reports and their result Observations (by id). */
    private fun testsFrom(reports: List<DiagnosticReport>, observations: Map<String, Observation>): List<TestResult> =
        reports
            .filter {
                it.status != DiagnosticReport.DiagnosticReportStatus.ENTEREDINERROR &&
                    it.status != DiagnosticReport.DiagnosticReportStatus.CANCELLED
            }
            .flatMap { report ->
                val reportFinal = report.status in FINAL_REPORT
                val reportDate = report.issued ?: report.takeIf { it.hasEffectiveDateTimeType() }?.effectiveDateTimeType?.value
                report.result.mapNotNull { ref ->
                    val id = ref.reference?.let { IdType(it).idPart } ?: return@mapNotNull null
                    val observation = observations[id] ?: return@mapNotNull null
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

    /**
     * Specimens of many patients loaded at once for the case list: three queries per
     * [BATCH_CHUNK] patients (specimens, their reports, the reports' results) instead of
     * several per case.
     */
    class Batch internal constructor(private val byPatient: Map<String, List<Pair<Specimen, Row>>>) {
        /** As [summaryForCase], from the loaded specimens. */
        fun summaryForCase(patientId: String, caseEncounterId: String?): Summary? =
            VhfSpecimens.summarize(
                byPatient[patientId].orEmpty()
                    .filter { (specimen, _) ->
                        specimen.status != Specimen.SpecimenStatus.ENTEREDINERROR &&
                            VhfSpecimens.belongsTo(specimen, caseEncounterId)
                    }
                    .map { it.second }
            )
    }

    suspend fun loadBatch(fhirEngine: FhirEngine, patientIds: Collection<String>): Batch {
        val specimens = patientIds.distinct().chunked(BATCH_CHUNK).flatMap { chunk ->
            fhirEngine.search<Specimen> {
                filter(Specimen.SUBJECT, *references(chunk.map { "Patient/$it" }))
            }.map { it.resource }
        }
        if (specimens.isEmpty()) return Batch(emptyMap())

        val reports = specimens.map { "Specimen/${it.idElement.idPart}" }.chunked(BATCH_CHUNK).flatMap { chunk ->
            fhirEngine.search<DiagnosticReport> {
                filter(DiagnosticReport.SPECIMEN, *references(chunk))
            }.map { it.resource }
        }.distinctBy { it.idElement.idPart }
        val reportsBySpecimen = HashMap<String, MutableList<DiagnosticReport>>()
        reports.forEach { report ->
            report.specimen.mapNotNull { ref -> ref.reference?.let { IdType(it).idPart } }.distinct()
                .forEach { id -> reportsBySpecimen.getOrPut(id) { ArrayList() } += report }
        }

        val resultIds = reports.flatMap { it.result }
            .mapNotNull { ref -> ref.reference?.let { IdType(it).idPart } }
            .distinct()
        val observations = resultIds.chunked(BATCH_CHUNK).flatMap { chunk ->
            fhirEngine.search<Observation> {
                filter(Resource.RES_ID, *chunk.map<String, TokenParamFilterCriterion.() -> Unit> { id ->
                    { value = of(id) }
                }.toTypedArray())
            }.map { it.resource }
        }.associateBy { it.idElement.idPart }

        val byPatient = specimens.mapNotNull { specimen ->
            val patientId = specimen.subject.reference?.let { IdType(it).idPart } ?: return@mapNotNull null
            val tests = testsFrom(reportsBySpecimen[specimen.idElement.idPart].orEmpty(), observations)
            patientId to (specimen to rowOf(specimen, tests))
        }.groupBy({ it.first }, { it.second })
        return Batch(byPatient)
    }

    private fun references(values: List<String>) =
        values.map<String, ReferenceParamFilterCriterion.() -> Unit> { ref -> { value = ref } }
            .toTypedArray()

    private const val BATCH_CHUNK = 100

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
    internal fun belongsTo(specimen: Specimen, caseEncounterId: String?): Boolean {
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

    private suspend fun toRow(fhirEngine: FhirEngine, specimen: Specimen): Row =
        rowOf(specimen, testsFor(fhirEngine, specimen.idElement.idPart.orEmpty()))

    private fun rowOf(specimen: Specimen, tests: List<TestResult>): Row {
        val logicalId = specimen.idElement.idPart.orEmpty()
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
