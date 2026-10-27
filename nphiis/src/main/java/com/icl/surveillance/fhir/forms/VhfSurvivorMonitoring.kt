package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation

/**
 * The EPESM records of a VHF case ([FormFields.Epesm]) and what they add up to: where the
 * survivor is in monitoring, semen / breast milk clearance and an open relapse alert. Nothing
 * here is stored; it is worked out from the records on every view.
 */
object VhfSurvivorMonitoring {

    /** One saved EPESM form: its child Encounter, title, creation time and answers. */
    data class Record(
        val id: String,
        val title: String,
        val createdAt: String,
        val answers: List<Observation>,
    ) {
        fun value(code: String): String = answers.valueOf(code)

        fun date(code: String): LocalDate? = VhfContactTracker.parseDate(value(code))

        /** True when the answer to [code] is the option [optionCode] (by code or display). */
        fun answered(code: String, optionCode: String): Boolean = matches(value(code), optionCode)
    }

    /** One semen or breast milk result. */
    data class TestResult(val record: Record, val date: LocalDate?, val result: String)

    /** The results of one sample type, oldest first. */
    data class TestSeries(val results: List<TestResult>) {
        /** Negative results in a row, counted back from the latest. */
        val consecutiveNegatives: Int
            get() = results.asReversed().takeWhile { matches(it.result, "negative") }.size

        val cleared: Boolean get() = consecutiveNegatives >= FormFields.Epesm.NEGATIVES_TO_CLEAR

        /** The sample that gave the second negative in a row, once cleared. */
        val clearedOn: LocalDate?
            get() {
                if (!cleared) return null
                val run = results.takeLast(consecutiveNegatives)
                return run[FormFields.Epesm.NEGATIVES_TO_CLEAR - 1].date
            }

        val latest: TestResult? get() = results.lastOrNull()
    }

    data class Status(val records: List<Record>) {
        private fun titled(title: String) =
            records.filter { it.title == title }.sortedByDescending { it.createdAt }

        val release: Record? get() = titled(FormFields.Epesm.CONTACT_RELEASE_TITLE).firstOrNull()
        val discharge: Record? get() = titled(FormFields.Epesm.DISCHARGE_TITLE).firstOrNull()
        val clearance: Record? get() = titled(FormFields.Epesm.CLEARANCE_TITLE).firstOrNull()

        /** When the discharge assessment was done: its official date, else when it was saved. */
        val dischargeDate: LocalDate?
            get() = discharge?.let { it.date(FormFields.Vhf.DATE_CHECKED) ?: VhfContactTracker.parseDate(it.createdAt) }

        /** First day of follow-up visits: day [FormFields.Epesm.FOLLOW_UP_START_DAY] after discharge. */
        val followUpStart: LocalDate?
            get() = dischargeDate?.plusDays(FormFields.Epesm.FOLLOW_UP_START_DAY.toLong())

        /** Last day of follow-up visits, [FormFields.Epesm.FOLLOW_UP_DAYS] days from [followUpStart]. */
        val followUpEnd: LocalDate?
            get() = followUpStart?.plusDays(FormFields.Epesm.FOLLOW_UP_DAYS - 1L)

        /** Days from discharge to the follow-up [visit] (day 22 is the first visit day). */
        fun dayAfterDischarge(visit: Record): Long? {
            val discharged = dischargeDate ?: return null
            val date = visit.date(FormFields.Epesm.FOLLOW_UP_DATE) ?: return null
            return ChronoUnit.DAYS.between(discharged, date)
        }

        /** Newest first, as listed. */
        val followUps: List<Record> get() = titled(FormFields.Epesm.FOLLOW_UP_TITLE)
        val tests: List<Record> get() = titled(FormFields.Epesm.PERSISTENCE_TITLE)
        val relapses: List<Record> get() = titled(FormFields.Epesm.RELAPSE_TITLE)

        val semen: TestSeries
            get() = series(FormFields.Epesm.SAMPLE_SEMEN_CODE, FormFields.Epesm.SEMEN_DATE, FormFields.Epesm.SEMEN_RESULT)

        val breastMilk: TestSeries
            get() = series(
                FormFields.Epesm.SAMPLE_BREAST_MILK_CODE,
                FormFields.Epesm.BREAST_MILK_DATE,
                FormFields.Epesm.BREAST_MILK_RESULT,
            )

        private fun series(sample: String, dateCode: String, resultCode: String) = TestSeries(
            tests.filter { it.answered(FormFields.Epesm.SAMPLE_TYPE, sample) }
                .map { TestResult(it, it.date(dateCode), it.value(resultCode)) }
                .filter { it.result.isNotBlank() }
                .sortedWith(compareBy<TestResult> { it.date ?: LocalDate.MIN }.thenBy { it.record.createdAt })
        )

        /**
         * The latest follow up where previous symptoms returned or worsened, while no relapse
         * record was saved after it and monitoring is not cleared: relapse testing is due.
         */
        val relapseAlert: Record?
            get() {
                if (clearance != null) return null
                val visit = followUps.firstOrNull { it.answered(FormFields.Epesm.SYMPTOMS_RETURNED, "yes") }
                    ?: return null
                val lastRelapse = relapses.firstOrNull()
                return visit.takeIf { lastRelapse == null || lastRelapse.createdAt < it.createdAt }
            }
    }

    /** The EPESM records saved under the case [caseEncounterId] of [patientId]. */
    suspend fun load(fhirEngine: FhirEngine, patientId: String, caseEncounterId: String?): Status {
        val encounterId = VhfContactTracker.resolveCaseEncounterId(fhirEngine, patientId, caseEncounterId)
        val titles = FormFields.Epesm.TITLES.values.toSet()
        val records = fhirEngine.search<Encounter> {
            filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
            filter(Encounter.PART_OF, { value = "Encounter/$encounterId" })
        }.map { it.resource }
            .filter { it.reasonCodeFirstRep.codingFirstRep.code in titles }
            .map { encounter ->
                Record(
                    id = encounter.logicalId,
                    title = encounter.reasonCodeFirstRep.codingFirstRep.code,
                    createdAt = encounter.identifier
                        .firstOrNull { it.system == FhirSystems.SYSTEM_CREATION }?.value.orEmpty(),
                    answers = fhirEngine.search<Observation> {
                        filter(Observation.ENCOUNTER, { value = "Encounter/${encounter.logicalId}" })
                    }.map { it.resource }
                        .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR },
                )
            }
        return Status(records)
    }

    /**
     * A contact whose 21 days are over, alive and not released yet: the release (Part 1) is due.
     */
    fun readyForRelease(state: ContactFollowUpState, status: Status): Boolean =
        state.isContact && state.windowComplete && state.deathVisit == null && status.release == null

    /**
     * Part 1 worked out from the daily follow ups, for the officer to confirm: whether all 21
     * days have passed, any day without a follow up or without both temperatures, and whether
     * the contact stayed free of symptoms.
     */
    fun releasePrefill(state: ContactFollowUpState): Map<String, String> {
        val window = FormFields.Vhf.FOLLOW_UP_WINDOW_DAYS
        val gap = (1..window).any { it !in state.recordedDays } ||
            state.visits.any { it.temperatures.size < 2 }
        val symptomFree = state.firstSymptomaticVisit == null && state.deathVisit == null
        return buildMap {
            put(FormFields.Epesm.MONITORING_COMPLETED, yesNo(state.windowComplete))
            put(FormFields.Epesm.TEMPERATURE_GAP, yesNo(gap))
            put(FormFields.Epesm.SYMPTOM_FREE, yesNo(symptomFree))
            if (state.windowComplete && symptomFree) put(FormFields.Epesm.RELEASE_TYPE, "21-day")
        }
    }

    /**
     * A new semen / breast milk test: the sample type from the patient's sex and, for semen, the
     * negative results in a row before this sample.
     */
    fun persistencePrefill(status: Status, gender: String?): Map<String, String> = buildMap {
        when (gender?.lowercase()) {
            "male" -> {
                put(FormFields.Epesm.SAMPLE_TYPE, FormFields.Epesm.SAMPLE_SEMEN_CODE)
                put(FormFields.Epesm.SEMEN_NEGATIVE_COUNT, status.semen.consecutiveNegatives.toString())
            }
            "female" -> put(FormFields.Epesm.SAMPLE_TYPE, FormFields.Epesm.SAMPLE_BREAST_MILK_CODE)
        }
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** An answer as stored (a display such as "Breast milk") against an option code ("breast-milk"). */
    fun matches(answer: String, optionCode: String): Boolean {
        fun norm(text: String) = text.trim().lowercase().replace('-', ' ')
        return answer.isNotBlank() && norm(answer) == norm(optionCode)
    }
}
