package com.icl.surveillance.debug

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.clients.ScreenedCaseDetailActivity
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseResponse
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.ContactGroups
import com.icl.surveillance.fhir.forms.FhirSystems
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.fhir.forms.VhfSourceCases
import com.icl.surveillance.fhir.forms.VhfSpecimens
import com.icl.surveillance.fhir.forms.VhfSurvivorMonitoring
import com.icl.surveillance.fhir.forms.latestValueOf
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.AddClientViewModel
import com.icl.surveillance.viewmodels.AddClientViewModel.PersistResult
import com.icl.surveillance.viewmodels.EditSupervisorChecklistViewModel
import com.icl.surveillance.viewmodels.ScreenerViewModel
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Settings > Process flagged VHF cases (administrators): every flagged person through the VHF
 * workflow, through the same save code the screens use, in two phases.
 *
 * Phase 1, "Investigate flagged": for each flagged person, the CIF from their screening answers
 * as a suspected case with a blood specimen; two contacts registered; days 1-3 of follow up for
 * both (the first symptomatic on day 3); the first converted to a suspected case with its own
 * specimen. The cases wait for the lab, whose results are posted outside the app.
 *
 * Phase 2, "Advance lab-confirmed": each case from phase 1 whose specimen has a final positive
 * result is updated to Confirmed, recorded Alive and taken through survivor monitoring to
 * clearance (discharge assessment, three visits, two negative samples, clearance). Cases still
 * pending or negative are left as they are and listed.
 *
 * Every record is tagged [TAG_SYSTEM] with the run's id. The cases phase 1 made are kept in an
 * app-private file ([REGISTRY_FILE], never synced) for phase 2 to find.
 */
object VhfFlaggedWorkflow {
    const val TAG = "VHF_FLAGGED_WORKFLOW"
    const val TAG_SYSTEM = "http://nphiis.health.go.ke/fhir/CodeSystem/flagged-workflow"
    private const val REGISTRY_FILE = "vhf-flagged-workflow.json"

    /** Contacts registered for each investigated person; the first is converted on day 3. */
    const val CONTACTS_PER_CASE = 2

    enum class Phase(val title: String) {
        INVESTIGATE("Investigate flagged"),
        ADVANCE("Advance lab-confirmed"),
    }

    /** One person's result: what was done, and any problem that stopped or failed a check. */
    data class PersonResult(val name: String, val outcome: String, val problems: List<String> = emptyList()) {
        val passed: Boolean get() = problems.isEmpty()
    }

    data class State(
        val running: Boolean = false,
        val phase: Phase? = null,
        val total: Int = 0,
        val current: String? = null,
        val results: List<PersonResult> = emptyList(),
        val finished: Boolean = false,
        val runId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    /** A case phase 1 made: the investigated person or a converted contact. */
    data class Entry(val patientId: String, val encounterId: String, val name: String, val runId: String)

    /** What phase 2 does with a case, from its records. */
    sealed class Decision {
        object Advance : Decision()
        data class Skip(val reason: String) : Decision()
    }

    /**
     * Phase 2's rule: a case moves on only with a final positive lab result, while it is not
     * already cleared and did not die. [labResult] is [VhfSpecimens.Summary.result]'s label.
     */
    fun decide(labResult: String?, cleared: Boolean, died: Boolean): Decision = when {
        cleared -> Decision.Skip("already cleared")
        died -> Decision.Skip("outcome is Dead: no survivor monitoring")
        labResult == VhfSpecimens.Result.POSITIVE.label -> Decision.Advance
        labResult == VhfSpecimens.Result.NEGATIVE.label -> Decision.Skip("lab result Negative")
        labResult == VhfSpecimens.Result.REJECTED.label -> Decision.Skip("specimen rejected by the lab")
        else -> Decision.Skip("waiting for a final lab result")
    }

    /**
     * Survivor monitoring dates, counted back from [today] so every visit is in the past (the
     * forms take no future dates): discharge [DISCHARGED_DAYS_AGO] days ago, visits on days 22,
     * 29 and 35 after discharge, the two negative samples on days 22 and 35, cleared today.
     */
    data class SurvivorDates(val today: LocalDate) {
        val discharged: LocalDate = today.minusDays(DISCHARGED_DAYS_AGO)
        val admitted: LocalDate = discharged.minusDays(14)
        val dischargePcr1: LocalDate = discharged.minusDays(3)
        val dischargePcr2: LocalDate = discharged.minusDays(1)
        val visits: List<LocalDate> = VISIT_DAYS.map { discharged.plusDays(it) }
        val samples: List<LocalDate> = listOf(visits.first(), visits.last())

        companion object {
            const val DISCHARGED_DAYS_AGO = 36L
            val VISIT_DAYS = listOf(FormFields.Epesm.FOLLOW_UP_START_DAY.toLong(), 29L, 35L)
        }
    }

    /** Starts [phase] in the background; progress is in [state]. */
    fun start(context: Context, phase: Phase, syncEachPerson: Boolean): Boolean {
        if (isRunning) return false
        val app = context.applicationContext as Application
        val runId = "flagged-" + UUID.randomUUID().toString().take(8)
        _state.value = State(running = true, phase = phase, runId = runId)
        job = scope.launch {
            try {
                val run = Run(app, runId, syncEachPerson)
                when (phase) {
                    Phase.INVESTIGATE -> run.investigateAll()
                    Phase.ADVANCE -> run.advanceAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Run %s stopped", runId)
                addResult(PersonResult("Run", "stopped", listOf(e.message ?: e.javaClass.simpleName)))
            } finally {
                _state.update { it.copy(running = false, finished = true, current = null) }
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Cases phase 1 made on this device and phase 2 has not finished. */
    fun pendingCount(context: Context): Int = readRegistry(context).size

    private fun addResult(result: PersonResult) {
        val log = Timber.tag(TAG)
        log.i("%s: %s%s", result.name, result.outcome, if (result.passed) "" else " · " + result.problems.joinToString("; "))
        _state.update { it.copy(results = it.results + result) }
    }

    // ---------------------------------------------------------------- registry

    private fun registry(context: Context) = File(context.filesDir, REGISTRY_FILE)

    @Synchronized
    fun readRegistry(context: Context): List<Entry> = runCatching {
        val array = JSONArray(registry(context).readText())
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                Entry(it.getString("patientId"), it.getString("encounterId"), it.optString("name"), it.optString("runId"))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun writeRegistry(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.distinctBy { it.patientId }.forEach {
            array.put(
                JSONObject().put("patientId", it.patientId).put("encounterId", it.encounterId)
                    .put("name", it.name).put("runId", it.runId)
            )
        }
        registry(context).writeText(array.toString())
    }

    // ---------------------------------------------------------------- run

    private class Run(val app: Application, val runId: String, private val sync: Boolean) {
        val engine: FhirEngine = FhirApplication.fhirEngine(app)
        private val tags = listOf(Coding(TAG_SYSTEM, runId, "VHF flagged workflow"))
        private val parser = FhirContext.forR4Cached().newJsonParser()
        private val today: LocalDate = LocalDate.now()
        /** Day 0 of the contacts' follow up: 3 days ago, so days 1-3 are due by today. */
        private val dayZero: LocalDate = today.minusDays(3)

        // ------------------------------------------------------------ phase 1

        suspend fun investigateAll() {
            val flagged = VhfScreened.list(engine)
            _state.update { it.copy(total = flagged.size) }
            Timber.tag(TAG).i("Run %s: %d flagged people to investigate", runId, flagged.size)
            flagged.forEach { row ->
                val name = row.name.ifBlank { row.patientId }
                _state.update { it.copy(current = name) }
                addResult(runPerson(name) { investigate(row.patientId, name) })
            }
            if (!sync && flagged.isNotEmpty()) {
                status("final sync …")
                SyntheticDataGenerator.syncNow(app)
            }
        }

        /** One flagged person through phase 1; returns what was done. */
        private suspend fun investigate(flaggedId: String, name: String): String {
            val problems = ArrayList<String>()
            // 1. The CIF from the screening answers, as a suspected case with a blood specimen.
            status("$name · case investigation form")
            val details = checkNotNull(VhfScreened.details(app, engine, flaggedId)) { "The flagged record was not found" }
            val prefill = details.prefill.filterKeys { key ->
                ScreenedCaseDetailActivity.REPORTING_SITE_IDS.none { key.startsWith(it) }
            }
            val (casePatient, caseEncounter) = saveCase(
                prefill + mapOf(
                    FormFields.Vhf.CASE_TYPE to FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE,
                    FormFields.Vhf.INVESTIGATION_DATE to today.toString(),
                    FormFields.Vhf.SAMPLES_COLLECTED to "yes",
                    FormFields.Vhf.SAMPLE_TYPE to "blood",
                ),
                screenedId = flaggedId,
            )
            remember(Entry(casePatient, caseEncounter, name, runId))
            problems += check("Same person continued", casePatient == flaggedId)
            problems += check("No longer flagged", VhfScreened.list(engine).none { it.patientId == flaggedId })
            problems += check("Type of case Suspected", caseType(caseEncounter) == "Suspected")
            problems += check("Specimen sent", VhfSpecimens.forCase(engine, casePatient, caseEncounter).isNotEmpty())

            // 2. Contacts are linked by the case's EPID number, which the server assigns.
            if (sync) awaitServerEpid(name, casePatient)
            val epid = patient(casePatient)?.let { VhfSourceCases.epidOf(it) ?: VhfSourceCases.epidOrPartialOf(it) }
                ?: answers(caseEncounter).latestValueOf(FhirSystems.EPID)
            check(epid.isNotBlank()) { "The case has no EPID number or prefix to link contacts to" }

            // 3. Contacts, days 1-3 of follow up; the first is symptomatic on day 3.
            val contacts = (1..CONTACTS_PER_CASE).map { index ->
                status("$name · contact $index of $CONTACTS_PER_CASE")
                saveCase(
                    mapOf(
                        FormFields.Vhf.CASE_TYPE to "contact",
                        FormFields.Vhf.EXPOSURE_TYPE to "human",
                        FormFields.Vhf.CONTACT_DATE to dayZero.toString(),
                        FormFields.Vhf.SAMPLES_COLLECTED to "no",
                    ),
                    codings = mapOf(FormFields.Vhf.SOURCE_CASE_EPID to Coding().setCode(epid).setDisplay(epid)),
                    contactSourceId = casePatient,
                )
            }
            problems += check("$CONTACTS_PER_CASE contacts under the case",
                ContactGroups.contactIds(engine, casePatient).containsAll(contacts.map { it.first }))
            contacts.forEachIndexed { index, (patientId, encounterId) ->
                status("$name · follow ups of contact ${index + 1}")
                (1..3).forEach { day -> followUp(patientId, encounterId, day, symptomatic = index == 0 && day == 3) }
            }

            // 4. The symptomatic contact converted to a suspected case with its own specimen.
            val (convertedPatient, convertedEncounter) = contacts.first()
            status("$name · converting contact 1 to a case")
            convert(convertedPatient, convertedEncounter)
            val convertedName = patient(convertedPatient)?.nameFirstRep?.nameAsSingleString?.ifBlank { null }
                ?: "contact of $name"
            remember(Entry(convertedPatient, convertedEncounter, convertedName, runId))
            problems += check("Contact converted to Suspected", caseType(convertedEncounter) == "Suspected")
            problems += check("Converted contact's specimen sent",
                VhfSpecimens.forCase(engine, convertedPatient, convertedEncounter).isNotEmpty())
            problems += check("Other contact still under follow up",
                VhfContactTracker.load(engine, contacts[1].first, contacts[1].second)?.isContact == true)

            if (sync) {
                status("$name · syncing …")
                if (!SyntheticDataGenerator.syncNow(app)) problems += "Sync failed (offline or server error)"
            }
            check(problems.isEmpty()) { problems.joinToString("; ") }
            return "Suspected · specimen sent · $CONTACTS_PER_CASE contacts, 1 converted (specimen sent)"
        }

        private suspend fun followUp(patientId: String, encounterId: String, day: Int, symptomatic: Boolean) {
            saveChild(
                FormFields.Vhf.CONTACT_FOLLOW_UP_FORM, FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE, patientId, encounterId,
                buildMap {
                    put(FormFields.Vhf.FOLLOW_UP_DAY, day.toString())
                    put(FormFields.Vhf.FOLLOW_UP_DATE, dayZero.plusDays(day.toLong()).toString())
                    put(FormFields.Vhf.FOLLOW_UP_CONTACT_STATUS, "alive")
                    put(FormFields.Vhf.FOLLOW_UP_SYMPTOMATIC, if (symptomatic) "yes" else "no")
                    if (symptomatic) put(FormFields.Vhf.FOLLOW_UP_SYMPTOMS, "fever")
                    put("vhf-follow-up-temperature-am-taken", "yes")
                    put(FormFields.Vhf.FOLLOW_UP_TEMPERATURE_AM, if (symptomatic) "38.6" else "36.8")
                    put("vhf-follow-up-temperature-pm-taken", "no")
                    put(FormFields.Vhf.FOLLOW_UP_REFERRED, if (symptomatic) "yes" else "no")
                },
            )
        }

        /** "Convert to case", both steps (VhfContactActions): monitoring outcome, then the CIF. */
        private suspend fun convert(patientId: String, encounterId: String) {
            val state = checkNotNull(VhfContactTracker.load(engine, patientId, encounterId)) { "No follow-up state" }
            val day = state.firstSymptomaticVisit?.day ?: 3
            saveChild(
                FormFields.Vhf.CONTACT_MONITORING_FORM, FormFields.Vhf.CONTACT_MONITORING_TITLE, patientId, encounterId,
                mapOf(
                    FormFields.Vhf.MONITORING_STATUS to FormFields.Vhf.STATUS_BECAME_CASE_CODE,
                    FormFields.Vhf.FINAL_OUTCOME_DATE to state.dateOfDay(day).toString(),
                    FormFields.Vhf.MISSED_FOLLOW_UP_DAYS to state.missedDays.count { it < day }.toString(),
                ),
            )
            ContactGroups.closeMember(engine, patientId, today)
            editCaseForm(
                patientId, encounterId,
                mapOf(
                    FormFields.Vhf.CASE_TYPE to FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE,
                    FormFields.Vhf.INVESTIGATION_DATE to today.toString(),
                    FormFields.Vhf.SAMPLES_COLLECTED to "yes",
                    FormFields.Vhf.SAMPLE_TYPE to "blood",
                ),
            )
        }

        // ------------------------------------------------------------ phase 2

        suspend fun advanceAll() {
            val entries = readRegistry(app)
            _state.update { it.copy(total = entries.size) }
            Timber.tag(TAG).i("Run %s: %d cases to check for lab confirmation", runId, entries.size)
            entries.forEach { entry ->
                val name = entry.name.ifBlank { entry.patientId }
                _state.update { it.copy(current = name) }
                addResult(runPerson(name) { advance(entry, name) })
            }
            // Cleared (now or before) or died: phase 2 has nothing more to do for the case.
            val done = entries.filter { entry ->
                runCatching {
                    VhfSurvivorMonitoring.load(engine, entry.patientId, entry.encounterId).clearance != null ||
                        answers(entry.encounterId).latestValueOf(FormFields.Vhf.OUTCOME).equals("Dead", ignoreCase = true)
                }.getOrDefault(false)
            }.map { it.patientId }.toSet()
            writeRegistry(app, readRegistry(app).filterNot { it.patientId in done })
            if (!sync && entries.isNotEmpty()) {
                status("final sync …")
                SyntheticDataGenerator.syncNow(app)
            }
        }

        private suspend fun advance(entry: Entry, name: String): String {
            val (patientId, encounterId) = entry.patientId to entry.encounterId
            val lab = VhfSpecimens.summaryForCase(engine, patientId, encounterId)?.result?.label
            val survivor = VhfSurvivorMonitoring.load(engine, patientId, encounterId)
            val died = answers(encounterId).latestValueOf(FormFields.Vhf.OUTCOME).equals("Dead", ignoreCase = true)
            when (val decision = decide(lab, survivor.clearance != null, died)) {
                is Decision.Skip -> return "Skipped: ${decision.reason}"
                Decision.Advance -> Unit
            }
            val dates = SurvivorDates(today)
            val problems = ArrayList<String>()

            status("$name · Confirmed, outcome Alive")
            if (caseType(encounterId) != FormFields.Vhf.CASE_TYPE_CONFIRMED) {
                editCaseForm(patientId, encounterId, mapOf(FormFields.Vhf.CASE_TYPE to "confirmed"))
            }
            saveChild(
                FormFields.Vhf.CASE_OUTCOME_FORM, FormFields.Vhf.CASE_OUTCOME_TITLE, patientId, encounterId,
                mapOf(
                    FormFields.Vhf.OUTCOME to "alive",
                    FormFields.Vhf.CASE_STATUS to "recovered",
                    FormFields.Vhf.DISCHARGE_DATE to dates.discharged.toString(),
                ),
            )
            problems += check("Type of case Confirmed", caseType(encounterId) == FormFields.Vhf.CASE_TYPE_CONFIRMED)

            status("$name · discharge assessment")
            if (survivor.discharge == null) {
                saveChild(
                    FormFields.Epesm.DISCHARGE_FORM, FormFields.Epesm.DISCHARGE_TITLE, patientId, encounterId,
                    mapOf(
                        "epesm-admission-date" to dates.admitted.toString(),
                        "epesm-clinically-stable" to "yes",
                        "epesm-discharge-pcr-1-date" to dates.dischargePcr1.toString(),
                        "epesm-discharge-pcr-1-result" to "negative",
                        "epesm-discharge-pcr-2-date" to dates.dischargePcr2.toString(),
                        "epesm-discharge-pcr-2-result" to "negative",
                        FormFields.Epesm.MEETS_DISCHARGE_CRITERIA to "yes",
                    ),
                    checked = dates.discharged,
                )
            }

            dates.visits.forEachIndexed { index, date ->
                status("$name · survivor visit ${index + 1} of ${dates.visits.size}")
                saveChild(
                    FormFields.Epesm.FOLLOW_UP_FORM, FormFields.Epesm.FOLLOW_UP_TITLE, patientId, encounterId,
                    mapOf(
                        FormFields.Epesm.FOLLOW_UP_DATE to date.toString(),
                        "epesm-follow-up-mode" to "in-person",
                        "epesm-eye-pain" to "no",
                        "epesm-vision-change" to "no",
                        "epesm-hearing-loss" to "no",
                        "epesm-joint-pain" to "no",
                        "epesm-mental-health-screened" to "yes",
                        FormFields.Epesm.SYMPTOMS_RETURNED to "no",
                    ),
                    checked = date,
                )
            }

            val female = patient(patientId)?.gender == AdministrativeGender.FEMALE
            dates.samples.forEachIndexed { index, date ->
                status("$name · ${if (female) "breast milk" else "semen"} sample ${index + 1} of ${dates.samples.size}")
                val values = if (female) {
                    mapOf(
                        FormFields.Epesm.SAMPLE_TYPE to FormFields.Epesm.SAMPLE_BREAST_MILK_CODE,
                        FormFields.Epesm.BREAST_MILK_DATE to date.toString(),
                        FormFields.Epesm.BREAST_MILK_RESULT to "negative",
                        "epesm-breastfeeding-counselling" to "yes",
                    )
                } else {
                    buildMap {
                        put(FormFields.Epesm.SAMPLE_TYPE, FormFields.Epesm.SAMPLE_SEMEN_CODE)
                        put(FormFields.Epesm.SEMEN_DATE, date.toString())
                        put(FormFields.Epesm.SEMEN_RESULT, "negative")
                        put(FormFields.Epesm.SEMEN_NEGATIVE_COUNT, index.toString())
                        if (index == dates.samples.lastIndex) put(FormFields.Epesm.SEMEN_CLEARANCE_DATE, date.toString())
                        put("epesm-safe-sex-counselling", "yes")
                    }
                }
                saveChild(FormFields.Epesm.PERSISTENCE_FORM, FormFields.Epesm.PERSISTENCE_TITLE, patientId, encounterId, values, checked = date)
            }

            status("$name · clearance")
            saveChild(
                FormFields.Epesm.CLEARANCE_FORM, FormFields.Epesm.CLEARANCE_TITLE, patientId, encounterId,
                mapOf(
                    FormFields.Epesm.CLEARANCE_DATE to today.toString(),
                    FormFields.Epesm.CLEARED_BY_NAME to userName(),
                    FormFields.Epesm.CLEARED_DATE to today.toString(),
                ),
            )
            val after = VhfSurvivorMonitoring.load(engine, patientId, encounterId)
            val series = if (female) after.breastMilk else after.semen
            problems += check("Discharge assessment recorded", after.discharge != null)
            problems += check("${dates.visits.size} survivor visits", after.followUps.size >= dates.visits.size)
            problems += check("Two negatives in a row", series.cleared)
            problems += check("Cleared", after.clearance != null)

            if (sync) {
                status("$name · syncing …")
                if (!SyntheticDataGenerator.syncNow(app)) problems += "Sync failed (offline or server error)"
            }
            check(problems.isEmpty()) { problems.joinToString("; ") }
            return "Cleared: Confirmed · Alive · discharged ${dates.discharged} · ${dates.visits.size} visits · 2 negatives"
        }

        // ------------------------------------------------------------ saving

        private fun json(response: QuestionnaireResponse) = parser.encodeResourceToString(response)

        private val caseQuestionnaire: Questionnaire by lazy {
            parser.parseResource(
                Questionnaire::class.java,
                app.assets.open(FormFields.Vhf.CASE_FORM).bufferedReader().use { it.readText() },
            )
        }

        /** Dates set to their earliest accepted value while saving the current person's records. */
        private val adjusted = ArrayList<FormDateRepair.Change>()

        /**
         * A new case form record, saved as the case screen saves it. Answers are completed
         * randomly (like generated test data); an answer carried in from screening that breaks
         * a form rule (phone format, …) is left out on the next attempt, as the investigator
         * would correct it.
         */
        private suspend fun saveCase(
            values: Map<String, String>,
            codings: Map<String, Coding> = emptyMap(),
            screenedId: String? = null,
            contactSourceId: String? = null,
        ): Pair<String, String> {
            val viewModel = AddClientViewModel(
                app, SavedStateHandle(mapOf(QUESTIONNAIRE_FILE_PATH_KEY to FormFields.Vhf.CASE_FORM))
            )
            var answers = values
            var retry: QuestionnaireResponse? = null
            var result = PersistResult.INVALID
            for (attempt in 1..SAVE_ATTEMPTS) {
                val response = retry ?: SyntheticDataGenerator.buildRecord(app, FormFields.Vhf.CASE_FORM, answers, codings)
                retry = null
                result = viewModel.persistPatientData(
                    response.copy(), app, caseOverride = CaseTypes.VHF, extraTags = tags,
                    screenedId = screenedId, contactSourceId = contactSourceId,
                )
                if (result != PersistResult.INVALID) break
                val failing = viewModel.lastValidationErrors.map { it.substringBefore(':').trim() }.toSet()
                Timber.tag(TAG).w("Case form attempt %d failed validation: %s", attempt, viewModel.lastValidationErrors)
                // A date out of range is set to the earliest date its question accepts and kept
                // for later attempts; any other failing answer from screening is left out.
                val fixed = FormDateRepair.repair(caseQuestionnaire, response, failing, today)
                fixed.forEach { Timber.tag(TAG).i("Date %s: %s -> %s (earliest accepted)", it.linkId, it.from, it.to) }
                adjusted += fixed
                answers = answers.filterKeys { it !in failing || it in KEPT } + fixed.associate { it.linkId to it.to }
                // Only dates failed: the same answers are tried again with the dates fixed.
                if (fixed.isNotEmpty() && fixed.map { it.linkId }.containsAll(failing)) retry = response
            }
            check(result == PersistResult.SAVED) {
                when (result) {
                    PersistResult.INVALID -> "The case form failed validation $SAVE_ATTEMPTS times: " +
                        viewModel.lastValidationErrors.take(5).joinToString("; ")
                    else -> "The case form was not saved: ${viewModel.lastSaveError ?: "see logcat tag CASE_SAVE"}"
                }
            }
            return checkNotNull(viewModel.lastSavedCase) { "Saved, but the new record is not known" }
        }

        /** A follow-up style record of the case (child Encounter), as AddCaseActivity saves it. */
        private suspend fun saveChild(
            file: String,
            title: String,
            patientId: String,
            encounterId: String,
            values: Map<String, String>,
            checked: LocalDate = today,
        ) {
            val response = SyntheticDataGenerator.buildRecord(app, file, values + official(checked))
            response.meta.tag = tags.mapTo(ArrayList()) { it.copy() }
            val saved = ScreenerViewModel(app, SavedStateHandle(mapOf(QUESTIONNAIRE_FILE_PATH_KEY to file)))
                .saveChildRecord(response, patientId, encounterId, title, json(response), app)
            check(saved) { "$title was not saved (see logcat tag ScreenerViewModel)" }
        }

        /** Edits the case form (CIF) as the edit screen saves it, waiting for every step. */
        private suspend fun editCaseForm(patientId: String, encounterId: String, values: Map<String, String>) {
            val response = checkNotNull(CaseResponse.find(engine, patientId, encounterId)) { "The CIF was not found" }
            val edited = SyntheticDataGenerator.completeRecord(app, FormFields.Vhf.CASE_FORM, response, values)
            val viewModel = EditSupervisorChecklistViewModel(app, response.idElement.idPart, FormFields.Vhf.CASE_FORM)
            check(viewModel.updatePatientAndWait(edited, FormFields.Vhf.CASE_FORM, json(edited))) { "The CIF edit was not saved" }
        }

        /** The Official section: the logged-in user, on [checked]. Forms without it ignore it. */
        private fun official(checked: LocalDate): Map<String, String> = mapOf(
            FormFields.Vhf.CHECKED_BY_NAME to userName(),
            FormFields.Vhf.CHECKED_BY_EMAIL to (FormatterClass().getSharedPref("email", app) ?: "workflow@example.org"),
            FormFields.Vhf.DATE_CHECKED to checked.toString(),
        )

        private fun userName(): String {
            val prefs = FormatterClass()
            return listOfNotNull(prefs.getSharedPref("firstName", app), prefs.getSharedPref("lastName", app))
                .joinToString(" ").ifBlank { "Flagged workflow" }
        }

        // ------------------------------------------------------------ helpers

        /** Runs [work] for one person; a failure is that person's result, and the run goes on. */
        private suspend fun runPerson(name: String, work: suspend () -> String): PersonResult =
            try {
                adjusted.clear()
                val outcome = work()
                PersonResult(
                    name,
                    if (adjusted.isEmpty()) outcome
                    else "$outcome · ${adjusted.size} date(s) set to the earliest accepted: " +
                        adjusted.joinToString { "${it.linkId} ${it.to}" },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "%s failed", name)
                PersonResult(name, "stopped", listOf(e.message ?: e.javaClass.simpleName))
            }

        private fun remember(entry: Entry) = writeRegistry(app, readRegistry(app) + entry)

        private fun status(text: String) = _state.update { it.copy(current = text) }

        /** [name] when [passed] is false, else nothing (collected into a person's problems). */
        private fun check(name: String, passed: Boolean): List<String> = if (passed) emptyList() else listOf("✗ $name")

        /** Syncs until the server's EPID number is on [patientId], a few times at most. */
        private suspend fun awaitServerEpid(name: String, patientId: String) {
            repeat(EPID_SYNC_ATTEMPTS) { attempt ->
                if (patient(patientId)?.let { VhfSourceCases.epidOf(it) } != null) return
                if (attempt > 0) kotlinx.coroutines.delay(EPID_RETRY_MS)
                status("$name · waiting for the EPID number (sync ${attempt + 1} of $EPID_SYNC_ATTEMPTS) …")
                SyntheticDataGenerator.syncNow(app)
            }
        }

        private suspend fun patient(id: String): Patient? = runCatching { engine.get<Patient>(id) }.getOrNull()

        private suspend fun answers(encounterId: String): List<Observation> =
            engine.search<Observation> { filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" }) }
                .map { it.resource }

        private suspend fun caseType(encounterId: String) = answers(encounterId).latestValueOf(FormFields.Vhf.CASE_TYPE)
    }

    /** A date fix retries the same answers, so one more attempt than the simulator. */
    private const val SAVE_ATTEMPTS = 4
    private const val EPID_SYNC_ATTEMPTS = 4
    private const val EPID_RETRY_MS = 15_000L

    /** Answers the run sets itself: never dropped when a save fails validation. */
    private val KEPT = setOf(
        FormFields.Vhf.CASE_TYPE, FormFields.Vhf.INVESTIGATION_DATE,
        FormFields.Vhf.SAMPLES_COLLECTED, FormFields.Vhf.SAMPLE_TYPE,
    )
}
