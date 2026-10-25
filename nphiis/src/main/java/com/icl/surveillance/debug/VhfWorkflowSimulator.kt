package com.icl.surveillance.debug

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.FhirConformance
import com.icl.surveillance.fhir.forms.CaseResponse
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.ContactGroups
import com.icl.surveillance.fhir.forms.FhirSystems
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfCaseOutcome
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfForms
import com.icl.surveillance.fhir.forms.VhfListMode
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.fhir.forms.VhfSourceCases
import com.icl.surveillance.fhir.forms.VhfSpecimens
import com.icl.surveillance.fhir.forms.latestValueOf
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.AddClientViewModel
import com.icl.surveillance.viewmodels.AddClientViewModel.PersistResult
import com.icl.surveillance.viewmodels.EditSupervisorChecklistViewModel
import com.icl.surveillance.viewmodels.ScreenerViewModel
import java.time.LocalDate
import java.util.Date
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
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DiagnosticReport
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import timber.log.Timber

/**
 * Settings > Simulate VHF workflow (administrators): one person through the whole VHF workflow,
 * through the same save code the screens use, checking the records after every step:
 *
 * 1. a suspected source case;  2. a flagged (screened) person;  3. the flagged person
 * investigated as a contact of the source case;  4. three daily follow ups (symptomatic on day
 * 3);  5. converted to a suspected case (monitoring outcome, then the CIF);  6. the lab confirms
 * the specimen;  7. the CIF updated from suspected to confirmed;  8. the outcome: died;
 * 9. a last sync and a check of every record.
 *
 * With sync on, each step is uploaded (and the server's copies downloaded) before the next, and
 * its checks run again on what came back, so a sync that breaks a record shows at that step.
 * Every record is tagged [TAG_SYSTEM] with the run's id, so it can be found on the server.
 */
object VhfWorkflowSimulator {
    const val TAG = "VHF_WORKFLOW_SIM"
    const val TAG_SYSTEM = "http://nphiis.health.go.ke/fhir/CodeSystem/workflow-simulation"

    data class Check(val name: String, val passed: Boolean, val detail: String = "")

    data class StepReport(
        val number: Int,
        val title: String,
        val checks: List<Check> = emptyList(),
        /** null: not synced (sync off); else whether the sync succeeded. */
        val synced: Boolean? = null,
        val afterSync: List<Check> = emptyList(),
        val error: String? = null,
    ) {
        val passed: Boolean
            get() = error == null && synced != false && (checks + afterSync).all { it.passed }
    }

    data class State(
        val running: Boolean = false,
        val current: String? = null,
        val steps: List<StepReport> = emptyList(),
        val finished: Boolean = false,
        val runId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    /** Starts a run in the background (Settings); progress is in [state]. */
    fun start(context: Context, syncEachStep: Boolean): Boolean {
        if (isRunning) return false
        val app = context.applicationContext as Application
        _state.value = State(running = true)
        job = scope.launch {
            try {
                run(app, syncEachStep) { report ->
                    _state.update { it.copy(steps = it.steps.filter { s -> s.number != report.number } + report) }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(running = false, finished = true, current = null) }
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Runs every step in order and returns their reports. A step whose action fails stops the
     * run (later steps need its records). [sync] uploads and downloads after each step.
     */
    suspend fun run(app: Application, sync: Boolean, onStep: (StepReport) -> Unit = {}): List<StepReport> {
        var number = 0
        var title = ""
        // Shown while a step waits (e.g. for the server's EPID number).
        val run = Run(app, sync) { status -> onStep(StepReport(number, "$title · $status")) }
        _state.update { it.copy(runId = run.runId) }
        Timber.tag(TAG).i("Run %s started (sync after each step: %s)", run.runId, sync)
        val reports = ArrayList<StepReport>()
        for ((index, step) in run.steps.withIndex()) {
            number = index + 1
            title = step.title
            _state.update { it.copy(current = step.title) }
            onStep(StepReport(number, step.title + " …"))
            var report = try {
                step.action()
                StepReport(number, step.title, checks = run.safely(step.verify))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Step %d (%s) failed", number, step.title)
                StepReport(number, step.title, error = e.message ?: e.javaClass.simpleName)
            }
            if (report.error == null && (sync || step.alwaysSync)) {
                onStep(report.copy(title = step.title + " · syncing …"))
                val synced = SyntheticDataGenerator.syncNow(app)
                report = report.copy(
                    synced = synced,
                    afterSync = if (synced) run.safely(step.verify) + run.nothingPending() else emptyList(),
                )
            }
            report.log()
            reports += report
            onStep(report)
            if (report.error != null) break
        }
        Timber.tag(TAG).i(
            "Run %s finished: %d/%d steps passed", run.runId, reports.count { it.passed }, run.steps.size,
        )
        return reports
    }

    private fun StepReport.log() {
        val log = Timber.tag(TAG)
        log.i("%d. %s: %s%s", number, title, if (passed) "PASSED" else "FAILED", error?.let { " ($it)" }.orEmpty())
        checks.forEach { log.i("   %s %s %s", if (it.passed) "✓" else "✗", it.name, it.detail) }
        synced?.let { log.i("   sync %s", if (it) "succeeded" else "FAILED") }
        afterSync.forEach { log.i("   after sync %s %s %s", if (it.passed) "✓" else "✗", it.name, it.detail) }
    }

    private class Step(
        val title: String,
        val action: suspend () -> Unit,
        val verify: suspend () -> List<Check>,
        /** The last step always syncs (when sync is on for the run it already did). */
        val alwaysSync: Boolean = false,
    )

    /** One simulated person and the records the run made for them. */
    private class Run(
        val app: Application,
        private val sync: Boolean,
        private val status: (String) -> Unit,
    ) {
        val runId = "sim-" + UUID.randomUUID().toString().take(8)
        val engine: FhirEngine = FhirApplication.fhirEngine(app)
        private val tags = listOf(Coding(TAG_SYSTEM, runId, "VHF workflow simulation"))
        private val parser = FhirContext.forR4Cached().newJsonParser()
        private val today: LocalDate = LocalDate.now()
        /** Day 0 of the contact's follow up: 3 days ago, so days 1-3 are due by today. */
        private val dayZero: LocalDate = today.minusDays(3)

        private var sourcePatient = ""
        private var sourceEncounter = ""
        private var sourceEpid = ""
        private var flaggedPatient = ""
        private var casePatient = ""
        private var caseEncounter = ""
        private var specimenId = ""
        private var labReportId = ""

        private fun json(response: QuestionnaireResponse) = parser.encodeResourceToString(response)

        private fun newCase() = AddClientViewModel(
            app, SavedStateHandle(mapOf(QUESTIONNAIRE_FILE_PATH_KEY to FormFields.Vhf.CASE_FORM))
        )

        private fun childForms(file: String) =
            ScreenerViewModel(app, SavedStateHandle(mapOf(QUESTIONNAIRE_FILE_PATH_KEY to file)))

        private suspend fun saveCase(
            values: Map<String, String>,
            codings: Map<String, Coding> = emptyMap(),
            screenedId: String? = null,
            contactSourceId: String? = null,
        ): Pair<String, String> {
            val viewModel = newCase()
            // Answers are random (like generated test data), so one can break a form rule: a
            // fresh form is built and tried again, as the test data generator does.
            var result = PersistResult.INVALID
            for (attempt in 1..SAVE_ATTEMPTS) {
                val response = SyntheticDataGenerator.buildRecord(app, FormFields.Vhf.CASE_FORM, values, codings)
                result = viewModel.persistPatientData(
                    response, app, caseOverride = CaseTypes.VHF, extraTags = tags,
                    screenedId = screenedId, contactSourceId = contactSourceId,
                )
                if (result != PersistResult.INVALID) break
                Timber.tag(TAG).w("Case form attempt %d failed validation: %s", attempt, viewModel.lastValidationErrors)
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
        private suspend fun saveChild(file: String, title: String, patientId: String, encounterId: String, values: Map<String, String>) {
            val response = SyntheticDataGenerator.buildRecord(app, file, values + official(file))
            response.meta.tag = tags.mapTo(ArrayList()) { it.copy() }
            val saved = childForms(file).saveChildRecord(response, patientId, encounterId, title, json(response), app)
            check(saved) { "$title was not saved (see logcat tag ScreenerViewModel)" }
        }

        /** "Checked by": the logged-in user, today (as the screens pre-fill it). */
        private fun official(file: String): Map<String, String> {
            if (file != FormFields.Vhf.CONTACT_MONITORING_FORM && file != FormFields.Vhf.CASE_OUTCOME_FORM) return emptyMap()
            val prefs = FormatterClass()
            val name = listOfNotNull(prefs.getSharedPref("firstName", app), prefs.getSharedPref("lastName", app))
                .joinToString(" ").ifBlank { "Workflow simulator" }
            return mapOf(
                FormFields.Vhf.CHECKED_BY_NAME to name,
                FormFields.Vhf.CHECKED_BY_EMAIL to (prefs.getSharedPref("email", app) ?: "simulator@example.org"),
                FormFields.Vhf.DATE_CHECKED to today.toString(),
            )
        }

        /** Edits the case form (CIF) as the edit screen saves it, waiting for every step. */
        private suspend fun editCaseForm(values: Map<String, String>) {
            val response = checkNotNull(CaseResponse.find(engine, casePatient, caseEncounter)) { "The CIF was not found" }
            val edited = SyntheticDataGenerator.completeRecord(app, FormFields.Vhf.CASE_FORM, response, values)
            val viewModel = EditSupervisorChecklistViewModel(app, response.logicalId, FormFields.Vhf.CASE_FORM)
            check(viewModel.updatePatientAndWait(edited, FormFields.Vhf.CASE_FORM, json(edited))) { "The CIF edit was not saved" }
        }

        // ---------------------------------------------------------------- steps

        val steps = listOf(
            Step("Register a suspected source case", action = {
                val (patient, encounter) = saveCase(
                    mapOf(FormFields.Vhf.CASE_TYPE to FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE, FormFields.Vhf.SAMPLES_COLLECTED to "no")
                )
                sourcePatient = patient
                sourceEncounter = encounter
                // The device saves only the EPID prefix (an answer on the case); the server
                // assigns the number and puts it on the Patient. Contacts are linked by it, so
                // with sync on the run waits for it (as a user waits for the case to sync).
                if (sync) awaitServerEpid()
            }, verify = {
                val patient = patient(sourcePatient)
                val serverEpid = patient?.let { VhfSourceCases.epidOf(it) }
                val prefix = answers(sourceEncounter).latestValueOf(FhirSystems.EPID)
                // Sync off: the device's prefix stands in for it, so the rest of the run works.
                sourceEpid = serverEpid ?: patient?.let { VhfSourceCases.epidOrPartialOf(it) } ?: prefix
                listOfNotNull(
                    check("Source case saved", patient != null, sourcePatient),
                    check("Type of case is Suspected", caseType(sourceEncounter) == "Suspected", caseType(sourceEncounter)),
                    check("EPID prefix saved on the device", prefix.isNotBlank(), prefix.ifBlank { "none" }),
                    if (sync) {
                        check("EPID number assigned by the server", serverEpid != null,
                            serverEpid ?: "not received after $EPID_SYNC_ATTEMPTS syncs (is hie-auth assigning EPIDs?)")
                    } else {
                        info("EPID number assigned by the server", serverEpid ?: "not yet: sync is off, the prefix is used")
                    },
                )
            }),

            Step("Flag a person (screened)", action = {
                val response = SyntheticDataGenerator.buildRecord(app, FormFields.Vhf.CASE_FORM, emptyMap())
                response.item = response.item.filter { it.linkId in VhfScreened.SCREENING_SECTIONS.keys }.toMutableList()
                flaggedPatient = VhfScreened.createScreened(engine, response, tags)
            }, verify = {
                val patient = patient(flaggedPatient)
                listOf(
                    check("Flagged person saved", patient != null, flaggedPatient),
                    check("Listed as screened", patient?.let { VhfScreened.isScreened(it) } == true),
                )
            }),

            Step("Investigate the flagged person as a contact", action = {
                check(sourceEpid.isNotBlank()) { "The source case has no EPID number or prefix to link the contact to" }
                val (patient, encounter) = saveCase(
                    values = mapOf(
                        FormFields.Vhf.CASE_TYPE to "contact",
                        FormFields.Vhf.EXPOSURE_TYPE to "human",
                        FormFields.Vhf.CONTACT_DATE to dayZero.toString(),
                        FormFields.Vhf.SAMPLES_COLLECTED to "no",
                    ),
                    codings = mapOf(FormFields.Vhf.SOURCE_CASE_EPID to Coding().setCode(sourceEpid).setDisplay(sourceEpid)),
                    screenedId = flaggedPatient,
                    contactSourceId = sourcePatient,
                )
                casePatient = patient
                caseEncounter = encounter
            }, verify = {
                val patient = patient(casePatient)
                val link = answers(caseEncounter).latestValueOf(FormFields.Vhf.SOURCE_CASE_EPID)
                listOf(
                    check("The flagged person's record is continued", casePatient == flaggedPatient, "$casePatient / $flaggedPatient"),
                    check("No longer listed as screened", VhfScreened.list(engine).none { it.patientId == casePatient }),
                    check("Type of case is Contact", caseType(caseEncounter) == FormFields.Vhf.CASE_TYPE_CONTACT, caseType(caseEncounter)),
                    check("Link to case names the source EPID", VhfSourceCases.epidFrom(link) == sourceEpid, link),
                    check("Listed under the source case", casePatient in ContactGroups.contactIds(engine, sourcePatient)),
                    check("Has a contact number", patient?.let { VhfListMode.contactIdOf(it) } != null,
                        patient?.let { VhfListMode.contactIdOf(it) } ?: "none"),
                )
            }),

            Step("Record 3 daily follow ups", action = {
                (1..3).forEach { day ->
                    val symptomatic = day == 3
                    saveChild(
                        FormFields.Vhf.CONTACT_FOLLOW_UP_FORM, FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE, casePatient, caseEncounter,
                        buildMap {
                            put(FormFields.Vhf.FOLLOW_UP_DAY, day.toString())
                            put(FormFields.Vhf.FOLLOW_UP_DATE, dayZero.plusDays(day.toLong()).toString())
                            put(FormFields.Vhf.FOLLOW_UP_CONTACT_STATUS, "alive")
                            put(FormFields.Vhf.FOLLOW_UP_SYMPTOMATIC, if (symptomatic) "yes" else "no")
                            if (symptomatic) put(FormFields.Vhf.FOLLOW_UP_SYMPTOMS, "fever")
                            put("vhf-follow-up-temperature-am-taken", "yes")
                            put(FormFields.Vhf.FOLLOW_UP_TEMPERATURE_AM, if (symptomatic) "38.6" else "36.8")
                            put("vhf-follow-up-temperature-pm-taken", "no")
                            put(FormFields.Vhf.FOLLOW_UP_REFERRED, "no")
                        },
                    )
                }
            }, verify = {
                val state = VhfContactTracker.load(engine, casePatient, caseEncounter)
                listOf(
                    check("3 follow ups recorded", state?.visits?.size == 3, "${state?.visits?.size}"),
                    check("Days 1, 2 and 3", state?.recordedDays == setOf(1, 2, 3), "${state?.recordedDays}"),
                    check("Symptomatic on day 3", state?.firstSymptomaticVisit?.day == 3, state?.summary.orEmpty()),
                    check("Still a contact", state?.isContact == true),
                )
            }),

            Step("Convert the contact to a suspected case (open the CIF)", action = {
                val state = checkNotNull(VhfContactTracker.load(engine, casePatient, caseEncounter)) { "No follow-up state" }
                // Step 1 of "Convert to case": the monitoring outcome (VhfContactActions).
                val day = state.firstSymptomaticVisit?.day ?: 3
                saveChild(
                    FormFields.Vhf.CONTACT_MONITORING_FORM, FormFields.Vhf.CONTACT_MONITORING_TITLE, casePatient, caseEncounter,
                    mapOf(
                        FormFields.Vhf.MONITORING_STATUS to FormFields.Vhf.STATUS_BECAME_CASE_CODE,
                        FormFields.Vhf.FINAL_OUTCOME_DATE to state.dateOfDay(day).toString(),
                        FormFields.Vhf.MISSED_FOLLOW_UP_DAYS to state.missedDays.count { it < day }.toString(),
                    ),
                )
                // AddCaseActivity: the follow up is over, so the contact's membership is closed.
                ContactGroups.closeMember(engine, casePatient, today)
                // Step 2: the CIF opens with Type of case = Suspected; a blood sample is taken.
                editCaseForm(
                    mapOf(
                        FormFields.Vhf.CASE_TYPE to FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE,
                        FormFields.Vhf.INVESTIGATION_DATE to today.toString(),
                        FormFields.Vhf.SAMPLES_COLLECTED to "yes",
                        FormFields.Vhf.SAMPLE_TYPE to "blood",
                    )
                )
            }, verify = {
                val response = CaseResponse.find(engine, casePatient, caseEncounter)
                val member = ContactGroups.groupOf(engine, sourcePatient)?.member
                    ?.firstOrNull { it.entity?.referenceElement?.idPart == casePatient }
                val specimens = VhfSpecimens.forCase(engine, casePatient, caseEncounter)
                specimenId = specimens.firstOrNull()?.logicalId.orEmpty()
                listOf(
                    check("The CIF opens (case form)", response != null && VhfForms.formFor(response) == FormFields.Vhf.CASE_FORM),
                    check("Type of case is Suspected", caseType(caseEncounter) == "Suspected", caseType(caseEncounter)),
                    check("Follow up under the source case closed", member?.inactive == true),
                    check("Link to the source case kept",
                        VhfSourceCases.epidFrom(answers(caseEncounter).latestValueOf(FormFields.Vhf.SOURCE_CASE_EPID)) == sourceEpid),
                    check("Follow-up history kept", VhfContactTracker.load(engine, casePatient, caseEncounter)?.visits?.size == 3),
                    check("One specimen collected", specimens.size == 1, "${specimens.size}"),
                )
            }),

            Step("Lab confirms the specimen (positive)", action = {
                check(specimenId.isNotBlank()) { "The case has no specimen" }
                // As NPHL sends it: a final DiagnosticReport on the specimen with its result.
                val result = Observation().apply {
                    id = UUID.randomUUID().toString()
                    status = Observation.ObservationStatus.FINAL
                    code = CodeableConcept().setText("VHF PCR")
                    subject = Reference("Patient/$casePatient")
                    specimen = Reference("Specimen/$specimenId")
                    value = StringType("Positive")
                    issued = Date()
                    meta.tag = tags.mapTo(ArrayList()) { it.copy() }
                }
                val report = DiagnosticReport().apply {
                    id = UUID.randomUUID().toString()
                    status = DiagnosticReport.DiagnosticReportStatus.FINAL
                    code = CodeableConcept().setText("VHF PCR")
                    subject = Reference("Patient/$casePatient")
                    addSpecimen(Reference("Specimen/$specimenId"))
                    addResult(Reference("Observation/${result.id}"))
                    issued = Date()
                    meta.tag = tags.mapTo(ArrayList()) { it.copy() }
                }
                labReportId = report.id
                engine.create(FhirConformance.ensureRequired(result), FhirConformance.ensureRequired(report))
            }, verify = {
                val summary = VhfSpecimens.summaryForCase(engine, casePatient, caseEncounter)
                listOf(
                    check("Specimen result Positive", summary?.result == VhfSpecimens.Result.POSITIVE, "${summary?.result}"),
                    check("Classified Confirmed by lab", summary?.classification == "Confirmed by lab", summary?.classification.orEmpty()),
                )
            }),

            Step("Update the CIF from suspected to confirmed", action = {
                editCaseForm(mapOf(FormFields.Vhf.CASE_TYPE to "confirmed"))
            }, verify = {
                listOf(
                    check("Type of case is Confirmed", caseType(caseEncounter) == FormFields.Vhf.CASE_TYPE_CONFIRMED, caseType(caseEncounter)),
                    check("Still one specimen", VhfSpecimens.forCase(engine, casePatient, caseEncounter).size == 1),
                    check("Lab result kept",
                        VhfSpecimens.summaryForCase(engine, casePatient, caseEncounter)?.result == VhfSpecimens.Result.POSITIVE),
                )
            }),

            Step("Confirmed case follow up: record outcome Died", action = {
                saveChild(
                    FormFields.Vhf.CASE_OUTCOME_FORM, FormFields.Vhf.CASE_OUTCOME_TITLE, casePatient, caseEncounter,
                    mapOf(
                        FormFields.Vhf.OUTCOME to FormFields.Vhf.OUTCOME_DEAD_CODE,
                        FormFields.Vhf.DEATH_DATE to today.toString(),
                        FormFields.Vhf.DEATH_PLACE to "health-facility",
                        FormFields.Vhf.CASE_STATUS to "dead",
                    ),
                )
            }, verify = { deathChecks() }),

            Step("Final sync and check of every record", action = {}, verify = {
                listOf(
                    check("Type of case is Confirmed", caseType(caseEncounter) == FormFields.Vhf.CASE_TYPE_CONFIRMED, caseType(caseEncounter)),
                    check("3 follow ups", VhfContactTracker.load(engine, casePatient, caseEncounter)?.visits?.size == 3),
                    check("Lab result Positive",
                        VhfSpecimens.summaryForCase(engine, casePatient, caseEncounter)?.result == VhfSpecimens.Result.POSITIVE),
                    check("One case form response", responses(casePatient).count { !isChild(it) } == 1,
                        "${responses(casePatient).count { !isChild(it) }}"),
                    check("One live answer per code on the CIF", duplicateCodes(caseEncounter).isEmpty(),
                        duplicateCodes(caseEncounter).joinToString().ifBlank { "none" }),
                    check("Lab report kept", runCatching { engine.get<DiagnosticReport>(labReportId) }.isSuccess),
                ) + deathChecks()
            }, alwaysSync = true),
        )

        private suspend fun deathChecks(): List<Check> {
            val answers = answers(caseEncounter)
            val response = CaseResponse.find(engine, casePatient, caseEncounter)
            val patient = patient(casePatient)
            val records = engine.search<Encounter> {
                filter(Encounter.PART_OF, { value = "Encounter/$caseEncounter" })
            }.count { it.resource.reasonCodeFirstRep.codingFirstRep.code == FormFields.Vhf.CASE_OUTCOME_TITLE }
            val deceased = patient?.deceased?.primitiveValue().orEmpty()
            return listOf(
                check("Outcome record saved", records >= 1, "$records"),
                check("Outcome on the case is Dead", answers.latestValueOf(FormFields.Vhf.OUTCOME) == "Dead",
                    answers.latestValueOf(FormFields.Vhf.OUTCOME)),
                check("Date of death on the case", answers.latestValueOf(FormFields.Vhf.DEATH_DATE).startsWith(today.toString()),
                    answers.latestValueOf(FormFields.Vhf.DEATH_DATE)),
                check("The CIF shows Dead", response?.let { outcomeCode(it) } == FormFields.Vhf.OUTCOME_DEAD_CODE,
                    response?.let { outcomeCode(it) }.orEmpty()),
                check("Patient marked deceased", deceased.startsWith(today.toString()), deceased.ifBlank { "no" }),
            )
        }

        /** Syncs until the server's EPID number is on the source case, a few times at most. */
        private suspend fun awaitServerEpid() {
            repeat(EPID_SYNC_ATTEMPTS) { attempt ->
                if (patient(sourcePatient)?.let { VhfSourceCases.epidOf(it) } != null) return
                if (attempt > 0) kotlinx.coroutines.delay(EPID_RETRY_MS)
                Timber.tag(TAG).i("Waiting for the source case's EPID number (sync %d)", attempt + 1)
                status("waiting for the server's EPID number (sync ${attempt + 1} of $EPID_SYNC_ATTEMPTS) …")
                SyntheticDataGenerator.syncNow(app)
            }
        }

        // ---------------------------------------------------------------- checks

        private fun check(name: String, passed: Boolean, detail: String = "") = Check(name, passed, detail)

        /** Shown, never failing (e.g. what the server has done so far). */
        private fun info(name: String, detail: String) = Check(name, true, detail)

        suspend fun safely(verify: suspend () -> List<Check>): List<Check> =
            try {
                verify()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Check failed to run")
                listOf(Check("Checks ran", false, e.message ?: e.javaClass.simpleName))
            }

        /** After a sync: no change left to upload on the run's records. */
        suspend fun nothingPending(): List<Check> {
            val patients = listOf(sourcePatient, casePatient).filter { it.isNotBlank() }.distinct()
            val ids = patients.flatMap { patientId ->
                listOf(ResourceType.Patient to patientId) +
                    engine.search<Encounter> { filter(Encounter.SUBJECT, { value = "Patient/$patientId" }) }
                        .map { ResourceType.Encounter to it.resource.logicalId } +
                    responses(patientId).map { ResourceType.QuestionnaireResponse to it.logicalId }
            } + listOfNotNull(
                (ResourceType.Group to ContactGroups.groupId(sourcePatient)).takeIf { sourcePatient.isNotBlank() },
                (ResourceType.Specimen to specimenId).takeIf { specimenId.isNotBlank() },
                (ResourceType.DiagnosticReport to labReportId).takeIf { labReportId.isNotBlank() },
            )
            val pending = ids.filter { (type, id) -> runCatching { engine.getLocalChanges(type, id) }.getOrDefault(emptyList()).isNotEmpty() }
            return listOf(
                Check(
                    "Everything uploaded (${ids.size} records)", pending.isEmpty(),
                    pending.joinToString { "${it.first}/${it.second}" }.ifBlank { "nothing pending" },
                )
            )
        }

        private suspend fun patient(id: String): Patient? =
            id.takeIf { it.isNotBlank() }?.let { runCatching { engine.get<Patient>(it) }.getOrNull() }

        private suspend fun answers(encounterId: String): List<Observation> =
            engine.search<Observation> { filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" }) }
                .map { it.resource }

        private suspend fun caseType(encounterId: String) = answers(encounterId).latestValueOf(FormFields.Vhf.CASE_TYPE)

        private suspend fun responses(patientId: String): List<QuestionnaireResponse> =
            engine.search<QuestionnaireResponse> { filter(QuestionnaireResponse.SUBJECT, { value = "Patient/$patientId" }) }
                .map { it.resource }

        /** A follow-up record's response (its Encounter is partOf the case). */
        private suspend fun isChild(response: QuestionnaireResponse): Boolean {
            val id = response.encounter?.referenceElement?.idPart ?: return false
            return runCatching { engine.get<Encounter>(id).hasPartOf() }.getOrDefault(false)
        }

        /** Codes with more than one live answer on the case encounter. */
        private suspend fun duplicateCodes(encounterId: String): List<String> =
            answers(encounterId)
                .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR }
                .groupBy { it.code.codingFirstRep.code }
                .filter { (code, list) -> code in DUPLICATE_CHECKED && list.size > 1 }
                .keys.filterNotNull()

        private fun outcomeCode(response: QuestionnaireResponse): String? {
            fun find(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>): String? =
                items.firstNotNullOfOrNull { item ->
                    if (item.linkId == FormFields.Vhf.OUTCOME) (item.answerFirstRep.value as? Coding)?.code
                    else find(item.item) ?: item.answer.firstNotNullOfOrNull { find(it.item) }
                }
            return find(response.item)
        }
    }

    /** Answers that must stay single on the case record: the type of case and the outcome. */
    private const val SAVE_ATTEMPTS = 3
    private const val EPID_SYNC_ATTEMPTS = 4
    private const val EPID_RETRY_MS = 15_000L

    private val DUPLICATE_CHECKED = setOf(FormFields.Vhf.CASE_TYPE) + VhfCaseOutcome.CODES
}
